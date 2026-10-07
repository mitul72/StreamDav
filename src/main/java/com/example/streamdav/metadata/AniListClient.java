package com.example.streamdav.metadata;

import com.example.streamdav.metadata.Metadata.Kind;
import com.example.streamdav.metadata.Metadata.Provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** AniList's GraphQL API, for anime: needs no key, and numbers episodes the way fansub releases do. */
public final class AniListClient {
    public static final URI API = URI.create("https://graphql.anilist.co/");
    private static final String SEARCH = """
            query ($search: String, $formats: [MediaFormat]) {
              Page(perPage: 10) {
                media(search: $search, type: ANIME, format_in: $formats, sort: SEARCH_MATCH) {
                  id format episodes seasonYear startDate { year }
                  title { romaji english native } synonyms
                  description(asHtml: false) genres averageScore isAdult
                  coverImage { extraLarge large } bannerImage
                }
              }
            }
            """;
    private static final String MOVIE_FORMATS = "[\"MOVIE\"]";
    private static final String SHOW_FORMATS = "[\"TV\", \"TV_SHORT\", \"ONA\", \"OVA\", \"SPECIAL\"]";

    private final ApiHttp http;
    private final URI api;

    public AniListClient() {
        this(ApiHttp.defaultClient(), API, Duration.ofSeconds(2));
    }

    /**
     * @param minInterval the least time between requests; AniList allows 30 a minute while degraded, 90 normally
     */
    public AniListClient(HttpClient http, URI api, Duration minInterval) {
        this.http = new ApiHttp(http, "AniList", minInterval);
        this.api = api;
    }

    public List<Metadata> search(String query, Kind kind) throws IOException, InterruptedException {
        String variables = "{\"search\": " + Json.quote(query) + ", \"formats\": "
                + (kind == Kind.MOVIE ? MOVIE_FORMATS : SHOW_FORMATS) + "}";
        String body = "{\"query\": " + Json.quote(SEARCH) + ", \"variables\": " + variables + "}";
        Json.Obj response = http.post(api, Map.of("Content-Type", "application/json"), body);
        return response.object("data").object("Page").objects("media").stream()
                .filter(media -> !media.bool("isAdult"))
                .map(AniListClient::metadata)
                .toList();
    }

    private static Metadata metadata(Json.Obj media) {
        Json.Obj titles = media.object("title");
        String english = titles.text("english");
        String romaji = titles.text("romaji");
        String title = english != null ? english : romaji != null ? romaji : titles.text("native");
        List<String> alternatives = new ArrayList<>();
        for (String alternative : new String[] {romaji, english, titles.text("native")}) {
            if (alternative != null && !alternative.equals(title)) {
                alternatives.add(alternative);
            }
        }
        alternatives.addAll(media.strings("synonyms"));
        Integer year = media.integer("seasonYear") != null ? media.integer("seasonYear") : media.object("startDate").integer("year");
        Json.Obj cover = media.object("coverImage");
        Integer score = media.integer("averageScore");
        Kind kind = "MOVIE".equals(media.text("format")) ? Kind.MOVIE : Kind.SHOW;
        return new Metadata(Provider.ANILIST, String.valueOf(media.integer("id")), kind, title, alternatives, year,
                plainText(media.text("description")), cover.text("extraLarge") != null ? cover.text("extraLarge") : cover.text("large"),
                media.text("bannerImage"), media.strings("genres"), score == null ? null : score / 10.0,
                media.integer("episodes"), true);
    }

    /** AniList descriptions carry a little HTML even when asked for plain text. */
    static String plainText(String description) {
        if (description == null) {
            return null;
        }
        String text = description.replaceAll("(?i)<br\\s*/?>\\n?", "\n").replaceAll("<[^>]+>", "")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\n?\\(Source: [^)]*\\)\\s*$", "")
                .replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
        return text.strip();
    }
}
