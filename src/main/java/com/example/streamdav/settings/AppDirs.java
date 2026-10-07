package com.example.streamdav.settings;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Where the app keeps its data and caches, following each platform's conventions. */
public final class AppDirs {
    private static final String NAME = "StreamDav";

    private AppDirs() {
    }

    /** Data worth keeping, such as the library database. */
    public static Path data() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (os.contains("win")) {
            return env("APPDATA").orElse(home.resolve("AppData/Roaming")).resolve(NAME);
        }
        if (os.contains("mac")) {
            return home.resolve("Library/Application Support").resolve(NAME);
        }
        return env("XDG_DATA_HOME").orElse(home.resolve(".local/share")).resolve(NAME.toLowerCase(Locale.ROOT));
    }

    /** Data that can be fetched again, such as artwork. */
    public static Path cache() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (os.contains("win")) {
            return env("LOCALAPPDATA").orElse(home.resolve("AppData/Local")).resolve(NAME).resolve("Cache");
        }
        if (os.contains("mac")) {
            return home.resolve("Library/Caches").resolve(NAME);
        }
        return env("XDG_CACHE_HOME").orElse(home.resolve(".cache")).resolve(NAME.toLowerCase(Locale.ROOT));
    }

    private static Optional<Path> env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }
}
