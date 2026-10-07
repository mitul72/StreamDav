package com.example.streamdav.ui;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Launches a desktop player (mpv, VLC, IINA) for formats the built-in player can't decode. */
final class ExternalPlayer {
    private ExternalPlayer() {
    }

    /** Finds an installed player and returns it as a command for {@link #launch}. */
    static Optional<String> detect() {
        for (String name : List.of("mpv", "vlc", "iina")) {
            Optional<Path> found = findOnPath(name);
            if (found.isPresent()) {
                return found.map(path -> quote(path.toString()));
            }
        }
        String programFiles = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
        List<String> wellKnown = List.of(
                "/Applications/IINA.app/Contents/MacOS/iina-cli",
                "/Applications/VLC.app/Contents/MacOS/VLC",
                programFiles + "\\VideoLAN\\VLC\\vlc.exe",
                programFiles + "\\mpv\\mpv.exe");
        return wellKnown.stream().filter(ExternalPlayer::isExecutable).findFirst().map(ExternalPlayer::quote);
    }

    /** Runs {@code command} with the URL appended as its last argument. */
    static void launch(String command, URI url) throws IOException {
        List<String> argv = new ArrayList<>(parseCommand(command));
        if (argv.isEmpty()) {
            throw new IOException("No external player is configured.");
        }
        argv.add(url.toString());
        new ProcessBuilder(argv)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /** Splits on whitespace, keeping "double quoted" parts together. Backslashes are literal, for Windows paths. */
    static List<String> parseCommand(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        boolean inToken = false;
        for (char c : command.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                inToken = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (inToken) {
                    tokens.add(token.toString());
                    token.setLength(0);
                    inToken = false;
                }
            } else {
                token.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            tokens.add(token.toString());
        }
        return tokens;
    }

    private static Optional<Path> findOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        String fileName = System.getProperty("os.name", "").startsWith("Windows") ? name + ".exe" : name;
        for (String directory : path.split(File.pathSeparator)) {
            if (!directory.isBlank() && isExecutable(directory + File.separator + fileName)) {
                return Optional.of(Path.of(directory, fileName));
            }
        }
        return Optional.empty();
    }

    private static boolean isExecutable(String file) {
        try {
            Path path = Path.of(file);
            return Files.isRegularFile(path) && Files.isExecutable(path);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static String quote(String path) {
        return path.chars().anyMatch(Character::isWhitespace) ? '"' + path + '"' : path;
    }
}
