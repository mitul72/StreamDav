package com.example.streamdav.player;

import com.example.streamdav.player.mpv.LibMpv;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PlayersTest {

    @Test
    void withoutMpvOnlyJavaFxFormatsPlayInTheApp() {
        Players players = new Players(Optional.empty(), OptionalLong::empty);

        assertTrue(players.forFile("clip.mp4").isPresent());
        assertTrue(players.forFile("song.mp3").isPresent());
        assertTrue(players.forFile("movie.mkv").isEmpty(), "MKV needs an external player");
        assertTrue(players.forFile("clip.flv").isEmpty(), "so does FLV");
        assertTrue(players.forFile("notes.txt").isEmpty());
    }

    @Test
    void withMpvEveryMediaFilePlaysInTheApp() {
        Optional<LibMpv> mpv = LibMpv.load();
        assumeTrue(mpv.isPresent(), "libmpv is not installed");
        Players players = new Players(mpv, OptionalLong::empty);

        assertTrue(players.forFile("movie.mkv").isPresent());
        assertTrue(players.forFile("show.webm").isPresent());
        assertTrue(players.forFile("song.flac").isPresent());
        assertTrue(players.forFile("notes.txt").isEmpty());
    }

    @Test
    void mpvDrawsOnTheWindowOnlyWhenThereIsAHandle() {
        Optional<LibMpv> mpv = LibMpv.load();
        assumeTrue(mpv.isPresent(), "libmpv is not installed");

        assertFalse(new Players(mpv, OptionalLong::empty).forFile("movie.mkv").orElseThrow().drawsOnWindow());
        assertTrue(new Players(mpv, () -> OptionalLong.of(42)).forFile("movie.mkv").orElseThrow().drawsOnWindow());
        assertFalse(new Players(Optional.empty(), () -> OptionalLong.of(42)).forFile("clip.mp4").orElseThrow().drawsOnWindow(),
                "JavaFX Media can't draw on a native window");
    }
}
