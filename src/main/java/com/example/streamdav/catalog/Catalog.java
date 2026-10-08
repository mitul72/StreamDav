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
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
     * @param anime  the file names looked like anime releases; metadata can confirm it later
     * @param extras files packed with the show that aren't episodes, such as its openings and endings
     */
    public record Show(String title, Integer year, boolean anime, List<Episode> episodes, List<ScannedFile> extras) {

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
            return episodeLabel(season, numbers);
        }
    }

    /** "S01E05", "S01E05–E06", "Special 2", or "Episode 1071" for absolutely numbered episodes. */
    public static String episodeLabel(Integer season, List<Integer> numbers) {
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

    /** Absolute numbering first, then seasons in order, then specials. */
    public static final Comparator<Integer> SEASON_ORDER = Comparator.comparingInt(season ->
            season == null ? -1 : season == 0 ? Integer.MAX_VALUE : season);

    public static Catalog of(List<ScannedFile> files) {
        files = joinNumberedFilesToShows(files);
        Map<String, List<ScannedFile>> extras = extrasByShow(files);
        Set<ScannedFile> extraFiles = extras.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        files = files.stream().filter(file -> !extraFiles.contains(file)).toList();
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
                shows.entrySet().stream().map(entry -> show(entry.getValue(), extras.getOrDefault(entry.getKey(), List.of())))
                        .sorted(Comparator.comparing(Show::title, byTitle)).toList(),
                List.copyOf(unsorted));
    }

    /**
     * "Naruto Shippuuden 121" on its own reads as a film, but not next to "Naruto Shippuuden 013" (an episode): a
     * year-less "movie" named like an existing show plus a number is that show's episode.
     */
    private static List<ScannedFile> joinNumberedFilesToShows(List<ScannedFile> files) {
        Map<String, ParsedRelease> shows = new LinkedHashMap<>();
        for (ScannedFile file : files) {
            if (file.release().kind() == Kind.EPISODE && !file.release().title().isBlank()) {
                shows.putIfAbsent(key(file.release().title()), file.release());
            }
        }
        return files.stream().map(file -> {
            ParsedRelease release = file.release();
            Matcher numbered = NUMBERED_TITLE.matcher(release.title());
            if (release.kind() != Kind.MOVIE || release.year() != null || !numbered.matches()) {
                return file;
            }
            ParsedRelease show = shows.get(key(numbered.group(1)));
            if (show == null) {
                return file;
            }
            int number = Integer.parseInt(numbered.group(2));
            return new ScannedFile(file.file(), file.folders(), ParsedRelease.episode(show.title(), show.year(),
                    show.absolute() ? null : show.season(), List.of(number), show.anime()));
        }).toList();
    }

    private static final Pattern NUMBERED_TITLE = Pattern.compile("^(.+?) (\\d{1,4})$");
    /** {@code 02 - Haruka Kanata (Far Away).mkv}, {@code 12 [A] - Parade.mkv}: a number and a name, no show. */
    private static final Pattern NUMBER_AND_NAME = Pattern.compile("^\\d{1,3}(?:\\s*\\[[^\\]]*\\])?\\s*-\\s");
    /** A folder needs this many episodes of one show before its odd files count as that show's extras. */
    private static final int SHOW_FOLDER_EPISODES = 5;
    /** More files than this under one name are a show of their own, even in another show's folder. */
    private static final int MAX_STRAY_FILES = 3;

    /**
     * "[Anime Time] Naruto Complete/02 - Haruka Kanata (Far Away).mkv" reads as episode 2 of a show called "Haruka
     * Kanata", but it's Naruto's second opening: servers like Real-Debrid list a pack's files in one folder, losing
     * the "Openings" folder it came from. In a folder that's clearly one show, files named only by a number and a
     * name, that make up a handful of one-off "shows", are that show's extras.
     */
    private static Map<String, List<ScannedFile>> extrasByShow(List<ScannedFile> files) {
        Map<String, Long> filesPerShow = files.stream().filter(file -> file.release().kind() == Kind.EPISODE)
                .collect(Collectors.groupingBy(file -> key(file.release().title()), Collectors.counting()));
        Map<List<String>, List<ScannedFile>> byFolder = files.stream()
                .collect(Collectors.groupingBy(ScannedFile::folders, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<ScannedFile>> extras = new LinkedHashMap<>();
        for (List<ScannedFile> folder : byFolder.values()) {
            Map<String, Long> episodesPerShow = folder.stream()
                    .filter(file -> file.release().kind() == Kind.EPISODE && !numberAndName(file))
                    .collect(Collectors.groupingBy(file -> key(file.release().title()), Collectors.counting()));
            Optional<Map.Entry<String, Long>> main = episodesPerShow.entrySet().stream().max(Map.Entry.comparingByValue());
            if (main.isEmpty() || main.get().getValue() < SHOW_FOLDER_EPISODES) {
                continue;
            }
            String show = main.get().getKey();
            for (ScannedFile file : folder) {
                String title = key(file.release().title());
                if (file.release().kind() == Kind.EPISODE && numberAndName(file) && !title.equals(show)
                        && filesPerShow.getOrDefault(title, 0L) <= MAX_STRAY_FILES) {
                    extras.computeIfAbsent(show, k -> new ArrayList<>()).add(file);
                }
            }
        }
        return extras;
    }

    private static boolean numberAndName(ScannedFile file) {
        return NUMBER_AND_NAME.matcher(file.file().name()).find();
    }

    private static Movie movie(List<ScannedFile> versions) {
        ParsedRelease first = versions.getFirst().release();
        return new Movie(mostCommon(versions, file -> file.release().title()), first.year(), List.copyOf(versions));
    }

    private static Show show(List<ScannedFile> files, List<ScannedFile> extras) {
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
        return new Show(mostCommon(files, file -> file.release().title()), year, anime, episodes, List.copyOf(extras));
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
