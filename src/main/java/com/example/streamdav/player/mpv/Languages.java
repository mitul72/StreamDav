package com.example.streamdav.player.mpv;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;

/** Names for the ISO 639 language codes that video files tag their tracks with ("eng", "fre", "de", ...). */
final class Languages {
    /** Bibliographic ISO 639-2 codes, common in MKV files, that differ from the terminology codes Java knows. */
    private static final Map<String, String> BIBLIOGRAPHIC = Map.ofEntries(
            Map.entry("alb", "sq"), Map.entry("arm", "hy"), Map.entry("baq", "eu"), Map.entry("bur", "my"),
            Map.entry("chi", "zh"), Map.entry("cze", "cs"), Map.entry("dut", "nl"), Map.entry("fre", "fr"),
            Map.entry("geo", "ka"), Map.entry("ger", "de"), Map.entry("gre", "el"), Map.entry("ice", "is"),
            Map.entry("mac", "mk"), Map.entry("may", "ms"), Map.entry("per", "fa"), Map.entry("rum", "ro"),
            Map.entry("slo", "sk"), Map.entry("tib", "bo"), Map.entry("wel", "cy"));
    /** Codes that mean "no particular language". */
    private static final Set<String> UNSPECIFIED = Set.of("und", "mul", "zxx", "mis");
    private static final Map<String, String> TERMINOLOGY = new HashMap<>();

    static {
        for (String code : Locale.getISOLanguages()) {
            try {
                TERMINOLOGY.put(Locale.of(code).getISO3Language(), code);
            } catch (MissingResourceException ignored) {
                // No three-letter equivalent.
            }
        }
    }

    private Languages() {
    }

    /** The language's name in {@code displayLocale}, the code itself if unknown, or null if there's no language. */
    static String displayName(String code, Locale displayLocale) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalised = code.strip().toLowerCase(Locale.ROOT);
        if (UNSPECIFIED.contains(normalised)) {
            return null;
        }
        String twoLetter = normalised.length() == 2 ? normalised
                : BIBLIOGRAPHIC.getOrDefault(normalised, TERMINOLOGY.get(normalised));
        if (twoLetter == null) {
            return code.strip();
        }
        String name = Locale.of(twoLetter).getDisplayLanguage(displayLocale);
        return name.isEmpty() || name.equalsIgnoreCase(twoLetter) ? code.strip() : name;
    }
}
