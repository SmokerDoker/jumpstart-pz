package io.github.jumpstartpz.installer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project Zomboid's Steam launch options on macOS and Linux, where Jumpstart starts as
 * {@code -javaagent:Jumpstart.jar=hotswap=true} (relative to the game's Java folder). A ZombieBuddy
 * agent of the player's ({@code -javaagent:ZombieBuddy.jar}) becomes Jumpstart's and keeps its
 * options: Jumpstart has ZombieBuddy built in, and two agents would load every mod twice. The
 * player's other options stay, including BiteCheck's wrapper
 * ({@code ".../BiteCheck" --wrap %command% -javaagent:Jumpstart.jar=hotswap=true --}).
 *
 * <p>Everything up to {@code --} goes to Java, the rest to the game.
 */
final class LaunchOptions {
    static final String AGENT = "-javaagent:Jumpstart.jar";
    static final String HOTSWAP = "hotswap=true";
    private static final String COMMAND = "%command%";
    private static final Pattern ZB_AGENT = Pattern.compile("-javaagent:(\\S*ZombieBuddy\\.jar)(?:=(\\S*))?");
    private static final Pattern JS_AGENT = Pattern.compile("-javaagent:(\\S*Jumpstart\\.jar)(?:=(\\S*))?");
    /** Windows: ZombieBuddy's own installer starts it as a native agent. */
    private static final Pattern ZB_NATIVE = Pattern.compile("-agentlib:zbNative(?:=\\S*)?");

    private LaunchOptions() {
    }

    /** True if the options start Jumpstart with hotswap on. */
    static boolean hasJumpstart(String options) {
        Matcher m = JS_AGENT.matcher(options == null ? "" : options);
        return m.find() && options(m.group(2)).contains(HOTSWAP);
    }

    /** True if the options start Jumpstart.jar (with or without hotswap). */
    static boolean startsJumpstart(String options) {
        return JS_AGENT.matcher(options == null ? "" : options).find();
    }

    /** True if the options start a Java mod loader: Jumpstart or a ZombieBuddy of the player's. */
    static boolean startsLoader(String options) {
        String s = options == null ? "" : options;
        return JS_AGENT.matcher(s).find() || startsZombieBuddy(s);
    }

    /** True if the options start a ZombieBuddy of the player's (which Jumpstart replaces). */
    static boolean startsZombieBuddy(String options) {
        String s = options == null ? "" : options;
        return ZB_AGENT.matcher(s).find() || ZB_NATIVE.matcher(s).find();
    }

    /** The options with Jumpstart, hotswap on, instead of a ZombieBuddy agent; the player's other options stay. */
    static String withJumpstart(String current) {
        String s = current == null ? "" : current.strip();
        List<String> opts = new ArrayList<>();
        Matcher js = JS_AGENT.matcher(s);
        if (js.find()) {
            opts.addAll(options(js.group(2)));
            Matcher zb = ZB_AGENT.matcher(s);
            while (zb.find()) {
                addAll(opts, options(zb.group(2)));
            }
            s = removeAll(s, ZB_AGENT);
            js = JS_AGENT.matcher(s);
            js.find();
            if (!opts.contains(HOTSWAP)) {
                opts.add(HOTSWAP);
            }
            return s.substring(0, js.start()) + AGENT + "=" + String.join(",", opts) + s.substring(js.end());
        }
        Matcher zb = ZB_AGENT.matcher(s);
        if (zb.find()) {
            opts.addAll(options(zb.group(2)));
            opts.remove(HOTSWAP);
            opts.add(HOTSWAP);
            String tail = s.substring(zb.end());
            String rest = ZB_AGENT.matcher(tail).find() ? " " + removeAll(tail, ZB_AGENT) : tail;
            return s.substring(0, zb.start()) + AGENT + "=" + String.join(",", opts) + rest;
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
     * The options without Jumpstart. With {@code keepZombieBuddy} (the player had a ZombieBuddy.jar
     * before) Jumpstart's agent becomes {@code -javaagent:ZombieBuddy.jar} again, with its options
     * but without hotswap=true; otherwise it goes.
     */
    static String withoutJumpstart(String current, boolean keepZombieBuddy) {
        String s = current == null ? "" : current.strip();
        Matcher m = JS_AGENT.matcher(s);
        if (!m.find()) {
            // Jumpstart before 0.2: ZombieBuddy's agent with hotswap=true
            m = ZB_AGENT.matcher(s);
            if (!m.find() || !options(m.group(2)).contains(HOTSWAP)) {
                return s;
            }
        }
        if (keepZombieBuddy) {
            List<String> opts = options(m.group(2));
            opts.remove(HOTSWAP);
            String agent = "-javaagent:ZombieBuddy.jar" + (opts.isEmpty() ? "" : "=" + String.join(",", opts));
            return s.substring(0, m.start()) + agent + s.substring(m.end());
        }
        return tidy((s.substring(0, m.start()) + " " + s.substring(m.end())).strip().replaceAll("\\s+", " "));
    }

    /**
     * Windows: the Steam options without a ZombieBuddy agent of the player's (BiteCheck's Steam
     * setup adds {@code -agentlib:zbNative}); Jumpstart starts from ProjectZomboid64.json there.
     */
    static String withoutZombieBuddy(String current) {
        String s = current == null ? "" : current.strip();
        String out = removeAll(removeAll(s, ZB_AGENT), ZB_NATIVE);
        return out.equals(s) ? s : tidy(out);
    }

    /**
     * The game's command line as Steam hands it to Jumpstart's wrapper, for this start. Once
     * Jumpstart.jar replaced ZombieBuddy.jar, a ZombieBuddy agent in the launch options (set before
     * Jumpstart 0.2, or by BiteCheck) would not find its jar, and Steam's options cannot change while
     * Steam runs. macOS and Linux: it becomes Jumpstart's agent; Windows (ProjectZomboid64.json
     * starts Jumpstart there): it goes.
     */
    static List<String> forJumpstart(List<String> command, boolean windows) {
        List<String> out = new ArrayList<>();
        boolean hasJumpstart = windows || command.stream().anyMatch(a -> JS_AGENT.matcher(a).matches());
        for (String arg : command) {
            Matcher zb = ZB_AGENT.matcher(arg);
            if (ZB_NATIVE.matcher(arg).matches() || (zb.matches() && hasJumpstart)) {
                continue;
            }
            if (zb.matches()) {
                List<String> opts = options(zb.group(2));
                opts.remove(HOTSWAP);
                opts.add(HOTSWAP);
                out.add(AGENT + "=" + String.join(",", opts));
                hasJumpstart = true;
                continue;
            }
            out.add(arg);
        }
        return out;
    }

    /**
     * Jumpstart's own wrapper at the start of the options ({@code "<Jumpstart app>" --wrap}), which
     * updates Jumpstart before every game start: the app, or a Java plus {@code -jar JumpstartSetup.jar}.
     */
    private static final Pattern OUR_WRAPPER = Pattern.compile(
            "^(?:\"[^\"]*\"\\s+-jar\\s+)?\"(?:[^\"]*[/\\\\])?[^\"/\\\\]*[Jj]umpstart[^\"/\\\\]*\"\\s+--wrap(?:\\s+|$)");

    static boolean hasWrapper(String options) {
        return OUR_WRAPPER.matcher(options == null ? "" : options.strip()).find();
    }

    /**
     * The options with Jumpstart's wrapper in front, before any other wrapper (BiteCheck's too):
     * Jumpstart updates first, then BiteCheck checks what will load.
     *
     * @param wrapper e.g. {@code "C:\...\Jumpstart.exe" --wrap}
     */
    static String withWrapper(String current, String wrapper) {
        String s = withoutWrapper(current);
        if (s.contains(COMMAND)) {
            return wrapper + " " + s;
        }
        return wrapper + " " + COMMAND + (s.isEmpty() ? "" : " " + s);
    }

    /** The options without Jumpstart's wrapper. */
    static String withoutWrapper(String current) {
        String s = current == null ? "" : current.strip();
        s = OUR_WRAPPER.matcher(s).replaceFirst("").strip();
        if (s.equals(COMMAND)) {
            return "";
        }
        if (s.startsWith(COMMAND + " ")) {
            // without any wrapper left, Steam appends options to the command anyway
            return s.substring(COMMAND.length() + 1).strip();
        }
        return s;
    }

    /** Options with an agent taken out: no lone {@code --} left behind. */
    private static String tidy(String rest) {
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

    private static String removeAll(String s, Pattern agent) {
        return agent.matcher(s).replaceAll("").strip().replaceAll("\\s+", " ");
    }

    private static void addAll(List<String> opts, List<String> more) {
        for (String o : more) {
            if (!opts.contains(o)) {
                opts.add(o);
            }
        }
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
