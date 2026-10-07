package com.example.streamdav.library;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaKindTest {

    @Test
    void classifiesByExtensionIgnoringCase() {
        assertEquals(MediaKind.VIDEO, MediaKind.of("Movie.MKV"));
        assertEquals(MediaKind.VIDEO, MediaKind.of("clip.mp4"));
        assertEquals(MediaKind.AUDIO, MediaKind.of("song.flac"));
        assertEquals(MediaKind.OTHER, MediaKind.of("notes.txt"));
        assertEquals(MediaKind.OTHER, MediaKind.of("README"));
        assertEquals(MediaKind.OTHER, MediaKind.of("archive.mp4.part"));
    }

    @Test
    void builtInPlayerOnlyClaimsFormatsJavaFxDecodes() {
        assertTrue(MediaKind.playsInBuiltInPlayer("clip.MP4"));
        assertTrue(MediaKind.playsInBuiltInPlayer("song.mp3"));
        assertFalse(MediaKind.playsInBuiltInPlayer("movie.mkv"));
        assertFalse(MediaKind.playsInBuiltInPlayer("song.flac"));
        assertFalse(MediaKind.playsInBuiltInPlayer("clip.flv"), "JavaFX dropped VP6, the codec FLV needs");
    }
}
