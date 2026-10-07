package com.example.streamdav.metadata;

import com.example.streamdav.metadata.Metadata.Kind;
import com.example.streamdav.metadata.Metadata.Provider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Movie Database (TMDB) API, v3: searches movies and shows and lists a show's episodes.
 *
 * <p>Takes either a v3 API key or a v4 read access token (a JWT, starting {@code eyJ}).
 */
public final class TmdbClient {
    public static final URI API = URI.create("https://api.themoviedb.org/3/");
    private static final String IMAGES = "https://image.tmdb.org/t/p/";
    private static final Set<String> ANIME_LANGUAGES = Set.of("ja", "zh", "ko");
    private static final int ANIMATION = 16;
    private static final Map<Integer, String> GENRES = Map.ofEntries(
            Map.entry(28, "Action"), Map.entry(12, "Adventure"), Map.entry(16, "Animation"), Map.entry(35, "Comedy"),
            Map.entry(80, "Crime"), Map.entry(99, "Documentary"), Map.entry(18, "Drama"), Map.entry(10751, "Family"),
            Map.entry(14, "Fantasy"), Map.entry(36, "History"), Map.entry(27, "Horror"), Map.entry(10402, "Music"),
            Map.entry(9648, "Mystery"), Map.entry(10749, "Romance"), Map.entry(878, "Science Fiction"),
            Map.entry(10770, "TV Movie"), Map.entry(53, "Thriller"), Map.entry(10752, "War"), Map.entry(37, "Western"),
            Map.entry(10759, "Action & Adventure"), Map.entry(10762, "Kids"), Map.entry(10763, "News"),
            Map.entry(10764, "Reality"), Map.entry(10765, "Sci-Fi & Fantasy"), Map.entry(10766, "Soap"),
            Map.entry(10767, "Talk"), Map.entry(10768, "War & Politics"));

    private final ApiHttp http;
    private final URI api;
    private final String key;

    public TmdbClient(String key) {
        this(ApiHttp.defaultClient(), API, key);
    }

    public TmdbClient(HttpClient http, URI api, String key) {
        // TMDB allows about 50 requests a second; staying well below keeps a big first scan polite.
        this.http = new ApiHttp(http, "TMDB", Duration.ofMillis(60));
        this.api = api;
        this.key = Objects.requireNonNull(key).strip();
    }

    public List<Metadata> searchMovies(String query, Integer year) throws IOException, InterruptedException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("query", query);
        params.put("include_adult", "false");
        if (year != null) {
            params.put("year", year.toString());
        }
        Json.Obj page = get("search/movie", params);
        return page.objects("results").stream().map(result -> metadata(result, Kind.MOVIE)).toList();
    }

    public List<Metadata> searchShows(String query, Integer year) throws IOException, InterruptedException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("query", query);
        params.put("include_adult", "false");
        if (year != null) {
            params.put("first_air_date_year", year.toString());
        }
        Json.Obj page = get("search/tv", params);
        return page.objects("results").stream().map(result -> metadata(result, Kind.SHOW)).toList();
    }

    /** Every episode of a show, specials (season 0) included, in season and episode order. */
    public List<EpisodeInfo> episodes(String showId) throws IOException, InterruptedException {
        Json.Obj show = get("tv/" + showId, Map.of());
        List<EpisodeInfo> episodes = new ArrayList<>();
        for (Json.Obj season : show.objects("seasons")) {
            Integer number = season.integer("season_number");
            Integer count = season.integer("episode_count");
            if (number == null || count == null || count == 0) {
                continue;
            }
            Json.Obj details = get("tv/" + showId + "/season/" + number, Map.of());
            for (Json.Obj episode : details.objects("episodes")) {
                Integer episodeNumber = episode.integer("episode_number");
                if (episodeNumber != null) {
                    episodes.add(new EpisodeInfo(number, episodeNumber, episode.text("name"), episode.text("overview"),
                            image("w300", episode.text("still_path")), date(episode.text("air_date"))));
                }
            }
        }
        return episodes;
    }

    private Json.Obj get(String path, Map<String, String> params) throws IOException, InterruptedException {
        StringBuilder query = new StringBuilder();
        Map<String, String> all = new LinkedHashMap<>(params);
        all.put("language", "en-US");
        boolean bearer = key.startsWith("eyJ");
        if (!bearer) {
            all.put("api_key", key);
        }
        all.forEach((name, value) -> query.append(query.isEmpty() ? "" : "&").append(name).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        URI uri = api.resolve(path + "?" + query);
        return http.get(uri, bearer ? Map.of("Authorization", "Bearer " + key) : Map.of());
    }

    private static Metadata metadata(Json.Obj result, Kind kind) {
        boolean movie = kind == Kind.MOVIE;
        String title = result.text(movie ? "title" : "name");
        String original = result.text(movie ? "original_title" : "original_name");
        LocalDate released = date(result.text(movie ? "release_date" : "first_air_date"));
        List<Integer> genreIds = result.integers("genre_ids");
        boolean anime = genreIds.contains(ANIMATION) && ANIME_LANGUAGES.contains(result.text("original_language"));
        Double rating = result.number("vote_average");
        return new Metadata(Provider.TMDB, String.valueOf(result.integer("id")), kind, title == null ? original : title,
                original == null || original.equals(title) ? List.of() : List.of(original),
                released == null ? null : released.getYear(), result.text("overview"),
                image("w342", result.text("poster_path")), image("w1280", result.text("backdrop_path")),
                genreIds.stream().map(GENRES::get).filter(Objects::nonNull).toList(),
                rating == null || rating == 0 ? null : rating, null, anime);
    }

    private static String image(String size, String path) {
        return path == null ? null : IMAGES + size + path;
    }

    private static LocalDate date(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
