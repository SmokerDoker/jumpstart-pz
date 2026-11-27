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
 *       The game goes on meanwhile (phase 1, nothing changes yet): every player except the admin who
 *       started it must agree, and for an update the client gets exactly that jar (Steam Workshop)
 *       and BiteCheck checks it (the player answers its questions). Then <b>ready</b>, or
 *       <b>failed</b> with a reason.</li>
 *   <li>When everybody is ready, or the deadline passed, clients that are not ready are kicked and
 *       the world stops (a real server pause, {@link Port#pauseWorld}). Phase 2: the server changes
 *       the mod first. If that fails, everybody gets <b>abort</b>: nobody changed anything, the game
 *       goes on as before. Else every ready client gets <b>commit</b>, does the change it prepared and
 *       answers <b>applied</b> (or <b>failed</b>: it is kicked, it no longer matches the server).
 *       Then the world goes on and everybody gets <b>done</b>.</li>
 *   <li><b>install</b> (admins only) with a Steam Workshop item: the server downloads it first; then
 *       the same round as an update with action <i>install</i>: every client gets the item, BiteCheck
 *       checks a Java part, the mod's Java and Lua parts load live; at the end the server loads it
 *       too and puts it into its mod list (and .ini), so players who join later get it.</li>
 *   <li><b>change</b> with action <i>remove</i> (admins only): the mod goes off everywhere (Java
 *       switched off, Lua event handlers stopped) and out of the server's mod list.</li>
 *   <li><b>Joining:</b> hello carries the client's Java mods (id, hash, state). The server compares
 *       them with its own and sends <b>sync</b>, one mod at a time, until the client matches: load the
 *       server's version, switch on, or switch off (also Java mods the server does not run). The
 *       client answers <b>applied</b> or <b>failed</b>; who fails or is too slow is kicked. Who joins
 *       during phase 1 of a round takes part in it; who joins during phase 2 is compared after it.
 *       The client reports its own hashes, so this keeps honest clients in line, it does not catch a
 *       client that lies.</li>
 *   <li><b>Emergency switch-off:</b> {@code change} with action <i>off</i> and {@code urgent=1} (an
 *       admin), or {@link #emergencyOff} (the server itself, when BiteCheck blocks a running mod): the
 *       same two phases, but nobody is asked and phase 1 lasts {@link #URGENT_DEADLINE_MS} at most.
 *       A running round finishes first; the switch-off comes right after it.</li>
 *   <li><b>Snapshot:</b> at the start of a round (not an emergency) the server saves its world and
 *       copies the save ({@link Port#beginSnapshot}) while the game goes on; phase 2 waits for it.</li>
 *   <li><b>Lua checksum:</b> after an install or remove, everybody who did it says hello with the
 *       game's Lua checksum; whose differs from the server's is kicked (the game would turn them away
 *       at their next join anyway).</li>
 *   <li><b>A Workshop item with several mods:</b> the server sends the admin <b>choose</b> with the
 *       mods in it; the admin sends install again with the one to install.</li>
 * </ul>
 *
 * Not thread-safe: the game calls it from its server thread only.
 *
 * @param <C> the game's connection type
 */
public final class ServerCoordinator<C> {
    public static final String MODULE = "Jumpstart";
    public static final long DEFAULT_DEADLINE_MS = 180_000;

    /**
     * The time for phase 1 from a server's setting in seconds (30 to 900; the game goes on
     * meanwhile), {@link #DEFAULT_DEADLINE_MS} when it is missing or not a number.
     */
    public static long deadlineMs(String seconds) {
        try {
            long s = Long.parseLong(seconds == null ? "" : seconds.strip());
            return Math.max(30, Math.min(900, s)) * 1000;
        } catch (NumberFormatException e) {
            return DEFAULT_DEADLINE_MS;
        }
    }

    /**
     * A mod of the server. {@code diskSha256} differs from {@code sha256} when a new version is
     * waiting. A Lua-only mod has no hashes and the state {@link #LUA}.
     */
    public record ModInfo(String id, String workshopId, String sha256, String diskSha256, String state) {
        public boolean lua() {
            return LUA.equals(state);
        }
    }

    public static final String LUA = "lua";

    /**
     * A Workshop item the server downloaded for an install: the mod in it, the hash of its jar and
     * BiteCheck's verdict on the server ("" for a Lua-only mod), or what went wrong.
     */
    public record Fetched(String error, String modId, String sha256, String verdict, boolean java, List<String> choices) {
        public Fetched(String error, String modId, String sha256, String verdict, boolean java) {
            this(error, modId, sha256, verdict, java, List.of());
        }

        public static Fetched failed(String error) {
            return new Fetched(error, "", "", "", false);
        }

        /** The item has several mods and none was named: "id\tname" of each, for the admin to choose. */
        public static Fetched choose(List<String> mods) {
            return new Fetched(null, "", "", "", false, List.copyOf(mods));
        }
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
         * Does the change on the server. For an update or install, {@code adminConfirmed}: the admin
         * who started it loaded the same hash on their own computer, after BiteCheck's questions.
         * Null when done.
         */
        String apply(Action action, String modId, String sha256, boolean adminConfirmed);

        /** Starts downloading a Workshop item on the server; null when it started, else why not. */
        String fetch(String workshopId);

        /**
         * The downloaded item, or null while Steam is still at it. {@code modId}: the mod in it to
         * install, "" for the only one (several: {@link Fetched#choose}).
         */
        Fetched fetched(String workshopId, String modId);

        /**
         * Stops or restarts the world (zombies, time, players) while the network keeps running, like
         * the game's own pause on an empty server; clients are paused with {@code commit}.
         */
        void pauseWorld(boolean paused);

        /** Saves the world and starts copying the save in the background (if snapshots are on). */
        default void beginSnapshot() {
        }

        /** Whether the copy {@link #beginSnapshot} started is still running. */
        default boolean snapshotRunning() {
            return false;
        }

        /** The game's Lua checksum on the server ("" when unknown). */
        default String luaChecksum() {
            return "";
        }
    }

    /** What an admin can do to a Java mod for everybody. */
    public enum Action {
        UPDATE, OFF, ON, INSTALL, REMOVE;

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

    /**
     * A round. Phase 1 ({@code committing} false): {@code ready} are the clients that prepared the
     * change. Phase 2: {@code ready} are those still in it, {@code applied} those that did it.
     */
    private record Update(Action action, String mod, String sha256, String workshopId, boolean java, Object admin, long deadline,
                          Set<Object> expected, Set<Object> ready, Map<Object, String> failed,
                          boolean committing, long commitDeadline, Set<Object> applied, boolean urgent, String reason) {
        Update commit(long until) {
            return new Update(action, mod, sha256, workshopId, java, admin, deadline, expected, ready, new LinkedHashMap<>(),
                    true, until, new LinkedHashSet<>(), urgent, reason);
        }
    }

    /** How long clients have for phase 2 (the change itself, BiteCheck answered already). */
    public static final long COMMIT_DEADLINE_MS = 60_000;

    /** Phase 1 of an emergency switch-off: nobody is asked, switching off needs no download. */
    public static final long URGENT_DEADLINE_MS = 20_000;

    /** How long phase 2 waits for the snapshot after phase 1 is over (the game goes on meanwhile). */
    public static final long SNAPSHOT_WAIT_MS = 120_000;

    /** A Workshop item the server downloads before an install round starts; {@code mod}: the one to install, or "". */
    private record Download(String workshopId, String mod, Object admin, long deadline) {
    }

    public static final long DOWNLOAD_DEADLINE_MS = 10 * 60_000;

    /** An emergency switch-off waiting for the running round; {@code by}: the admin, or null for the server. */
    private record Emergency(String mod, Object by, String reason) {
    }

    private final Port<C> port;
    private final long deadlineMs;
    private final Map<C, String> tokens = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private Update pending;
    private Download download;
    private final java.util.ArrayDeque<Emergency> emergencies = new java.util.ArrayDeque<>();
    /** The running round still has to start its snapshot (from the next tick, the game's own loop). */
    private boolean snapshotToStart;
    private long snapshotUntil;
    /** Clients that did an install or remove: their next hello carries the Lua checksum to compare. */
    private final Set<C> compareLua = new LinkedHashSet<>();

    public ServerCoordinator(Port<C> port, long deadlineMs) {
        this.port = Objects.requireNonNull(port);
        this.deadlineMs = deadlineMs;
    }

    public boolean updateRunning() {
        return pending != null || download != null;
    }

    /** A Java mod as a client reports it in hello: loaded hash and state there ("on", "off", "not loaded"). */
    public record ClientMod(String id, String sha256, String state) {
    }

    /** One step to bring a client in line with the server. */
    public record Sync(Action action, String mod, String sha256, String workshopId) {
    }

    /** "id\thash\tstate" lines, as hello carries them; null when the client sent none (an older one). */
    public static List<ClientMod> parseClientMods(String lines) {
        if (lines == null) {
            return null;
        }
        List<ClientMod> mods = new ArrayList<>();
        for (String line : lines.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length >= 3 && !f[0].isBlank()) {
                mods.add(new ClientMod(f[0], f[1], f[2]));
            }
        }
        return mods;
    }

    /**
     * What a client has to do to match the server's Java mods: the server's version where it is
     * missing, another or not loaded; on where only the client has it off; off where the server has
     * it off or does not run it at all.
     */
    public static List<Sync> differences(List<ModInfo> server, List<ClientMod> client) {
        List<Sync> steps = new ArrayList<>();
        Map<String, ClientMod> byId = new LinkedHashMap<>();
        for (ClientMod m : client) {
            byId.put(m.id(), m);
        }
        Set<String> serverIds = new LinkedHashSet<>();
        for (ModInfo m : server) {
            serverIds.add(m.id());
            if (m.lua()) {
                continue; // the game checks Lua mods itself when a player joins
            }
            ClientMod mine = byId.get(m.id());
            boolean clientOn = mine != null && "on".equals(mine.state());
            if ("off".equals(m.state())) {
                if (clientOn) {
                    steps.add(new Sync(Action.OFF, m.id(), mine.sha256(), nz(m.workshopId())));
                }
            } else if (nz(m.sha256()).isEmpty()) {
                continue; // the server does not know which version it runs: nothing to compare
            } else if (mine == null || !m.sha256().equals(mine.sha256()) || "not loaded".equals(mine.state())) {
                steps.add(new Sync(Action.UPDATE, m.id(), m.sha256(), nz(m.workshopId())));
            } else if (!clientOn) {
                steps.add(new Sync(Action.ON, m.id(), m.sha256(), nz(m.workshopId())));
            }
        }
        for (ClientMod m : client) {
            if (!serverIds.contains(m.id()) && "on".equals(m.state())) {
                steps.add(new Sync(Action.OFF, m.id(), m.sha256(), "")); // Java the server does not run
            }
        }
        return steps;
    }

    /** A client being brought in line: the steps left (the first is running) and its deadline. */
    private static final class Syncing {
        final java.util.ArrayDeque<Sync> steps;
        long deadline;

        Syncing(List<Sync> steps) {
            this.steps = new java.util.ArrayDeque<>(steps);
        }
    }

    private final Map<C, Syncing> syncing = new LinkedHashMap<>();
    /** Clients that joined while a round ran: compared with the server when it is over. */
    private final Map<C, List<ClientMod>> joinedDuringRound = new LinkedHashMap<>();

    public boolean syncing(C c) {
        return syncing.containsKey(c);
    }

    /** A Jumpstart command from a client (already filtered to this module by the channel). */
    public void onCommand(C c, String command, Map<String, String> args, long now) {
        if (command.equals("hello")) {
            welcome(c);
            if (luaDiffers(c, args.get("lua"))) {
                return;
            }
            List<ClientMod> mods = parseClientMods(args.get("mods"));
            if (mods != null && !syncing.containsKey(c)) {
                joined(c, mods, now);
            }
            return;
        }
        String token = tokens.get(c);
        if (token == null || !token.equals(args.get("token"))) {
            return; // not from Jumpstart's Java side of this connection (for example a Lua mod)
        }
        switch (command) {
            case "change" -> {
                Action action = Action.of(args.getOrDefault("action", ""));
                if (action == Action.OFF && "1".equals(args.get("urgent"))) {
                    urgentOff(c, args.getOrDefault("mod", ""), now);
                } else if (action != null) {
                    startChange(c, action, args.getOrDefault("mod", ""), now);
                }
            }
            case "install" -> startInstall(c, args.getOrDefault("item", "").strip(), args.getOrDefault("mod", "").strip(), now);
            case "ready" -> {
                if (pending != null && !pending.committing() && pending.mod().equals(args.get("mod"))
                        && pending.sha256().equals(args.get("sha"))) {
                    pending.ready().add(c);
                    pending.failed().remove(c);
                }
            }
            case "applied" -> {
                if ("1".equals(args.get("sync"))) {
                    synced(c, args.getOrDefault("mod", ""), now);
                } else if (pending != null && pending.committing() && pending.ready().contains(c) && pending.mod().equals(args.get("mod"))) {
                    pending.applied().add(c);
                }
            }
            case "failed" -> {
                if ("1".equals(args.get("sync"))) {
                    Syncing s = syncing.get(c);
                    if (s != null && !s.steps.isEmpty() && s.steps.peek().mod().equals(args.get("mod"))) {
                        syncing.remove(c);
                        port.kick(c, "Jumpstart: " + args.get("mod") + " does not match the server: " + args.getOrDefault("reason", "failed"));
                    }
                } else if (pending != null && pending.mod().equals(args.get("mod")) && !pending.applied().contains(c)) {
                    pending.failed().put(c, args.getOrDefault("reason", "failed"));
                }
            }
            default -> {
                // newer commands are ignored
            }
        }
    }

    /** A client said hello with its Java mods: in line with the server now, or after the running round. */
    private void joined(C c, List<ClientMod> mods, long now) {
        if (pending != null) {
            joinedDuringRound.put(c, mods);
            if (!pending.committing() && !pending.expected().contains(c)) {
                pending.expected().add(c); // phase 1: takes part like everybody else
                sendPrepare(c, pending, now);
            }
            return;
        }
        startSync(c, differences(port.manifest(), mods), now);
    }

    /**
     * After an install or remove: a client whose Lua checksum differs from the server's is kicked.
     * Only once, for the clients that did the change (the game compares everybody else at join).
     */
    private boolean luaDiffers(C c, String lua) {
        if (pending != null || !compareLua.remove(c) || lua == null || lua.isEmpty()) {
            return false;
        }
        String mine = nz(port.luaChecksum());
        if (mine.isEmpty() || mine.equals(lua)) {
            return false;
        }
        port.kick(c, "Jumpstart: the Lua files differ from the server's after the change; please join again");
        return true;
    }

    /**
     * The server switches a running Java mod off for everybody at once (BiteCheck blocked it), after
     * a round that runs now. Nobody is asked.
     */
    public void emergencyOff(String mod, String reason, long now) {
        if (emergencies.stream().anyMatch(e -> e.mod().equals(mod)) || (pending != null && pending.urgent() && pending.mod().equals(mod))) {
            return;
        }
        emergencies.add(new Emergency(mod, null, reason));
        startEmergency(now);
    }

    /** An admin's emergency switch-off: like {@link #emergencyOff}, without the players' consent. */
    private void urgentOff(C admin, String mod, long now) {
        if (!port.isAdmin(admin)) {
            note(admin, "only admins can switch mods in multiplayer");
            return;
        }
        if (pending != null) {
            note(admin, mod + " goes off for everybody right after the change of " + pending.mod());
        }
        emergencies.add(new Emergency(mod, admin, "emergency switch-off by " + port.name(admin)));
        startEmergency(now);
    }

    /** Starts the next emergency switch-off when no round runs. */
    private void startEmergency(long now) {
        while (pending == null && !emergencies.isEmpty()) {
            Emergency e = emergencies.poll();
            ModInfo info = port.manifest().stream().filter(m -> m.id().equals(e.mod())).findFirst().orElse(null);
            if (info == null || info.lua() || "off".equals(info.state())) {
                if (e.by() != null) {
                    note(cast(e.by()), e.mod() + (info == null ? " is not a mod of this server" : info.lua() ? " is a Lua mod" : " is off already"));
                }
                continue;
            }
            System.out.println("[Jumpstart] multiplayer: " + e.mod() + " goes off for everybody now: " + e.reason());
            startRound(e.by(), Action.OFF, e.mod(), nz(info.sha256()), nz(info.workshopId()), true, true, e.reason(), now);
        }
    }

    private void startSync(C c, List<Sync> steps, long now) {
        if (steps.isEmpty()) {
            return;
        }
        syncing.put(c, new Syncing(steps));
        sendSync(c, now);
    }

    private void sendSync(C c, long now) {
        Syncing s = syncing.get(c);
        if (s.steps.isEmpty()) {
            syncing.remove(c);
            port.send(c, "synced", Map.of());
            return;
        }
        Sync step = s.steps.peek();
        s.deadline = now + deadlineMs;
        Map<String, String> args = new LinkedHashMap<>();
        args.put("action", step.action().wire());
        args.put("mod", step.mod());
        args.put("sha", step.sha256());
        args.put("workshop", step.workshopId());
        args.put("java", "1");
        args.put("seconds", String.valueOf(deadlineMs / 1000));
        port.send(c, "sync", args);
    }

    private void synced(C c, String mod, long now) {
        Syncing s = syncing.get(c);
        if (s != null && !s.steps.isEmpty() && s.steps.peek().mod().equals(mod)) {
            s.steps.poll();
            sendSync(c, now);
        }
    }

    /** Clients that are too slow to match the server are kicked. */
    private void followSyncs(long now) {
        List<C> connected = port.connections();
        syncing.keySet().retainAll(connected);
        joinedDuringRound.keySet().retainAll(connected);
        for (Map.Entry<C, Syncing> e : new ArrayList<>(syncing.entrySet())) {
            if (now >= e.getValue().deadline) {
                syncing.remove(e.getKey());
                Sync step = e.getValue().steps.peek();
                port.kick(e.getKey(), "Jumpstart: " + (step == null ? "" : step.mod() + " ") + "not in line with the server in time");
            }
        }
    }

    /**
     * After a round: who joined during it says hello again (check), so it is compared with the server
     * as it is now. Everybody who got done or abort says hello again anyway.
     */
    private void afterRound(Set<Object> answered) {
        for (C c : new ArrayList<>(joinedDuringRound.keySet())) {
            if (!answered.contains(c) && port.connections().contains(c)) {
                port.send(c, "check", Map.of());
            }
        }
        joinedDuringRound.clear();
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

    private void startInstall(C admin, String item, String mod, long now) {
        if (!port.isAdmin(admin)) {
            note(admin, "only admins can install mods in multiplayer");
            return;
        }
        if (busy(admin)) {
            return;
        }
        if (!item.matches("\\d{1,20}")) {
            note(admin, "not a Workshop item: " + item);
            return;
        }
        String problem = port.fetch(item);
        if (problem != null) {
            note(admin, "the server cannot download " + item + ": " + problem);
            return;
        }
        download = new Download(item, mod, admin, now + DOWNLOAD_DEADLINE_MS);
        note(admin, "the server downloads Workshop item " + item + " ...");
    }

    private boolean busy(C admin) {
        if (pending != null) {
            note(admin, "a change of " + pending.mod() + " is running");
            return true;
        }
        if (download != null) {
            note(admin, "the server is downloading Workshop item " + download.workshopId());
            return true;
        }
        return false;
    }

    private void startChange(C admin, Action action, String mod, long now) {
        if (!port.isAdmin(admin)) {
            note(admin, "only admins can switch mods in multiplayer");
            return;
        }
        if (busy(admin)) {
            return;
        }
        if (action == Action.INSTALL) {
            return; // installs start with the Workshop item (command install)
        }
        String sha;
        String workshop;
        boolean java = true;
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
                note(admin, mod + " is not a mod of this server");
                return;
            }
            if (info.lua() && action != Action.REMOVE) {
                note(admin, mod + " is a Lua mod: it can be installed or removed, not switched");
                return;
            }
            sha = nz(info.sha256());
            workshop = nz(info.workshopId());
            java = !info.lua();
        }
        startRound(admin, action, mod, sha, workshop, java, false, "", now);
    }

    private void startRound(Object admin, Action action, String mod, String sha, String workshop, boolean java,
                            boolean urgent, String reason, long now) {
        Set<Object> expected = new LinkedHashSet<>(port.connections());
        long deadline = now + (urgent ? Math.min(deadlineMs, URGENT_DEADLINE_MS) : deadlineMs);
        pending = new Update(action, mod, sha, workshop, java, admin, deadline,
                expected, new LinkedHashSet<>(), new LinkedHashMap<>(), false, 0, new LinkedHashSet<>(), urgent, nz(reason));
        snapshotToStart = !urgent;
        snapshotUntil = deadline + SNAPSHOT_WAIT_MS;
        for (Object c : expected) {
            sendPrepare(cast(c), pending, now);
        }
    }

    private void sendPrepare(C c, Update u, long now) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("action", u.action().wire());
        args.put("mod", u.mod());
        args.put("sha", u.sha256());
        args.put("workshop", u.workshopId());
        args.put("java", u.java() ? "1" : "0");
        args.put("seconds", String.valueOf(Math.max(1, (u.deadline() - now) / 1000)));
        args.put("by", u.admin() == null ? "server" : port.name(cast(u.admin())));
        // the admin who started it is not asked again; nobody is asked in an emergency
        args.put("yours", u.urgent() || c.equals(u.admin()) ? "1" : "0");
        if (u.urgent()) {
            args.put("urgent", "1");
            args.put("reason", u.reason());
        }
        port.send(c, "prepare", args);
    }

    /** Every server tick: follows a download, finishes a round when everybody answered or the deadline passed. */
    public void tick(long now) {
        followSyncs(now);
        startEmergency(now);
        if (download != null && pending == null) {
            followDownload(now);
        }
        if (pending == null) {
            return;
        }
        if (snapshotToStart) {
            snapshotToStart = false;
            try {
                port.beginSnapshot();
            } catch (RuntimeException e) {
                System.out.println("[Jumpstart] multiplayer: no snapshot: " + e);
            }
        }
        List<C> connected = port.connections();
        tokens.keySet().retainAll(connected);
        compareLua.retainAll(connected);
        if (pending.committing()) {
            pending.ready().retainAll(connected);
            boolean allAnswered = pending.ready().stream().allMatch(c -> pending.applied().contains(c) || pending.failed().containsKey(c));
            if (allAnswered || now >= pending.commitDeadline()) {
                finishCommit();
                startEmergency(now); // a switch-off that waited for this round
            }
            return;
        }
        pending.expected().retainAll(connected); // who left does not hold the others up
        boolean allAnswered = true;
        for (Object c : pending.expected()) {
            if (!pending.ready().contains(c) && !pending.failed().containsKey(c)) {
                allAnswered = false;
            }
        }
        if (allAnswered || now >= pending.deadline()) {
            if (port.snapshotRunning() && now < snapshotUntil) {
                return; // the copy of the save is not done yet; the game goes on meanwhile
            }
            commit(now);
        }
    }

    /** End of phase 1: kick who is not ready, stop the world, change the server, then the clients. */
    private void commit(long now) {
        Update u = pending;
        u.ready().retainAll(u.expected());
        for (Object o : u.expected()) {
            if (!u.ready().contains(o)) {
                String why = u.failed().containsKey(o) ? u.failed().get(o) : "not ready in time";
                kick(o, u, why);
            }
        }
        port.pauseWorld(true);
        String problem;
        try {
            problem = port.apply(u.action(), u.mod(), u.sha256(), u.ready().contains(u.admin()));
        } catch (RuntimeException e) {
            problem = String.valueOf(e);
        }
        if (problem != null) {
            // the server could not: nobody changed anything yet, so everybody simply goes on
            pending = null;
            kickedNames.clear();
            port.pauseWorld(false);
            Map<String, String> args = result(u, false, "server: " + problem);
            for (Object o : u.ready()) {
                port.send(cast(o), "abort", args);
            }
            afterRound(u.ready());
            startEmergency(now);
            return;
        }
        pending = u.commit(now + COMMIT_DEADLINE_MS);
        Map<String, String> args = new LinkedHashMap<>();
        args.put("action", u.action().wire());
        args.put("mod", u.mod());
        args.put("sha", u.sha256());
        for (Object o : u.ready()) {
            port.send(cast(o), "commit", args);
        }
        tick(now); // nobody left to wait for: done at once
    }

    /** End of phase 2: who could not follow the server is kicked, the world goes on. */
    private void finishCommit() {
        Update u = pending;
        pending = null;
        for (Object o : u.ready()) {
            if (!u.applied().contains(o)) {
                String why = u.failed().containsKey(o) ? u.failed().get(o) : "not done in time";
                kick(o, u, why);
            }
        }
        port.pauseWorld(false);
        Map<String, String> args = result(u, true, kickedNames.isEmpty() ? "" : "kicked: " + String.join(", ", kickedNames));
        kickedNames.clear();
        if (u.action() == Action.INSTALL || u.action() == Action.REMOVE) {
            for (Object o : u.applied()) {
                compareLua.add(cast(o)); // their hello after done carries the Lua checksum
            }
        }
        for (Object o : u.applied()) {
            port.send(cast(o), "done", args);
        }
        afterRound(u.applied());
    }

    /** Who was kicked during the running round, "name (why)", for the closing message. */
    private final List<String> kickedNames = new ArrayList<>();

    private void kick(Object o, Update u, String why) {
        C c = cast(o);
        kickedNames.add(port.name(c) + " (" + why + ")");
        port.kick(c, "Jumpstart: " + u.mod() + " " + u.action().wire() + ": " + why);
    }

    private static Map<String, String> result(Update u, boolean ok, String message) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("action", u.action().wire());
        args.put("mod", u.mod());
        args.put("ok", ok ? "1" : "0");
        args.put("message", message);
        return args;
    }

    private void followDownload(long now) {
        Download d = download;
        C admin = cast(d.admin());
        Fetched f = port.fetched(d.workshopId(), d.mod());
        if (f == null) {
            if (now >= d.deadline()) {
                download = null;
                note(admin, "the server could not download Workshop item " + d.workshopId() + " in time");
            }
            return;
        }
        download = null;
        if (f.error() != null) {
            note(admin, "Workshop item " + d.workshopId() + ": " + f.error());
            return;
        }
        if (!f.choices().isEmpty()) {
            if (port.connections().contains(admin)) {
                port.send(admin, "choose", Map.of("item", d.workshopId(), "mods", String.join("\n", f.choices())));
            }
            return;
        }
        if ("REFUSE".equals(f.verdict())) {
            note(admin, "BiteCheck blocks " + f.modId() + " on the server");
            return;
        }
        if (port.manifest().stream().anyMatch(m -> m.id().equals(f.modId()))) {
            note(admin, f.modId() + " is already a mod of this server");
            return;
        }
        if (!port.connections().contains(admin)) {
            return; // the admin left while the server downloaded
        }
        startRound(admin, Action.INSTALL, f.modId(), nz(f.sha256()), d.workshopId(), f.java(), false, "", now);
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
