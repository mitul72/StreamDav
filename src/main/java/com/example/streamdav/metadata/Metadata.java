package com.example.streamdav.metadata;

import java.util.List;
import java.util.stream.Stream;

/**
 * A movie or show as a metadata provider describes it.
 *
 * @param id            the provider's id, as text (TMDB and AniList both use numbers)
 * @param altTitles     other names it's known by (original, romaji, English, synonyms), for matching
 * @param posterUrl     portrait artwork, or null
 * @param backdropUrl   landscape artwork, or null
 * @param rating        out of 10, or null
 * @param episodeCount  for shows, when the provider says; null otherwise
 * @param anime         Japanese animation (or Chinese and Korean), which the library lists separately
 */
public record Metadata(Provider provider, String id, Kind kind, String title, List<String> altTitles, Integer year,
                       String overview, String posterUrl, String backdropUrl, List<String> genres, Double rating,
                       Integer episodeCount, boolean anime) {

    public enum Provider { TMDB, ANILIST }

    public enum Kind { MOVIE, SHOW }

    public Metadata {
        altTitles = List.copyOf(altTitles);
        genres = List.copyOf(genres);
    }

    /** The title followed by the alternative titles, without duplicates. */
    public List<String> allTitles() {
        return Stream.concat(Stream.of(title), altTitles.stream())
                .filter(name -> name != null && !name.isBlank()).distinct().toList();
    }
}
