package com.ademola.esm.common.persistence;

import java.util.Locale;

/** Builds safe SQL LIKE patterns from user input. */
public final class LikePatterns {

    public static final char ESCAPE = '\\';

    private LikePatterns() {}

    /**
     * Case-folded "contains" pattern. User-typed {@code %} and {@code _} are escaped so that searching
     * for "50%" doesn't match everything. This isn't about SQL injection (the value is always a
     * bound parameter); it's about getting correct results.
     */
    public static String containsIgnoringCase(String text) {
        String escaped = text.trim()
                .toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
