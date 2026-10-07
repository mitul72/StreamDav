package com.example.streamdav.player;

import com.example.streamdav.library.MediaKind;

import java.util.Optional;

/** Chooses the engine that plays a file in the app. */
public final class Players {

    /** The engine for this file, or empty when it has to be handed to an external player. */
    public Optional<Playback.Factory> forFile(String fileName) {
        return MediaKind.playsInBuiltInPlayer(fileName) ? Optional.of(JavaFxPlayback::new) : Optional.empty();
    }
}
