package com.example.streamdav.player;

/** An audio or subtitle track the user can switch to; {@code id} is engine-specific. */
public record Track(Kind kind, String id, String label, boolean selected) {

    public enum Kind { AUDIO, SUBTITLE }
}
