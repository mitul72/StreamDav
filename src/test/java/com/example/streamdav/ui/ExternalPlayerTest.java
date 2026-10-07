package com.example.streamdav.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalPlayerTest {

    @Test
    void splitsOnWhitespace() {
        assertEquals(List.of("mpv"), ExternalPlayer.parseCommand("mpv"));
        assertEquals(List.of("open", "-a", "VLC"), ExternalPlayer.parseCommand("  open  -a VLC "));
    }

    @Test
    void keepsQuotedPathsTogether() {
        assertEquals(List.of("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe", "--fullscreen"),
                ExternalPlayer.parseCommand("\"C:\\Program Files\\VideoLAN\\VLC\\vlc.exe\" --fullscreen"));
    }

    @Test
    void keepsEmptyQuotedArgument() {
        assertEquals(List.of("player", ""), ExternalPlayer.parseCommand("player \"\""));
    }

    @Test
    void blankCommandHasNoTokens() {
        assertEquals(List.of(), ExternalPlayer.parseCommand("   "));
    }
}
