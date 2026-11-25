package io.github.jumpstartpz.mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Follows the ZombieBuddy fork's crash protection: which mod threw, where, and what the fork did
 * (logged, switched it off, refused to load it). Asks only for the newest event number each frame
 * (cheap) and fetches the events when it changed. Starts at 0, so errors of mods loaded with the
 * save (before Jumpstart's first frame) are reported too.
 */
final class GuardFeed {

    /** One caught error. {@code action}: logged, off or refused. */
    record Event(long seq, String modId, String kind, String where, String exception, String action) {

        /** A line "seq TAB modId TAB kind TAB where TAB exception TAB action"; null if it is not one. */
        static Event parse(String line) {
            String[] f = line.split("\t", -1);
            if (f.length < 6) {
                return null;
            }
            try {
                return new Event(Long.parseLong(f[0].strip()), f[1], f[2], f[3], f[4], f[5].strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** The player hears about it: the mod was switched off or not loaded. */
        boolean notable() {
            return action.equals("off") || action.equals("refused");
        }
    }

    private String lastSeq = "0";

    /** New events since the last call; empty if nothing happened or the fork has no guard. */
    List<Event> poll(Actions actions) {
        String newest = actions.guardLastSeq();
        if (newest == null || newest.equals(lastSeq)) {
            return List.of();
        }
        String text = actions.guardEvents(lastSeq);
        List<Event> events = new ArrayList<>();
        long max = parse(lastSeq);
        for (String line : text == null ? new String[0] : text.split("\n")) {
            Event e = line.isBlank() ? null : Event.parse(line);
            if (e != null && e.seq() > parse(lastSeq)) {
                events.add(e);
                max = Math.max(max, e.seq());
            }
        }
        // move on even if a line could not be read, so the same events are not fetched every frame
        lastSeq = Long.toString(Math.max(max, parse(newest)));
        return events;
    }

    /** "count TAB state" from the fork: the number of caught errors, 0 if none. */
    static int count(String status) {
        if (status == null || status.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(status.split("\t", -1)[0].strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** True if the fork switched the mod off after its errors. */
    static boolean offByGuard(String status) {
        return status != null && status.endsWith("\toff-by-guard");
    }

    private static long parse(String seq) {
        try {
            return Long.parseLong(seq.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
