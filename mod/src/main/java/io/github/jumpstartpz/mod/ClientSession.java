package io.github.jumpstartpz.mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the client knows about the multiplayer server it is on: the token for Jumpstart's channel,
 * whether the player is an admin, the server's Java mods, and a running server update.
 * Used on the main thread only (SecureScreen hands commands over).
 */
final class ClientSession {
    /** A Java mod of the server: loaded hash, hash on the server's disk, state there. */
    record ServerMod(String id, String workshopId, String sha256, String diskSha256, String state) {
        boolean updateWaiting() {
            return !diskSha256.isEmpty() && !diskSha256.equals(sha256);
        }
    }

    enum Phase { GETTING, ASKING, SENT }

    /** A server update this client takes part in. */
    static final class Update {
        final String mod;
        final String sha256;
        final String workshopId;
        final String by;
        final long deadline;
        Phase phase = Phase.GETTING;
        boolean downloadRequested;
        long nextLook;
        String status = "";

        Update(String mod, String sha256, String workshopId, String by, long deadline) {
            this.mod = mod;
            this.sha256 = sha256;
            this.workshopId = workshopId;
            this.by = by;
            this.deadline = deadline;
        }
    }

    private final ServerLink link;
    private boolean helloSent;
    String token;
    boolean admin;
    List<ServerMod> serverMods = List.of();
    Update update;
    String lastNote = "";

    ClientSession(ServerLink link) {
        this.link = link;
    }

    boolean connected() {
        return token != null;
    }

    /** Every frame: say hello once per game, forget everything when the game ends. */
    void tick() {
        if (!link.connected()) {
            if (helloSent || token != null) {
                helloSent = false;
                token = null;
                serverMods = List.of();
                if (update != null) {
                    update = null;
                    link.pause(false);
                }
            }
            return;
        }
        if (!helloSent) {
            helloSent = true;
            link.send("hello", Map.of("v", "1"));
        }
    }

    void refresh() {
        if (link.connected()) {
            link.send("hello", Map.of("v", "1"));
        }
    }

    /** A command from the server. Returns what the screen should do: "prepare", "done" or null. */
    String onCommand(String command, Map<String, String> args, long now) {
        switch (command) {
            case "welcome" -> {
                token = args.get("token");
                admin = "1".equals(args.get("admin"));
                List<ServerMod> mods = new ArrayList<>();
                for (String line : args.getOrDefault("mods", "").split("\n")) {
                    String[] f = line.split("\t", -1);
                    if (f.length >= 5 && !f[0].isEmpty()) {
                        mods.add(new ServerMod(f[0], f[1], f[2], f[3], f[4]));
                    }
                }
                serverMods = List.copyOf(mods);
                return null;
            }
            case "prepare" -> {
                long seconds = parse(args.get("seconds"), 180);
                update = new Update(args.getOrDefault("mod", ""), args.getOrDefault("sha", ""),
                        args.getOrDefault("workshop", ""), args.getOrDefault("by", "?"), now + seconds * 1000);
                link.pause(true);
                return "prepare";
            }
            case "done" -> {
                update = null;
                link.pause(false);
                lastNote = "1".equals(args.get("ok")) ? args.getOrDefault("message", "") : args.getOrDefault("message", "failed");
                refresh();
                return "done";
            }
            case "note" -> {
                lastNote = args.getOrDefault("text", "");
                return "note";
            }
            default -> {
                return null;
            }
        }
    }

    void requestUpdate(String mod) {
        if (token != null) {
            link.send("update", Map.of("token", token, "mod", mod));
        }
    }

    void ready() {
        if (update != null && token != null) {
            update.phase = Phase.SENT;
            link.send("ready", Map.of("token", token, "mod", update.mod, "sha", update.sha256));
        }
    }

    void failed(String reason) {
        if (update != null && token != null) {
            update.phase = Phase.SENT;
            Map<String, String> args = new LinkedHashMap<>();
            args.put("token", token);
            args.put("mod", update.mod);
            args.put("reason", reason);
            link.send("failed", args);
        }
    }

    private static long parse(String s, long fallback) {
        try {
            return s == null ? fallback : Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
