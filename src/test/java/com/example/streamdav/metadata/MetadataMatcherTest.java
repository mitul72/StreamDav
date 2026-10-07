package com.example.streamdav.metadata;

import com.example.streamdav.metadata.Metadata.Kind;
import com.example.streamdav.metadata.Metadata.Provider;
import com.example.streamdav.metadata.MetadataMatcher.Query;
import com.example.streamdav.metadata.MetadataMatcher.Source;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataMatcherTest {

    @Test
    void titlesWrittenDifferentlyAreTheSame() {
        assertEquals(1, MetadataMatcher.similarity("Mr. Robot", "mr robot"));
        assertEquals(1, MetadataMatcher.similarity("Tomorrow's Joe", "Tomorrows Joe"));
        assertEquals(1, MetadataMatcher.similarity("Spy x Family", "SPY×FAMILY"));
        assertEquals(1, MetadataMatcher.similarity("Amélie", "Amelie"));
        assertEquals(1, MetadataMatcher.similarity("Office", "The Office"));
        assertEquals(1, MetadataMatcher.similarity("Fast & Furious", "Fast and Furious"));
        assertTrue(MetadataMatcher.similarity("Ashita no Joe", "Ashita no Joe 2") < 1);
        assertTrue(MetadataMatcher.similarity("Heat", "Hot") < MetadataMatcher.ACCEPT);
    }

    @Test
    void picksTheExactTitleAndYear() throws Exception {
        Metadata remake = movie("1", "Dune", 1984);
        Metadata dune = movie("2", "Dune", 2021);
        Metadata partTwo = movie("3", "Dune: Part Two", 2024);
        MetadataMatcher matcher = new MetadataMatcher(fixed(List.of(remake, dune, partTwo)), null);

        assertEquals("2", matcher.match(new Query(Kind.MOVIE, "Dune", 2021, false)).orElseThrow().metadata().id());
        assertEquals("1", matcher.match(new Query(Kind.MOVIE, "Dune", 1984, false)).orElseThrow().metadata().id());
        // Without a year the provider's ranking decides.
        assertEquals("1", matcher.match(new Query(Kind.MOVIE, "Dune", null, false)).orElseThrow().metadata().id());
    }

    @Test
    void rejectsLooseMatches() throws Exception {
        MetadataMatcher matcher = new MetadataMatcher(fixed(List.of(movie("1", "The Heat", 2013))), null);

        assertTrue(matcher.match(new Query(Kind.MOVIE, "Heathers", 1988, false)).isEmpty());
        // Right title, but decades off: a different film.
        assertTrue(matcher.match(new Query(Kind.MOVIE, "The Heat", 1955, false)).isEmpty());
    }

    @Test
    void matchesAlternativeTitles() throws Exception {
        Metadata aot = new Metadata(Provider.ANILIST, "16498", Kind.SHOW, "Attack on Titan",
                List.of("Shingeki no Kyojin", "進撃の巨人"), 2013, null, null, null, List.of(), null, 25, true);
        MetadataMatcher matcher = new MetadataMatcher(null, fixed(List.of(aot)));

        assertEquals("16498", matcher.match(new Query(Kind.SHOW, "Shingeki no Kyojin", null, true)).orElseThrow().metadata().id());
    }

    @Test
    void animeGoesToAniListFirstAndOthersToTmdb() throws Exception {
        List<String> asked = new ArrayList<>();
        Source tmdb = recording("tmdb", asked, Map.of());
        Source aniList = recording("anilist", asked, Map.of());
        MetadataMatcher matcher = new MetadataMatcher(tmdb, aniList);

        matcher.match(new Query(Kind.SHOW, "Frieren", null, true));
        assertEquals(List.of("anilist", "tmdb"), asked);

        asked.clear();
        matcher.match(new Query(Kind.SHOW, "Severance", null, false));
        assertEquals(List.of("tmdb", "anilist"), asked);
    }

    @Test
    void theFallbackNeedsAnExactTitle() throws Exception {
        Metadata similar = new Metadata(Provider.ANILIST, "9", Kind.SHOW, "Castlevania: Nocturne", List.of(), 2023,
                null, null, null, List.of(), null, 8, true);
        Metadata exact = new Metadata(Provider.ANILIST, "7", Kind.SHOW, "Mushishi", List.of(), 2005,
                null, null, null, List.of(), null, 26, true);
        MetadataMatcher matcher = new MetadataMatcher(fixed(List.of()), fixed(List.of(similar, exact)));

        assertEquals(Optional.empty(), matcher.match(new Query(Kind.SHOW, "Castlevania", null, false)));
        assertEquals("7", matcher.match(new Query(Kind.SHOW, "Mushishi", null, false)).orElseThrow().metadata().id());
    }

    @Test
    void retriesWithoutTheYearWhenTheProviderFiltersByIt() throws Exception {
        List<Integer> years = new ArrayList<>();
        Source tmdb = new Source() {
            @Override
            public List<Metadata> search(String title, Integer year, Kind kind) {
                years.add(year);
                return year == null ? List.of(movie("5", "Heat", 1995)) : List.of();
            }

            @Override
            public boolean filtersByYear() {
                return true;
            }
        };

        Optional<MetadataMatcher.Match> match = new MetadataMatcher(tmdb, null).match(new Query(Kind.MOVIE, "Heat", 1996, false));

        assertEquals("5", match.orElseThrow().metadata().id());
        assertEquals(java.util.Arrays.asList(1996, null), years);
    }

    @Test
    void episodeCountsTellSeriesApart() throws Exception {
        Metadata sequel = anime("19647", "Hajime no Ippo: Rising", List.of("Hajime no Ippo 3"), 2013, 25);
        Metadata original = anime("263", "Hajime no Ippo: The Fighting!", List.of("Fighting Spirit"), 2000, 75);
        MetadataMatcher matcher = new MetadataMatcher(null, fixed(List.of(sequel, original)));

        assertEquals("263", matcher.match(new Query(Kind.SHOW, "Hajime no Ippo", null, true, 75)).orElseThrow().metadata().id());
        assertEquals("263", matcher.match(new Query(Kind.SHOW, "Fighting Spirit", 2000, false, null)).orElseThrow().metadata().id());
    }

    @Test
    void aSeriesNameMatchesItsSubtitledTitle() {
        Metadata titled = anime("1", "Hajime no Ippo: The Fighting!", List.of(), 2000, 75);
        double score = MetadataMatcher.titleScore("Hajime no Ippo", titled);
        assertTrue(score >= MetadataMatcher.ACCEPT && score < 1, String.valueOf(score));
    }

    @Test
    void ignoresCandidatesOfTheOtherKind() throws Exception {
        Metadata show = new Metadata(Provider.TMDB, "1", Kind.SHOW, "Fargo", List.of(), 2014, null, null, null,
                List.of(), null, null, false);
        assertTrue(new MetadataMatcher(fixed(List.of(show)), null).match(new Query(Kind.MOVIE, "Fargo", null, false)).isEmpty());
    }

    private static Metadata movie(String id, String title, int year) {
        return new Metadata(Provider.TMDB, id, Kind.MOVIE, title, List.of(), year, null, null, null, List.of(), null, null, false);
    }

    private static Metadata anime(String id, String title, List<String> alternatives, int year, int episodes) {
        return new Metadata(Provider.ANILIST, id, Kind.SHOW, title, alternatives, year, null, null, null, List.of(), null,
                episodes, true);
    }

    private static Source fixed(List<Metadata> results) {
        return (title, year, kind) -> results;
    }

    private static Source recording(String name, List<String> asked, Map<String, List<Metadata>> results) {
        return (title, year, kind) -> {
            asked.add(name);
            return results.getOrDefault(title, List.of());
        };
    }
}
