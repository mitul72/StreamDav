package com.example.streamdav.catalog;

import com.example.streamdav.catalog.ParsedRelease.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseParserTest {

    private static ParsedRelease parse(String path) {
        List<String> parts = List.of(path.split("/"));
        return ReleaseParser.parse(parts.getLast(), parts.subList(0, parts.size() - 1));
    }

    private static void movie(String path, String title, Integer year) {
        ParsedRelease parsed = parse(path);
        assertAll(path,
                () -> assertEquals(Kind.MOVIE, parsed.kind(), "kind"),
                () -> assertEquals(title, parsed.title(), "title"),
                () -> assertEquals(year, parsed.year(), "year"));
    }

    private static void episode(String path, String title, Integer season, List<Integer> episodes, boolean anime) {
        ParsedRelease parsed = parse(path);
        assertAll(path,
                () -> assertEquals(Kind.EPISODE, parsed.kind(), "kind"),
                () -> assertEquals(title, parsed.title(), "title"),
                () -> assertEquals(season, parsed.season(), "season"),
                () -> assertEquals(episodes, parsed.episodes(), "episodes"),
                () -> assertEquals(anime, parsed.anime(), "anime"));
    }

    @Test
    void sceneEpisodes() {
        episode("Breaking.Bad.S01E01.Pilot.1080p.BluRay.x264-ROVERS.mkv", "Breaking Bad", 1, List.of(1), false);
        episode("The.Office.US.S09E23.1080p.WEB-DL.DD5.1.H.264.mkv", "The Office US", 9, List.of(23), false);
        episode("Mr.Robot.s02e05.720p.HDTV.x264.mkv", "Mr Robot", 2, List.of(5), false);
        episode("Severance S02 E10 2160p ATVP WEB-DL.mkv", "Severance", 2, List.of(10), false);
        episode("Friends 1x02 The One With the Sonogram.avi", "Friends", 1, List.of(2), false);
        episode("Lost - Season 1 Episode 4 - Walkabout.mp4", "Lost", 1, List.of(4), false);
    }

    @Test
    void multiEpisodeFiles() {
        episode("Doctor.Who.2005.S01E01E02.mkv", "Doctor Who", 1, List.of(1, 2), false);
        episode("Show.S03E05-E06.1080p.mkv", "Show", 3, List.of(5, 6), false);
        episode("Show.S03E05-07.mkv", "Show", 3, List.of(5, 6, 7), false);
        episode("Show S01E09 1080p.mkv", "Show", 1, List.of(9), false);
    }

    @Test
    void yearsInShowTitles() {
        ParsedRelease parsed = parse("Doctor Who (2005) - S01E01 - Rose.mkv");
        assertEquals("Doctor Who", parsed.title());
        assertEquals(2005, parsed.year());
        assertEquals(1, parsed.season());
    }

    @Test
    void titlesComeFromFoldersWhenFilesOnlyHaveNumbers() {
        episode("TV/The Expanse/Season 03/S03E04.mkv", "The Expanse", 3, List.of(4), false);
        episode("The Expanse (2015)/Season 1/01 - Dulcinea.mkv", "The Expanse", 1, List.of(1), false);
        episode("The Expanse/Season 2/E05.mkv", "The Expanse", 2, List.of(5), false);
        episode("The Expanse/Specials/S00E01.mkv", "The Expanse", 0, List.of(1), false);
        episode("Severance.S02.2160p.ATVP.WEB-DL.DDP5.1.DV.HDR.H.265-FLUX/03.mkv", "Severance", 2, List.of(3), false);
    }

    @Test
    void seasonPacksAsRealDebridLaysThemOut() {
        episode("torrents/Severance.S02.2160p.ATVP.WEB-DL.DDP5.1.DV.HDR.H.265-FLUX/Severance.S02E03.Who.Is.Alive.2160p.mkv",
                "Severance", 2, List.of(3), false);
    }

    @Test
    void animeFansubReleases() {
        episode("[SubsPlease] Sousou no Frieren - 12 (1080p) [ABCD1234].mkv", "Sousou no Frieren", null, List.of(12), true);
        episode("[Erai-raws] One Piece - 1071 [1080p][Multiple Subtitle].mkv", "One Piece", null, List.of(1071), true);
        episode("[SubsPlease] Jujutsu Kaisen S2 - 05 (1080p).mkv", "Jujutsu Kaisen", 2, List.of(5), true);
        episode("[Judas] Vinland Saga - S02E03.mkv", "Vinland Saga", 2, List.of(3), true);
        episode("[HorribleSubs] Made in Abyss - 07v2 [720p].mkv", "Made in Abyss", null, List.of(7), true);
        episode("Anime/Frieren/[SubsPlease] Sousou no Frieren - 03 (1080p).mkv", "Sousou no Frieren", null, List.of(3), true);
    }

    @Test
    void animeBatchFoldersSupplySeasons() {
        episode("[Group] Spy x Family (Season 2) [1080p]/Season 2/[Group] Spy x Family - 04.mkv",
                "Spy x Family", 2, List.of(4), true);
    }

    @Test
    void episodeWords() {
        episode("Naruto Shippuden Episode 245.mkv", "Naruto Shippuden", null, List.of(245), false);
        episode("Some Show Ep 12.mp4", "Some Show", null, List.of(12), false);
    }

    @Test
    void sceneMovies() {
        movie("The.Matrix.1999.1080p.BluRay.x264-GRP.mkv", "The Matrix", 1999);
        movie("Inception (2010) [2160p] [HDR].mkv", "Inception", 2010);
        movie("Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv", "Dune Part Two", 2024);
        movie("Spider-Man Into the Spider-Verse (2018).mp4", "Spider-Man Into the Spider-Verse", 2018);
        movie("Amélie.2001.1080p.mkv", "Amélie", 2001);
    }

    @Test
    void yearsInsideMovieTitles() {
        movie("Blade.Runner.2049.2017.1080p.BluRay.mkv", "Blade Runner 2049", 2017);
        movie("2001.A.Space.Odyssey.1968.2160p.UHD.mkv", "2001 A Space Odyssey", 1968);
        movie("1917.2019.1080p.mkv", "1917", 2019);
        movie("2012 (2009).mkv", "2012", 2009);
    }

    @Test
    void moviesWithoutYears() {
        movie("Charlotte's Web.mkv", "Charlotte's Web", null);
        movie("Some.Movie.1080p.WEBRip.x265.mkv", "Some Movie", null);
        movie("Se7en.mkv", "Se7en", null);
        movie("Apollo 13.mkv", "Apollo 13", null);
        movie("District 9.mkv", "District 9", null);
    }

    @Test
    void movieFoldersNameTheirFiles() {
        movie("Movies/Arrival (2016)/arrival.mkv", "Arrival", 2016);
        movie("torrents/The.Matrix.1999.2160p.UHD.BluRay.REMUX/1080p.mkv", "The Matrix", 1999);
        movie("Movies/Heat (1995)/Heat (1995).mkv", "Heat", 1995);
    }

    /** Names from a real Real-Debrid library that the first version got wrong. */
    @Test
    void namesFromARealLibrary() {
        movie("Saga Harry Potter 4k/1-Harry.Potter.Y.La.Piedra.Filosofal.(2001)(Spanish.English.Subs).UHD.1440p.HEVC-AC3.by.Geot.mkv",
                "Harry Potter Y La Piedra Filosofal", 2001);
        movie("Saga Harry Potter 4k/7-Harry.Potter.Y.Las.Reliquias.De.La.Muerte.Parte.1.(2010)(Spanish.English.Subs).mkv",
                "Harry Potter Y Las Reliquias De La Muerte Parte 1", 2010);
        movie("The.Social.Network.KP.HDR.2160p-SOFCJ.mkv/The.Social.Network.KP.HDR.2160p-SOFCJ.mkv",
                "The Social Network KP", null);
        episode("[One Pace][1-7] Romance Dawn [1080p]/[One Pace][1] Romance Dawn 01 [1080p][FB72C13F].mkv",
                "Romance Dawn", null, List.of(1), true);
        episode("[One Pace][1-7] Romance Dawn [1080p]/[One Pace][5-7] Romance Dawn 04 [1080p][9A48E3EB].mkv",
                "Romance Dawn", null, List.of(4), true);
        ParsedRelease fightingSpirit = parse(
                "Fighting Spirit 2000 S01 1080p BluRay x264 DD 2.0 MP3 2.0-CriptidZzz/Fighting Spirit - 1x01 - The First Step.mkv");
        assertEquals("Fighting Spirit", fightingSpirit.title());
        assertEquals(2000, fightingSpirit.year(), "the year comes from the season pack's folder");
        episode("Naruto Shippuden AV1/Naruto Shippuuden 013 [1080p BD AV1 Dual Audio].mkv",
                "Naruto Shippuuden", null, List.of(13), false);
        episode("[AnimeRG] Dragon Ball KAI [DUAL-AUDIO] [1080p] [x265] [pseudo]/[AnimeRG] Dragon Ball KAI - 012 [1080p] [x265] [pseudo].mkv",
                "Dragon Ball KAI", null, List.of(12), true);
        episode("[nekotan] Hajime no Ippo S01 v2 (BD Remux 1080p x264 8-bit FLAC) [Dual Audio]/"
                + "[nekotan] Hajime no Ippo S01E01 v2 (BD Remux 1080p x264 8-bit FLAC) [Dual Audio].mkv",
                "Hajime no Ippo", 1, List.of(1), true);
    }

    /** More names from the same library, from a large film collection. */
    @Test
    void namesFromAFilmCollection() {
        String collection = "moviesbyrizzo 650 top all-time movies collection 1080p 598GB/";
        movie(collection + "Black Book (Zwartboek) (2006) 1080p (moviesbyrizzo upl).mp4", "Black Book", 2006);
        movie(collection + "A Doll's House (Et Dukkehjem - Norway 1973) 720p (moviesbyrizzo).mp4", "A Doll's House", 1973);
        movie(collection + "Breaker Morant (Australia - 1980) 1080p (moviesbyrizzo upl).mp4", "Breaker Morant", 1980);
        movie(collection + "100%  Arabica  (France) (moviesbyrizzo) (engsubs).mp4", "100% Arabica", null);
        movie(collection + "(1968) Planet Of The Apes 1080p (moviesbyrizzo upl).mp4", "Planet Of The Apes", 1968);
        movie("Amazing Films 8 Mp4 1080p/2017a Dave Chappelle The Age Of Spin.mp4", "Dave Chappelle The Age Of Spin", 2017);
        movie("Amazing Films 8 Mp4 1080p/E1 The Equalizer (2014) 1080p Surround.mp4", "E1 The Equalizer", 2014);
        movie("(500) Days of Summer (2009).mkv", "(500) Days of Summer", 2009);
        movie("2001 A Space Odyssey.mkv", "2001 A Space Odyssey", null);
    }

    @Test
    void collectionNumberingOnlyStripsInsideCollections() {
        movie("Movies/3-Iron (2004).mkv", "3-Iron", 2004);
        // The accepted trade-off: inside a collection, a title that really starts with "N-" loses it. Metadata
        // matching by year still finds the right film.
        movie("Kim Ki-duk Collection/3-Iron (2004).mkv", "Iron", 2004);
    }

    @Test
    void absoluteNumberingIsKnown() {
        assertTrue(parse("[SubsPlease] Frieren - 12.mkv").absolute());
        assertFalse(parse("Show.S01E01.mkv").absolute());
        assertFalse(parse("The.Matrix.1999.mkv").absolute());
    }

    @Test
    void recognisesSamplesAndExtras() {
        assertTrue(ReleaseParser.isExtra("sample-the.matrix.1999.mkv", List.of()));
        assertTrue(ReleaseParser.isExtra("The.Matrix.1999.Sample.mkv", List.of()));
        assertTrue(ReleaseParser.isExtra("Making Of.mkv", List.of("The Matrix (1999)", "Extras")));
        assertTrue(ReleaseParser.isExtra("trailer.mkv", List.of("Movie", "Featurettes")));
        assertFalse(ReleaseParser.isExtra("The.Matrix.1999.mkv", List.of("Movies")));
        assertFalse(ReleaseParser.isExtra("Sampled.Lives.2020.mkv", List.of()));
    }
}
