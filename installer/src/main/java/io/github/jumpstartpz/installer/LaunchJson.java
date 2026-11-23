package io.github.jumpstartpz.installer;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ProjectZomboid64.json on Windows: the game's JVM options, so Windows needs no Steam launch
 * options. Same changes as kit/install-windows.ps1:
 * <ul>
 *   <li>ZombieBuddy through ZombieBuddy's own installer ({@code -agentlib:zbNative}): add {@code hotswap=true} to its options</li>
 *   <li>otherwise: {@code -javaagent:<game>/ZombieBuddy.jar=hotswap=true} as the first JVM option</li>
 * </ul>
 */
final class LaunchJson {
    private static final Pattern ZB_NATIVE_WITH_OPTIONS = Pattern.compile("\"-agentlib:zbNative=([^\"]*)\"");
    private static final String ZB_NATIVE = "\"-agentlib:zbNative\"";
    private static final Pattern VM_ARGS = Pattern.compile("\"vmArgs\"\\s*:\\s*\\[");
    /** The option the installer adds, with the separators around it. */
    private static final Pattern ADDED_AGENT = Pattern.compile("\\s*\"-javaagent:[^\"]*ZombieBuddy\\.jar=hotswap=true\",");

    private LaunchJson() {
    }

    static boolean hasHotswap(String json) {
        return json.contains("hotswap=true");
    }

    /** True if the game starts ZombieBuddy at all (with or without Jumpstart). */
    static boolean startsZombieBuddy(String json) {
        return json.contains("-agentlib:zbNative") || json.contains("ZombieBuddy.jar");
    }

    /** The json with Jumpstart's option; unchanged if it is there already. Null if there is no vmArgs list. */
    static String withHotswap(String json, Path agentJar) {
        if (hasHotswap(json)) {
            return json;
        }
        Matcher m = ZB_NATIVE_WITH_OPTIONS.matcher(json);
        if (m.find()) {
            return m.replaceFirst(Matcher.quoteReplacement("\"-agentlib:zbNative=" + m.group(1) + ",hotswap=true\""));
        }
        if (json.contains(ZB_NATIVE)) {
            return json.replace(ZB_NATIVE, "\"-agentlib:zbNative=hotswap=true\"");
        }
        Matcher vm = VM_ARGS.matcher(json);
        if (!vm.find()) {
            return null;
        }
        String nl = json.contains("\r\n") ? "\r\n" : "\n";
        String agent = "-javaagent:" + agentJar.toAbsolutePath().toString().replace('\\', '/') + "=hotswap=true";
        return json.substring(0, vm.end()) + nl + "\t\t\"" + agent + "\"," + json.substring(vm.end());
    }

    /** The json without Jumpstart's option, as before the installer (ZombieBuddy's own options stay). */
    static String withoutHotswap(String json) {
        String s = ADDED_AGENT.matcher(json).replaceAll("");
        s = s.replace(",hotswap=true", "");
        s = s.replace("\"-agentlib:zbNative=hotswap=true\"", ZB_NATIVE);
        s = s.replace("=hotswap=true\"", "\"");
        return s;
    }
}
