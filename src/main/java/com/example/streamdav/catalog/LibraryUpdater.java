package com.example.streamdav.catalog;

import com.example.streamdav.catalog.LibraryScanner.ScanResult;
import com.example.streamdav.library.MediaLibrary;
import com.example.streamdav.metadata.EpisodeInfo;
import com.example.streamdav.metadata.Metadata;
import com.example.streamdav.metadata.Metadata.Kind;
import com.example.streamdav.metadata.MetadataMatcher;
import com.example.streamdav.metadata.MetadataMatcher.Query;
import com.example.streamdav.metadata.TmdbClient;
import com.example.streamdav.store.LibraryStore;
import com.example.streamdav.store.LibraryStore.FoundFile;
import com.example.streamdav.store.LibraryStore.Source;
import com.example.streamdav.store.LibraryStore.StoredMatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Brings the library up to date: scans its folders, then finds metadata for anything new. */
public final class LibraryUpdater {
    private static final Logger log = LogManager.getLogger(LibraryUpdater.class);
    /** How long a title that matched nothing waits before it's searched for again. */
    static final Duration RETRY_UNMATCHED = Duration.ofDays(3);
    /** How long a show's episode list is kept before it's fetched again, for new episodes and titles. */
    static final Duration REFRESH_EPISODES = Duration.ofDays(7);
    private static final int CONCURRENT_MATCHES = 4;

    /** Opens a saved server by its id. */
    @FunctionalInterface
    public interface Servers {
        MediaLibrary open(String serverId) throws IOException;
    }

    /**
     * @param problems what went wrong, worded for the user; the rest of the refresh carried on
     */
    public record Report(int files, int matched, List<String> problems) {
    }

    private final LibraryStore store;
    private final Servers servers;
    private final MetadataMatcher matcher;
    private final TmdbClient tmdb;
    private final Clock clock;

    /**
     * @param tmdb null without a TMDB key; shows matched elsewhere just have no episode titles
     */
    public LibraryUpdater(LibraryStore store, Servers servers, MetadataMatcher matcher, TmdbClient tmdb, Clock clock) {
        this.store = store;
        this.servers = servers;
        this.matcher = matcher;
        this.tmdb = tmdb;
        this.clock = clock;
    }

    /** Scans every folder in the library, then matches. Blocks; interrupting the thread stops it. */
    public Report refresh(Consumer<String> progress) throws IOException, InterruptedException {
        Set<String> problems = new LinkedHashSet<>();
        int files = 0;
        for (Source source : store.sources()) {
            files += scan(source, progress, problems);
        }
        int matched = match(progress, problems);
        return new Report(files, matched, List.copyOf(problems));
    }

    /** Scans one folder, as when it's first added. */
    public Report scan(Source source, Consumer<String> progress) throws IOException, InterruptedException {
        Set<String> problems = new LinkedHashSet<>();
        int files = scan(source, progress, problems);
        int matched = match(progress, problems);
        return new Report(files, matched, List.copyOf(problems));
    }

    private int scan(Source source, Consumer<String> progress, Set<String> problems) throws IOException, InterruptedException {
        progress.accept("Scanning " + source.name() + "…");
        ScanResult result;
        try (MediaLibrary library = servers.open(source.serverId())) {
            result = new LibraryScanner(library).scan(source.uri());
        } catch (IOException e) {
            log.warn("Could not scan {}", source.uri(), e);
            problems.add("Couldn't scan " + source.name() + ": " + e.getMessage());
            return 0;
        }
        List<FoundFile> found = result.files().stream().map(file -> new FoundFile(file.file(), file.folders())).toList();
        if (result.failedFolders().isEmpty()) {
            store.replaceFiles(source.id(), found, clock.instant());
        } else {
            // Files in the folders that failed may still be there, so nothing is removed this time.
            store.addFiles(source.id(), found, clock.instant());
            problems.add(result.failedFolders().size() == 1 && result.failedFolders().getFirst().equals(source.uri())
                    ? "Couldn't open " + source.name() + "."
                    : "Couldn't open " + result.failedFolders().size() + " folders in " + source.name() + ".");
        }
        return found.size();
    }

    /** Finds metadata for every title that hasn't been matched, as when a TMDB key is added. */
    public Report match(Consumer<String> progress) throws IOException, InterruptedException {
        Set<String> problems = new LinkedHashSet<>();
        int matched = match(progress, problems);
        return new Report(0, matched, List.copyOf(problems));
    }

