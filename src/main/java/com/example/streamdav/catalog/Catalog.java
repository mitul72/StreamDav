package com.example.streamdav.catalog;

import com.example.streamdav.catalog.LibraryScanner.ScannedFile;
import com.example.streamdav.catalog.ParsedRelease.Kind;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Scanned files grouped the way a library shows them: movies, and shows made of episodes.
 *
 * @param unsorted files whose names didn't yield a title
 */
public record Catalog(List<Movie> movies, List<Show> shows, List<ScannedFile> unsorted) {

    /** A movie; several files are several versions of it (e.g. 1080p and 4K). */
    public record Movie(String title, Integer year, List<ScannedFile> versions) {
    }

    /**
     * @param anime the file names looked like anime releases; metadata can confirm it later
     */
    public record Show(String title, Integer year, boolean anime, List<Episode> episodes) {

        /** Season numbers in display order, specials (season 0) last; null stands for absolutely numbered episodes. */
        public List<Integer> seasons() {
            return episodes.stream().map(Episode::season).distinct().sorted(SEASON_ORDER).toList();
        }

        public List<Episode> episodes(Integer season) {
            return episodes.stream().filter(episode -> Objects.equals(episode.season(), season)).toList();
        }
    }

    /**
     * @param season  null when numbered absolutely
     * @param numbers more than one for multi-episode files
     */
    public record Episode(Integer season, List<Integer> numbers, List<ScannedFile> versions) {

        /** "S01E05", "S01E05–E06", "Special 2", or "Episode 1071" for absolutely numbered episodes. */
        public String label() {
            String first = String.valueOf(numbers.getFirst());
            String last = String.valueOf(numbers.getLast());
            boolean range = numbers.size() > 1;
            if (season == null) {
                return range ? "Episodes " + first + "–" + last : "Episode " + first;
            }
            if (season == 0) {
                return range ? "Specials " + first + "–" + last : "Special " + first;
            }
            String code = String.format(Locale.ROOT, "S%02dE%02d", season, numbers.getFirst());
            return range ? code + String.format(Locale.ROOT, "–E%02d", numbers.getLast()) : code;
        }
    }

    /** Absolute numbering first, then seasons in order, then specials. */
    public static final Comparator<Integer> SEASON_ORDER = Comparator.comparingInt(season ->
            season == null ? -1 : season == 0 ? Integer.MAX_VALUE : season);

    public static Catalog of(List<ScannedFile> files) {
        Map<String, List<ScannedFile>> movies = new LinkedHashMap<>();
        Map<String, List<ScannedFile>> shows = new LinkedHashMap<>();
        List<ScannedFile> unsorted = new ArrayList<>();
        for (ScannedFile file : files) {
            ParsedRelease release = file.release();
            if (release.title().isBlank()) {
                unsorted.add(file);
            } else if (release.kind() == Kind.MOVIE) {
                movies.computeIfAbsent(key(release.title()) + "|" + release.year(), k -> new ArrayList<>()).add(file);
            } else {
                // Episode names often leave the year out, so shows are grouped by title alone.
                shows.computeIfAbsent(key(release.title()), k -> new ArrayList<>()).add(file);
            }
        }
        Comparator<String> byTitle = String.CASE_INSENSITIVE_ORDER;
        return new Catalog(
                movies.values().stream().map(Catalog::movie).sorted(Comparator.comparing(Movie::title, byTitle)).toList(),
                shows.values().stream().map(Catalog::show).sorted(Comparator.comparing(Show::title, byTitle)).toList(),
                List.copyOf(unsorted));
    }

    private static Movie movie(List<ScannedFile> versions) {
        ParsedRelease first = versions.getFirst().release();
        return new Movie(mostCommon(versions, file -> file.release().title()), first.year(), List.copyOf(versions));
    }

    private static Show show(List<ScannedFile> files) {
        Map<String, List<ScannedFile>> byEpisode = new LinkedHashMap<>();
        for (ScannedFile file : files) {
            ParsedRelease release = file.release();
            byEpisode.computeIfAbsent(release.season() + "|" + release.episodes().getFirst(), k -> new ArrayList<>()).add(file);
        }
        List<Episode> episodes = byEpisode.values().stream()
                .map(versions -> {
                    ParsedRelease release = versions.getFirst().release();
                    return new Episode(release.season(), release.episodes(), List.copyOf(versions));
                })
                .sorted(Comparator.comparing(Episode::season, SEASON_ORDER)
                        .thenComparing(episode -> episode.numbers().getFirst()))
                .toList();
        Integer year = files.stream().map(file -> file.release().year()).filter(Objects::nonNull).findFirst().orElse(null);
        boolean anime = files.stream().anyMatch(file -> file.release().anime());
        return new Show(mostCommon(files, file -> file.release().title()), year, anime, episodes);
    }

    /** The spelling most files use, so one oddly named file doesn't rename the show. */
    private static String mostCommon(List<ScannedFile> files, Function<ScannedFile, String> title) {
        Map<String, Long> counts = files.stream().collect(Collectors.groupingBy(title, LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    /** Titles that differ only in case, accents or punctuation belong together: "Mr. Robot" and "Mr Robot". */
    static String key(String title) {
        String decomposed = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
