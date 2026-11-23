package io.github.jumpstartpz.installer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project Zomboid's Steam launch options on macOS and Linux, where ZombieBuddy starts as
 * {@code -javaagent:ZombieBuddy.jar} (relative to the game's Java folder). Jumpstart needs the
 * option {@code hotswap=true} on it. The player's other options stay, including BiteCheck's wrapper
 * ({@code ".../BiteCheck" --wrap %command% -javaagent:ZombieBuddy.jar --}).
 *
 * <p>Everything up to {@code --} goes to Java, the rest to the game.
 */
final class LaunchOptions {
    static final String AGENT = "-javaagent:ZombieBuddy.jar";
    static final String HOTSWAP = "hotswap=true";
    private static final String COMMAND = "%command%";
    private static final Pattern ZB_AGENT = Pattern.compile("-javaagent:(\\S*ZombieBuddy\\.jar)(?:=(\\S*))?");

    private LaunchOptions() {
    }

    /** True if the options start ZombieBuddy with hotswap on. */
    static boolean hasJumpstart(String options) {
        Matcher m = ZB_AGENT.matcher(options == null ? "" : options);
        return m.find() && m.group(2) != null && Arrays.asList(m.group(2).split(",")).contains(HOTSWAP);
    }

    /** True if the options start ZombieBuddy at all. */
    static boolean startsZombieBuddy(String options) {
        return ZB_AGENT.matcher(options == null ? "" : options).find();
    }

    /** The options with ZombieBuddy and hotswap on; the player's other options stay. */
    static String withJumpstart(String current) {
        String s = current == null ? "" : current.strip();
        Matcher m = ZB_AGENT.matcher(s);
        if (m.find()) {
            List<String> opts = options(m.group(2));
            if (opts.contains(HOTSWAP)) {
                return s;
            }
            opts.add(HOTSWAP);
            return s.substring(0, m.start()) + "-javaagent:" + m.group(1) + "=" + String.join(",", opts) + s.substring(m.end());
        }
        String agent = AGENT + "=" + HOTSWAP;
        if (s.isEmpty()) {
            return agent + " --";
        }
        int cmd = s.indexOf(COMMAND);
        if (cmd >= 0) {
            String head = s.substring(0, cmd + COMMAND.length());
            String tail = s.substring(cmd + COMMAND.length()).strip();
            if (hasSeparator(tail)) {
                return head + " " + agent + " " + tail;
            }
            return head + " " + agent + " --" + (tail.isEmpty() ? "" : " " + tail);
        }
        return hasSeparator(s) ? agent + " " + s : agent + " -- " + s;
    }

    /**
     * The options without Jumpstart: {@code hotswap=true} goes; with {@code keepZombieBuddy} false the
     * whole ZombieBuddy option goes (there is no ZombieBuddy.jar any more after uninstalling).
     */
    static String withoutJumpstart(String current, boolean keepZombieBuddy) {
        String s = current == null ? "" : current.strip();
        Matcher m = ZB_AGENT.matcher(s);
        if (!m.find()) {
            return s;
        }
        if (keepZombieBuddy) {
            List<String> opts = options(m.group(2));
            opts.remove(HOTSWAP);
            String agent = "-javaagent:" + m.group(1) + (opts.isEmpty() ? "" : "=" + String.join(",", opts));
            return s.substring(0, m.start()) + agent + s.substring(m.end());
        }
        String rest = (s.substring(0, m.start()) + " " + s.substring(m.end())).strip().replaceAll("\\s+", " ");
        if (rest.equals("--")) {
            return "";
        }
        if (rest.endsWith(COMMAND + " --")) {
            return rest.substring(0, rest.length() - 3);
        }
        if (rest.startsWith("-- ") && !rest.contains(COMMAND)) {
            return rest.substring(3);
        }
        return rest;
    }

    private static List<String> options(String group) {
        List<String> opts = new ArrayList<>();
        if (group != null) {
            for (String o : group.split(",")) {
                if (!o.isEmpty()) {
                    opts.add(o);
                }
            }
        }
        return opts;
    }

    private static boolean hasSeparator(String s) {
        return Arrays.asList(s.split("\\s+")).contains("--");
    }
}
