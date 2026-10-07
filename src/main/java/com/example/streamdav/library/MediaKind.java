package com.example.streamdav.library;

import java.util.Locale;
import java.util.Set;

/** Classifies files by extension. */
public enum MediaKind {
    VIDEO, AUDIO, OTHER;

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "3gp", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "mts", "ogv", "ts", "webm", "wmv");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "aac", "aif", "aiff", "flac", "m4a", "mp3", "oga", "ogg", "opus", "wav", "wma");
    /**
     * Containers JavaFX Media can play. Everything else is handed to an external player. FLV isn't here: JavaFX no
     * longer decodes the VP6 video it carries.
     */
    private static final Set<String> BUILT_IN_EXTENSIONS = Set.of(
            "aif", "aiff", "m4a", "m4v", "mp3", "mp4", "wav");

    public static MediaKind of(String fileName) {
        String extension = extension(fileName);
        if (VIDEO_EXTENSIONS.contains(extension)) {
            return VIDEO;
        }
        if (AUDIO_EXTENSIONS.contains(extension)) {
            return AUDIO;
        }
        return OTHER;
    }

    public static boolean playsInBuiltInPlayer(String fileName) {
        return BUILT_IN_EXTENSIONS.contains(extension(fileName));
    }

    public boolean isMedia() {
        return this != OTHER;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
