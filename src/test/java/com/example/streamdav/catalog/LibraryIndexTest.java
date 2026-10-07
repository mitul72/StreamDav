package com.example.streamdav.catalog;

import com.example.streamdav.catalog.LibraryIndex.Category;
import com.example.streamdav.catalog.LibraryIndex.EpisodeEntry;
import com.example.streamdav.catalog.LibraryIndex.Item;
import com.example.streamdav.library.RemoteFile;
import com.example.streamdav.metadata.EpisodeInfo;
import com.example.streamdav.metadata.Metadata;
import com.example.streamdav.metadata.Metadata.Kind;
import com.example.streamdav.metadata.Metadata.Provider;
import com.example.streamdav.store.LibraryStore.StoredFile;
import com.example.streamdav.store.LibraryStore.StoredMatch;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryIndexTest {
    private final List<StoredFile> files = new ArrayList<>();
    private final Map<String, StoredMatch> matches = new HashMap<>();
    private final Map<String, List<EpisodeInfo>> episodes = new HashMap<>();

    @Test
    void unmatchedEntriesUseTheirParsedTitles() {
        file(1, "The.Matrix.1999.1080p.mkv");
        file(2, "Severance.S01E01.mkv");

        LibraryIndex index = build();

        Item matrix = index.category(Category.MOVIES).getFirst();
        assertEquals("The Matrix", matrix.title());
        assertEquals(1999, matrix.year());
        assertEquals("movie|the matrix|1999", matrix.id());
        assertEquals("server", matrix.versions().getFirst().serverId());
        assertEquals("Severance", index.category(Category.SHOWS).getFirst().title());
    }

    @Test
    void entriesMatchedToTheSameTitleMerge() {
        file(1, "[Group] Naruto Shippuuden - 001.mkv");
        file(2, "[Other] Naruto Shippuden - 001 [1080p].mkv");
        file(3, "[Other] Naruto Shippuden - 002 [1080p].mkv");
        Metadata naruto = anime("1735", "Naruto: Shippuden");
        match(LibraryIndex.showKey("Naruto Shippuuden"), naruto);
        match(LibraryIndex.showKey("Naruto Shippuden"), naruto);

        LibraryIndex index = build();

        assertEquals(1, index.items().size());
        Item show = index.items().getFirst();
        assertEquals("Naruto: Shippuden", show.title());
        assertEquals(Category.ANIME, show.category());
        assertEquals(2, show.episodes().size());
        assertEquals(2, show.episodes().getFirst().versions().size());
        assertEquals(2, show.itemKeys().size());
    }

    @Test
    void metadataDecidesWhatIsAnime() {
        file(1, "Dragon.Ball.Super.S01E02.1080p.mkv");
        Metadata dbs = new Metadata(Provider.TMDB, "62715", Kind.SHOW, "Dragon Ball Super", List.of(), 2015, null,
                null, null, List.of("Animation"), null, null, true);
        match(LibraryIndex.showKey("Dragon Ball Super"), dbs);

        assertEquals(Category.ANIME, build().items().getFirst().category());
    }

    @Test
    void episodesGetTheirTitlesFromTheProvider() {
        file(1, "Severance.S01E02.mkv");
        file(2, "[Group] Frieren - 03.mkv");
        Metadata severance = new Metadata(Provider.TMDB, "95396", Kind.SHOW, "Severance", List.of(), 2022, null,
                null, null, List.of(), null, null, false);
        Metadata frieren = new Metadata(Provider.TMDB, "209867", Kind.SHOW, "Frieren", List.of(), 2023, null,
                null, null, List.of(), null, null, true);
        match(LibraryIndex.showKey("Severance"), severance);
        match(LibraryIndex.showKey("Frieren"), frieren);
        episodes.put("95396", List.of(info(1, 1, "Good News About Hell"), info(1, 2, "Half Loop")));
        // Absolute episode 3 is the first episode of season 2 when season 1 has two; specials don't count.
        episodes.put("209867", List.of(info(0, 1, "Special"), info(1, 1, "One"), info(1, 2, "Two"), info(2, 1, "Three")));

        LibraryIndex index = build();

        EpisodeEntry halfLoop = index.category(Category.SHOWS).getFirst().episodes().getFirst();
        assertEquals("Half Loop", halfLoop.info().orElseThrow().title());
        EpisodeEntry three = index.category(Category.ANIME).getFirst().episodes().getFirst();
        assertEquals("Three", three.info().orElseThrow().title());
        assertEquals("Episode 3", three.label());
    }

    @Test
    void recentlyAddedOrdersByTheNewestFile() {
        file(1, "Old.Movie.1990.mkv");
        file(5, "Show.S01E01.mkv");
        file(9, "Show.S01E02.mkv");
        file(3, "New.Movie.2020.mkv");

        List<String> recent = build().recentlyAdded(10).stream().map(Item::title).toList();

        assertEquals(List.of("Show", "New Movie", "Old Movie"), recent);
    }

    @Test
    void extrasNeverShowUp() {
        file(1, "Movie.2020.mkv");
        file(2, "Movie 2020 Extras - Trailer.mkv");

        LibraryIndex index = build();

        assertEquals(1, index.items().size());
        assertEquals(1, index.items().getFirst().versions().size());
        assertTrue(index.item("movie|movie|2020").isPresent());
    }

    private void file(int day, String name) {
        RemoteFile remote = new RemoteFile(URI.create("https://dav.example/" + files.size()), name, false, 1, null);
        files.add(new StoredFile(files.size(), 7, remote, List.of(), Instant.parse("2026-01-01T00:00:00Z").plusSeconds(day * 86_400L)));
    }

    private void match(String key, Metadata metadata) {
        matches.put(key, new StoredMatch(key, Optional.of(metadata), Instant.now(), false));
    }

    private LibraryIndex build() {
        return LibraryIndex.build(files, Map.of(7L, "server"), matches, metadata -> episodes.getOrDefault(metadata.id(), List.of()));
    }

    private static Metadata anime(String id, String title) {
        return new Metadata(Provider.ANILIST, id, Kind.SHOW, title, List.of(), null, null, null, null, List.of(), null, null, true);
    }

    private static EpisodeInfo info(int season, int episode, String title) {
        return new EpisodeInfo(season, episode, title, null, null, null);
    }
}
