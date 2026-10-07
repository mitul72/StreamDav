package com.example.streamdav.metadata;

import com.example.streamdav.metadata.StubApi.Response;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TmdbClientTest {

    @Test
    void searchesMoviesWithTheYear() throws Exception {
        try (StubApi api = new StubApi(request -> Response.ok("""
                {"page": 1, "results": [{"id": 603, "title": "The Matrix", "original_title": "The Matrix",
                  "release_date": "1999-03-30", "overview": "Neo.", "poster_path": "/p.jpg", "backdrop_path": "/b.jpg",
                  "genre_ids": [28, 878], "vote_average": 8.2, "original_language": "en"}]}
                """))) {
            List<Metadata> results = client(api, "v3key").searchMovies("The Matrix", 1999);

            Metadata matrix = results.getFirst();
            assertEquals("603", matrix.id());
            assertEquals("The Matrix", matrix.title());
            assertTrue(matrix.altTitles().isEmpty());
            assertEquals(1999, matrix.year());
            assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", matrix.posterUrl());
            assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", matrix.backdropUrl());
            assertEquals(List.of("Action", "Science Fiction"), matrix.genres());
            assertFalse(matrix.anime());

            String query = api.requests.getFirst().uri().getRawQuery();
            assertEquals("/search/movie", api.requests.getFirst().uri().getPath());
            assertTrue(query.contains("query=The+Matrix"), query);
            assertTrue(query.contains("year=1999"), query);
            assertTrue(query.contains("api_key=v3key"), query);
        }
    }

    @Test
    void readAccessTokensGoInTheHeader() throws Exception {
        try (StubApi api = new StubApi(request -> Response.ok("{\"results\": []}"))) {
            client(api, "eyJhbGciOiJIUzI1NiJ9.token").searchShows("Severance", null);

            StubApi.Request request = api.requests.getFirst();
            assertEquals("Bearer eyJhbGciOiJIUzI1NiJ9.token", request.authorization());
            assertFalse(request.uri().getRawQuery().contains("api_key"));
            assertFalse(request.uri().getRawQuery().contains("first_air_date_year"));
        }
    }

    @Test
    void japaneseAnimationIsAnime() throws Exception {
        try (StubApi api = new StubApi(request -> Response.ok("""
                {"results": [{"id": 1429, "name": "Attack on Titan", "original_name": "進撃の巨人",
                  "first_air_date": "2013-04-07", "genre_ids": [16, 10765], "original_language": "ja"}]}
                """))) {
            Metadata show = client(api, "k").searchShows("Attack on Titan", null).getFirst();

            assertTrue(show.anime());
            assertEquals(List.of("進撃の巨人"), show.altTitles());
            assertEquals(Metadata.Kind.SHOW, show.kind());
            assertNull(show.posterUrl());
            assertNull(show.rating());
        }
    }

    @Test
    void listsEpisodesOfEverySeason() throws Exception {
        try (StubApi api = new StubApi(request -> switch (request.uri().getPath()) {
            case "/tv/42" -> Response.ok("""
                    {"seasons": [{"season_number": 0, "episode_count": 1}, {"season_number": 1, "episode_count": 2},
                      {"season_number": 2, "episode_count": 0}]}
                    """);
            case "/tv/42/season/0" -> Response.ok("{\"episodes\": [{\"episode_number\": 1, \"name\": \"Special\"}]}");
            case "/tv/42/season/1" -> Response.ok("""
                    {"episodes": [{"episode_number": 1, "name": "Pilot", "air_date": "2020-01-05", "still_path": "/s.jpg"},
                      {"episode_number": 2, "name": "Second", "air_date": ""}]}
                    """);
            default -> new Response(404, "{}", null);
        })) {
            List<EpisodeInfo> episodes = client(api, "k").episodes("42");

            assertEquals(List.of(
                    new EpisodeInfo(0, 1, "Special", null, null, null),
                    new EpisodeInfo(1, 1, "Pilot", null, "https://image.tmdb.org/t/p/w300/s.jpg", LocalDate.of(2020, 1, 5)),
                    new EpisodeInfo(1, 2, "Second", null, null, null)), episodes);
        }
    }

    @Test
    void waitsOutRateLimits() throws Exception {
        int[] calls = {0};
        try (StubApi api = new StubApi(request -> ++calls[0] == 1
                ? new Response(429, "{}", "1") : Response.ok("{\"results\": []}"))) {
            assertTrue(client(api, "k").searchMovies("Heat", null).isEmpty());
            assertEquals(2, api.requests.size());
        }
    }

    @Test
    void reportsARejectedKey() throws Exception {
        try (StubApi api = new StubApi(request -> new Response(401, "{\"status_message\": \"Invalid API key\"}", null))) {
            MetadataException error = assertThrows(MetadataException.class, () -> client(api, "bad").searchMovies("Heat", null));
            assertEquals(401, error.status());
            assertEquals("TMDB rejected the API key.", error.getMessage());
        }
    }

    private static TmdbClient client(StubApi api, String key) {
        return new TmdbClient(HttpClient.newHttpClient(), api.uri(), key);
    }
}