    /** Finds metadata for every title that hasn't been matched, then episode lists for matched shows. */
    private int match(Consumer<String> progress, Set<String> problems) throws IOException, InterruptedException {
        Catalog catalog = LibraryIndex.catalog(store.files());
        Map<String, StoredMatch> matches = store.matches();
        Map<String, Query> pending = new LinkedHashMap<>();
        for (Catalog.Show show : catalog.shows()) {
            String key = LibraryIndex.showKey(show.title());
            if (needsMatch(matches.get(key))) {
                Integer maxEpisode = show.episodes().stream()
                        .filter(episode -> episode.season() == null || episode.season() == 1)
                        .map(episode -> episode.numbers().getLast()).max(Integer::compare).orElse(null);
                pending.putIfAbsent(key, new Query(Kind.SHOW, show.title(), show.year(), show.anime(), maxEpisode));
            }
        }
        for (Catalog.Movie movie : catalog.movies()) {
            String key = LibraryIndex.movieKey(movie.title(), movie.year());
            if (needsMatch(matches.get(key))) {
                boolean anime = movie.versions().stream().anyMatch(file -> file.release().anime());
                pending.putIfAbsent(key, new Query(Kind.MOVIE, movie.title(), movie.year(), anime, null));
            }
        }

        AtomicInteger done = new AtomicInteger();
        AtomicInteger matched = new AtomicInteger();
        Map<String, Future<?>> tasks = new HashMap<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_MATCHES,
                Thread.ofVirtual().name("metadata-", 0).factory())) {
            for (Map.Entry<String, Query> entry : pending.entrySet()) {
                tasks.put(entry.getKey(), executor.submit(() -> {
                    Optional<MetadataMatcher.Match> match = matcher.match(entry.getValue());
                    store.saveMatch(entry.getKey(), match.map(MetadataMatcher.Match::metadata), clock.instant(), false);
                    if (match.isPresent()) {
                        matched.incrementAndGet();
                    }
                    progress.accept("Finding details… " + done.incrementAndGet() + " of " + pending.size());
                    return null;
                }));
            }
            for (Map.Entry<String, Future<?>> task : tasks.entrySet()) {
                try {
                    task.getValue().get();
                } catch (ExecutionException e) {
                    // Not recorded as unmatched, so it's tried again next time.
                    log.warn("Could not match {}", task.getKey(), e.getCause());
                    problems.add(e.getCause() instanceof IOException io ? io.getMessage() : "Couldn't look up some titles.");
                }
            }
        } catch (InterruptedException e) {
            tasks.values().forEach(task -> task.cancel(true));
            throw e;
        }
        fetchEpisodes(catalog, progress, problems);
        return matched.get();
    }

    private boolean needsMatch(StoredMatch match) {
        if (match == null) {
            return true;
        }
        return match.metadata().isEmpty() && !match.manual()
                && match.matchedAt().plus(RETRY_UNMATCHED).isBefore(clock.instant());
    }

    private void fetchEpisodes(Catalog catalog, Consumer<String> progress, Set<String> problems)
            throws IOException, InterruptedException {
        if (tmdb == null) {
            return;
        }
        Map<String, StoredMatch> matches = store.matches();
        Set<String> fetched = new LinkedHashSet<>();
        for (Catalog.Show show : catalog.shows()) {
            Metadata metadata = Optional.ofNullable(matches.get(LibraryIndex.showKey(show.title())))
                    .flatMap(StoredMatch::metadata).orElse(null);
            if (metadata == null || metadata.provider() != Metadata.Provider.TMDB || !fetched.add(metadata.id())) {
                continue;
            }
            Optional<Instant> last = store.episodesFetched(metadata.provider(), metadata.id());
            if (last.isPresent() && last.get().plus(REFRESH_EPISODES).isAfter(clock.instant())) {
                continue;
            }
            progress.accept("Fetching episodes of " + metadata.title() + "…");
            try {
                List<EpisodeInfo> episodes = tmdb.episodes(metadata.id());
                store.saveEpisodes(metadata.provider(), metadata.id(), episodes, clock.instant());
            } catch (IOException e) {
                log.warn("Could not fetch episodes of {}", metadata.title(), e);
                problems.add(e.getMessage());
            }
        }
    }

    /** The library as stored, ready to show. */
    public static LibraryIndex load(LibraryStore store) throws IOException {
        Map<Long, String> servers = new HashMap<>();
        for (Source source : store.sources()) {
            servers.put(source.id(), source.serverId());
        }
        Map<String, List<EpisodeInfo>> episodes = new HashMap<>();
        IOException[] failure = {null};
        LibraryIndex index = LibraryIndex.build(store.files(), servers, store.matches(), metadata -> {
            if (metadata.provider() != Metadata.Provider.TMDB || metadata.kind() != Kind.SHOW) {
                return List.of();
            }
            return episodes.computeIfAbsent(metadata.id(), id -> {
                try {
                    return store.episodes(metadata.provider(), id);
                } catch (IOException e) {
                    failure[0] = e;
                    return List.of();
                }
            });
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return index;
    }
}
