package com.example.streamdav.metadata;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiKeysTest {

    @Test
    void settingsWinThenTheEnvironmentThenTheBuild() {
        Properties built = new Properties();
        built.setProperty("tmdb", "built");
        Map<String, String> env = Map.of("TMDB_API_KEY", "env");

        assertEquals(Optional.of("mine"), ApiKeys.tmdb(Optional.of(" mine "), env::get, built));
        assertEquals(Optional.of("env"), ApiKeys.tmdb(Optional.of(" "), env::get, built));
        assertEquals(Optional.of("built"), ApiKeys.tmdb(Optional.empty(), name -> null, built));
        assertEquals(Optional.empty(), ApiKeys.tmdb(Optional.empty(), name -> "", new Properties()));
    }
}
