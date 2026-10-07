package com.example.streamdav.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out what a video file is from its name and the folders it's in, the way scene, Plex-style and anime
 * fansub releases name things:
 * <ul>
 *   <li>{@code Show.Name.S01E02.1080p.WEB-DL.mkv}, {@code Show 1x02}, {@code Show - Season 1 Episode 2}</li>
 *   <li>{@code Show/Season 01/S01E02.mkv} and {@code Show/Season 1/02 - Pilot.mkv}</li>
 *   <li>{@code [SubsPlease] Sousou no Frieren - 12 (1080p) [ABCD1234].mkv}, {@code Title S2 - 05}</li>
 *   <li>{@code The.Matrix.1999.1080p.BluRay.x264-GRP.mkv}, {@code Blade Runner 2049 (2017).mkv}</li>
 * </ul>
 */
public final class ReleaseParser {
    private static final Pattern GROUP_PREFIX = Pattern.compile("^\\s*\\[[^\\]]*\\]\\s*");
    private static final Pattern BRACKETED = Pattern.compile("\\[[^\\]]*\\]|\\{[^}]*\\}");
    private static final Pattern SEPARATORS = Pattern.compile("[._]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static final Pattern SEASON_EPISODE =
            Pattern.compile("(?i)(?<![a-z0-9])s(\\d{1,2}) ?-? ?e(\\d{1,4})(?!\\d)");
    private static final Pattern MORE_EPISODES = Pattern.compile("(?i)^(?: ?-? ?e(\\d{1,4})|-(\\d{1,4}))(?![\\dp])");
    private static final Pattern CROSS_FORMAT = Pattern.compile("(?i)(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?![\\dp])");
    private static final Pattern SEASON_WORD_EPISODE =
            Pattern.compile("(?i)(?<![a-z0-9])season ?(\\d{1,2}) ?-? ?(?:episode|ep) ?(\\d{1,4})(?!\\d)");
    /** {@code Title S2 - 05}, {@code Title Season 2 - 05}. */
    private static final Pattern SEASON_DASH_EPISODE =
            Pattern.compile("(?i)^(.+?) (?:s|season ?)(\\d{1,2}) ?- ?(?:e|ep ?)?(\\d{1,4})(?:v\\d)?(?!\\d)");
    /** {@code Title - 05}, {@code Title - 05v2}: the classic fansub form, numbered absolutely. */
    private static final Pattern DASH_EPISODE =
            Pattern.compile("(?i)^(.+?) - (?:e|ep ?|episode )?(\\d{1,4})(?:v\\d)?(?: |$|\\()");
    /** {@code Title Episode 05}, {@code Title Ep 05}, {@code Title E05}. */
    private static final Pattern WORD_EPISODE =
            Pattern.compile("(?i)^(.*?) ?(?<![a-z])(?:episode|ep|e) ?(\\d{1,4})(?:v\\d)?(?: |$)");
    /** A file named only by its number, like {@code 05}, {@code E05} or {@code 05 - Pilot}. */
    private static final Pattern EPISODE_ONLY =
            Pattern.compile("(?i)^(?:(?:episode|ep|e) ?)?(\\d{1,3})(?:v\\d)?(?: - .*| .*)?$");

    private static final Pattern SEASON_FOLDER =
            Pattern.compile("(?i)^(?:(?:season|series|staffel|saison|temporada) ?(\\d{1,2})|s(\\d{1,2}))$");
    private static final Pattern SEASON_PACK = Pattern.compile("(?i)(?<![a-z0-9])(?:s(\\d{1,2})|season ?(\\d{1,2}))(?! ?e\\d)(?!\\d)");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?![\\dp])");
    private static final Pattern TRAILING_YEAR = Pattern.compile("^(.*?) ?\\(?((?:19|20)\\d{2})\\)?$");
    /** Tokens that only appear after the title: resolution, source and codec. Everything from here on is noise. */
    private static final Pattern RELEASE_TAG = Pattern.compile("(?i)(?<![a-z0-9])(?:2160p|1080p|1080i|720p|576p|480p|4k|uhd"
            + "|blu ?ray|bdrip|brrip|bdremux|remux|web ?dl|webrip|hdtv|dvdrip|hdrip"
            + "|x ?264|x ?265|h ?264|h ?265|hevc|xvid|av1|10 ?bit)(?![a-z0-9])");

    private static final Set<String> EXTRAS_FOLDERS = Set.of("sample", "samples", "extras", "extra", "featurettes",
            "featurette", "trailers", "trailer", "behind the scenes", "deleted scenes", "interviews", "bonus", "shorts");
    /** Folders that organise a library rather than name a title. */
    private static final Set<String> GENERIC_FOLDERS = Set.of("movies", "movie", "films", "tv", "tv shows", "shows",
            "series", "anime", "downloads", "torrents", "links", "__all__", "media", "videos", "video", "complete");
    private static final Pattern SAMPLE = Pattern.compile("(?i)(?<![a-z0-9])sample(?![a-z0-9])");

