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

        boolean lua() {
            return "lua".equals(state);
        }
    }

    /**
     * Phase 1, while the game goes on: CONSENT (the player has to agree first, everybody except the
     * admin who started it), GETTING the server's files, ASKING BiteCheck's questions, then READY
     * (prepared, nothing changed yet). Phase 2, paused: APPLYING the change, APPLIED. FAILED: told
     * the server why not.
     */
    enum Phase { CONSENT, GETTING, ASKING, READY, APPLYING, APPLIED, FAILED }

    /** A server update this client takes part in. */
    static final class Update {
        final String action;
        final boolean yours;
        long since;
        final String mod;
        final String sha256;
        final String workshopId;
        final String by;
        final long deadline;
        /** Install or remove: whether the mod has a Java part (a Lua-only mod has no hash). */
        boolean java = true;
        /**
         * Brings only this client in line with the server (sync, when joining): the server runs it
         * already, so the change is made as soon as it is ready, without a commit.
         */
        boolean solo;
        Phase phase = Phase.GETTING;
        boolean downloadRequested;
        long nextLook;
        String status = "";

        Update(String action, boolean yours, String mod, String sha256, String workshopId, String by, long deadline) {
            this.action = action;
            this.yours = yours;
            this.phase = yours ? Phase.GETTING : Phase.CONSENT;
            this.mod = mod;
            this.sha256 = sha256;
            this.workshopId = workshopId;
            this.by = by;
            this.deadline = deadline;
        }
    }

    private final ServerLink link;
    /** This client's Java mods for hello, "id\thash\tstate" lines (see ServerCoordinator.ClientMod). */
    java.util.function.Supplier<String> localMods = () -> null;

    /** In a multiplayer game (with or without Jumpstart on the server): nobody switches mods alone. */
    boolean inMultiplayer() {
        return link.connected();
    }
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
            hello();
        }
    }

    void refresh() {
        if (link.connected()) {
            hello();
        }
    }

    /** Hello with this client's Java mods, so the server can compare them with its own. */
    private void hello() {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("v", "2");
        String mods = localMods.get();
        if (mods != null && update == null) {
            args.put("mods", mods); // not in the middle of a change: the server would compare half of it
        }
        link.send("hello", args);
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
                update = new Update(args.getOrDefault("action", "update"), "1".equals(args.get("yours")),
                        args.getOrDefault("mod", ""), args.getOrDefault("sha", ""),
                        args.getOrDefault("workshop", ""), args.getOrDefault("by", "?"), now + seconds * 1000);
                update.since = now;
                update.java = !"0".equals(args.get("java"));
                return "prepare"; // the game goes on while everybody gets ready
            }
            case "sync" -> {
                long seconds = parse(args.get("seconds"), 180);
                update = new Update(args.getOrDefault("action", "update"), true,
                        args.getOrDefault("mod", ""), args.getOrDefault("sha", ""),
                        args.getOrDefault("workshop", ""), "server", now + seconds * 1000);
                update.since = now;
                update.java = !"0".equals(args.get("java"));
                update.solo = true;
                return "sync";
            }
            case "synced" -> {
                lastNote = "";
                return "synced";
            }
            case "check" -> {
                refresh();
                return null;
            }
            case "commit" -> {
                if (update == null || !update.mod.equals(args.get("mod"))) {
                    return null;
                }
                link.pause(true); // the server stopped its world too
                return "commit";
            }
            case "abort" -> {
                update = null;
                link.pause(false);
                lastNote = args.getOrDefault("message", "");
                refresh();
                return "abort";
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

    /** Admins: install a Steam Workshop item for everybody (the server downloads it first). */
    void requestInstall(long workshopId) {
        if (token != null) {
            link.send("install", Map.of("token", token, "item", Long.toString(workshopId)));
        }
    }

    /** Admins: "update", "off", "on" or "remove" for everybody. */
    void requestChange(String action, String mod) {
        if (token != null) {
            link.send("change", Map.of("token", token, "action", action, "mod", mod));
        }
    }

    /** Phase 1 done: prepared, nothing changed yet. */
    void ready() {
        if (update != null && token != null) {
            update.phase = Phase.READY;
            link.send("ready", Map.of("token", token, "mod", update.mod, "sha", update.sha256));
        }
    }

    /** Phase 2 done: the change is made here too. A sync step is over with it. */
    void applied() {
        if (update != null && token != null) {
            update.phase = Phase.APPLIED;
            Map<String, String> args = new LinkedHashMap<>();
            args.put("token", token);
            args.put("mod", update.mod);
            if (update.solo) {
                args.put("sync", "1");
                update = null;
            }
            link.send("applied", args);
        }
    }

    /** Waiting for the server: prepared, or the change made, or told it why not. */
    boolean answered() {
        return update != null && (update.phase == Phase.READY || update.phase == Phase.APPLYING
                || update.phase == Phase.APPLIED || update.phase == Phase.FAILED);
    }

    void failed(String reason) {
        if (update != null && token != null) {
            update.phase = Phase.FAILED;
            Map<String, String> args = new LinkedHashMap<>();
            args.put("token", token);
            args.put("mod", update.mod);
            args.put("reason", reason);
            if (update.solo) {
                args.put("sync", "1");
            }
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
