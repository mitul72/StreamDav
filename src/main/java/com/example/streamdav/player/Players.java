package com.example.streamdav.player;

import com.example.streamdav.library.MediaKind;
import com.example.streamdav.player.mpv.LibMpv;
import com.example.streamdav.player.mpv.MpvPlayback;

import java.util.Optional;

/** Chooses the engine that plays a file in the app: embedded mpv when installed, otherwise JavaFX Media. */
public final class Players {
    private final Optional<LibMpv> mpv;

    public Players(Optional<LibMpv> mpv) {
        this.mpv = mpv;
    }

    /** Uses libmpv if it can be loaded. */
    public static Players detect() {
        return new Players(LibMpv.load());
    }

    /** The engine for this file, or empty when it has to be handed to an external player. */
    public Optional<Playback.Factory> forFile(String fileName) {
        if (mpv.isPresent() && MediaKind.of(fileName).isMedia()) {
            LibMpv library = mpv.get();
            return Optional.of((url, start) -> new MpvPlayback(library, url, start));
        }
        return MediaKind.playsInBuiltInPlayer(fileName) ? Optional.of(JavaFxPlayback::new) : Optional.empty();
    }
}
