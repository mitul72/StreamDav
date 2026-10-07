package com.example.streamdav.player.mpv;

import com.example.streamdav.player.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Reads mpv's {@code track-list} property into the tracks the player offers. */
final class MpvTracks {
    private MpvTracks() {
    }

    /**
     * @param property looks up an mpv property as a string (null when unavailable)
     */
    static List<Track> read(Function<String, String> property, Locale displayLocale) {
        int count = parseInt(property.apply("track-list/count"));
        List<Track> tracks = new ArrayList<>();
        int audioCount = 0;
        int subtitleCount = 0;
        for (int i = 0; i < count; i++) {
            String prefix = "track-list/" + i + "/";
            Track.Kind kind = switch (String.valueOf(property.apply(prefix + "type"))) {
                case "audio" -> Track.Kind.AUDIO;
                case "sub" -> Track.Kind.SUBTITLE;
                default -> null;
            };
            if (kind == null) {
                continue;
            }
            int number = kind == Track.Kind.AUDIO ? ++audioCount : ++subtitleCount;
            String label = label(property.apply(prefix + "title"), property.apply(prefix + "lang"),
                    property.apply(prefix + "codec"), "yes".equals(property.apply(prefix + "forced")), number,
                    displayLocale);
            tracks.add(new Track(kind, property.apply(prefix + "id"), label,
                    "yes".equals(property.apply(prefix + "selected"))));
        }
        return tracks;
    }

    /** E.g. "Commentary · English · AC3", or "Track 2" when the file says nothing about it. */
    static String label(String title, String languageCode, String codec, boolean forced, int number, Locale displayLocale) {
        List<String> parts = new ArrayList<>();
        if (title != null && !title.isBlank()) {
            parts.add(title.strip());
        }
        String language = Languages.displayName(languageCode, displayLocale);
        if (language != null && (parts.isEmpty() || !parts.getFirst().toLowerCase(Locale.ROOT)
                .contains(language.toLowerCase(Locale.ROOT)))) {
            parts.add(language);
        }
        if (parts.isEmpty()) {
            parts.add("Track " + number);
        }
        if (forced) {
            parts.add("Forced");
        }
        if (codec != null && !codec.isBlank()) {
            parts.add(codec.strip().toUpperCase(Locale.ROOT));
        }
        return String.join(" · ", parts);
    }

    private static int parseInt(String text) {
        try {
            return text == null ? 0 : Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
