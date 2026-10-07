package com.example.streamdav.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {
    private Preferences node;
    private Settings settings;

    @BeforeEach
    void setUp() {
        // A single uniquely named node, so removing it in tearDown leaves nothing behind.
        node = Preferences.userRoot().node("com/example/streamdav-test-" + UUID.randomUUID());
        settings = new Settings(node);
    }

    @AfterEach
    void tearDown() throws BackingStoreException {
        node.removeNode();
    }

    @Test
    void savesServersSortedByName() {
        settings.saveServer("Office", "https://office.example/dav/", "me", "");
        settings.saveServer("attic", "https://attic.example/", "", "");

        List<ServerProfile> servers = settings.servers();
        assertEquals(List.of("attic", "Office"), servers.stream().map(ServerProfile::name).toList());
        assertEquals("me", servers.get(1).username());
        assertEquals("", servers.get(1).password());
    }

    @Test
    void sameUrlAndUsernameReplacesTheSavedServer() {
        settings.saveServer("NAS", "https://nas.local/", "me", "secret");
        settings.saveServer("Renamed", "https://nas.local/", "me", "");
        settings.saveServer("NAS as guest", "https://nas.local/", "guest", "");

        List<ServerProfile> servers = settings.servers();
        assertEquals(2, servers.size());
        ServerProfile mine = servers.stream().filter(s -> s.username().equals("me")).findFirst().orElseThrow();
        assertEquals("Renamed", mine.name());
        assertEquals("", mine.password(), "forgetting the password must remove the stored one");
    }

    @Test
    void removesServers() {
        settings.saveServer("NAS", "https://nas.local/", "", "");
        settings.removeServer(settings.servers().getFirst());
        assertTrue(settings.servers().isEmpty());
    }

    @Test
    void blankExternalPlayerClearsTheSetting() {
        settings.setExternalPlayerCommand("  mpv --fs ");
        assertEquals(Optional.of("mpv --fs"), settings.externalPlayerCommand());
        settings.setExternalPlayerCommand(" ");
        assertEquals(Optional.empty(), settings.externalPlayerCommand());
    }

    @Test
    void remembersResumePositionsPerUrl() {
        URI movie = URI.create("https://nas.local/Movies/" + "Very%20Long%20Name".repeat(10) + ".mp4");
        URI other = URI.create("https://nas.local/Movies/Other.mp4");

        settings.setResumeMillis(movie, 754_000);
        assertEquals(754_000, settings.resumeMillis(movie));
        assertEquals(0, settings.resumeMillis(other));

        settings.clearResume(movie);
        assertEquals(0, settings.resumeMillis(movie));
    }
}
