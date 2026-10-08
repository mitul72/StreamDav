package com.example.streamdav.player;

import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The operating system's handle for a JavaFX window, which mpv can draw video into: an X11 window id on Linux
 * (JavaFX runs on X11 there, under XWayland on Wayland desktops) or an HWND on Windows.
 *
 * <p>JavaFX has no public API for this, so it's read from the internal glass toolkit by reflection. That needs
 * {@code --add-exports javafx.graphics/com.sun.glass.ui=com.example.streamdav} at runtime; without it, or on other
 * platforms, there's no handle and video is drawn by JavaFX instead.
 */
public final class NativeWindow {
    private static final Logger log = LogManager.getLogger(NativeWindow.class);

    /** How mpv should draw into a native window on this platform. */
    public enum Platform { X11, WINDOWS, UNSUPPORTED }

    private NativeWindow() {
    }

    public static Platform platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            return Platform.X11;
        }
        return os.contains("win") ? Platform.WINDOWS : Platform.UNSUPPORTED;
    }

    /** The stage's native handle; call on the JavaFX thread while the stage is showing. */
    public static OptionalLong handle(Stage stage) {
        if (platform() == Platform.UNSUPPORTED || !stage.isShowing()) {
            return OptionalLong.empty();
        }
        // Glass windows don't say which stage they belong to, so tag the stage with a unique title, which glass
        // receives straight away, and look for it.
        String title = stage.getTitle();
        String tag = "streamdav-" + UUID.randomUUID();
        try {
            stage.setTitle(tag);
            Class<?> glassWindow = Class.forName("com.sun.glass.ui.Window");
            Method windows = glassWindow.getMethod("getWindows");
            Method getTitle = glassWindow.getMethod("getTitle");
            Method nativeHandle = glassWindow.getMethod("getNativeWindow");
            for (Object window : (List<?>) windows.invoke(null)) {
                if (tag.equals(getTitle.invoke(window))) {
                    long handle = (long) nativeHandle.invoke(window);
                    return handle == 0 ? OptionalLong.empty() : OptionalLong.of(handle);
                }
            }
            log.warn("Could not find the native window for the stage");
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Typically IllegalAccessException when the --add-exports flag is missing.
            log.warn("Native window handles are unavailable ({}); video will be drawn by JavaFX", e.toString());
        } finally {
            stage.setTitle(title);
        }
        return OptionalLong.empty();
    }
}
