package com.example.streamdav.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

/**
 * Where API keys come from, first match wins: the user's own key from settings, the {@code TMDB_API_KEY}
 * environment variable, then a key built into release builds. Source builds have no built-in key.
 */
public final class ApiKeys {
    private static final String RESOURCE = "api-keys.properties";

    private ApiKeys() {
    }

    public static Optional<String> tmdb(Optional<String> fromSettings) {
        return tmdb(fromSettings, System::getenv, builtIn());
    }

    static Optional<String> tmdb(Optional<String> fromSettings, Function<String, String> environment, Properties builtIn) {
        return fromSettings.filter(key -> !key.isBlank())
                .or(() -> Optional.ofNullable(environment.apply("TMDB_API_KEY")).filter(key -> !key.isBlank()))
                .or(() -> Optional.ofNullable(builtIn.getProperty("tmdb")).filter(key -> !key.isBlank()))
                .map(String::strip);
    }

    /** Whether this build came with a TMDB key, so asking the user for one is optional. */
    public static boolean hasBuiltInTmdbKey() {
        return !builtIn().getProperty("tmdb", "").isBlank();
    }

    private static Properties builtIn() {
        Properties properties = new Properties();
        try (InputStream in = ApiKeys.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            // No built-in keys, as in a source build.
        }
        return properties;
    }
}
