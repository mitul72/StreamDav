package com.example.streamdav.store;

import com.example.streamdav.library.RemoteFile;
import com.example.streamdav.metadata.EpisodeInfo;
import com.example.streamdav.metadata.Metadata;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The library index in SQLite: the folders the user added, the files found in them, and the metadata matched to
 * them. Files are stored as found, not as parsed, so parser improvements apply without a rescan.
 *
 * <p>One connection, used by one thread at a time.
 */
public final class LibraryStore implements AutoCloseable {
    private static final int SCHEMA_VERSION = 1;

    /** A folder on a saved server that the library scans. */
    public record Source(long id, String serverId, URI uri, String name, Instant lastScan) {
    }

    /**
     * @param folders the folders between the source and the file, outermost first
     * @param added   when the file was first found, kept across rescans
     */
    public record StoredFile(long id, long sourceId, RemoteFile file, List<String> folders, Instant added) {
        public StoredFile {
            folders = List.copyOf(folders);
        }
    }

    /** A file found by a scan, before it's stored. */
    public record FoundFile(RemoteFile file, List<String> folders) {
    }

    /**
     * What an item was matched to. {@code metadata} is empty when the providers had nothing, so the item isn't
     * searched for again on every refresh.
     *
     * @param manual the user picked the match, so automatic matching leaves it alone
     */
    public record StoredMatch(String itemKey, Optional<Metadata> metadata, Instant matchedAt, boolean manual) {
    }

    private final Connection connection;

    private LibraryStore(Connection connection) {
        this.connection = connection;
    }

