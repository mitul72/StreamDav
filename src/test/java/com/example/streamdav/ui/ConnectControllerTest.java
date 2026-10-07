package com.example.streamdav.ui;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static com.example.streamdav.ui.ConnectController.parseServerUrl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectControllerTest {

    @Test
    void addsTrailingSlash() {
        assertEquals(URI.create("https://nas.local/dav/"), parseServerUrl("https://nas.local/dav"));
        assertEquals(URI.create("http://nas.local:8080/"), parseServerUrl("http://nas.local:8080"));
    }

    @Test
    void defaultsToHttps() {
        assertEquals(URI.create("https://nas.local/media/"), parseServerUrl("  nas.local/media/  "));
    }

    @Test
    void encodesSpaces() {
        assertEquals(URI.create("https://nas.local/My%20Videos/"), parseServerUrl("https://nas.local/My Videos"));
    }

    @Test
    void normalisesSchemeCaseAndDropsQuery() {
        assertEquals(URI.create("https://nas.local/dav/"), parseServerUrl("HTTPS://nas.local/dav?x=1"));
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> parseServerUrl(""));
        assertThrows(IllegalArgumentException.class, () -> parseServerUrl("   "));
        assertThrows(IllegalArgumentException.class, () -> parseServerUrl("ftp://nas.local/"));
        assertThrows(IllegalArgumentException.class, () -> parseServerUrl("https://user:secret@nas.local/"));
        assertThrows(IllegalArgumentException.class, () -> parseServerUrl("https://nas local/"));
    }
}
