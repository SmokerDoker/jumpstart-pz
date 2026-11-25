package io.github.jumpstartpz.mod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which mods need which: from {@code require=} in each mod.info (for example
 * {@code require=\damnlib,\tsarslib}). Switching a library off breaks the mods that need it;
 * switching a mod on needs its libraries on first. Cycles in the requirements are tolerated.
 *
 * <p>{@code requires}: mod id to the ids it requires, as read; {@code on}: whether a mod is on now.
 */
final class ModDeps {
    /** Always there with Jumpstart: a mod that requires them needs nothing else. */
    static final Set<String> PROVIDED = Set.of("zombiebuddy", "jumpstart");

    private ModDeps() {
    }

    /** "\damnlib" or " damnlib " -> "damnlib"; mod ids are compared without case. */
    static String id(String raw) {
        String s = raw == null ? "" : raw.strip();
        while (s.startsWith("\\") || s.startsWith("/")) {
            s = s.substring(1).strip();
        }
        return s;
    }

    /** The mod.info requirements, cleaned: no blanks, no duplicates, no mods Jumpstart provides. */
    static List<String> cleaned(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String r : raw == null ? List.<String>of() : raw) {
            String id = id(r);
            if (!id.isEmpty() && !PROVIDED.contains(key(id))) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    private static String key(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    /** The id as the mod list has it (the requirement may differ in case), or the requirement as written. */
    private static String known(String id, Map<String, List<String>> requires) {
        for (String m : requires.keySet()) {
            if (m.equalsIgnoreCase(id)) {
                return m;
            }
        }
        return id;
    }

    private static boolean needs(Map<String, List<String>> requires, String mod, String lib) {
        for (String r : requires.getOrDefault(mod, List.of())) {
            if (r.equalsIgnoreCase(lib)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The mods that are on and need {@code lib}, directly or through another mod: in the order to
     * switch them off (a mod before the mods it needs).
     */
    static List<String> dependentsOn(String lib, Map<String, List<String>> requires, Predicate<String> on) {
        List<String> order = new ArrayList<>();
        collectDependents(lib, requires, on, new LinkedHashSet<>(List.of(key(lib))), order);
        return order;
    }

    private static void collectDependents(String lib, Map<String, List<String>> requires, Predicate<String> on,
            Set<String> seen, List<String> order) {
        for (String mod : requires.keySet()) {
            if (seen.contains(key(mod)) || !on.test(mod) || !needs(requires, mod, lib)) {
                continue;
            }
            seen.add(key(mod));
            collectDependents(mod, requires, on, seen, order); // who needs this one goes first
            order.add(mod);
        }
    }

    /** What switching {@code mod} on needs first: installed mods that are off (libraries first), and missing ones. */
    record Needs(List<String> off, List<String> missing) {
        boolean none() {
            return off.isEmpty() && missing.isEmpty();
        }
    }

    static Needs needs(String mod, Map<String, List<String>> requires, Predicate<String> on) {
        List<String> off = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        collectNeeds(mod, requires, on, new LinkedHashSet<>(List.of(key(mod))), off, missing);
        return new Needs(off, missing);
    }

    private static void collectNeeds(String mod, Map<String, List<String>> requires, Predicate<String> on,
            Set<String> seen, List<String> off, List<String> missing) {
        for (String raw : requires.getOrDefault(mod, List.of())) {
            String lib = known(raw, requires);
            if (!seen.add(key(lib))) {
                continue;
            }
            if (!requires.containsKey(lib)) {
                missing.add(lib); // not installed (or not for this game version)
                continue;
            }
            collectNeeds(lib, requires, on, seen, off, missing); // its own libraries first
            if (!on.test(lib)) {
                off.add(lib);
            }
        }
    }

    /**
     * After {@code gone} went off: the libraries they needed that are still on and that no mod that
     * is on needs any more. Whether such a library does something on its own nobody knows: the
     * player is only asked.
     */
    static List<String> unneeded(List<String> gone, Map<String, List<String>> requires, Predicate<String> on) {
        Set<String> goneKeys = new LinkedHashSet<>();
        gone.forEach(g -> goneKeys.add(key(g)));
        Set<String> out = new LinkedHashSet<>();
        for (String g : gone) {
            for (String raw : requires.getOrDefault(g, List.of())) {
                String lib = known(raw, requires);
                if (goneKeys.contains(key(lib)) || !requires.containsKey(lib) || !on.test(lib)) {
                    continue;
                }
                boolean stillNeeded = requires.keySet().stream()
                        .anyMatch(m -> !goneKeys.contains(key(m)) && on.test(m) && needs(requires, m, lib));
                if (!stillNeeded) {
                    out.add(lib);
                }
            }
        }
        return List.copyOf(out);
    }

    /** The installed mods that need {@code mod} directly (for the detail pane). */
    static List<String> neededBy(String mod, Map<String, List<String>> requires) {
        List<String> out = new ArrayList<>();
        for (String m : requires.keySet()) {
            if (needs(requires, m, mod)) {
                out.add(m);
            }
        }
        return out;
    }
}
