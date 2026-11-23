package io.github.jumpstartpz.installer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jumpstart's versions: releases are {@code x.y.z} (tag {@code vx.y.z}), test builds from main are
 * {@code test-YYYYMMDD-<commit>}. Automatic updates install only signed releases that are newer.
 */
final class Versions {
    private static final Pattern RELEASE = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern TEST = Pattern.compile("test-(\\d{8})-.*");

    private Versions() {
    }

    static boolean isRelease(String v) {
        return v != null && RELEASE.matcher(v).matches();
    }

    /** The date of a test build, empty for anything else. */
    static Optional<LocalDate> testDate(String v) {
        Matcher m = TEST.matcher(v == null ? "" : v);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(m.group(1), DateTimeFormatter.BASIC_ISO_DATE));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Release versions only: negative, zero or positive like {@link Comparable}. */
    static int compareReleases(String a, String b) {
        Matcher x = RELEASE.matcher(a);
        Matcher y = RELEASE.matcher(b);
        if (!x.matches() || !y.matches()) {
            throw new IllegalArgumentException("not a release version: " + a + " / " + b);
        }
        for (int i = 1; i <= 3; i++) {
            int c = Long.compare(Long.parseLong(x.group(i)), Long.parseLong(y.group(i)));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /**
     * True if the release should replace what is installed: a newer release; or, over a test build,
     * a release built on that day or later; or anything over an unknown version (installed by an
     * older kit). Never an older or the same release.
     *
     * @param built the day the release was built (from its signed manifest)
     */
    static boolean isUpdate(String release, LocalDate built, Optional<String> installed) {
        if (!isRelease(release)) {
            return false;
        }
        if (installed.isEmpty()) {
            return true;
        }
        String v = installed.get();
        if (isRelease(v)) {
            return compareReleases(release, v) > 0;
        }
        Optional<LocalDate> test = testDate(v);
        return test.isEmpty() || !built.isBefore(test.get());
    }
}
