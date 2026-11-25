package io.github.jumpstartpz.installer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ProjectZomboid64.json on Windows: the game's JVM options, so Windows needs no Steam launch
 * options. Jumpstart is the game's only Java agent:
 * {@code -javaagent:<game>/Jumpstart.jar=hotswap=true} as the first JVM option. A ZombieBuddy agent
 * of the player's ({@code -agentlib:zbNative}, {@code -javaagent:...ZombieBuddy.jar}) is taken out,
 * as two agents would load every mod twice; its options go onto Jumpstart's, and the entry is
 * recorded so uninstalling puts it back exactly.
 */
final class LaunchJson {
    private static final Pattern VM_ARGS = Pattern.compile("\"vmArgs\"\\s*:\\s*\\[");
    private static final Pattern ITEM = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ZOMBIE_BUDDY = Pattern.compile("-agentlib:zbNative(?:=(.*))?|-javaagent:.*ZombieBuddy\\.jar(?:=(.*))?");
    private static final Pattern JUMPSTART = Pattern.compile("-javaagent:.*Jumpstart\\.jar(?:=(.*))?");
    static final String HOTSWAP = "hotswap=true";

    /** The changed json and the entries it no longer has (to record for uninstalling). */
    record Change(String json, List<String> removed) {
    }

    /** The vmArgs list: its items (raw, as written) and where it is. */
    private record Args(int start, int end, String indent, String closing, List<String> items) {
    }

    private LaunchJson() {
    }

    private static Args args(String json) {
        Matcher vm = VM_ARGS.matcher(json);
        if (!vm.find()) {
            return null;
        }
        int end = json.indexOf(']', vm.end());
        if (end < 0) {
            return null;
        }
        String body = json.substring(vm.end(), end);
        List<String> items = new ArrayList<>();
        Matcher m = ITEM.matcher(body);
        int firstStart = -1;
        int lastEnd = 0;
        while (m.find()) {
            if (firstStart < 0) {
                firstStart = m.start();
            }
            items.add(m.group(1));
            lastEnd = m.end();
        }
        String nl = json.contains("\r\n") ? "\r\n" : "\n";
        String indent = firstStart < 0 ? nl + "\t\t" : body.substring(0, firstStart);
        String closing = firstStart < 0 ? nl + "\t" : body.substring(lastEnd);
        return new Args(vm.end(), end, indent, closing, items);
    }

    private static String withItems(String json, Args a, List<String> items) {
        StringBuilder b = new StringBuilder(json.substring(0, a.start()));
        for (int i = 0; i < items.size(); i++) {
            b.append(a.indent()).append('"').append(items.get(i)).append('"').append(i < items.size() - 1 ? "," : "");
        }
        b.append(items.isEmpty() ? "" : a.closing()).append(json.substring(a.end()));
        return b.toString();
    }

    /** True if the game starts Jumpstart with hotswap on. */
    static boolean hasJumpstart(String json) {
        Args a = args(json);
        return a != null && a.items().stream().map(JUMPSTART::matcher)
                .anyMatch(m -> m.matches() && options(m.group(1)).contains(HOTSWAP));
    }

    /** True if the game starts a Java mod loader at all: Jumpstart or a ZombieBuddy of its own. */
    static boolean startsLoader(String json) {
        Args a = args(json);
        return a != null && a.items().stream().anyMatch(i -> JUMPSTART.matcher(i).matches() || ZOMBIE_BUDDY.matcher(i).matches());
    }

    /**
     * The json with Jumpstart as the only agent, first in vmArgs; unchanged if it is so already.
     * Null if there is no vmArgs list. An entry Jumpstart before 0.2 added
     * ({@code -javaagent:...ZombieBuddy.jar=hotswap=true}) is not reported as removed: it was never
     * the player's.
     */
    static Change withJumpstart(String json, Path jumpstartJar) {
        Args a = args(json);
        if (a == null) {
            return null;
        }
        Set<String> opts = new LinkedHashSet<>();
        List<String> removed = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String item : a.items()) {
            Matcher zb = ZOMBIE_BUDDY.matcher(item);
            Matcher js = JUMPSTART.matcher(item);
            if (js.matches()) {
                opts.addAll(options(js.group(1)));
            } else if (zb.matches()) {
                List<String> o = options(zb.group(1) != null ? zb.group(1) : zb.group(2));
                opts.addAll(o);
                boolean oursBefore = item.startsWith("-javaagent:") && o.equals(List.of(HOTSWAP));
                if (!oursBefore) {
                    removed.add(withoutHotswap(item)); // hotswap=true was Jumpstart's, added by 0.1
                }
            } else {
                rest.add(item);
            }
        }
        opts.remove(HOTSWAP);
        opts.add(HOTSWAP);
        List<String> items = new ArrayList<>();
        items.add("-javaagent:" + jumpstartJar.toAbsolutePath().toString().replace('\\', '/') + "=" + String.join(",", opts));
        items.addAll(rest);
        String out = items.equals(a.items()) ? json : withItems(json, a, items);
        return new Change(out, removed);
    }

    /** The json without Jumpstart, with the entries it replaced back at the front. */
    static String withoutJumpstart(String json, List<String> restore) {
        Args a = args(json);
        if (a == null) {
            return json;
        }
        List<String> items = new ArrayList<>(restore);
        boolean changed = !restore.isEmpty();
        for (String item : a.items()) {
            Matcher zb = ZOMBIE_BUDDY.matcher(item);
            if (JUMPSTART.matcher(item).matches()) {
                changed = true;
            } else if (zb.matches() && item.contains(HOTSWAP)) {
                // Jumpstart before 0.2: its own agent goes, hotswap=true goes from the player's
                changed = true;
                List<String> o = options(zb.group(1) != null ? zb.group(1) : zb.group(2));
                if (!(item.startsWith("-javaagent:") && o.equals(List.of(HOTSWAP))) && !restore.contains(withoutHotswap(item))) {
                    items.add(withoutHotswap(item));
                }
            } else if (!restore.contains(item)) {
                items.add(item);
            }
        }
        return changed ? withItems(json, a, items) : json;
    }

    private static String withoutHotswap(String item) {
        String s = item.replace("," + HOTSWAP, "").replace("=" + HOTSWAP + ",", "=");
        return s.endsWith("=" + HOTSWAP) ? s.substring(0, s.length() - HOTSWAP.length() - 1) : s;
    }

    private static List<String> options(String group) {
        List<String> out = new ArrayList<>();
        if (group != null) {
            for (String o : group.split(",")) {
                if (!o.isEmpty() && !out.contains(o)) {
                    out.add(o);
                }
            }
        }
        return out;
    }
}
