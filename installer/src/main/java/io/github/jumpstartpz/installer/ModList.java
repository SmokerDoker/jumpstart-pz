package io.github.jumpstartpz.installer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Zomboid/mods/default.txt, the mod list of the main menu: which mods are on. A line per mod,
 * {@code mod = Id,} (Build 42 may write {@code mod = \Id,}).
 */
final class ModList {
    static final String EMPTY = "VERSION = 1,\n\nmods\n{\n}\n\nmaps\n{\n}\n";

    private ModList() {
    }

    static boolean contains(String list, String id) {
        return line(id).matcher(list).find();
    }

    /** The list with the given mods on, in this order at the top of the mods block; null if it has no mods block. */
    static String enable(String list, List<String> ids) {
        String nl = list.contains("\r\n") ? "\r\n" : "\n";
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            if (!contains(list, id)) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return list;
        }
        var block = Pattern.compile("(?m)^mods\\r?\\n\\{\\r?\\n").matcher(list);
        if (!block.find()) {
            return null;
        }
        StringBuilder lines = new StringBuilder();
        for (String id : missing) {
            lines.append("    mod = ").append(id).append(',').append(nl);
        }
        return list.substring(0, block.end()) + lines + list.substring(block.end());
    }

    /** The list without the mod. */
    static String disable(String list, String id) {
        return line(id).matcher(list).replaceAll("");
    }

    private static Pattern line(String id) {
        return Pattern.compile("(?m)^[ \\t]*mod = \\\\?" + Pattern.quote(id) + ",[ \\t]*(\\r?\\n|$)");
    }
}
