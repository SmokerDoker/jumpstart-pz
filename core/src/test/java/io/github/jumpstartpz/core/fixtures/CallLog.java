package io.github.jumpstartpz.core.fixtures;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records which test mods' advice ran. Inlined advice code calls {@link #add}. */
public final class CallLog {
    private static final List<String> ENTRIES = new CopyOnWriteArrayList<>();

    private CallLog() {
    }

    public static void add(String entry) {
        ENTRIES.add(entry);
    }

    public static List<String> drain() {
        List<String> copy = List.copyOf(ENTRIES);
        ENTRIES.clear();
        return copy;
    }
}
