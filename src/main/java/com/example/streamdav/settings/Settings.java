package com.example.streamdav.settings;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** User preferences: saved servers, the external player command, and where to resume each video. */
public final class Settings {
    private static final Logger log = LogManager.getLogger(Settings.class);
    private static final String SERVERS = "servers";
    private static final String RESUME = "resume";
    private static final String EXTERNAL_PLAYER = "externalPlayer";
    private static final String AUTO_CONNECT = "autoConnect";

    private final Preferences prefs;

    public Settings() {
        this(Preferences.userRoot().node("com/example/streamdav"));
    }

    public Settings(Preferences prefs) {
        this.prefs = prefs;
    }

    public List<ServerProfile> servers() {
        Preferences servers = prefs.node(SERVERS);
        String autoConnect = prefs.get(AUTO_CONNECT, "");
        List<ServerProfile> result = new ArrayList<>();
        try {
            for (String id : servers.childrenNames()) {
                Preferences node = servers.node(id);
                result.add(new ServerProfile(id, node.get("name", ""), node.get("url", ""),
                        node.get("username", ""), node.get("password", ""), id.equals(autoConnect)));
            }
        } catch (BackingStoreException e) {
            log.warn("Could not read saved servers", e);
        }
        result.sort(Comparator.comparing(ServerProfile::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Saves a server, replacing any saved server with the same URL and username, and returns its id. */
    public String saveServer(String name, String url, String username, String password) {
        String id = servers().stream()
                .filter(server -> server.url().equals(url) && server.username().equals(username))
                .map(ServerProfile::id)
                .findFirst()
                .orElseGet(() -> UUID.randomUUID().toString());
        Preferences node = prefs.node(SERVERS).node(id);
        node.put("name", name);
        node.put("url", url);
        node.put("username", username);
        if (password.isEmpty()) {
            node.remove("password");
        } else {
            node.put("password", password);
        }
        flush();
        return id;
    }

    public void removeServer(ServerProfile server) {
        try {
            prefs.node(SERVERS).node(server.id()).removeNode();
            if (server.id().equals(prefs.get(AUTO_CONNECT, null))) {
                prefs.remove(AUTO_CONNECT);
            }
            flush();
        } catch (BackingStoreException e) {
            log.warn("Could not remove saved server {}", server.name(), e);
        }
    }

    /** The saved server to connect to at startup, if the user picked one. */
    public Optional<ServerProfile> autoConnectServer() {
        return servers().stream().filter(ServerProfile::autoConnect).findFirst();
    }

    /** Makes the server with this id the one to connect to at startup; {@code null} turns autoconnect off. */
    public void setAutoConnect(String serverId) {
        if (serverId == null) {
            prefs.remove(AUTO_CONNECT);
        } else {
            prefs.put(AUTO_CONNECT, serverId);
        }
        flush();
    }

    public Optional<String> externalPlayerCommand() {
        return Optional.ofNullable(prefs.get(EXTERNAL_PLAYER, null)).filter(command -> !command.isBlank());
    }

    /** A blank command clears the setting, so the player is detected automatically again. */
    public void setExternalPlayerCommand(String command) {
        if (command == null || command.isBlank()) {
            prefs.remove(EXTERNAL_PLAYER);
        } else {
            prefs.put(EXTERNAL_PLAYER, command.strip());
        }
        flush();
    }

    /** Where to resume the given file, or 0 to start from the beginning. */
    public long resumeMillis(URI media) {
        return prefs.node(RESUME).getLong(key(media), 0);
    }

    public void setResumeMillis(URI media, long millis) {
        prefs.node(RESUME).putLong(key(media), millis);
        flush();
    }

    public void clearResume(URI media) {
        prefs.node(RESUME).remove(key(media));
        flush();
    }

    private static String key(URI media) {
        // Preference keys are limited to 80 characters, so store a digest of the URL instead.
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(media.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void flush() {
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            log.warn("Could not save settings", e);
        }
    }
}
