package com.example.streamdav.metadata;

import com.example.streamdav.metadata.Metadata.Kind;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the provider entry for a parsed title. Anime-looking releases go to AniList first and everything else to
 * TMDB; the other provider is a fallback that has to match the title almost exactly.
 */
public final class MetadataMatcher {
    /** Below this, a candidate is a different film or show that happens to share words. */
    static final double ACCEPT = 0.8;
    /** A fallback provider's title has to match this well, so a western show never turns into an anime. */
    static final double FALLBACK_TITLE = 0.95;
    private static final double SERIES_NAME = 0.92;
    private static final Pattern SUBTITLE = Pattern.compile(": | - ");

    /**
     * What the file names say.
     *
     * @param maxEpisode the highest episode number among absolutely numbered or first-season files, or null; a show
     *                   with fewer episodes than that is a different season or series (AniList lists each season
     *                   on its own)
     */
    public record Query(Kind kind, String title, Integer year, boolean animeHint, Integer maxEpisode) {

        public Query(Kind kind, String title, Integer year, boolean animeHint) {
            this(kind, title, year, animeHint, null);
        }
    }

    public record Match(Metadata metadata, double score) {
    }

    /** One provider's search. */
    @FunctionalInterface
    public interface Source {
        List<Metadata> search(String title, Integer year, Kind kind) throws IOException, InterruptedException;

        /** Whether the provider narrows by year itself, so a search without one is worth a retry. */
        default boolean filtersByYear() {
            return false;
        }
    }

    private final Source tmdb;
    private final Source aniList;

    /**
     * @param tmdb null when there's no TMDB key; anime still matches through AniList
     */
    public MetadataMatcher(Source tmdb, Source aniList) {
        this.tmdb = tmdb;
        this.aniList = aniList;
    }

    public static MetadataMatcher of(TmdbClient tmdb, AniListClient aniList) {
        Source tmdbSource = tmdb == null ? null : new Source() {
            @Override
            public List<Metadata> search(String title, Integer year, Kind kind) throws IOException, InterruptedException {
                return kind == Kind.MOVIE ? tmdb.searchMovies(title, year) : tmdb.searchShows(title, year);
            }

            @Override
            public boolean filtersByYear() {
                return true;
            }
        };
        return new MetadataMatcher(tmdbSource, (title, year, kind) -> aniList.search(title, kind));
    }

    public Optional<Match> match(Query query) throws IOException, InterruptedException {
        List<Source> order = new ArrayList<>();
        if (query.animeHint()) {
            order.add(aniList);
            order.add(tmdb);
        } else {
            order.add(tmdb);
            order.add(aniList);
        }
        boolean first = true;
        for (Source source : order) {
            if (source == null) {
                continue;
            }
            Optional<Match> match = best(query, search(source, query), first ? 0 : FALLBACK_TITLE);
            if (match.isPresent()) {
                return match;
            }
            first = false;
        }
        return Optional.empty();
    }

    private static List<Metadata> search(Source source, Query query) throws IOException, InterruptedException {
        List<Metadata> results = source.search(query.title(), query.year(), query.kind());
        if (results.isEmpty() && query.year() != null && source.filtersByYear()) {
            // A release year off by one, or a show's later season, still finds it.
            results = source.search(query.title(), null, query.kind());
        }
        return results;
    }

    static Optional<Match> best(Query query, List<Metadata> candidates, double minimumTitle) {
        Match best = null;
        for (int rank = 0; rank < candidates.size(); rank++) {
            Metadata candidate = candidates.get(rank);
            if (candidate.kind() != query.kind()) {
                continue;
            }
            double title = titleScore(query.title(), candidate);
            if (title < minimumTitle) {
                continue;
            }
            // The provider's own ranking breaks ties between equally good titles.
            double score = title + yearScore(query, candidate) + episodeScore(query, candidate) - 0.02 * rank;
            if (best == null || score > best.score()) {
                best = new Match(candidate, score);
            }
        }
        return Optional.ofNullable(best).filter(match -> match.score() >= ACCEPT);
    }

    static double titleScore(String query, Metadata candidate) {
        double best = 0;
        for (String title : candidate.allTitles()) {
            best = Math.max(best, similarity(query, title));
            // "Hajime no Ippo" for "Hajime no Ippo: The Fighting!": the series name without its subtitle, which
            // sequels share, so a little below an exact match.
            Matcher subtitle = SUBTITLE.matcher(title);
            // Films that share a series name are different films ("Dragon Ball Super: Broly").
            if (candidate.kind() == Kind.SHOW && subtitle.find()) {
                best = Math.max(best, similarity(query, title.substring(0, subtitle.start())) * SERIES_NAME);
            }
        }
        return best;
    }

    private static double episodeScore(Query query, Metadata candidate) {
        if (query.maxEpisode() == null || candidate.episodeCount() == null) {
            return 0;
        }
        return query.maxEpisode() > candidate.episodeCount() ? -0.2 : 0.05;
    }

    private static double yearScore(Query query, Metadata candidate) {
        if (query.year() == null || candidate.year() == null) {
            return 0;
        }
        int difference = Math.abs(query.year() - candidate.year());
        if (difference == 0) {
            return 0.1;
        }
        if (difference == 1) {
            return 0;
        }
        // A show's files may carry a later season's year; a film's year is its own.
        return query.kind() == Kind.SHOW ? -0.15 : -0.3;
    }

    /** 1 for the same title written differently ("Mr. Robot", "mr robot"), falling with the edit distance. */
    static double similarity(String a, String b) {
        String left = normalize(a);
        String right = normalize(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        // "SPY×FAMILY" and "Spy x Family", "Spiderman" and "Spider-Man": only the spacing differs.
        if (withoutArticle(left).replace(" ", "").equals(withoutArticle(right).replace(" ", ""))) {
            return 1;
        }
        int distance = levenshtein(left, right);
        return 1 - (double) distance / Math.max(left.length(), right.length());
    }

    /** Lower case, without accents or punctuation: how titles are compared. */
    public static String normalize(String title) {
        String decomposed = Normalizer.normalize(title, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return decomposed.toLowerCase(Locale.ROOT)
                .replace("&", " and ").replace('×', 'x')
                .replaceAll("['’`]", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip();
    }

    private static String withoutArticle(String title) {
        return title.replaceFirst("^(?:the|a|an) ", "");
    }

    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** Candidates in the order they'd be picked, for showing alternatives when the user fixes a match. */
    public static List<Match> ranked(Query query, List<Metadata> candidates) {
        List<Match> matches = new ArrayList<>();
        for (int rank = 0; rank < candidates.size(); rank++) {
            Metadata candidate = candidates.get(rank);
            matches.add(new Match(candidate, titleScore(query.title(), candidate) + yearScore(query, candidate)
                    + episodeScore(query, candidate) - 0.02 * rank));
        }
        matches.sort(Comparator.comparingDouble(Match::score).reversed());
        return matches;
    }
}