    private ReleaseParser() {
    }

    /**
     * @param fileName the file's name, with its extension
     * @param folders  the folders it's in, outermost first (relative to the library source, so the source's own
     *                 name isn't mistaken for a title)
     */
    public static ParsedRelease parse(String fileName, List<String> folders) {
        String base = stripExtension(fileName);
        boolean anime = GROUP_PREFIX.matcher(base).find()
                || folders.stream().anyMatch(folder -> folder.strip().equalsIgnoreCase("anime"));
        String name = normalize(base);
        Integer folderSeason = seasonFromFolders(folders);

        Matcher match = SEASON_EPISODE.matcher(name);
        if (match.find()) {
            List<Integer> episodes = episodes(Integer.parseInt(match.group(2)), name.substring(match.end()));
            return episode(name.substring(0, match.start()), Integer.parseInt(match.group(1)), episodes, anime, folders);
        }
        match = SEASON_WORD_EPISODE.matcher(name);
        if (match.find()) {
            return episode(name.substring(0, match.start()), Integer.parseInt(match.group(1)),
                    List.of(Integer.parseInt(match.group(2))), anime, folders);
        }
        match = CROSS_FORMAT.matcher(name);
        if (match.find()) {
            return episode(name.substring(0, match.start()), Integer.parseInt(match.group(1)),
                    List.of(Integer.parseInt(match.group(2))), anime, folders);
        }

        String head = beforeReleaseTags(name);
        match = SEASON_DASH_EPISODE.matcher(head);
        if (match.find()) {
            return episode(match.group(1), Integer.parseInt(match.group(2)),
                    List.of(Integer.parseInt(match.group(3))), true, folders);
        }
        match = DASH_EPISODE.matcher(head);
        if (match.find() && (anime || !isYear(match.group(2)))) {
            return episode(match.group(1), folderSeason, List.of(Integer.parseInt(match.group(2))), true, folders);
        }
        match = EPISODE_ONLY.matcher(head);
        boolean inSeries = folderSeason != null || seasonPackFolder(folders).isPresent();
        if (match.matches() && (inSeries || !Character.isDigit(head.charAt(0)))) {
            return episode("", folderSeason != null ? folderSeason : seasonPackFolder(folders).orElse(null),
                    List.of(Integer.parseInt(match.group(1))), anime, folders);
        }
        match = WORD_EPISODE.matcher(head);
        if (match.find() && !match.group(1).isBlank()) {
            return episode(match.group(1), folderSeason, List.of(Integer.parseInt(match.group(2))), anime, folders);
        }
        return movie(name, folders);
    }

    /** True for samples and bonus material, which shouldn't appear in the library. */
    public static boolean isExtra(String fileName, List<String> folders) {
        if (SAMPLE.matcher(stripExtension(fileName)).find()) {
            return true;
        }
        return folders.stream().map(ReleaseParser::normalize).map(folder -> folder.toLowerCase(Locale.ROOT))
                .anyMatch(EXTRAS_FOLDERS::contains);
    }

    /** True for folders of bonus material and samples, which a scan needn't open. */
    public static boolean isExtrasFolder(String folder) {
        return EXTRAS_FOLDERS.contains(normalize(folder).toLowerCase(Locale.ROOT));
    }

    private static ParsedRelease episode(String rawTitle, Integer season, List<Integer> episodes, boolean anime,
                                         List<String> folders) {
        String title = cleanTitle(rawTitle);
        if (title.isEmpty()) {
            title = titleFromFolders(folders).orElse("");
        }
        Integer year = null;
        Matcher trailingYear = TRAILING_YEAR.matcher(title);
        if (trailingYear.matches() && !trailingYear.group(1).isBlank()) {
            title = cleanTitle(trailingYear.group(1));
            year = Integer.parseInt(trailingYear.group(2));
        }
        if (season == null) {
            season = seasonFromFolders(folders);
        }
        return ParsedRelease.episode(title, year, season, episodes, anime);
    }

    private static ParsedRelease movie(String name, List<String> folders) {
        TitleAndYear fromFile = titleAndYear(name);
        if (fromFile.year() == null || fromFile.title().isEmpty()) {
            // "Movie (2014)/movie.mkv": the folder usually names it better.
            Optional<TitleAndYear> fromFolder = nearestTitleFolder(folders).map(ReleaseParser::titleAndYear)
                    .filter(parsed -> parsed.year() != null || fromFile.title().isEmpty());
            if (fromFolder.isPresent()) {
                return ParsedRelease.movie(fromFolder.get().title(), fromFolder.get().year());
            }
        }
        return ParsedRelease.movie(fromFile.title(), fromFile.year());
    }

    private record TitleAndYear(String title, Integer year) {
    }

