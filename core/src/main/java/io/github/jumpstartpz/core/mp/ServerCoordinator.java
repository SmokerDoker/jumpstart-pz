package io.github.jumpstartpz.core.mp;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The server's side of Jumpstart in multiplayer, without any game code (see the mod's ServerSide).
 *
 * <ul>
 *   <li><b>hello</b> (client): the server answers <b>welcome</b> with a per-connection token, whether
 *       the player is an admin, and the server's Java mods (id, Workshop item, loaded hash, the hash
 *       of the jar on disk now, state). The token travels only through Jumpstart's Java channel,
 *       which Lua neither sees nor can fake, so every later command must carry it.</li>
 *   <li><b>change</b> (admins only; nobody else switches Java mods in multiplayer): action
 *       <i>update</i> (load or reload the jar the server has on disk now), <i>off</i> or <i>on</i>.
 *       Every connected client gets <b>prepare</b> with the action, the mod, the hash and a deadline.
 *       The game pauses; every player except the admin who started it must agree, and for an update
 *       the client gets exactly that jar (Steam Workshop), BiteCheck checks it (the player answers its
 *       questions) and loads it. Then <b>ready</b>, or <b>failed</b> with a reason.</li>
 *   <li>When everybody is ready, or the deadline passed, clients that are not ready are kicked, the
 *       server loads the same jar itself and everybody gets <b>done</b>.</li>
 * </ul>
 *
 * Not thread-safe: the game calls it from its server thread only.
 *
 * @param <C> the game's connection type
 */
public final class ServerCoordinator<C> {
    public static final String MODULE = "Jumpstart";
    public static final long DEFAULT_DEADLINE_MS = 180_000;

    /** A Java mod of the server. {@code diskSha256} differs from {@code sha256} when a new version is waiting. */
    public record ModInfo(String id, String workshopId, String sha256, String diskSha256, String state) {
    }

    /** The jar on the server's disk for a mod: what clients must load. */
    public record Candidate(String sha256, String workshopId, String verdict) {
    }

    /** What the coordinator needs from the game. */
    public interface Port<C> {
        void send(C connection, String command, Map<String, String> args);

        void kick(C connection, String reason);

        boolean isAdmin(C connection);

        /** Fully connected clients. */
        List<C> connections();

        String name(C connection);

        List<ModInfo> manifest();

        /** The jar on disk for a mod id, or null. */
        Candidate candidate(String modId);

        /**
         * Does the change on the server. For an update, {@code adminConfirmed}: the admin who started
         * it loaded the same hash on their own computer, after BiteCheck's questions. Null when done.
         */
        String apply(Action action, String modId, String sha256, boolean adminConfirmed);
    }

    /** What an admin can do to a Java mod for everybody. */
    public enum Action {
        UPDATE, OFF, ON;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static Action of(String s) {
            for (Action a : values()) {
                if (a.wire().equals(s)) {
                    return a;
                }
            }
            return null;
        }
    }

    private record Update(Action action, String mod, String sha256, String workshopId, Object admin, long deadline,
                          Set<Object> expected, Set<Object> ready, Map<Object, String> failed) {
    }

    private final Port<C> port;
    private final long deadlineMs;
    private final Map<C, String> tokens = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private Update pending;

    public ServerCoordinator(Port<C> port, long deadlineMs) {
        this.port = Objects.requireNonNull(port);
        this.deadlineMs = deadlineMs;
    }

    public boolean updateRunning() {
        return pending != null;
    }

    /** A Jumpstart command from a client (already filtered to this module by the channel). */
    public void onCommand(C c, String command, Map<String, String> args, long now) {
        if (command.equals("hello")) {
            welcome(c);
            return;
        }
        String token = tokens.get(c);
        if (token == null || !token.equals(args.get("token"))) {
            return; // not from Jumpstart's Java side of this connection (for example a Lua mod)
        }
        switch (command) {
            case "change" -> {
                Action action = Action.of(args.getOrDefault("action", ""));
                if (action != null) {
                    startChange(c, action, args.getOrDefault("mod", ""), now);
                }
            }
            case "ready" -> {
                if (pending != null && pending.mod().equals(args.get("mod")) && pending.sha256().equals(args.get("sha"))) {
                    pending.ready().add(c);
                    pending.failed().remove(c);
                }
            }
            case "failed" -> {
                if (pending != null && pending.mod().equals(args.get("mod"))) {
                    pending.failed().put(c, args.getOrDefault("reason", "failed"));
                }
            }
            default -> {
                // newer commands are ignored
            }
        }
    }

