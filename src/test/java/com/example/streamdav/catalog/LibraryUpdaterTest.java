package com.example.streamdav.catalog;

import com.example.streamdav.catalog.LibraryIndex.Category;
import com.example.streamdav.library.MediaLibrary;
import com.example.streamdav.library.RemoteFile;
import com.example.streamdav.metadata.Metadata;
import com.example.streamdav.metadata.Metadata.Kind;
import com.example.streamdav.metadata.Metadata.Provider;
import com.example.streamdav.metadata.MetadataMatcher;
import com.example.streamdav.store.LibraryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryUpdaterTest {
    private static final URI ROOT = URI.create("https://dav.example/torrents/");

    @TempDir
    Path dir;

    private final Map<URI, List<RemoteFile>> folders = new HashMap<>();
    private final Set<URI> broken = new HashSet<>();
    private final List<String> searched = new CopyOnWriteArrayList<>();
    private Instant now = Instant.parse("2026-05-01T00:00:00Z");

    @Test
    void scansMatchesAndShowsTheLibrary() throws Exception {
        add("The.Matrix.1999.1080p.mkv", "[Group] Frieren - 01.mkv", "[Group] Frieren - 02.mkv", "Unknown.Film.2001.mkv");
        try (LibraryStore store = LibraryStore.open(dir.resolve("library.db"))) {
            store.addSource("server", ROOT, "torrents");

            LibraryUpdater.Report report = updater(store).refresh(message -> { });

            assertEquals(4, report.files());
            assertEquals(2, report.matched());
            assertTrue(report.problems().isEmpty());
            LibraryIndex index = LibraryUpdater.load(store);
            assertEquals(List.of("Frieren: Beyond Journey's End"), titles(index.category(Category.ANIME)));
            assertEquals(List.of("The Matrix", "Unknown Film"), titles(index.category(Category.MOVIES)));
            assertEquals(2, index.category(Category.ANIME).getFirst().episodes().size());
        }
    }

    @Test
    void matchedTitlesAreNotSearchedAgainAndUnmatchedOnesWait() throws Exception {
        add("The.Matrix.1999.1080p.mkv", "Unknown.Film.2001.mkv");
        try (LibraryStore store = LibraryStore.open(dir.resolve("library.db"))) {
            store.addSource("server", ROOT, "torrents");
            updater(store).refresh(message -> { });
            searched.clear();

            updater(store).refresh(message -> { });
            assertTrue(searched.isEmpty(), searched.toString());

            now = now.plus(LibraryUpdater.RETRY_UNMATCHED).plusSeconds(1);
            updater(store).refresh(message -> { });
            assertEquals(List.of("Unknown Film"), searched);
        }
    }

    @Test
    void lookupFailuresAreRetriedNextTime() throws Exception {
        add("Heat.1995.mkv");
        try (LibraryStore store = LibraryStore.open(dir.resolve("library.db"))) {
            store.addSource("server", ROOT, "torrents");
            MetadataMatcher failing = new MetadataMatcher((title, year, kind) -> {
                throw new IOException("TMDB rejected the API key.");
            }, null);

            LibraryUpdater.Report report = new LibraryUpdater(store, this::open, failing, null, clock()).refresh(message -> { });

            assertEquals(List.of("TMDB rejected the API key."), report.problems());
            assertTrue(store.matches().isEmpty());
        }
    }

    @Test
    void aFailedFolderKeepsItsFiles() throws Exception {
        add("Movies/Heat.1995.mkv", "Shows/Severance.S01E01.mkv");
        try (LibraryStore store = LibraryStore.open(dir.resolve("library.db"))) {
            store.addSource("server", ROOT, "torrents");
            updater(store).refresh(message -> { });

            broken.add(ROOT.resolve("Shows/"));
            LibraryUpdater.Report report = updater(store).refresh(message -> { });

            assertEquals(List.of("Couldn't open 1 folders in torrents."), report.problems());
            assertEquals(2, store.files().size());
        }
    }

    @Test
    void anUnreachableServerIsReported() throws Exception {
        try (LibraryStore store = LibraryStore.open(dir.resolve("library.db"))) {
            store.addSource("gone", ROOT, "torrents");

            LibraryUpdater.Report report = new LibraryUpdater(store, id -> {
                throw new IOException("The server is no longer saved.");
            }, matcher(), null, clock()).refresh(message -> { });

            assertEquals(List.of("Couldn't scan torrents: The server is no longer saved."), report.problems());
        }
    }

    private LibraryUpdater updater(LibraryStore store) {
        return new LibraryUpdater(store, this::open, matcher(), null, clock());
    }

    private MetadataMatcher matcher() {
        Map<String, Metadata> known = Map.of(
                "The Matrix", new Metadata(Provider.TMDB, "603", Kind.MOVIE, "The Matrix", List.of(), 1999, null, null,
                        null, List.of(), null, null, false),
                "Frieren", new Metadata(Provider.ANILIST, "154587", Kind.SHOW, "Frieren: Beyond Journey's End",
                        List.of("Sousou no Frieren", "Frieren"), 2023, null, null, null, List.of(), null, 28, true));
        MetadataMatcher.Source source = (title, year, kind) -> {
            searched.add(title);
            return known.containsKey(title) ? List.of(known.get(title)) : List.of();
        };
        return new MetadataMatcher(source, null);
    }

    private Clock clock() {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    private MediaLibrary open(String serverId) {
        return new MediaLibrary() {
            @Override
            public URI root() {
                return ROOT;
            }

            @Override
            public List<RemoteFile> list(URI folder) throws IOException {
                if (broken.contains(folder)) {
                    throw new IOException("HTTP 500");
                }
                return folders.getOrDefault(folder, List.of());
            }

            @Override
            public URI streamUrl(RemoteFile file) {
                return file.uri();
            }
        };
    }

    private void add(String... paths) {
        for (String path : paths) {
            String[] parts = path.split("/");
            String parent = "";
            for (int i = 0; i < parts.length; i++) {
                boolean directory = i < parts.length - 1;
                String child = parent + parts[i].replace(" ", "%20").replace("[", "%5B").replace("]", "%5D") + (directory ? "/" : "");
                RemoteFile entry = new RemoteFile(ROOT.resolve(child), parts[i], directory, directory ? -1 : 1000, null);
                List<RemoteFile> entries = folders.computeIfAbsent(ROOT.resolve(parent), uri -> new ArrayList<>());
                if (!entries.contains(entry)) {
                    entries.add(entry);
                }
                parent = child;
            }
        }
    }

    private static List<String> titles(List<LibraryIndex.Item> items) {
        return items.stream().map(LibraryIndex.Item::title).toList();
    }
}