    public static LibraryStore open(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            LibraryStore store = new LibraryStore(connection);
            store.migrate();
            return store;
        } catch (SQLException e) {
            throw new IOException("Could not open the library database at " + file, e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            int version;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                version = result.next() ? result.getInt(1) : 0;
            }
            if (version > SCHEMA_VERSION) {
                throw new SQLException("The library database is from a newer version of StreamDav.");
            }
            if (version < 1) {
                connection.setAutoCommit(false);
                statement.execute("""
                        CREATE TABLE source (
                            id INTEGER PRIMARY KEY,
                            server_id TEXT NOT NULL,
                            uri TEXT NOT NULL,
                            name TEXT NOT NULL,
                            last_scan INTEGER,
                            UNIQUE (server_id, uri))""");
                statement.execute("""
                        CREATE TABLE file (
                            id INTEGER PRIMARY KEY,
                            source_id INTEGER NOT NULL REFERENCES source (id) ON DELETE CASCADE,
                            uri TEXT NOT NULL,
                            name TEXT NOT NULL,
                            folders TEXT NOT NULL,
                            size INTEGER NOT NULL,
                            modified INTEGER,
                            added INTEGER NOT NULL,
                            scan INTEGER NOT NULL,
                            UNIQUE (source_id, uri))""");
                statement.execute("""
                        CREATE TABLE metadata (
                            provider TEXT NOT NULL,
                            provider_id TEXT NOT NULL,
                            kind TEXT NOT NULL,
                            title TEXT NOT NULL,
                            alt_titles TEXT NOT NULL,
                            year INTEGER,
                            overview TEXT,
                            poster_url TEXT,
                            backdrop_url TEXT,
                            genres TEXT NOT NULL,
                            rating REAL,
                            episode_count INTEGER,
                            anime INTEGER NOT NULL,
                            PRIMARY KEY (provider, provider_id))""");
                statement.execute("""
                        CREATE TABLE item_match (
                            item_key TEXT PRIMARY KEY,
                            provider TEXT,
                            provider_id TEXT,
                            matched_at INTEGER NOT NULL,
                            manual INTEGER NOT NULL DEFAULT 0)""");
                statement.execute("""
                        CREATE TABLE episode (
                            provider TEXT NOT NULL,
                            provider_id TEXT NOT NULL,
                            season INTEGER NOT NULL,
                            episode INTEGER NOT NULL,
                            title TEXT,
                            overview TEXT,
                            still_url TEXT,
                            air_date TEXT,
                            PRIMARY KEY (provider, provider_id, season, episode))""");
                statement.execute("CREATE TABLE episode_list (provider TEXT NOT NULL, provider_id TEXT NOT NULL,"
                        + " fetched_at INTEGER NOT NULL, PRIMARY KEY (provider, provider_id))");
                statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
                connection.commit();
                connection.setAutoCommit(true);
            }
        }
    }

    // Sources

    public synchronized List<Source> sources() throws IOException {
        return query("SELECT id, server_id, uri, name, last_scan FROM source ORDER BY name COLLATE NOCASE", statement -> {
        }, result -> new Source(result.getLong(1), result.getString(2), URI.create(result.getString(3)),
                result.getString(4), instant(result, 5)));
    }

    /** Adds a folder to the library, or returns it if it's already there. */
    public synchronized Source addSource(String serverId, URI uri, String name) throws IOException {
        update("INSERT INTO source (server_id, uri, name) VALUES (?, ?, ?) ON CONFLICT (server_id, uri) DO NOTHING",
                statement -> {
                    statement.setString(1, serverId);
                    statement.setString(2, uri.toString());
                    statement.setString(3, name);
                });
        return query("SELECT id, server_id, uri, name, last_scan FROM source WHERE server_id = ? AND uri = ?", statement -> {
            statement.setString(1, serverId);
            statement.setString(2, uri.toString());
        }, result -> new Source(result.getLong(1), result.getString(2), URI.create(result.getString(3)),
                result.getString(4), instant(result, 5))).getFirst();
    }

    /** Removes a folder and its files from the library. */
    public synchronized void removeSource(long sourceId) throws IOException {
        update("DELETE FROM source WHERE id = ?", statement -> statement.setLong(1, sourceId));
    }

    /** Removes every folder on a server, when the server itself is removed. */
    public synchronized void removeServer(String serverId) throws IOException {
        update("DELETE FROM source WHERE server_id = ?", statement -> statement.setString(1, serverId));
    }

    // Files

    /**
     * Records the outcome of scanning a source: files found are added or updated, keeping when they were first
     * found, and files no longer there are dropped.
     */
    public synchronized void replaceFiles(long sourceId, List<FoundFile> found, Instant scannedAt) throws IOException {
        saveScan(sourceId, found, scannedAt, true);
    }

    /**
     * Records a scan that couldn't list every folder: files found are added or updated, but none are dropped, since
     * the missing ones may only be in the folders that failed.
     */
    public synchronized void addFiles(long sourceId, List<FoundFile> found, Instant scannedAt) throws IOException {
        saveScan(sourceId, found, scannedAt, false);
    }

    private void saveScan(long sourceId, List<FoundFile> found, Instant scannedAt, boolean complete) throws IOException {
        long scan = scannedAt.toEpochMilli();
        inTransaction(() -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO file (source_id, uri, name, folders, size, modified, added, scan)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (source_id, uri) DO UPDATE SET name = excluded.name, folders = excluded.folders,
                        size = excluded.size, modified = excluded.modified, scan = excluded.scan""")) {
                for (FoundFile file : found) {
                    upsert.setLong(1, sourceId);
                    upsert.setString(2, file.file().uri().toString());
                    upsert.setString(3, file.file().name());
                    upsert.setString(4, String.join("\n", file.folders()));
                    upsert.setLong(5, file.file().size());
                    setInstant(upsert, 6, file.file().lastModified());
                    upsert.setLong(7, scan);
                    upsert.setLong(8, scan);
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
            if (complete) {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM file WHERE source_id = ? AND scan <> ?")) {
                    delete.setLong(1, sourceId);
                    delete.setLong(2, scan);
                    delete.executeUpdate();
                }
            }
            try (PreparedStatement mark = connection.prepareStatement("UPDATE source SET last_scan = ? WHERE id = ?")) {
                mark.setLong(1, scan);
                mark.setLong(2, sourceId);
                mark.executeUpdate();
            }
        });
    }

    public synchronized List<StoredFile> files() throws IOException {
        return query("SELECT id, source_id, uri, name, folders, size, modified, added FROM file", statement -> {
        }, result -> {
            String folders = result.getString(5);
            RemoteFile file = new RemoteFile(URI.create(result.getString(3)), result.getString(4), false,
                    result.getLong(6), instant(result, 7));
            return new StoredFile(result.getLong(1), result.getLong(2), file,
                    folders.isEmpty() ? List.of() : Arrays.asList(folders.split("\n")), instant(result, 8));
        });
    }

    // Matches and metadata

    /** Forgets items that matched nothing, so the next refresh searches for them again (say, with a new API key). */
    public synchronized void forgetUnmatched() throws IOException {
        update("DELETE FROM item_match WHERE provider IS NULL", statement -> {
        });
    }

    public synchronized Map<String, StoredMatch> matches() throws IOException {
        Map<String, Metadata> metadata = new HashMap<>();
        for (Metadata entry : query("SELECT provider, provider_id, kind, title, alt_titles, year, overview, poster_url,"
                + " backdrop_url, genres, rating, episode_count, anime FROM metadata", statement -> {
        }, LibraryStore::metadata)) {
            metadata.put(entry.provider() + "|" + entry.id(), entry);
        }
        Map<String, StoredMatch> matches = new HashMap<>();
        for (StoredMatch match : query("SELECT item_key, provider, provider_id, matched_at, manual FROM item_match",
                statement -> {
                }, result -> new StoredMatch(result.getString(1),
                        Optional.ofNullable(metadata.get(result.getString(2) + "|" + result.getString(3))),
                        instant(result, 4), result.getInt(5) != 0))) {
            matches.put(match.itemKey(), match);
        }
        return matches;
    }

    /** Records what an item matched; empty metadata records that nothing did. */
    public synchronized void saveMatch(String itemKey, Optional<Metadata> metadata, Instant matchedAt, boolean manual)
            throws IOException {
        inTransaction(() -> {
            if (metadata.isPresent()) {
                saveMetadata(metadata.get());
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO item_match (item_key, provider, provider_id, matched_at, manual) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (item_key) DO UPDATE SET provider = excluded.provider,
                        provider_id = excluded.provider_id, matched_at = excluded.matched_at, manual = excluded.manual""")) {
                statement.setString(1, itemKey);
                statement.setString(2, metadata.map(entry -> entry.provider().name()).orElse(null));
                statement.setString(3, metadata.map(Metadata::id).orElse(null));
                statement.setLong(4, matchedAt.toEpochMilli());
                statement.setInt(5, manual ? 1 : 0);
                statement.executeUpdate();
            }
        });
    }

    private void saveMetadata(Metadata metadata) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO metadata (provider, provider_id, kind, title, alt_titles, year, overview,
                    poster_url, backdrop_url, genres, rating, episode_count, anime)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, metadata.provider().name());
            statement.setString(2, metadata.id());
            statement.setString(3, metadata.kind().name());
            statement.setString(4, metadata.title());
            statement.setString(5, String.join("\n", metadata.altTitles()));
            setInteger(statement, 6, metadata.year());
            statement.setString(7, metadata.overview());
            statement.setString(8, metadata.posterUrl());
            statement.setString(9, metadata.backdropUrl());
            statement.setString(10, String.join("\n", metadata.genres()));
            if (metadata.rating() == null) {
                statement.setNull(11, Types.REAL);
            } else {
                statement.setDouble(11, metadata.rating());
            }
            setInteger(statement, 12, metadata.episodeCount());
            statement.setInt(13, metadata.anime() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private static Metadata metadata(ResultSet result) throws SQLException {
        Double rating = result.getDouble(11);
        if (result.wasNull()) {
            rating = null;
        }
        return new Metadata(Metadata.Provider.valueOf(result.getString(1)), result.getString(2),
                Metadata.Kind.valueOf(result.getString(3)), result.getString(4), lines(result.getString(5)),
                integer(result, 6), result.getString(7), result.getString(8), result.getString(9),
                lines(result.getString(10)), rating, integer(result, 12), result.getInt(13) != 0);
    }

    // Episodes

    /** When the show's episode list was last fetched, if it has been. */
    public synchronized Optional<Instant> episodesFetched(Metadata.Provider provider, String id) throws IOException {
        return query("SELECT fetched_at FROM episode_list WHERE provider = ? AND provider_id = ?", statement -> {
            statement.setString(1, provider.name());
            statement.setString(2, id);
        }, result -> instant(result, 1)).stream().findFirst();
    }

    public synchronized void saveEpisodes(Metadata.Provider provider, String id, List<EpisodeInfo> episodes,
                                          Instant fetchedAt) throws IOException {
        inTransaction(() -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM episode WHERE provider = ? AND provider_id = ?")) {
                delete.setString(1, provider.name());
                delete.setString(2, id);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR REPLACE INTO episode (provider, provider_id, season, episode, title, overview, still_url, air_date)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                for (EpisodeInfo episode : episodes) {
                    insert.setString(1, provider.name());
                    insert.setString(2, id);
                    insert.setInt(3, episode.season());
                    insert.setInt(4, episode.episode());
                    insert.setString(5, episode.title());
                    insert.setString(6, episode.overview());
                    insert.setString(7, episode.stillUrl());
                    insert.setString(8, episode.airDate() == null ? null : episode.airDate().toString());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            try (PreparedStatement mark = connection.prepareStatement(
                    "INSERT OR REPLACE INTO episode_list (provider, provider_id, fetched_at) VALUES (?, ?, ?)")) {
                mark.setString(1, provider.name());
                mark.setString(2, id);
                mark.setLong(3, fetchedAt.toEpochMilli());
                mark.executeUpdate();
            }
        });
    }

    public synchronized List<EpisodeInfo> episodes(Metadata.Provider provider, String id) throws IOException {
        return query("SELECT season, episode, title, overview, still_url, air_date FROM episode"
                + " WHERE provider = ? AND provider_id = ? ORDER BY season, episode", statement -> {
            statement.setString(1, provider.name());
            statement.setString(2, id);
        }, result -> new EpisodeInfo(result.getInt(1), result.getInt(2), result.getString(3), result.getString(4),
                result.getString(5), result.getString(6) == null ? null : LocalDate.parse(result.getString(6))));
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            // Nothing useful to do while shutting down.
        }
    }

    // Plumbing

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(ResultSet result) throws SQLException;
    }

    @FunctionalInterface
    private interface Work {
        void run() throws SQLException;
    }

    private <T> List<T> query(String sql, Binder binder, Reader<T> reader) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(reader.read(result));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IOException("Library database query failed: " + e.getMessage(), e);
        }
    }

    private void update(String sql, Binder binder) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Library database update failed: " + e.getMessage(), e);
        }
    }

    private void inTransaction(Work work) throws IOException {
        try {
            connection.setAutoCommit(false);
            try {
                work.run();
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IOException("Library database update failed: " + e.getMessage(), e);
        }
    }

    private static Instant instant(ResultSet result, int column) throws SQLException {
        long millis = result.getLong(column);
        return result.wasNull() ? null : Instant.ofEpochMilli(millis);
    }

    private static Integer integer(ResultSet result, int column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setLong(index, value.toEpochMilli());
        }
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static List<String> lines(String text) {
        return text == null || text.isEmpty() ? List.of() : Arrays.asList(text.split("\n"));
    }
}
