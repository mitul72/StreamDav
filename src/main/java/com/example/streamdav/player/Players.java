package com.example.streamdav.player;

import com.example.streamdav.library.MediaKind;
import com.example.streamdav.player.mpv.LibMpv;
import com.example.streamdav.player.mpv.MpvPlayback;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * Chooses the engine that plays a file in the app: embedded mpv when installed, otherwise JavaFX Media.
 *
 * <p>mpv draws on the GPU into the app's window when the platform gives a window handle; if that ever fails to
 * start, the rest of the session falls back to mpv's software renderer, drawn by JavaFX.
 */
public final class Players {
    private static final Logger log = LogManager.getLogger(Players.class);

    private final Optional<LibMpv> mpv;
    private final Supplier<OptionalLong> videoWindow;
    private volatile boolean windowOutputFailed;

    /**
     * @param videoWindow the native handle of the window video plays in, looked up when a file is opened
     */
    public Players(Optional<LibMpv> mpv, Supplier<OptionalLong> videoWindow) {
        this.mpv = mpv;
        this.videoWindow = videoWindow;
    }

    /** Uses libmpv if it can be loaded. */
    public static Players detect(Supplier<OptionalLong> videoWindow) {
        return new Players(LibMpv.load(), videoWindow);
    }

    /** The engine for this file, or empty when it has to be handed to an external player. */
    public Optional<Playback.Factory> forFile(String fileName) {
        if (mpv.isPresent() && MediaKind.of(fileName).isMedia()) {
            LibMpv library = mpv.get();
            OptionalLong window = windowOutputFailed ? OptionalLong.empty() : videoWindow.get();
            if (window.isPresent()) {
                long handle = window.getAsLong();
                return Optional.of(new Playback.Factory() {
                    @Override
                    public Playback open(URI url, Duration start) {
                        return MpvPlayback.onWindow(library, url, start, handle, NativeWindow.platform(), () -> {
                            log.warn("mpv's GPU video output failed; using its software renderer from now on");
                            windowOutputFailed = true;
                        });
                    }

                    @Override
                    public boolean drawsOnWindow() {
                        return true;
                    }
                });
            }
            return Optional.of((url, start) -> new MpvPlayback(library, url, start));
        }
        return MediaKind.playsInBuiltInPlayer(fileName) ? Optional.of(JavaFxPlayback::new) : Optional.empty();
    }
}
