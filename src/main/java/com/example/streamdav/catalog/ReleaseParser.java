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
 * Episode markers are tried strongest first; the weaker ones (a bare number) need corroboration, such as a fansub
 * group, zero padding, a TV source tag or a season folder, so that film titles with numbers stay films.
 */
public final class ReleaseParser {
    // Normalisation
    private static final Pattern GROUP_PREFIX = Pattern.compile("^\\s*\\[([^\\]]*)\\]\\s*");
    private static final Pattern BRACKETED = Pattern.compile("\\[([^\\]]*)\\]|\\{([^}]*)\\}");
    /** Bracketed text worth keeping: an episode marker or a year, not a tag or checksum. */
    private static final Pattern KEPT_BRACKET = Pattern.compile("(?i)^\\s*(?:(?:ep(?:isode)?[ ._]?|cap[ ._]?)?\\d{1,4}(?:v\\d)?(?:_\\d{3,4})?(?:[ ._]final)?"
            + "(?:\\s*(?:（end）|\\(end\\)|end))?|\\d{1,2}x\\d{1,4}|s\\d{1,2}[ ._]?e\\d{1,4}|\\d{1,4}[ ._]?of[ ._]?\\d{1,4})\\s*$");
    private static final Pattern END_MARK = Pattern.compile("(?i)\\s*(?:（end）|\\(end\\)|end)\\s*$");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern SYMBOLS = Pattern.compile("[\\p{So}]+");
    /** A parenthesised group of release tags, like {@code (DVD)} or {@code (Blu-Ray 1080p)}; not a title or a year. */
    private static final Pattern TAG_GROUP = Pattern.compile("(?i)\\((?=[^()]*(?:\\d{3,4}p|\\d{3,4}[x×]\\d{3,4}|blu-?ray|dvd|bd(?:rip)?"
            + "|web-?dl|hdtv|x264|x265|h\\.?26[45]|hevc|avc|aac|flac|ac3|10 ?bit|hi10p)\\b)[^()]*\\)");
    private static final Pattern CHECKSUM = Pattern.compile("[0-9A-Fa-f]{8}");
    /** Bracketed text that's never a title: subtitle languages, character sets, credits. */
    private static final Pattern NOT_A_TITLE = Pattern.compile("(?i)(?<![a-z])(?:big5|gb|chs|cht|jpn|jap|eng|sub|subs|raw"
            + "|vostfr|multi|dual|encoded|chap|fixed|batch|complete|end)(?![a-z])");
    private static final Pattern WEBSITE_PREFIX = Pattern.compile("(?i)^www [a-z0-9-]+ [a-z]{2,6} - ");

    // Episode markers, strongest first
    private static final Pattern SEASON_EPISODE =
            Pattern.compile("(?i)(?<![a-z0-9])s(\\d{1,2}|(?:19|20)\\d{2}|0\\d{2}) ?-? ?(?:ep|e|x|xe) ?(\\d{1,4})(?!\\d)");
    private static final Pattern CROSS_FORMAT =
            Pattern.compile("(?i)(?<![a-z0-9])(\\d{1,2}|(?:19|20)\\d{2})(?:[x×]|(?<=\\b\\d{1,2}) x (?=\\d{2}\\b))(\\d{1,3})(?![\\dp])");
    private static final Pattern SEASON_WORD_EPISODE = Pattern.compile("(?i)(?<![a-z0-9])season ?(\\d{1,2})(?!\\d)(?: ?of ?\\d{1,2})?"
            + " ?-? ?(?:(?:episode|ep|e) ?)?(\\d{1,4})(?: ?of ?\\d{1,4})?(?![\\dp])");
    /** Spanish TV rips: {@code Temporada 4 [Cap.408]} is season 4, episode 8. */
    private static final Pattern CAPITULO =
            Pattern.compile("(?i)(?<![a-z0-9])cap ?(\\d{1,2})(\\d{2})(?: (?:\\d{1,2})(\\d{2}))?(?!\\d)");
    /** Chinese and Japanese: 第3話 / 第3集 (episode 3), 第二季 / シーズン2 (season 2), in digits or Chinese numerals. */
    private static final Pattern CJK_EPISODE = Pattern.compile("第?([0-9一二三四五六七八九十百]+)[話话集]");
    private static final Pattern CJK_SEASON = Pattern.compile("第([0-9一二三四五六七八九十]+)[季期]|シーズン ?([0-9]+)");
    private static final Pattern ORDINAL_SEASON = Pattern.compile("(?i)(?<![a-z0-9])(?:(\\d{1,2})(?:st|nd|rd|th) season"
            + "|(?:stagione|temporada|saison|staffel) ?(\\d{1,2})|s(\\d{1,2})(?! ?e\\d)(?!-\\d| ?- ?s\\d))(?![a-z0-9])");
    /** {@code S01-S03}, {@code S01-05}: a batch of whole seasons. */
    private static final Pattern SEASON_RANGE = Pattern.compile("(?i)(?<![a-z0-9])s\\d{1,2}(?:-\\d{1,2}| ?- ?s\\d{1,2})(?![0-9])");
    /** {@code Title S2 - 05}, {@code Title Season 2 - 05}. */
    private static final Pattern SEASON_DASH_EPISODE =
            Pattern.compile("(?i)^(.+?) (?:s|season ?)(\\d{1,2}) ?- ?(?:e|ep ?)?(\\d{1,4})(?:v\\d)?(?!\\d)");
    /** {@code Title - 05}, {@code Title - 05v2}, {@code Title - 316-317}: the classic fansub form. */
    private static final Pattern DASH_EPISODE = Pattern.compile(
            "(?i)^(.+?) - (?:e|ep ?|episode )?(\\d{1,4})(?:v\\d)?(?:-(\\d{1,4})(?:v\\d)?)?(?: |$|\\()");
    private static final Pattern LAST_DASH_EPISODE = Pattern.compile(
            "(?i)^(.+) - (?:e|ep ?|episode )?(\\d{1,4})(?:v\\d)?(?:-(\\d{1,4})(?:v\\d)?)?$");
    /** {@code [Figmentos] Monster 34 - At the End of Darkness}: a fansub number, then the episode title. */
    private static final Pattern NUMBER_DASH_TITLE = Pattern.compile("^(.+?) (\\d{2,4})(?:v\\d)? - (?=\\p{L})");
    /** {@code 14 of 21}, and the same in other languages: {@code Capitulo 5 de 12}, {@code Folge 5 von 12}. */
    private static final Pattern EPISODE_OF = Pattern.compile("(?i)(?<![a-z0-9])(?:(?:capitulo|cap|episodio|folge|aflevering"
            + "|episodul|episode|ep) ?)?(\\d{1,3}) ?(?:of|de|di|von|van|din) ?\\d{1,3}(?!\\d)");
    private static final Pattern RUSSIAN_EPISODE = Pattern.compile("(?iu)(?<!\\d)(\\d{1,4}) ?серия");
    /** {@code Title Episode 05}, {@code Title Ep 05}, {@code Title E05}, {@code Title ep. 1-5}. */
    private static final Pattern WORD_EPISODE = Pattern.compile("(?iu)^(.*?) ?(?<![a-z])(?:[eé]pisode|episodio|capitulo|folge"
            + "|ep|e) ?(\\d{1,4})(?:v\\d(?:\\.\\d)?)?(?:-(\\d{1,4}))?(?: |-|:|$)");
    /** {@code 03-Criminal Minds}, {@code 01 - Tari Tari}, {@code 06 Sword Art Online II}. */
    private static final Pattern LEADING_EPISODE = Pattern.compile("^(0\\d{1,3}|\\d{1,3}(?= - ))(?:-(0?\\d{1,3}))?(?:v\\d)? ?-? ?(?=\\p{L})");
    /** A file named only by its number, like {@code 05}, {@code E05} or {@code 05 - Pilot}. */
    private static final Pattern EPISODE_ONLY =
            Pattern.compile("(?i)^(?:(?:episode|ep|e) ?)?(\\d{1,4})(?:v\\d)?(?: - .*)?$");
    /** In a season folder, {@code 01 Pilot} too. */
    private static final Pattern EPISODE_ONLY_IN_SEASON =
            Pattern.compile("(?i)^(?:(?:episode|ep|e) ?)?(\\d{1,4})(?:v\\d)?(?: ?- .*| .*)?$");
    /** {@code Oreshura #01v2}, {@code Nekomonogatari #1-4}. */
    private static final Pattern HASH_EPISODE = Pattern.compile("(?i)^(.+?) #(\\d{1,4})(?:v\\d)?(?:-(\\d{1,4}))?(?: |$)");
    /** Numbers that count something other than episodes: "Movie 9", "Part 1", "Vol.1". */
    private static final Pattern NOT_AN_EPISODE = Pattern.compile("(?i)(?:^|\\s)(?:movies?|film|gekijouban|part|pt|vol|volume)$");
    private static final Pattern VOLUME = Pattern.compile("(?i)\\s*-?\\s*vol(?:ume)? ?\\d.*$");
    private static final Pattern TRAILING_GROUP = Pattern.compile(" - [A-Z][A-Z0-9]{2,}$");
    /** {@code Naruto Shippuuden 013}, {@code Title 10 v2}, {@code Title 13-15}. */
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(?i)^(.+?) (\\d{1,4})(?: ?v\\d)?(?:-(\\d{1,4}))?$");
    /** {@code Neverwhere 05 Down Street}: a zero-padded number with the episode title after it. */
    private static final Pattern MIDDLE_NUMBER = Pattern.compile("^(.+?) (0\\d{1,2})(?:v\\d)? (?=\\p{L})");
    /** {@code new girl 117 hdtv}: season and episode run together, as TV rips do. */
    private static final Pattern COMPACT_NUMBER = Pattern.compile("^(.+?) (\\d)(\\d{2})(?: |$)|^(.+?) (\\d{2})(\\d{2})(?: |$)");
    private static final Pattern COPY_SUFFIX = Pattern.compile(" \\(\\d{1,2}\\)$");
    private static final Pattern PAREN_EPISODE = Pattern.compile("^(.+?) \\((\\d{1,3})\\)$");

