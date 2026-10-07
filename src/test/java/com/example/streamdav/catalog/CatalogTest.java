package com.example.streamdav.catalog;

import com.example.streamdav.catalog.Catalog.Episode;
import com.example.streamdav.catalog.Catalog.Show;
import com.example.streamdav.catalog.LibraryScanner.ScannedFile;
import com.example.streamdav.library.RemoteFile;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogTest {

    private static ScannedFile file(String path) {
        List<String> parts = Arrays.asList(path.split("/"));
        String name = parts.getLast();
        List<String> folders = parts.subList(0, parts.size() - 1);
        RemoteFile remote = new RemoteFile(URI.create("https://dav.example.com/" + path.replace(" ", "%20")
                .replace("[", "%5B").replace("]", "%5D")), name, false, 1000, null);
        return new ScannedFile(remote, folders, ReleaseParser.parse(name, folders));
    }

    private static Catalog catalog(String... paths) {
        return Catalog.of(Arrays.stream(paths).map(CatalogTest::file).toList());
    }

    @Test
    void groupsVersionsOfAMovie() {
        Catalog catalog = catalog(
                "The.Matrix.1999.1080p.BluRay.mkv",
                "The.Matrix.1999.2160p.UHD.REMUX.mkv",
                "The Matrix (2021 Resurrections)/The.Matrix.Resurrections.2021.mkv",
                "Arrival.2016.mkv");

        assertEquals(List.of("Arrival", "The Matrix", "The Matrix Resurrections"),
                catalog.movies().stream().map(Catalog.Movie::title).toList());
        assertEquals(2, catalog.movies().get(1).versions().size(), "1080p and 4K are one movie");
        assertEquals(1999, catalog.movies().get(1).year());
    }

    @Test
    void groupsEpisodesIntoShowsAcrossNamingStyles() {
        Catalog catalog = catalog(
                "Mr.Robot.S01E02.1080p.mkv",
                "Mr. Robot/Season 1/Mr Robot S01E01.mkv",
                "mr robot s01e03.mkv",
                "Mr.Robot.S02E01.mkv");

        assertEquals(1, catalog.shows().size());
        Show show = catalog.shows().getFirst();
        assertEquals("Mr Robot", show.title(), "the most common spelling wins");
        assertEquals(List.of("S01E01", "S01E02", "S01E03", "S02E01"), show.episodes().stream().map(Episode::label).toList());
        assertEquals(List.of(1, 2), show.seasons());
    }

    @Test
    void ordersSpecialsLastAndLabelsThem() {
        Show show = catalog(
                "Doctor Who/Specials/S00E01.mkv",
                "Doctor Who/Season 2/S02E01.mkv",
                "Doctor Who/Season 1/S01E01.mkv").shows().getFirst();

        assertEquals(List.of(1, 2, 0), show.seasons());
        assertEquals(List.of("S01E01", "S02E01", "Special 1"), show.episodes().stream().map(Episode::label).toList());
    }

    @Test
    void labelsMultiEpisodeFiles() {
        Show show = catalog("Show.S03E05-07.mkv", "Show.S01E01E02.mkv").shows().getFirst();

        assertEquals(List.of("S01E01–E02", "S03E05–E07"), show.episodes().stream().map(Episode::label).toList());
    }

    @Test
    void keepsAbsoluteAnimeNumbering() {
        Show show = catalog(
                "[SubsPlease] One Piece - 1071 (1080p).mkv",
                "[SubsPlease] One Piece - 1070 (1080p).mkv",
                "[Erai-raws] One Piece - 1070 [1080p].mkv").shows().getFirst();

        assertTrue(show.anime());
        assertEquals(List.of("Episode 1070", "Episode 1071"), show.episodes().stream().map(Episode::label).toList());
        assertEquals(2, show.episodes().getFirst().versions().size(), "two releases of the same episode");
        assertEquals(Collections.singletonList(null), show.seasons(), "absolute numbering has no seasons");
    }

    @Test
    void separatesSeasonAndAbsoluteNumbering() {
        Show show = catalog("[SubsPlease] Jujutsu Kaisen - 24.mkv", "[SubsPlease] Jujutsu Kaisen S2 - 01.mkv").shows().getFirst();

        assertEquals(List.of("Episode 24", "S02E01"), show.episodes().stream().map(Episode::label).toList());
    }

    @Test
    void movieAndShowWithTheSameTitleStayApart() {
        Catalog catalog = catalog("Fargo.1996.mkv", "Fargo.S01E01.mkv");

        assertEquals(1, catalog.movies().size());
        assertEquals(1, catalog.shows().size());
        assertFalse(catalog.shows().getFirst().anime());
    }

    @Test
    void numberedFilesJoinTheShowTheirSiblingsBelongTo() {
        Catalog catalog = catalog(
                "Naruto Shippuden AV1/Naruto Shippuuden 013 [1080p BD AV1 Dual Audio].mkv",
                "Naruto Shippuden AV1/Naruto Shippuuden 121 [1080p BD AV1 Dual Audio].mkv",
                "Call Northside 777 (1948).mkv");

        assertEquals(List.of("Call Northside 777"), catalog.movies().stream().map(Catalog.Movie::title).toList());
        assertEquals(List.of("Episode 13", "Episode 121"),
                catalog.shows().getFirst().episodes().stream().map(Episode::label).toList());
    }

    @Test
    void filesWithoutTitlesAreSetAside() {
        Catalog catalog = catalog("1080p.mkv");

        assertTrue(catalog.movies().isEmpty());
        assertEquals(1, catalog.unsorted().size());
    }

    @Test
    void keysIgnoreCaseAccentsAndPunctuation() {
        assertEquals(Catalog.key("Amélie"), Catalog.key("AMELIE"));
        assertEquals(Catalog.key("Mr. Robot"), Catalog.key("mr robot"));
        assertEquals(Catalog.key("Spider-Man"), Catalog.key("Spider Man"));
    }
}
