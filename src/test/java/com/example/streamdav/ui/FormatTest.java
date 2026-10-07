package com.example.streamdav.ui;

import javafx.util.Duration;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatTest {

    @Test
    void formatsSizes() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            assertEquals("", Format.size(-1));
            assertEquals("0 B", Format.size(0));
            assertEquals("1023 B", Format.size(1023));
            assertEquals("1.0 KB", Format.size(1024));
            assertEquals("1.5 MB", Format.size(1536 * 1024));
            assertEquals("4.2 GB", Format.size((long) (4.2 * 1024 * 1024 * 1024)));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void formatsDurations() {
        assertEquals("0:00", Format.duration(Duration.ZERO));
        assertEquals("4:05", Format.duration(Duration.seconds(245)));
        assertEquals("1:02:03", Format.duration(Duration.seconds(3723)));
        assertEquals("--:--", Format.duration(Duration.UNKNOWN));
        assertEquals("--:--", Format.duration(Duration.INDEFINITE));
        assertEquals("--:--", Format.duration(null));
    }
}