    private void welcome(C c) {
        String token = tokens.computeIfAbsent(c, k -> {
            byte[] b = new byte[16];
            random.nextBytes(b);
            return HexFormat.of().formatHex(b);
        });
        StringBuilder mods = new StringBuilder();
        for (ModInfo m : port.manifest()) {
            mods.append(m.id()).append('\t').append(nz(m.workshopId())).append('\t').append(nz(m.sha256())).append('\t')
                    .append(nz(m.diskSha256())).append('\t').append(nz(m.state())).append('\n');
        }
        Map<String, String> args = new LinkedHashMap<>();
        args.put("token", token);
        args.put("admin", port.isAdmin(c) ? "1" : "0");
        args.put("mods", mods.toString());
        args.put("updating", pending == null ? "" : pending.mod());
        port.send(c, "welcome", args);
    }

    private void startChange(C admin, Action action, String mod, long now) {
        if (!port.isAdmin(admin)) {
            note(admin, "only admins can switch Java mods in multiplayer");
            return;
        }
        if (pending != null) {
            note(admin, "a change of " + pending.mod() + " is running");
            return;
        }
        String sha;
        String workshop;
        if (action == Action.UPDATE) {
            Candidate candidate = port.candidate(mod);
            if (candidate == null) {
                note(admin, mod + " has no Java jar on the server");
                return;
            }
            if ("REFUSE".equals(candidate.verdict())) {
                note(admin, "BiteCheck blocks " + mod + " on the server");
                return;
            }
            sha = candidate.sha256();
            workshop = nz(candidate.workshopId());
        } else {
            ModInfo info = port.manifest().stream().filter(m -> m.id().equals(mod)).findFirst().orElse(null);
            if (info == null) {
                note(admin, mod + " is not a Java mod of this server");
                return;
            }
            sha = nz(info.sha256());
            workshop = nz(info.workshopId());
        }
        Set<Object> expected = new LinkedHashSet<>(port.connections());
        pending = new Update(action, mod, sha, workshop, admin, now + deadlineMs,
                expected, new LinkedHashSet<>(), new LinkedHashMap<>());
        for (Object c : expected) {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("action", action.wire());
            args.put("mod", mod);
            args.put("sha", sha);
            args.put("workshop", workshop);
            args.put("seconds", String.valueOf(deadlineMs / 1000));
            args.put("by", port.name(admin));
            args.put("yours", c.equals(admin) ? "1" : "0"); // the admin who started it is not asked again
            port.send(cast(c), "prepare", args);
        }
    }

    /** Every server tick: finishes an update when everybody answered or the deadline passed. */
    public void tick(long now) {
        if (pending == null) {
            return;
        }
        List<C> connected = port.connections();
        pending.expected().retainAll(connected); // who left does not hold the others up
        tokens.keySet().retainAll(connected);
        boolean allAnswered = true;
        for (Object c : pending.expected()) {
            if (!pending.ready().contains(c) && !pending.failed().containsKey(c)) {
                allAnswered = false;
            }
        }
        if (allAnswered || now >= pending.deadline()) {
            finish();
        }
    }

    private void finish() {
        Update u = pending;
        pending = null;
        List<String> kicked = new ArrayList<>();
        for (Object o : u.expected()) {
            if (!u.ready().contains(o)) {
                C c = cast(o);
                String why = u.failed().containsKey(o) ? u.failed().get(o) : "not ready in time";
                kicked.add(port.name(c) + " (" + why + ")");
                port.kick(c, "Jumpstart: " + u.mod() + " " + u.action().wire() + ": " + why);
            }
        }
        String problem = port.apply(u.action(), u.mod(), u.sha256(), u.ready().contains(u.admin()));
        Map<String, String> args = new LinkedHashMap<>();
        args.put("action", u.action().wire());
        args.put("mod", u.mod());
        args.put("ok", problem == null ? "1" : "0");
        args.put("message", problem != null ? "server: " + problem
                : kicked.isEmpty() ? "" : "kicked: " + String.join(", ", kicked));
        for (Object o : u.ready()) {
            port.send(cast(o), "done", args);
        }
    }

    private void note(C c, String text) {
        port.send(c, "note", Map.of("text", text));
    }

    @SuppressWarnings("unchecked")
    private C cast(Object o) {
        return (C) o;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
