package com.example.streamdav.catalog;

import com.example.streamdav.catalog.LibraryScanner.ScannedFile;
import com.example.streamdav.library.RemoteFile;
import com.example.streamdav.metadata.EpisodeInfo;
import com.example.streamdav.metadata.Metadata;
import com.example.streamdav.metadata.Metadata.Kind;
import com.example.streamdav.store.LibraryStore.StoredFile;
import com.example.streamdav.store.LibraryStore.StoredMatch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The library as the home screen shows it: movies and shows, each with its metadata when it was matched. Entries
 * that matched the same title are one item ("Naruto Shippuden" and "Naruto Shippuuden" files are one show).
 */
public record LibraryIndex(List<Item> items) {

    public enum Category { MOVIES, SHOWS, ANIME }

    /** A file to play, and the saved server it's on. */
    public record FileRef(String serverId, RemoteFile file, Instant added) {
    }

    /**
     * @param season null when numbered absolutely
     * @param info   the provider's details for the episode, when it has them
     */
    public record EpisodeEntry(Integer season, List<Integer> numbers, List<FileRef> versions, Optional<EpisodeInfo> info) {

        public String label() {
            return Catalog.episodeLabel(season, numbers);
        }
    }

    /**
     * @param id       stable across refreshes: the provider id once matched, otherwise the parsed title
     * @param itemKeys the parsed titles merged into this item, for fixing its match
     * @param versions a movie's files (several for several editions or qualities); empty for shows
     * @param added    when its newest file was first found
     */
    public record Item(String id, Kind kind, Category category, String title, Integer year, Optional<Metadata> metadata,
                       List<String> itemKeys, List<FileRef> versions, List<EpisodeEntry> episodes, Instant added) {

        /** Season numbers in display order, as {@link Catalog.Show#seasons()} orders them. */
        public List<Integer> seasons() {
            return episodes.stream().map(EpisodeEntry::season).distinct().sorted(Catalog.SEASON_ORDER).toList();
        }

        public List<EpisodeEntry> episodes(Integer season) {
            return episodes.stream().filter(episode -> Objects.equals(episode.season(), season)).toList();
        }
    }

    public static final Comparator<Item> BY_TITLE = Comparator.comparing(Item::title, String.CASE_INSENSITIVE_ORDER);

    public List<Item> category(Category category) {
        return items.stream().filter(item -> item.category() == category).sorted(BY_TITLE).toList();
    }

