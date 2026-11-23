package io.github.jumpstartpz.installer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Project Zomboid's launch options in Steam's {@code userdata/<account>/config/localconfig.vdf}.
 * Steam reads them from {@code UserLocalConfigStore/Software/Valve/Steam/apps/108600/LaunchOptions};
 * newer clients keep a copy in {@code UserLocalConfigStore/WebStorage/apps/108600}, which is kept
 * equal. Steam rewrites the file when it exits, so it is only changed while Steam is closed.
 *
 * <p>A small KeyValues reader: quoted strings with backslash escapes, braces, {@code //} comments.
 * Everything outside the changed value stays byte for byte.
 */
final class LocalConfigVdf {
    static final String APP_ID = "108600";

    /** A {@code "108600" { ... }} block below an {@code "apps"} block. */
    private record AppBlock(List<String> path, int open, String indent, int valueStart, int valueEnd) {
        boolean main() {
            return path.size() >= 4 && path.get(path.size() - 3).equalsIgnoreCase("Steam")
                    && path.get(path.size() - 4).equalsIgnoreCase("Valve");
        }
    }

    private LocalConfigVdf() {
    }

    /** The launch options Steam uses, empty if Project Zomboid has none (or no block yet). */
    static Optional<String> launchOptions(String vdf) {
        List<AppBlock> blocks = appBlocks(vdf);
        for (AppBlock b : blocks) {
            if (b.main()) {
                return b.valueStart < 0 ? Optional.empty() : Optional.of(unescape(vdf.substring(b.valueStart + 1, b.valueEnd - 1)));
            }
        }
        return Optional.empty();
    }

    /** True if the file has Project Zomboid's block in Steam's app settings (it has been started on this account). */
    static boolean hasGame(String vdf) {
        return appBlocks(vdf).stream().anyMatch(AppBlock::main);
    }

    /** The file with new launch options in every Project Zomboid block that Steam reads them from; empty without the main block. */
    static Optional<String> withLaunchOptions(String vdf, String options) {
        List<AppBlock> blocks = new ArrayList<>(appBlocks(vdf));
        if (blocks.stream().noneMatch(AppBlock::main)) {
            return Optional.empty();
        }
        String value = "\"" + escape(options) + "\"";
        String nl = vdf.contains("\r\n") ? "\r\n" : "\n";
        // from the end, so the offsets of earlier blocks stay valid
        blocks.sort((a, b) -> Integer.compare(b.open, a.open));
        String s = vdf;
        for (AppBlock b : blocks) {
            if (b.valueStart >= 0) {
                s = s.substring(0, b.valueStart) + value + s.substring(b.valueEnd);
            } else if (b.main()) {
                s = s.substring(0, b.open + 1) + nl + b.indent + "\t\"LaunchOptions\"\t\t" + value + s.substring(b.open + 1);
            }
        }
        return Optional.of(s);
    }

    private static List<AppBlock> appBlocks(String vdf) {
        List<AppBlock> result = new ArrayList<>();
        List<String> path = new ArrayList<>();
        List<int[]> launch = new ArrayList<>(); // per open block: {valueStart, valueEnd}, or null
        List<Integer> opens = new ArrayList<>();
        List<String> indents = new ArrayList<>();
        String pendingKey = null;
        int pendingKeyStart = -1;
        int i = 0;
        int n = vdf.length();
        while (i < n) {
            char c = vdf.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < n && vdf.charAt(i + 1) == '/') {
                while (i < n && vdf.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '{') {
                path.add(pendingKey == null ? "" : pendingKey);
                opens.add(i);
                indents.add(indentOf(vdf, pendingKeyStart));
                launch.add(null);
                pendingKey = null;
                i++;
            } else if (c == '}') {
                if (!path.isEmpty()) {
                    int last = path.size() - 1;
                    if (path.size() >= 2 && path.get(last).equals(APP_ID) && path.get(last - 1).equalsIgnoreCase("apps")) {
                        int[] v = launch.get(last);
                        result.add(new AppBlock(List.copyOf(path), opens.get(last), indents.get(last),
                                v == null ? -1 : v[0], v == null ? -1 : v[1]));
                    }
                    path.remove(last);
                    opens.remove(last);
                    indents.remove(last);
                    launch.remove(last);
                }
                pendingKey = null;
                i++;
            } else if (c == '"') {
                int start = i;
                i++;
                while (i < n && vdf.charAt(i) != '"') {
                    i += vdf.charAt(i) == '\\' ? 2 : 1;
                }
                i = Math.min(i + 1, n);
                String token = unescape(vdf.substring(start + 1, Math.max(start + 1, i - 1)));
                if (pendingKey == null) {
                    pendingKey = token;
                    pendingKeyStart = start;
                } else {
                    // a key-value pair in the current block
                    if (pendingKey.equalsIgnoreCase("LaunchOptions") && !launch.isEmpty()) {
                        launch.set(launch.size() - 1, new int[] {start, i});
                    }
                    pendingKey = null;
                }
            } else {
                i++; // unquoted tokens do not occur in localconfig.vdf
            }
        }
        return result;
    }

    private static String indentOf(String s, int keyStart) {
        if (keyStart < 0) {
            return "";
        }
        int lineStart = s.lastIndexOf('\n', keyStart) + 1;
        StringBuilder indent = new StringBuilder();
        for (int i = lineStart; i < keyStart && (s.charAt(i) == '\t' || s.charAt(i) == ' '); i++) {
            indent.append(s.charAt(i));
        }
        return indent.toString();
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> next;
                });
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