    /** The title runs up to the last year before the release tags; "Blade Runner 2049 2017 1080p" is from 2017. */
    private static TitleAndYear titleAndYear(String name) {
        int tags = releaseTagStart(name);
        Matcher year = YEAR.matcher(name);
        int yearStart = -1;
        String yearText = null;
        while (year.find() && year.start() < tags) {
            if (year.start() > 0) {
                yearStart = year.start();
                yearText = year.group(1);
            }
        }
        if (yearText == null) {
            return new TitleAndYear(cleanTitle(name.substring(0, tags)), null);
        }
        return new TitleAndYear(cleanTitle(name.substring(0, yearStart)), Integer.parseInt(yearText));
    }

    /** Episode numbers following the first: "E01E02", "E01-E02", "E01-02". */
    private static List<Integer> episodes(int first, String rest) {
        List<Integer> episodes = new ArrayList<>(List.of(first));
        Matcher more = MORE_EPISODES.matcher(rest);
        while (more.find()) {
            int next = Integer.parseInt(more.group(1) != null ? more.group(1) : more.group(2));
            int previous = episodes.getLast();
            // A range means every episode in it; anything implausible (a resolution, a year) ends the list.
            if (next <= previous || next > previous + 20) {
                break;
            }
            for (int episode = previous + 1; episode <= next; episode++) {
                episodes.add(episode);
            }
            rest = rest.substring(more.end());
            more = MORE_EPISODES.matcher(rest);
        }
        return episodes;
    }

    private static Integer seasonFromFolders(List<String> folders) {
        for (int i = folders.size() - 1; i >= 0; i--) {
            String folder = normalize(folders.get(i));
            if (folder.equalsIgnoreCase("specials") || folder.equalsIgnoreCase("special")) {
                return 0;
            }
            Matcher match = SEASON_FOLDER.matcher(folder);
            if (match.matches()) {
                return Integer.parseInt(match.group(1) != null ? match.group(1) : match.group(2));
            }
        }
        return null;
    }

    /** The season of a season-pack folder such as {@code Show.Name.S02.1080p.WEB-DL}. */
    private static Optional<Integer> seasonPackFolder(List<String> folders) {
        if (folders.isEmpty()) {
            return Optional.empty();
        }
        Matcher match = SEASON_PACK.matcher(normalize(folders.getLast()));
        if (!match.find()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(match.group(1) != null ? match.group(1) : match.group(2)));
    }

    private static Optional<String> titleFromFolders(List<String> folders) {
        return nearestTitleFolder(folders).map(ReleaseParser::folderTitle).filter(title -> !title.isEmpty());
    }

    /** The innermost folder that names a title, skipping season, extras and organising folders. */
    private static Optional<String> nearestTitleFolder(List<String> folders) {
        for (int i = folders.size() - 1; i >= 0; i--) {
            String folder = normalize(folders.get(i));
            String lower = folder.toLowerCase(Locale.ROOT);
            if (folder.isEmpty() || SEASON_FOLDER.matcher(folder).matches() || lower.equals("specials")
                    || EXTRAS_FOLDERS.contains(lower) || GENERIC_FOLDERS.contains(lower)) {
                continue;
            }
            return Optional.of(folder);
        }
        return Optional.empty();
    }

    /** A show folder may be a season pack ("Show Name S01 1080p") or carry a year ("Show Name (2019)"). */
    private static String folderTitle(String folder) {
        Matcher seasonPack = SEASON_PACK.matcher(folder);
        String title = seasonPack.find() ? folder.substring(0, seasonPack.start()) : folder;
        TitleAndYear parsed = titleAndYear(title);
        return parsed.title().isEmpty() ? cleanTitle(title) : parsed.title();
    }

    private static String beforeReleaseTags(String name) {
        return name.substring(0, releaseTagStart(name)).strip();
    }

    private static int releaseTagStart(String name) {
        Matcher tag = RELEASE_TAG.matcher(name);
        return tag.find() ? tag.start() : name.length();
    }

    /** Drops the release group and bracketed tags, and turns dots and underscores into spaces. */
    private static String normalize(String name) {
        String withoutGroup = GROUP_PREFIX.matcher(name).replaceFirst("");
        String withoutTags = BRACKETED.matcher(withoutGroup).replaceAll(" ");
        return SPACES.matcher(SEPARATORS.matcher(withoutTags).replaceAll(" ")).replaceAll(" ").strip();
    }

    private static String cleanTitle(String title) {
        String cleaned = title.replaceAll("\\(\\s*\\)", " ");
        cleaned = SPACES.matcher(cleaned).replaceAll(" ").strip();
        // Separators left dangling once the episode or year is cut off: "Show -", "Movie (".
        return cleaned.replaceAll("^[\\s\\-:(]+|[\\s\\-:(]+$", "");
    }

    private static boolean isYear(String number) {
        return YEAR.matcher(number).matches();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && fileName.length() - dot <= 5 ? fileName.substring(0, dot) : fileName;
    }
}
