package com.example.streamdav.metadata;

import com.example.streamdav.metadata.StubApi.Response;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AniListClientTest {

    @Test
    void searchesAnimeAndPrefersEnglishTitles() throws Exception {
        try (StubApi api = new StubApi(request -> Response.ok("""
                {"data": {"Page": {"media": [
                  {"id": 16498, "format": "TV", "episodes": 25, "seasonYear": 2013,
                   "title": {"romaji": "Shingeki no Kyojin", "english": "Attack on Titan", "native": "進撃の巨人"},
                   "synonyms": ["AoT"], "description": "Humanity <i>lives</i> inside walls.<br><br>(Source: Kodansha)",
                   "genres": ["Action", "Drama"], "averageScore": 85, "isAdult": false,
                   "coverImage": {"extraLarge": "https://img/xl.jpg", "large": "https://img/l.jpg"},
                   "bannerImage": "https://img/banner.jpg"},
                  {"id": 1, "format": "OVA", "title": {"romaji": "Hidden"}, "isAdult": true}
                ]}}}
                """))) {
            List<Metadata> results = client(api).search("Shingeki no Kyojin", Metadata.Kind.SHOW);

            assertEquals(1, results.size());
            Metadata aot = results.getFirst();
            assertEquals(Metadata.Provider.ANILIST, aot.provider());
            assertEquals("16498", aot.id());
            assertEquals("Attack on Titan", aot.title());
            assertEquals(List.of("Shingeki no Kyojin", "進撃の巨人", "AoT"), aot.altTitles());
            assertEquals(2013, aot.year());
            assertEquals(25, aot.episodeCount());
            assertEquals(8.5, aot.rating());
            assertEquals("Humanity lives inside walls.", aot.overview());
            assertEquals("https://img/xl.jpg", aot.posterUrl());
            assertEquals("https://img/banner.jpg", aot.backdropUrl());
            assertTrue(aot.anime());

            String body = api.requests.getFirst().body();
            assertTrue(body.contains("\"search\": \"Shingeki no Kyojin\""), body);
            assertTrue(body.contains("\"TV\""), body);
        }
    }

    @Test
    void moviesSearchOnlyMovies() throws Exception {
        try (StubApi api = new StubApi(request -> Response.ok("""
                {"data": {"Page": {"media": [{"id": 21519, "format": "MOVIE", "startDate": {"year": 2016},
                  "title": {"romaji": "Kimi no Na wa.", "english": "Your Name."}}]}}}
                """))) {
            Metadata movie = client(api).search("Kimi no Na wa", Metadata.Kind.MOVIE).getFirst();

            assertEquals(Metadata.Kind.MOVIE, movie.kind());
            assertEquals(2016, movie.year());
            assertTrue(api.requests.getFirst().body().contains("\"formats\": [\"MOVIE\"]"));
        }
    }

    @Test
    void cleansDescriptions() {
        assertEquals("Line one\nLine two & \"three\"",
                AniListClient.plainText("Line one <br>\nLine two &amp; &quot;three&quot;\n\n(Source: Crunchyroll)"));
    }

    private static AniListClient client(StubApi api) {
        return new AniListClient(HttpClient.newHttpClient(), api.uri(), Duration.ZERO);
    }
}
