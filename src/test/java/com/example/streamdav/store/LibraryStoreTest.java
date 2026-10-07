package com.example.streamdav.store;

import com.example.streamdav.library.RemoteFile;
import com.example.streamdav.metadata.EpisodeInfo;
import com.example.streamdav.metadata.Metadata;
import com.example.streamdav.store.LibraryStore.FoundFile;
import com.example.streamdav.store.LibraryStore.Source;
import com.example.streamdav.store.LibraryStore.StoredFile;
import com.example.streamdav.store.LibraryStore.StoredMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryStoreTest {
    @TempDir
    Path dir;

    @Test
    void sourcesAreAddedOnce() throws Exception {
        try (LibraryStore store = LibraryStore.open(dir.resolve("library.db"))) {
            Source first = store.addSource("server-1", URI.create("https://dav.example/torrents/"), "torrents");
            Source again = store.addSource("server-1", URI.create("https://dav.example/torrents/"), "torrents");

            assertEquals(first.id(), again.id());
            assertEquals(List.of(first), store.sources());
            assertNull(first.lastScan());
        }
    }

    @Test
    void rescansKeepWhenFilesWereFirstFoundAndDropMissingOnes() throws Exception {
        try (LibraryStore store = LibraryStore.open(dir.resolve("library.db"))) {
            Source source = store.addSource("s", URI.create("https://dav.example/m/"), "m");
            Instant first = Instant.parse("2026-01-01T00:00:00Z");
            Instant second = Instant.parse("2026-02-01T00:00:00Z");
            store.replaceFiles(source.id(), List.of(found("a.mkv", "Show"), found("b.mkv")), first);
            store.replaceFiles(source.id(), List.of(found("a.mkv", "Show", "Season 1"), found("c.mkv")), second);

            Map<String, StoredFile> files = byName(store.files());
            assertEquals(2, files.size());
            assertEquals(first, files.get("a.mkv").added());
            assertEquals(List.of("Show", "Season 1"), files.get("a.mkv").folders());
            assertEquals(second, files.get("c.mkv").added());
            assertTrue(files.get("c.mkv").folders().isEmpty());
            assertEquals(1234, files.get("c.mkv").file().size());
            assertEquals(second, store.sources().getFirst().lastScan());
        }
    }

    @Test
    void anIncompleteScanDropsNothing() throws Exception {
        try (LibraryStore store = LibraryStore.open(dir.resolve("library.db"))) {
            Source source = store.addSource("s", URI.create("https://dav.example/m/"), "m");
            store.replaceFiles(source.id(), List.of(found("a.mkv"), found("b.mkv")), Instant.parse("2026-01-01T00:00:00Z"));
            store.addFiles(source.id(), List.of(found("c.mkv")), Instant.parse("2026-02-01T00:00:00Z"));

            assertEquals(3, store.files().size());
        }
    }

    @Test
    void unmatchedItemsCanBeForgotten() throws Exception {
        try (LibraryStore store = LibraryStore.open(dir.resolve("library.db"))) {
            Metadata heat = new Metadata(Metadata.Provider.TMDB, "949", Metadata.Kind.MOVIE, "Heat", List.of(), 1995,
                    null, null, null, List.of(), null, null, false);
            store.saveMatch("movie|heat|1995", Optional.of(heat), Instant.now(), false);
            store.saveMatch("movie|nothing|null", Optional.empty(), Instant.now(), false);

            store.forgetUnmatched();

            assertEquals(java.util.Set.of("movie|heat|1995"), store.matches().keySet());
        }
    }

    @Test
    void removingASourceRemovesItsFiles() throws Exception {
        try (LibraryStore store = LibraryStore.open(dir.resolve("library.db"))) {
            Source kept = store.addSource("s", URI.create("https://dav.example/a/"), "a");
            Source removed = store.addSource("t", URI.create("https://dav.example/b/"), "b");
            store.replaceFiles(kept.id(), List.of(found("kept.mkv")), Instant.now());
            store.replaceFiles(removed.id(), List.of(found("gone.mkv")), Instant.now());

            store.removeServer("t");

            assertEquals(List.of("kept.mkv"), store.files().stream().map(file -> file.file().name()).toList());
        }
    }

    @Test
    void matchesSurviveReopening() throws Exception {
        Path file = dir.resolve("library.db");
        Metadata joe = new Metadata(Metadata.Provider.ANILIST, "2402", Metadata.Kind.SHOW, "Tomorrow's Joe",
                List.of("Ashita no Joe", "あしたのジョー"), 1970, "Boxing.", "https://img/p.jpg", null,
                List.of("Drama", "Sports"), 8.1, 79, true);
        Instant at = Instant.parse("2026-03-01T10:00:00Z");
        try (LibraryStore store = LibraryStore.open(file)) {
            store.saveMatch("show|ashita no joe", Optional.of(joe), at, false);
            store.saveMatch("movie|unknown|null", Optional.empty(), at, false);
        }
        try (LibraryStore store = LibraryStore.open(file)) {
            Map<String, StoredMatch> matches = store.matches();
            assertEquals(Optional.of(joe), matches.get("show|ashita no joe").metadata());
            assertEquals(at, matches.get("show|ashita no joe").matchedAt());
            assertFalse(matches.get("show|ashita no joe").manual());
            assertTrue(matches.get("movie|unknown|null").metadata().isEmpty());
        }
    }

    @Test
    void episodeListsAreReplacedWhole() throws Exception {
        try (LibraryStore store = LibraryStore.open(dir.resolve("library.db"))) {
            Instant at = Instant.parse("2026-03-01T10:00:00Z");
            store.saveEpisodes(Metadata.Provider.TMDB, "42", List.of(
                    new EpisodeInfo(1, 1, "Pilot", "Begins.", "https://img/s.jpg", LocalDate.of(2020, 1, 5)),
                    new EpisodeInfo(1, 2, "Old", null, null, null)), at);
            store.saveEpisodes(Metadata.Provider.TMDB, "42", List.of(
                    new EpisodeInfo(1, 1, "Pilot", "Begins.", "https://img/s.jpg", LocalDate.of(2020, 1, 5))), at);

            assertEquals(List.of(new EpisodeInfo(1, 1, "Pilot", "Begins.", "https://img/s.jpg", LocalDate.of(2020, 1, 5))),
                    store.episodes(Metadata.Provider.TMDB, "42"));
            assertEquals(Optional.of(at), store.episodesFetched(Metadata.Provider.TMDB, "42"));
            assertTrue(store.episodesFetched(Metadata.Provider.TMDB, "43").isEmpty());
        }
    }

    private static FoundFile found(String name, String... folders) {
        return new FoundFile(new RemoteFile(URI.create("https://dav.example/m/" + name),
                name, false, 1234, Instant.parse("2025-12-24T00:00:00Z")), List.of(folders));
    }

    private static Map<String, StoredFile> byName(List<StoredFile> files) {
        return files.stream().collect(java.util.stream.Collectors.toMap(file -> file.file().name(), file -> file));
    }
}
