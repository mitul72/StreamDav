package com.example.streamdav.player;

import com.example.streamdav.player.mpv.LibMpv;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PlayersTest {

    @Test
    void withoutMpvOnlyJavaFxFormatsPlayInTheApp() {
        Players players = new Players(Optional.empty());

        assertTrue(players.forFile("clip.mp4").isPresent());
        assertTrue(players.forFile("song.mp3").isPresent());
        assertTrue(players.forFile("movie.mkv").isEmpty(), "MKV needs an external player");
        assertTrue(players.forFile("notes.txt").isEmpty());
    }

    @Test
    void withMpvEveryMediaFilePlaysInTheApp() {
        Optional<LibMpv> mpv = LibMpv.load();
        assumeTrue(mpv.isPresent(), "libmpv is not installed");
        Players players = new Players(mpv);

        assertTrue(players.forFile("movie.mkv").isPresent());
        assertTrue(players.forFile("show.webm").isPresent());
        assertTrue(players.forFile("song.flac").isPresent());
        assertTrue(players.forFile("notes.txt").isEmpty());
    }
}