    // Titles and years
    private static final Pattern SEASON_FOLDER =
            Pattern.compile("(?i)^(?:(?:season|series|staffel|saison|temporada) ?(\\d{1,2})|s(\\d{1,2}))$");
    private static final Pattern SEASON_PACK = Pattern.compile("(?i)(?<![a-z0-9])(?:s(\\d{1,2})|season ?(\\d{1,2}))(?! ?e\\d)(?!\\d)");
    private static final Pattern TRAILING_SEASON = Pattern.compile("(?i) s\\d{1,2}$");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?![\\dp])");
    private static final Pattern LEADING_YEAR = Pattern.compile("^(?:\\(((?:19|20)\\d{2})\\)|((?:19|20)\\d{2})[a-z]) ");
    private static final Pattern PAREN_YEAR = Pattern.compile("\\(((?:19|20)\\d{2})\\)");
    private static final Pattern YEAR_AFTER_EPISODE = Pattern.compile("^ ?\\(?((?:19|20)\\d{2})\\)?(?: |$)");
    private static final Pattern TRAILING_NOTE = Pattern.compile("\\s*\\((?!\\s*(?:19|20)\\d{2}\\s*\\))[^()]*\\)\\s*$");
    private static final Pattern TRAILING_YEAR = Pattern.compile("^(.*?) ?\\(?((?:19|20)\\d{2})\\)?$");
    private static final Pattern COUNTRY = Pattern.compile(" \\(?(?:US|UK|AU|NZ|CA)\\)?$");
    private static final Pattern INVERTED_ARTICLE = Pattern.compile("^(.+), (The|A|An)$");
    /** Tokens that only appear after the title: resolution, source and codec. Everything from here on is noise. */
    private static final Pattern RELEASE_TAG = Pattern.compile("(?i)(?<![a-z0-9])(?:2160p|1080p|1080i|720p|576p|480p|4k|uhd"
            + "|blu ?ray|bdrip|brrip|bdremux|remux|web ?dl|webrip|hdtv|pdtv|sdtv|dsr|tvrip|dvdrip|hdrip|dvd"
            + "|x ?264|x ?265|h ?264|h ?265|hevc|avc|xvid|divx|av1|10 ?bit|hi10p|hdr|hdr10\\+?|dv|dovi|sdr|hlg|bd|bdmv"
            + "|\\d{3,4}[x×]\\d{3,4})(?![a-z0-9])");
    /** Tokens of TV broadcasts, which make a bare number after the title an episode. */
    private static final Pattern TV_TAG = Pattern.compile("(?i)(?<![a-z0-9])(?:hdtv|pdtv|sdtv|dsr|tvrip|hdtvrip)(?![a-z0-9])");
    /**
     * Words that end a title when there's no year to do it: languages, editions, rip types. Too common in real titles
     * to cut at otherwise ("Dark City (Director's Cut)" is fine; "Director's Cut" alone is ambiguous).
     */
    private static final Pattern WEAK_TAG = Pattern.compile("(?<![A-Za-z0-9'])(?:(?i:truefrench|swissgerman|vostfr|vostf"
            + "|dual audio|subbed|dubbed|extended|unrated|uncut|director'?s cut|directors cut|director cut|theatrical|criterion"
            + "|collector'?s? edition|edition collector|special edition|remastered|remaster|nfofix|bdmux|brmux|bdripmux|brripmux|doku|\\d{2} \\d{3} fps"
            + "|vr ?180|vr ?360"
            + "|(?:german|french|italian|spanish)(?= (?:dl|ac3d?|dubbed|dts|md|ld)(?![a-z0-9])))"
            + "|FRENCH|GERMAN|ITALIAN|SPANISH|LATINO|MULTI|EDITION|PROPER|REPACK|INTERNAL|LIMITED|CONVERT|ITA|ENG|DL|VFF"
            + "|VFQ|VF|VO|VOST|DC|SE|IMAX|STV|3D|HFR|XXX|PAL|NTSC|DVDR|HD|HQ|2CD|CD\\d|AC3D?|AAC|DTS|MP4|SBS|VR)"
            + "(?![A-Za-z0-9])");

    private static final Pattern COLLECTION = Pattern.compile(
            "(?i)(?<![a-z])(?:saga|collection|trilogy|quadrilogy|anthology|box ?set|filmography)(?![a-z])");
    private static final Pattern COLLECTION_INDEX = Pattern.compile("^\\d{1,2} ?[-.] ?(?=\\p{L})");
    private static final Set<String> EXTRAS_FOLDERS = Set.of("sample", "samples", "extras", "extra", "featurettes",
            "featurette", "trailers", "trailer", "behind the scenes", "deleted scenes", "interviews", "bonus", "shorts");
    /** Folders that organise a library rather than name a title. */
    private static final Set<String> GENERIC_FOLDERS = Set.of("movies", "movie", "films", "tv", "tv shows", "shows",
            "series", "anime", "downloads", "torrents", "links", "__all__", "media", "videos", "video", "complete");
    private static final Set<String> TV_FOLDERS = Set.of("tv", "tv shows", "shows", "series");
    /** {@code blow-how.to.be.single}, {@code dmd-aw}: a scene group's abbreviated file name, all lower case. */
    private static final Pattern SCENE_FILE = Pattern.compile("^[a-z0-9]{2,6}-[a-z0-9][a-z0-9 .'-]*$");
    private static final Pattern LEADING_INDEX = Pattern.compile("^\\(\\d{1,2}\\) ?(?=\\p{L})");
    private static final Pattern LEADING_TAGS = Pattern.compile("^(?:" + RELEASE_TAG.pattern() + "[\\s\\-]*)+(?=\\p{L})");
    /** A Chinese or Japanese title followed by its English one: "超能警探 Memorist". */
    private static final Pattern CJK_THEN_LATIN =
            Pattern.compile("^[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}・ー\\s]+ (\\p{IsLatin}.*\\p{IsLatin}{3}.*)$");
    private static final Pattern TRAILING_RANGE = Pattern.compile("\\s+(\\d{1,4})-(\\d{1,4})$");
    /** {@code Movie - BROLY Extras - Trailer}: bonus material named after the film; "Extras.S01E01" is a show. */
    private static final Pattern EXTRAS_NAME = Pattern.compile("(?i)\\S[\\s._-]+(?:extras|bonus features?|featurettes?) ?- ");
    private static final Pattern SAMPLE = Pattern.compile("(?i)(?<![a-z0-9])sample(?![a-z0-9])");

    private ReleaseParser() {
    }

    /** A name with brackets resolved: kept markers inline, the rest set aside in case they hold the title. */
    private record Name(String text, String group, List<String> setAside) {
    }

    /**
     * @param fileName the file's name, with its extension
     * @param folders  the folders it's in, outermost first (relative to the library source, so the source's own
     *                 name isn't mistaken for a title)
     */
    public static ParsedRelease parse(String fileName, List<String> folders) {
        Name parsed = parseName(stripExtension(fileName));
        if (!folders.isEmpty() && !hasEpisodeMarker(parsed.text())) {
            // "Zoo.S02E05.1080p.WEB-DL/160725_02.mkv": an episode's release folder names it better than a scrambled
            // or numbered file inside it.
            String folder = folders.getLast();
            if (hasEpisodeMarker(parseName(folder).text())) {
                return parse(folder, folders.subList(0, folders.size() - 1));
            }
        }
        boolean anime = parsed.group() != null
                || parsed.setAside().stream().anyMatch(text -> CHECKSUM.matcher(text.strip()).matches())
                || folders.stream().anyMatch(folder -> folder.strip().equalsIgnoreCase("anime"));
        boolean tv = TV_TAG.matcher(parsed.text()).find()
                || folders.stream().anyMatch(folder -> TV_FOLDERS.contains(folder.strip().toLowerCase(Locale.ROOT)));
        Context context = new Context(parsed, folders, anime);
        String name = parsed.text();

        // Explicit season and episode
        Matcher match = SEASON_EPISODE.matcher(name);
        if (match.find()) {
            int season = Integer.parseInt(match.group(1));
            String rest = name.substring(match.end());
            List<Integer> episodes = moreEpisodes(Integer.parseInt(match.group(2)), season, rest);
            Integer year = season >= 1900 ? Integer.valueOf(season) : null;
            return context.episode(name.substring(0, match.start()), season, episodes, rest, year);
        }
        match = CROSS_FORMAT.matcher(name);
        if (match.find()) {
            int season = Integer.parseInt(match.group(1));
            String rest = name.substring(match.end());
            return context.episode(name.substring(0, match.start()), season,
                    moreEpisodes(Integer.parseInt(match.group(2)), season, rest), rest, season >= 1900 ? season : null);
        }
        match = CAPITULO.matcher(name);
        if (match.find()) {
            String title = beforeReleaseTags(name.substring(0, match.start()))
                    .replaceAll("(?i)\\s*-?\\s*(?:temporada|temp|tem) ?\\d{1,2}\\s*$", "");
            return context.episode(title, Integer.parseInt(match.group(1)), range(match.group(2), match.group(3)),
                    "", null);
        }
        match = SEASON_WORD_EPISODE.matcher(name);
        if (match.find()) {
            return context.episode(name.substring(0, match.start()), Integer.parseInt(match.group(1)),
                    List.of(Integer.parseInt(match.group(2))), name.substring(match.end()), null);
        }

        if (SEASON_RANGE.matcher(name).find()) {
            // "[Sokudo] Dragon Ball S01-S03 001-068": a whole series in one release.
            return context.movie(name);
        }

        match = CJK_EPISODE.matcher(name);
        if (match.find()) {
            Matcher cjkSeason = CJK_SEASON.matcher(name);
            Integer cjk = cjkSeason.find() ? chineseNumber(cjkSeason.group(1) != null ? cjkSeason.group(1) : cjkSeason.group(2)) : null;
            int titleEnd = cjk != null ? Math.min(match.start(), cjkSeason.start()) : match.start();
            return context.episode(name.substring(0, titleEnd), cjk != null ? cjk : context.folderSeason(),
                    List.of(chineseNumber(match.group(1))), "", null);
        }

        // "Hayate no Gotoku 2nd Season 24": take the season out, then look for the episode as usual.
        Integer namedSeason = null;
        match = ORDINAL_SEASON.matcher(name);
        if (match.find()) {
            namedSeason = Integer.parseInt(match.group(1) != null ? match.group(1) : match.group(2) != null ? match.group(2) : match.group(3));
            name = (name.substring(0, match.start()) + " " + name.substring(match.end())).replaceAll("\\s+", " ").strip();
        }
        // "Friends S03/Friends - 07.mkv" is season 3; a fansub pack ("[Anime Time] Vinland Saga (Season 1)") still
        // numbers its files absolutely.
        boolean fansubPack = !folders.isEmpty() && parseName(folders.getLast()).group() != null;
        Integer season = namedSeason != null ? namedSeason : context.folderSeason() != null ? context.folderSeason()
                : anime || fansubPack ? null : seasonPackFolder(folders).orElse(null);

        String head = beforeReleaseTags(name);
        match = SEASON_DASH_EPISODE.matcher(head);
        if (match.find()) {
            return context.episode(match.group(1), Integer.parseInt(match.group(2)),
                    List.of(Integer.parseInt(match.group(3))), "", null);
        }
        match = LAST_DASH_EPISODE.matcher(head);
        if (!match.matches() || !anime && isYear(match.group(2))) {
            match = DASH_EPISODE.matcher(head);
        }
        if (match.reset().find() && (anime || !isYear(match.group(2)))) {
            Optional<int[]> split = compact(match.group(2), match.group(3), tv && !anime, season, folders);
            if (split.isPresent()) {
                return context.episode(match.group(1), split.get()[0], List.of(split.get()[1]), "", null);
            }
            ParsedRelease episode = context.episode(match.group(1), season, range(match.group(2), match.group(3)), "", null);
            // Absolute numbering after a dash is the fansub form; "Friends S03/Friends - 07" is just a season's episode.
            return episode.absolute() ? episode.asAnime() : episode;
        }
        match = NUMBER_DASH_TITLE.matcher(head);
        if (anime && match.find() && !isYear(match.group(2)) && !NOT_AN_EPISODE.matcher(match.group(1)).find()) {
            return context.episode(match.group(1), season, List.of(Integer.parseInt(match.group(2))), "", null);
        }
        match = EPISODE_OF.matcher(head);
        if (match.find() && match.start() > 0) {
            return context.episode(head.substring(0, match.start()), season, List.of(Integer.parseInt(match.group(1))), "", null);
        }
        match = RUSSIAN_EPISODE.matcher(head);
        if (match.find() && match.start() > 0) {
            return context.episode(head.substring(0, match.start()), season, List.of(Integer.parseInt(match.group(1))), "", null);
        }
        boolean inSeason = context.folderSeason() != null || seasonPackFolder(folders).isPresent();
        Matcher seasonFile = EPISODE_ONLY_IN_SEASON.matcher(head);
        if (inSeason && seasonFile.matches() && !YEAR.matcher(head).find()) {
            // "Doctor Who/Season 06/E13 - The Wedding of River Song.mkv": the folders name the show.
            Integer packSeason = season != null ? season : seasonPackFolder(folders).orElse(null);
            return context.episode("", packSeason, List.of(Integer.parseInt(seasonFile.group(1))), "", null);
        }
        match = HASH_EPISODE.matcher(head);
        if (match.find()) {
            return context.episode(match.group(1), season, range(match.group(2), match.group(3)), "", null);
        }
        match = WORD_EPISODE.matcher(head);
        if (!match.find() && namedSeason != null) {
            // "Mastercook Italia - Stagione 6 (2016) 720p ep13": with a season named, the episode can follow the tags.
            match = WORD_EPISODE.matcher(head + " " + name.substring(head.length()));
            if (match.find() && !match.group(1).isBlank()) {
                return context.episode(beforeReleaseTags(match.group(1)), season, range(match.group(2), match.group(3)), "", null);
            }
            match = WORD_EPISODE.matcher(head);
        }
        if (match.reset().find()) {
            if (!match.group(1).isBlank()) {
                return context.episode(match.group(1), season, range(match.group(2), match.group(3)),
                        head.substring(match.end()), null);
            }
            // "Episode 14 Ore no Imouto ga Konnani Kawaii Wake ga Nai": the number first, then the title.
            String title = titleOnly(head.substring(match.end()));
            if (!title.isEmpty() && !YEAR.matcher(head).find()) {
                return context.episode(title, season, range(match.group(2), match.group(3)), "", null);
            }
        }
        boolean inSeries = context.folderSeason() != null || seasonPackFolder(folders).isPresent();
        match = EPISODE_ONLY_IN_SEASON.matcher(head);
        if (inSeries && match.matches() && !YEAR.matcher(head).find()) {
            // In a season folder, "01 - Dulcinea" is episode 1 called Dulcinea; the show is named by the folders.
            Integer packSeason = season != null ? season : seasonPackFolder(folders).orElse(null);
            return context.episode("", packSeason, List.of(Integer.parseInt(match.group(1))), "", null);
        }
        match = LEADING_EPISODE.matcher(head);
        // "09.03.08.The.Doors.(1991)": a film with a date in front, not an episode.
        if (match.find() && !isYear(match.group(1)) && !YEAR.matcher(head).find()) {
            String title = head.substring(match.end());
            int dash = title.indexOf(" - ");
            return context.episode(dash > 0 ? title.substring(0, dash) : title, season,
                    range(match.group(1), match.group(2)), "", null);
        }
        match = EPISODE_ONLY.matcher(head);
        // "E1 The Equalizer (2014)" in a collection is a film, not episode 1: episode-only names have no year.
        if (match.matches() && (inSeries || !Character.isDigit(head.charAt(0)) || context.titleFromBrackets().isPresent())
                && !YEAR.matcher(head).find()) {
            Integer packSeason = season != null ? season : seasonPackFolder(folders).orElse(null);
            return context.episode("", packSeason, List.of(Integer.parseInt(match.group(1))), "", null);
        }
        // Language and edition tags can follow the number: "One Piece 603 VOSTFR", "Dead Set 02 FRENCH".
        String plain = beforeWeakTags(head);
        String trailing = COPY_SUFFIX.matcher(plain).find() && TRAILING_NUMBER.matcher(COPY_SUFFIX.matcher(plain).replaceFirst("")).matches()
                ? COPY_SUFFIX.matcher(plain).replaceFirst("") : plain;
        match = TRAILING_NUMBER.matcher(head);
        if (!match.matches()) {
            match = TRAILING_NUMBER.matcher(trailing);
        }
        // Bracket-tagged releases, a zero-padded number ("Naruto Shippuuden 013"), or a TV rip ("Test 13 HDTV"):
        // film titles ending in a number ("District 9") have none of these.
        boolean taggedAfter = plain.length() < name.length();
        if (match.matches() && !isYear(match.group(2)) && !NOT_AN_EPISODE.matcher(match.group(1)).find()
                && (anime || match.group(2).startsWith("0") || tv && taggedAfter || namedSeason != null)) {
            Optional<int[]> split = compact(match.group(2), match.group(3), tv && !anime, season, folders);
            if (split.isPresent()) {
                return context.episode(match.group(1), split.get()[0], List.of(split.get()[1]), "", null);
            }
            return context.episode(match.group(1), season, range(match.group(2), match.group(3)), "", null);
        }
        match = PAREN_EPISODE.matcher(head);
        if (match.matches() && anime) {
            return context.episode(match.group(1), season, List.of(Integer.parseInt(match.group(2))), "", null);
        }
        match = MIDDLE_NUMBER.matcher(plain);
        if (match.find() && !match.group(1).isBlank()) {
            return context.episode(match.group(1), season, List.of(Integer.parseInt(match.group(2))), "", null);
        }
        match = COMPACT_NUMBER.matcher(head);
        if (tv && match.find()) {
            boolean three = match.group(1) != null;
            String number = three ? match.group(2) + match.group(3) : match.group(5) + match.group(6);
            if (!isYear(number)) {
                return context.episode(three ? match.group(1) : match.group(4),
                        Integer.parseInt(three ? match.group(2) : match.group(5)),
                        List.of(Integer.parseInt(three ? match.group(3) : match.group(6))), "", null);
            }
        }
        if (namedSeason != null) {
            // A season but no episode: a whole-season release, best described by its title.
            return context.movie(head);
        }
        return context.movie(name);
    }

    private static boolean hasEpisodeMarker(String name) {
        return SEASON_EPISODE.matcher(name).find() || CROSS_FORMAT.matcher(name).find() || CAPITULO.matcher(name).find();
    }

    /** True for samples and bonus material, which shouldn't appear in the library. */
    public static boolean isExtra(String fileName, List<String> folders) {
        String name = stripExtension(fileName);
        if (SAMPLE.matcher(name).find() || EXTRAS_NAME.matcher(name).find()) {
            return true;
        }
        return folders.stream().map(folder -> parseName(folder).text().toLowerCase(Locale.ROOT))
                .anyMatch(EXTRAS_FOLDERS::contains);
    }

    /** True for folders of bonus material and samples, which a scan needn't open. */
    public static boolean isExtrasFolder(String folder) {
        return EXTRAS_FOLDERS.contains(parseName(folder).text().toLowerCase(Locale.ROOT));
    }

    /** The file's surroundings, and how to finish a result from them. */
    private record Context(Name name, List<String> folders, boolean anime) {

        Integer folderSeason() {
            return seasonFromFolders(folders);
        }

        /** The best title held in brackets, for names like {@code [Group][Title][01]} with nothing outside them. */
        Optional<String> titleFromBrackets() {
            List<String> candidates = new ArrayList<>();
            for (String text : name.setAside()) {
                if (CHECKSUM.matcher(text.strip()).matches() || NOT_A_TITLE.matcher(text).find()) {
                    continue;
                }
                String title = titleOnly(normalizeSeparators(text));
                boolean substantial = title.contains(" ") || title.codePoints().filter(Character::isLetter).count() >= 5;
                if (substantial && !RELEASE_TAG.matcher(title).find()) {
                    candidates.add(title);
                }
            }
            Optional<String> latin = candidates.stream().filter(title -> title.matches(".*[A-Za-z]{3}.*")).findFirst();
            if (latin.isPresent()) {
                return latin;
            }
            if (!candidates.isEmpty()) {
                return Optional.of(candidates.getFirst());
            }
            return Optional.ofNullable(name.group()).map(group -> titleOnly(normalizeSeparators(group))).filter(title -> !title.isEmpty());
        }

        ParsedRelease episode(String rawTitle, Integer season, List<Integer> episodes, String after, Integer year) {
            String title = cleanTitle(rawTitle).replaceAll("(?i)\\s+(?:episode|ep)$", "");
            Matcher batch = TRAILING_RANGE.matcher(title);
            if (batch.find() && Integer.parseInt(batch.group(1)) < Integer.parseInt(batch.group(2))) {
                title = title.substring(0, batch.start());
            }
            if (title.isEmpty()) {
                title = titleFromFolders(folders).or(this::titleFromBrackets).orElse("");
            }
            Matcher trailingYear = TRAILING_YEAR.matcher(title);
            if (trailingYear.matches() && !trailingYear.group(1).isBlank()) {
                title = cleanTitle(trailingYear.group(1));
                year = Integer.parseInt(trailingYear.group(2));
            }
            if (year == null) {
                Matcher afterYear = YEAR_AFTER_EPISODE.matcher(after);
                if (afterYear.find()) {
                    year = Integer.parseInt(afterYear.group(1));
                }
            }
            if (year == null) {
                // "Show Name - 476-479 (2007) [HorribleSubs]": a year in parentheses later in the name.
                Matcher later = PAREN_YEAR.matcher(name.text());
                if (later.find()) {
                    year = Integer.parseInt(later.group(1));
                }
            }
            if (year == null) {
                // "Fighting Spirit 2000 S01 1080p/Fighting Spirit - 1x01.mkv": the season pack's folder has the year.
                String fileTitle = title;
                year = nearestTitleFolder(folders).map(ReleaseParser::folderTitleAndYear)
                        .filter(folder -> folder.year() != null && Catalog.key(folder.title()).equals(Catalog.key(fileTitle)))
                        .map(TitleAndYear::year).orElse(null);
            }
            // Shows carry their country in some names ("The Office US"); it isn't part of the title.
            title = preferLatin(INVERTED_ARTICLE.matcher(COUNTRY.matcher(title).replaceFirst("")).replaceFirst("$2 $1"));
            if (season == null) {
                season = folderSeason();
            }
            return ParsedRelease.episode(title, year, season, episodes, anime);
        }

        ParsedRelease movie(String text) {
            if (!folders.isEmpty() && COLLECTION.matcher(folders.getLast()).find()) {
                // "Saga Harry Potter/1-Harry.Potter...": the number orders the collection, it isn't part of the title.
                text = COLLECTION_INDEX.matcher(text).replaceFirst("");
            }
            // "(1)The Girl With The Dragon Tattoo": a leading index orders a set of files.
            text = LEADING_INDEX.matcher(text).replaceFirst("");
            TitleAndYear fromFile = titleAndYear(text);
            Optional<TitleAndYear> release = nearestTitleFolder(folders)
                    .filter(folder -> RELEASE_TAG.matcher(folder).find()).map(ReleaseParser::titleAndYear)
                    .filter(folder -> !folder.title().isEmpty());
            if (SCENE_FILE.matcher(fromFile.title()).matches() && release.isPresent()
                    && (fromFile.year() == null || fromFile.year().equals(release.get().year()))) {
                // "How.To.Be.Single.2016.1080p.BluRay.x264-BLOW/blow-how.to.be.single.2016.1080p.bluray.x264.mkv":
                // the release folder has the title the scene group abbreviated.
                return ParsedRelease.movie(release.get().title(), release.get().year());
            }
            String title = TRAILING_SEASON.matcher(fromFile.title()).replaceFirst("");
            if (fromFile.year() == null || title.isEmpty()) {
                // "Movie (2014)/movie.mkv": the folder usually names it better.
                // An obfuscated file in an obfuscated folder: keep looking up for the release folder.
                Optional<TitleAndYear> fromFolder = titleFolders(folders).stream().map(ReleaseParser::titleAndYear)
                        .filter(parsed -> parsed.year() != null).findFirst()
                        .or(() -> title.isEmpty() ? nearestTitleFolder(folders).map(ReleaseParser::titleAndYear) : Optional.empty());
                if (fromFolder.isPresent()) {
                    return ParsedRelease.movie(fromFolder.get().title(), fromFolder.get().year());
                }
            }
            if (title.isEmpty()) {
                return ParsedRelease.movie(titleFromBrackets().orElse(""), fromFile.year());
            }
            return ParsedRelease.movie(preferLatin(INVERTED_ARTICLE.matcher(title).replaceFirst("$2 $1")), fromFile.year());
        }
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
            // A year with no title before it ("(1968) Planet Of The Apes") is a leading year, handled below.
            if (!cleanTitle(name.substring(0, year.start())).isEmpty()) {
                yearStart = year.start();
                yearText = year.group(1);
            }
        }
        if (yearText != null) {
            // "Alien DC (1979)", "Brazil Criterion Edition (1985)": tags can come before the year too.
            return new TitleAndYear(cleanTitle(beforeTrailingWeakTags(name.substring(0, yearStart))), Integer.parseInt(yearText));
        }
        // "(1968) Planet Of The Apes", "2017a Dave Chappelle ...": the year first. A bare leading year is left alone,
        // since it's usually part of the title ("2001 A Space Odyssey").
        Matcher leading = LEADING_YEAR.matcher(name);
        if (leading.find() && leading.end() < tags) {
            String leadingYear = leading.group(1) != null ? leading.group(1) : leading.group(2);
            return new TitleAndYear(titleOnly(name.substring(leading.end(), tags)), Integer.parseInt(leadingYear));
        }
        return new TitleAndYear(titleOnly(name.substring(0, tags)), null);
    }

    /** A year-less title, cut at the first language or edition tag ("Fight Club French"). */
    private static String titleOnly(String text) {
        String head = TRAILING_GROUP.matcher(VOLUME.matcher(beforeReleaseTags(text)).replaceFirst("")).replaceFirst("");
        // "White Album 1-13", "Momokuri - 01+02", "Chrono Crusade ep. 1-5": a batch's range isn't part of its title.
        Matcher seasons = SEASON_RANGE.matcher(head);
        if (seasons.find()) {
            head = head.substring(0, seasons.start()).strip();
        }
        head = head.replaceAll("(?i)\\s*-?\\s*(?:ep\\.? ?)?\\d{1,4}(?: ?[-+&] ?\\d{1,4})+$", "").replaceAll("(?i) v\\d$", "")
                .replaceAll("(?i)\\s+complete(?: series| collection| version)?$", "");
        Matcher weak = WEAK_TAG.matcher(head);
        while (weak.find()) {
            String before = cleanTitle(head.substring(0, weak.start()));
            if (!before.isEmpty()) {
                return before;
            }
        }
        return cleanTitle(head);
    }

    /**
     * Episodes after the first: further markers for the same season ("E02E03", "S01E02 S01E03", "1x02x03",
     * "E01+02", "E00 &amp; S01E01") are a list; a hyphen ("E05-E07", "1x02-04") is a range.
     */
    private static List<Integer> moreEpisodes(int first, int season, String rest) {
        List<Integer> episodes = new ArrayList<>(List.of(first));
        Pattern next = Pattern.compile("(?i)^(?: ?(?:-|&|\\+|,|and)? ?(?:s0*" + season + " ?-? ?e|" + season + "x|e|x)(\\d{1,4})"
                + "|( ?- ?| ?(?:&|\\+) ?)(\\d{1,4}))(?![\\dp])");
        while (true) {
            Matcher match = next.matcher(rest);
            if (!match.find()) {
                return episodes;
            }
            boolean bare = match.group(1) == null;
            int number = Integer.parseInt(bare ? match.group(3) : match.group(1));
            int previous = episodes.getLast();
            // Anything implausible (a resolution, a year, an unrelated number) ends the list.
            if (number <= previous || number > previous + 50) {
                return episodes;
            }
            boolean range = bare && match.group(2).contains("-") || !bare && match.group().stripLeading().startsWith("-")
                    && !match.group().toLowerCase(Locale.ROOT).contains("s");
            if (range) {
                for (int episode = previous + 1; episode <= number; episode++) {
                    episodes.add(episode);
                }
            } else {
                episodes.add(number);
            }
            rest = rest.substring(match.end());
        }
    }

    /**
     * Season and episode run together ("117" is S01E17, "0307" S03E07), as TV rips number them. Only with corroboration
     * (a TV source or folder, or a season folder that agrees) and never for anime, which numbers episodes absolutely.
     */
    private static Optional<int[]> compact(String number, String rangeEnd, boolean tv, Integer season, List<String> folders) {
        if (rangeEnd != null || number.length() < 3 || number.length() > 4 || isYear(number)) {
            return Optional.empty();
        }
        int split = number.length() - 2;
        int compactSeason = Integer.parseInt(number.substring(0, split));
        int episode = Integer.parseInt(number.substring(split));
        Integer knownSeason = season != null ? season : seasonPackFolder(folders).orElse(null);
        boolean agrees = knownSeason != null && knownSeason == compactSeason;
        if ((tv || agrees) && compactSeason > 0 && episode > 0) {
            return Optional.of(new int[] {compactSeason, episode});
        }
        return Optional.empty();
    }

    /** The text before the first language or edition tag, when there's title text before it. */
    private static String beforeWeakTags(String head) {
        Matcher weak = WEAK_TAG.matcher(head);
        while (weak.find()) {
            if (!cleanTitle(head.substring(0, weak.start())).isEmpty()) {
                return head.substring(0, weak.start()).strip().replaceAll("[\\s\\-:(]+$", "");
            }
        }
        return head;
    }

    private static String preferLatin(String title) {
        Matcher match = CJK_THEN_LATIN.matcher(title);
        return match.matches() ? match.group(1) : title;
    }

    /**
     * Before a year, only tags that run up to it are cut: "Brazil Criterion Edition (1985)" is Brazil, but "A Very
     * Harold &amp; Kumar 3D Christmas (2011)" keeps its 3D.
     */
    private static String beforeTrailingWeakTags(String head) {
        Matcher weak = WEAK_TAG.matcher(head);
        while (weak.find()) {
            String rest = WEAK_TAG.matcher(head.substring(weak.start())).replaceAll(" ")
                    .replaceAll("(?i)\\b(?:edition|version|cut)\\b", " ");
            if (!cleanTitle(head.substring(0, weak.start())).isEmpty() && rest.replaceAll("[\\s\\-:()\\[\\],.]", "").isEmpty()) {
                return head.substring(0, weak.start()).strip().replaceAll("[\\s\\-:(]+$", "");
            }
        }
        return head;
    }

    /** "12", "十二", "二十三": digits or Chinese numerals below a thousand. */
    static int chineseNumber(String text) {
        if (text.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(text);
        }
        String digits = "零一二三四五六七八九";
        int total = 0;
        int current = 0;
        for (char c : text.toCharArray()) {
            int digit = digits.indexOf(c);
            if (digit >= 0) {
                current = digit;
            } else if (c == '十') {
                total += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else if (c == '百') {
                total += (current == 0 ? 1 : current) * 100;
                current = 0;
            }
        }
        return total + current;
    }

    private static List<Integer> range(String first, String last) {
        int from = Integer.parseInt(first);
        if (last == null) {
            return List.of(from);
        }
        int to = Integer.parseInt(last);
        if (to <= from || to > from + 50) {
            return List.of(from);
        }
        List<Integer> episodes = new ArrayList<>();
        for (int episode = from; episode <= to; episode++) {
            episodes.add(episode);
        }
        return episodes;
    }

    private static Integer seasonFromFolders(List<String> folders) {
        for (int i = folders.size() - 1; i >= 0; i--) {
            String folder = parseName(folders.get(i)).text();
            if (folder.equalsIgnoreCase("specials") || folder.equalsIgnoreCase("special")) {
                return 0;
            }
            Matcher match = SEASON_FOLDER.matcher(folder);
            if (match.matches()) {
                return Integer.parseInt(match.group(1) != null ? match.group(1) : match.group(2));
            }
            Matcher cjk = CJK_SEASON.matcher(folder);
            if (cjk.find() && cjk.end() == folder.length()) {
                return chineseNumber(cjk.group(1) != null ? cjk.group(1) : cjk.group(2));
            }
        }
        return null;
    }

    /** The season of a season-pack folder such as {@code Show.Name.S02.1080p.WEB-DL}. */
    private static Optional<Integer> seasonPackFolder(List<String> folders) {
        if (folders.isEmpty()) {
            return Optional.empty();
        }
        String folder = parseName(folders.getLast()).text();
        Matcher match = SEASON_PACK.matcher(folder);
        if (!match.find() || SEASON_RANGE.matcher(folder).find()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(match.group(1) != null ? match.group(1) : match.group(2)));
    }

    private static Optional<String> titleFromFolders(List<String> folders) {
        return nearestTitleFolder(folders).map(folder -> folderTitleAndYear(folder).title()).filter(title -> !title.isEmpty());
    }

    /** The folders that could name a title, innermost first. */
    private static List<String> titleFolders(List<String> folders) {
        List<String> result = new ArrayList<>();
        for (int i = folders.size() - 1; i >= 0; i--) {
            String folder = parseName(folders.get(i)).text();
            String lower = folder.toLowerCase(Locale.ROOT);
            if (!folder.isEmpty() && !SEASON_FOLDER.matcher(folder).matches() && !lower.equals("specials")
                    && !EXTRAS_FOLDERS.contains(lower) && !GENERIC_FOLDERS.contains(lower)) {
                result.add(folder);
            }
        }
        return result;
    }

    /** The innermost folder that names a title, skipping season, extras and organising folders. */
    private static Optional<String> nearestTitleFolder(List<String> folders) {
        for (int i = folders.size() - 1; i >= 0; i--) {
            String folder = parseName(folders.get(i)).text();
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
    private static TitleAndYear folderTitleAndYear(String folder) {
        Matcher cjk = CJK_SEASON.matcher(folder);
        if (cjk.find() && cjk.start() > 0) {
            folder = folder.substring(0, cjk.start());
        }
        Matcher seasonPack = SEASON_PACK.matcher(folder);
        String title = seasonPack.find() ? folder.substring(0, seasonPack.start()) : folder;
        TitleAndYear parsed = titleAndYear(title);
        return parsed.title().isEmpty() ? new TitleAndYear(cleanTitle(title), null) : parsed;
    }

    private static String beforeReleaseTags(String name) {
        return name.substring(0, releaseTagStart(name)).strip().replaceAll("[\\s\\-:(]+$", "");
    }

    private static int releaseTagStart(String name) {
        Matcher tag = RELEASE_TAG.matcher(name);
        return tag.find() ? tag.start() : name.length();
    }

    /**
     * Drops the release group and bracketed tags, keeping bracketed episode markers and years inline, and turns
     * separators into spaces. Set-aside brackets are kept in case nothing else names the title.
     */
    private static Name parseName(String raw) {
        String text = raw.replace('【', '[').replace('】', ']');
        String group = null;
        Matcher prefix = GROUP_PREFIX.matcher(text);
        // "[h265 - hevc] transformers 2": a bracketed tag up front isn't a fansub group.
        if (prefix.find() && !RELEASE_TAG.matcher(prefix.group(1)).find()) {
            group = prefix.group(1);
            text = text.substring(prefix.end());
        }
        List<String> setAside = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        Matcher bracket = BRACKETED.matcher(text);
        int last = 0;
        while (bracket.find()) {
            out.append(text, last, bracket.start());
            String content = bracket.group(1) != null ? bracket.group(1) : bracket.group(2);
            String rest = BRACKETED.matcher(text.substring(bracket.end())).replaceAll(" ");
            boolean atStart = out.toString().isBlank();
            // "[One Pace][1] Romance Dawn 01": a number first, with the title still to come, only orders the release.
            boolean ordering = atStart && rest.codePoints().anyMatch(Character::isLetter);
            if (KEPT_BRACKET.matcher(content).matches() && !ordering || YEAR.matcher(content.strip()).matches()) {
                out.append(' ').append(END_MARK.matcher(content).replaceFirst("")).append(' ');
            } else {
                out.append(' ');
                setAside.add(content);
            }
            last = bracket.end();
        }
        out.append(text.substring(last));
        // "(2013 BluRay - 1080p DUAL AUDIO)": the tags go, a year among them stays.
        String unbracketed = TAG_GROUP.matcher(out.toString()).replaceAll(tags -> {
            Matcher year = YEAR.matcher(tags.group());
            return year.find() ? " " + year.group(1) + " " : " ";
        });
        String normalized = normalizeSeparators(unbracketed);
        normalized = WEBSITE_PREFIX.matcher(normalized).replaceFirst("");
        // "h265 Volte Face 1080p": tags before the title too.
        normalized = LEADING_TAGS.matcher(normalized).replaceFirst("");
        return new Name(normalized, group, setAside);
    }

    private static String normalizeSeparators(String text) {
        String symbolsRemoved = SYMBOLS.matcher(text).replaceAll(" ").replace('_', ' ').replaceAll("(?<=\\p{L})\\+", " ");
        StringBuilder spaced = new StringBuilder(symbolsRemoved.length());
        for (int i = 0; i < symbolsRemoved.length(); i++) {
            char c = symbolsRemoved.charAt(i);
            spaced.append(c == '.' && !partOfTitleNumber(symbolsRemoved, i) ? ' ' : c);
        }
        String result = SPACES.matcher(spaced).replaceAll(" ").strip();
        // "kimetsu-no-yaiba-episode-25-1080p": hyphens as the only separator.
        if (!result.contains(" ") && result.chars().filter(c -> c == '-').count() >= 2) {
            result = result.replace('-', ' ');
        }
        return result;
    }

    /**
     * Dots separate words, except in numbers that belong to titles: "Evangelion 1.11", "No.6". A single digit on
     * the left and one or two on the right, so years and resolutions ("2014.1080p") still split.
     */
    private static boolean partOfTitleNumber(String text, int dot) {
        boolean digitBefore = dot > 0 && Character.isDigit(text.charAt(dot - 1));
        boolean singleDigitBefore = digitBefore && (dot < 2 || !Character.isDigit(text.charAt(dot - 2)) && text.charAt(dot - 2) != '.');
        int digitsAfter = 0;
        while (dot + 1 + digitsAfter < text.length() && Character.isDigit(text.charAt(dot + 1 + digitsAfter))) {
            digitsAfter++;
        }
        boolean endsAfter = dot + 1 + digitsAfter >= text.length() || text.charAt(dot + 1 + digitsAfter) != '.'
                && !Character.isLetterOrDigit(text.charAt(dot + 1 + digitsAfter));
        if (singleDigitBefore && digitsAfter >= 1 && digitsAfter <= 2 && endsAfter) {
            return true;
        }
        return digitsAfter > 0 && dot >= 2 && text.regionMatches(true, dot - 2, "No", 0, 2)
                && (dot < 3 || !Character.isLetter(text.charAt(dot - 3)));
    }

    private static String cleanTitle(String title) {
        String cleaned = title.replaceAll("[\\s\\-:,(]+$", "");
        // Notes in parentheses after the title ("Black Book (Zwartboek)", "(France)") aren't part of it, but ones
        // inside it are ("You Are (Not) Alone", "(500) Days of Summer"), and so is a year, which callers read off.
        String previous;
        do {
            previous = cleaned;
            cleaned = TRAILING_NOTE.matcher(cleaned).replaceFirst("");
        } while (!cleaned.equals(previous) && !cleaned.isBlank());
        if (cleaned.isBlank()) {
            cleaned = previous;
        }
        int open = cleaned.lastIndexOf('(');
        if (open > 0 && cleaned.indexOf(')', open) < 0) {
            // Left open where a year was cut off: "A Doll's House (Et Dukkehjem - Norway".
            cleaned = cleaned.substring(0, open);
        }
        cleaned = cleaned.replaceAll("\\(\\s*\\)", " ");
        cleaned = SPACES.matcher(cleaned).replaceAll(" ").strip();
        // Separators left dangling once the episode or year is cut off: "Show -", "Movie (".
        return cleaned.replaceAll("^[\\s\\-:,]+|[\\s\\-:,(]+$", "");
    }

    private static boolean isYear(String number) {
        return YEAR.matcher(number).matches();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        // "Show.Name.S01.E02.E03" has no extension.
        return dot > 0 && fileName.length() - dot <= 5 && !fileName.substring(dot + 1).matches("(?i)\\d+|[esx]\\d+")
                ? fileName.substring(0, dot) : fileName;
    }
}