    public List<Item> recentlyAdded(int limit) {
        return items.stream().sorted(Comparator.comparing(Item::added, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit).toList();
    }

    public Optional<Item> item(String id) {
        return items.stream().filter(item -> item.id().equals(id)).findFirst();
    }

    /** The key a movie's match is stored under. */
    public static String movieKey(String title, Integer year) {
        return "movie|" + Catalog.key(title) + "|" + year;
    }

    /** The key a show's match is stored under; shows are matched by title alone, as episodes rarely carry a year. */
    public static String showKey(String title) {
        return "show|" + Catalog.key(title);
    }

    /**
     * @param serverBySource each source's server id
     * @param episodes       the provider's episode list for a matched show, empty when it has none
     */
    public static LibraryIndex build(List<StoredFile> stored, Map<Long, String> serverBySource,
                                     Map<String, StoredMatch> matches, Function<Metadata, List<EpisodeInfo>> episodes) {
        Map<RemoteFile, StoredFile> byFile = new HashMap<>();
        List<ScannedFile> scanned = new ArrayList<>();
        for (StoredFile file : stored) {
            // Re-parsed every time, so parser improvements apply to files scanned before them.
            if (ReleaseParser.isExtra(file.file().name(), file.folders())) {
                continue;
            }
            byFile.put(file.file(), file);
            scanned.add(new ScannedFile(file.file(), file.folders(), ReleaseParser.parse(file.file().name(), file.folders())));
        }
        Catalog catalog = Catalog.of(scanned);
        Function<ScannedFile, FileRef> ref = file -> {
            StoredFile source = byFile.get(file.file());
            return new FileRef(serverBySource.get(source.sourceId()), file.file(), source.added());
        };

        Map<String, Builder> builders = new LinkedHashMap<>();
        for (Catalog.Movie movie : catalog.movies()) {
            String key = movieKey(movie.title(), movie.year());
            Optional<Metadata> metadata = metadata(matches, key);
            boolean anime = movie.versions().stream().anyMatch(file -> file.release().anime());
            Builder builder = builders.computeIfAbsent(identity(metadata, key),
                    id -> new Builder(id, Kind.MOVIE, movie.title(), movie.year(), metadata, anime));
            builder.keys.add(key);
            movie.versions().forEach(file -> builder.versions.add(ref.apply(file)));
        }
        for (Catalog.Show show : catalog.shows()) {
            String key = showKey(show.title());
            Optional<Metadata> metadata = metadata(matches, key);
            Builder builder = builders.computeIfAbsent(identity(metadata, key),
                    id -> new Builder(id, Kind.SHOW, show.title(), show.year(), metadata, show.anime()));
            builder.keys.add(key);
            for (Catalog.Episode episode : show.episodes()) {
                builder.addEpisode(episode, episode.versions().stream().map(ref).toList());
            }
        }
        return new LibraryIndex(builders.values().stream()
                .map(builder -> builder.build(builder.metadata.map(episodes).orElse(List.of())))
                .toList());
    }

    private static Optional<Metadata> metadata(Map<String, StoredMatch> matches, String key) {
        return Optional.ofNullable(matches.get(key)).flatMap(StoredMatch::metadata);
    }

    private static String identity(Optional<Metadata> metadata, String key) {
        return metadata.map(entry -> entry.provider().name().toLowerCase(Locale.ROOT) + ":" + entry.id()).orElse(key);
    }

    private static final class Builder {
        private final String id;
        private final Kind kind;
        private final String parsedTitle;
        private final Integer parsedYear;
        private final Optional<Metadata> metadata;
        private final boolean animeHint;
        private final List<String> keys = new ArrayList<>();
        private final List<FileRef> versions = new ArrayList<>();
        private final Map<String, Episode> episodes = new LinkedHashMap<>();

        private record Episode(Integer season, List<Integer> numbers, List<FileRef> versions) {
        }

        Builder(String id, Kind kind, String parsedTitle, Integer parsedYear, Optional<Metadata> metadata, boolean animeHint) {
            this.id = id;
            this.kind = kind;
            this.parsedTitle = parsedTitle;
            this.parsedYear = parsedYear;
            this.metadata = metadata;
            this.animeHint = animeHint;
        }

        void addEpisode(Catalog.Episode episode, List<FileRef> files) {
            // Two spellings of one show can have the same episode: they're versions of it.
            Episode entry = episodes.computeIfAbsent(episode.season() + "|" + episode.numbers().getFirst(),
                    key -> new Episode(episode.season(), episode.numbers(), new ArrayList<>()));
            entry.versions().addAll(files);
        }

        Item build(List<EpisodeInfo> episodeInfo) {
            Category category = metadata.map(Metadata::anime).orElse(animeHint) ? Category.ANIME
                    : kind == Kind.MOVIE ? Category.MOVIES : Category.SHOWS;
            Map<String, EpisodeInfo> bySeason = new HashMap<>();
            List<EpisodeInfo> regular = new ArrayList<>();
            for (EpisodeInfo info : episodeInfo) {
                bySeason.put(info.season() + "|" + info.episode(), info);
                if (info.season() > 0) {
                    regular.add(info);
                }
            }
            List<EpisodeEntry> entries = episodes.values().stream()
                    .map(episode -> new EpisodeEntry(episode.season(), episode.numbers(), List.copyOf(episode.versions()),
                            info(episode, bySeason, regular)))
                    .sorted(Comparator.comparing(EpisodeEntry::season, Catalog.SEASON_ORDER)
                            .thenComparing(episode -> episode.numbers().getFirst()))
                    .toList();
            Instant added = Stream.concat(versions.stream(),
                            episodes.values().stream().flatMap(episode -> episode.versions().stream()))
                    .map(FileRef::added).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            return new Item(id, kind, category, metadata.map(Metadata::title).orElse(parsedTitle),
                    metadata.map(Metadata::year).orElse(parsedYear), metadata, List.copyOf(keys), List.copyOf(versions),
                    entries, added);
        }

        /** By season and number; an absolute number counts through the provider's regular seasons. */
        private static Optional<EpisodeInfo> info(Episode episode, Map<String, EpisodeInfo> bySeason, List<EpisodeInfo> regular) {
            int number = episode.numbers().getFirst();
            if (episode.season() != null) {
                return Optional.ofNullable(bySeason.get(episode.season() + "|" + number));
            }
            return number >= 1 && number <= regular.size() ? Optional.of(regular.get(number - 1)) : Optional.empty();
        }
    }
}
