package com.example.streamdav.ui;

import com.example.streamdav.catalog.LibraryIndex;
import com.example.streamdav.catalog.LibraryUpdater;
import com.example.streamdav.library.MediaLibrary;
import com.example.streamdav.metadata.AniListClient;
import com.example.streamdav.metadata.ApiKeys;
import com.example.streamdav.metadata.MetadataMatcher;
import com.example.streamdav.metadata.TmdbClient;
import com.example.streamdav.settings.ServerProfile;
import com.example.streamdav.settings.Settings;
import com.example.streamdav.store.LibraryStore;
import com.example.streamdav.store.LibraryStore.Source;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The library as the UI sees it: the current index, and refreshes running one at a time in the background.
 * Properties change on the JavaFX thread.
 */
final class LibraryModel implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(LibraryModel.class);

    @FunctionalInterface
    interface Job {
        LibraryUpdater.Report run(LibraryUpdater updater) throws IOException, InterruptedException;
    }

    private final LibraryStore store;
    private final Settings settings;
    private final MediaLibrary.Connector connector;
    private final AniListClient aniList = new AniListClient();
    private final ExecutorService jobs = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("library").factory());
    private final AtomicInteger queued = new AtomicInteger();
    private final ReadOnlyObjectWrapper<LibraryIndex> index = new ReadOnlyObjectWrapper<>(new LibraryIndex(List.of()));
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("");
    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper empty = new ReadOnlyBooleanWrapper(true);

    LibraryModel(LibraryStore store, Settings settings, MediaLibrary.Connector connector) {
        this.store = store;
        this.settings = settings;
        this.connector = connector;
    }

    ReadOnlyObjectProperty<LibraryIndex> indexProperty() {
        return index.getReadOnlyProperty();
    }

    /** What the background work is doing, or how the last run went. */
    ReadOnlyStringProperty statusProperty() {
        return status.getReadOnlyProperty();
    }

    ReadOnlyBooleanProperty busyProperty() {
        return busy.getReadOnlyProperty();
    }

    /** True while the library has no folders. */
    ReadOnlyBooleanProperty emptyProperty() {
        return empty.getReadOnlyProperty();
    }

    boolean hasSources() {
        try {
            return !store.sources().isEmpty();
        } catch (IOException e) {
            log.warn("Could not read library folders", e);
            return false;
        }
    }

    List<Source> sources() throws IOException {
        return store.sources();
    }

    /** Shows what's stored straight away, without touching the network. */
    void load() {
        submit("Loading library…", null);
    }

    /** Rescans every folder and looks up anything new. */
    void refresh() {
        submit("Updating library…", updater -> updater.refresh(this::progress));
    }

    /** Adds a folder and scans it. */
    void addSource(String serverId, URI folder, String name) {
        submit("Adding " + name + "…", updater -> updater.scan(store.addSource(serverId, folder, name), this::progress));
    }

    void removeSource(Source source) {
        submit("Removing " + source.name() + "…", updater -> {
            store.removeSource(source.id());
            return new LibraryUpdater.Report(0, 0, List.of());
        });
    }

    /** Forgets the library folders on a server the user removed. */
    void removeServer(String serverId) {
        submit(null, updater -> {
            store.removeServer(serverId);
            return new LibraryUpdater.Report(0, 0, List.of());
        });
    }

    /** Saves the user's TMDB key and searches again for titles that matched nothing without it. */
    void setTmdbKey(String key) {
        settings.setTmdbApiKey(key);
        submit("Finding details…", updater -> {
            store.forgetUnmatched();
            return updater.match(this::progress);
        });
    }

    /** Opens a saved server, for playing or scanning. */
    MediaLibrary open(String serverId) throws IOException {
        ServerProfile server = settings.servers().stream().filter(saved -> saved.id().equals(serverId)).findFirst()
                .orElseThrow(() -> new IOException("The server for this folder is no longer saved."));
        return connector.open(URI.create(server.url()), server.username(), server.password());
    }

    private void submit(String message, Job job) {
        queued.incrementAndGet();
        busy.set(true);
        if (message != null) {
            status.set(message);
        }
        jobs.execute(() -> {
            String outcome = "";
            try {
                if (job != null) {
                    LibraryUpdater.Report report = job.run(updater());
                    outcome = describe(report);
                }
                LibraryIndex loaded = LibraryUpdater.load(store);
                boolean noSources = store.sources().isEmpty();
                Platform.runLater(() -> {
                    index.set(loaded);
                    empty.set(noSources);
                });
            } catch (InterruptedException e) {
                return;
            } catch (IOException | RuntimeException e) {
                log.warn("Library update failed", e);
                outcome = "Library update failed: " + e.getMessage();
            }
            String finalOutcome = outcome;
            Platform.runLater(() -> {
                if (queued.decrementAndGet() == 0) {
                    busy.set(false);
                    status.set(finalOutcome);
                }
            });
        });
    }

    private LibraryUpdater updater() {
        TmdbClient tmdb = ApiKeys.tmdb(settings.tmdbApiKey()).map(TmdbClient::new).orElse(null);
        return new LibraryUpdater(store, this::open, MetadataMatcher.of(tmdb, aniList), tmdb, Clock.systemUTC());
    }

    private void progress(String message) {
        Platform.runLater(() -> status.set(message));
    }

    private static String describe(LibraryUpdater.Report report) {
        if (!report.problems().isEmpty()) {
            return report.problems().getFirst()
                    + (report.problems().size() > 1 ? " (and " + (report.problems().size() - 1) + " more problems)" : "");
        }
        return "";
    }

    @Override
    public void close() {
        jobs.shutdownNow();
        store.close();
    }
}
