package com.example.streamdav.ui;

import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

final class Format {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault());
    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

    private Format() {
    }

    /** "1.4 GB"; empty for unknown sizes. */
    static String size(long bytes) {
        if (bytes < 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < UNITS.length - 1);
        return String.format("%.1f %s", value, UNITS[unit]);
    }

    static String timestamp(Instant instant) {
        return instant == null ? "" : TIMESTAMP.format(instant);
    }

    /** "4:05" or "1:02:03"; "--:--" when unknown. */
    static String duration(Duration duration) {
        if (duration == null || duration.isUnknown() || duration.isIndefinite()) {
            return "--:--";
        }
        long total = (long) Math.max(0, duration.toSeconds());
        long hours = total / 3600;
        long minutes = total % 3600 / 60;
        long seconds = total % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%d:%02d", minutes, seconds);
    }
}
