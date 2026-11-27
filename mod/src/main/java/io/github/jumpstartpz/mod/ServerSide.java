package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.launcher.UsageSharing;
import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.gate.BiteCheckGate;
import io.github.jumpstartpz.core.mp.ServerCoordinator;
import io.github.jumpstartpz.mod.net.Net;

/** Jumpstart on a server (dedicated or hosted): the game side of {@link ServerCoordinator}. */
final class ServerSide implements ServerCoordinator.Port<Object> {
    private final GameActions actions;
    private final ServerCoordinator<Object> coordinator;
    /** The hash each Java mod had when the server loaded it (or Jumpstart updated it). */
    private final Map<String, String> loadedSha = new java.util.concurrent.ConcurrentHashMap<>();
    /** Mods BiteCheck blocks now (from the index watcher's thread), for the server thread to switch off. */
    private final java.util.Queue<String[]> blocked = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** How often the server fetches BiteCheck's index again to find running mods it blocks now. */
    static final long INDEX_WATCH_MS = 15 * 60_000;
    /** Workshop items being downloaded for an install, and the mod each brought. */
    private final Map<String, Long> downloading = new HashMap<>();
    private final Map<String, Downloaded> downloaded = new HashMap<>();

    private record Downloaded(String item, String folder, Actions.FolderMod mod) {
    }

    private ServerSide(GameActions actions) {
        this.actions = actions;
        this.coordinator = new ServerCoordinator<>(this, ServerCoordinator.deadlineMs(actions.serverCountdown()));
        for (ModEntry mod : clientJars()) {
            loadedSha.put(mod.id(), hash(mod.jar()));
        }
    }

    /** The server's active mods with a Java part for the clients (client-only jars included). */
    private List<ModEntry> clientJars() {
        List<ModEntry> result = new ArrayList<>();
        try {
            List<?> ids = (List<?>) Class.forName("zombie.network.GameServer").getField("ServerMods").get(null);
            for (Object id : List.copyOf(ids)) {
                String modId = String.valueOf(id).replace("\\", "");
                if (modId.equals("ZombieBuddy") || modId.equals("Jumpstart")) {
                    continue;
                }
                Actions.FolderMod mod = actions.clientModById(modId);
                if (mod != null && mod.java() != null) {
                    result.add(mod.java());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot list the server's mods: " + e);
        }
        return result;
    }

    static void start(GameActions actions) {
        dropOldModEntry();
        ServerSide side = new ServerSide(actions);
        Net.onServer((who, args) -> side.coordinator.onCommand(connectionOf(who[0]), (String) who[1], args, System.currentTimeMillis()),
                side::tick);
        side.watchIndex();
        System.out.println("[Jumpstart] multiplayer: server side ready; Java mods for the clients: " + side.loadedSha.keySet());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!luaChecked) {
            luaChecked = true;
            LuaChecksum.selfTest();
            ScriptLive.checksumSelfTest();
        }
        for (String[] b; (b = blocked.poll()) != null; ) {
            if (actions.stateOf(b[0]).equals("on")) {
                coordinator.emergencyOff(b[0], b[1], now);
            }
        }
        coordinator.tick(now);
    }

    /**
     * Every {@link #INDEX_WATCH_MS}: BiteCheck's index again; a running Java mod it blocks now (its
     * key was revoked, it was found to be malware) goes off for everybody at once. Flagged ones are
     * only written to the log: an admin decides.
     */
    private void watchIndex() {
        Thread watcher = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(INDEX_WATCH_MS);
                } catch (InterruptedException e) {
                    return;
                }
                actions.refreshIndexQuietly();
                for (Map.Entry<String, String> e : Map.copyOf(loadedSha).entrySet()) {
                    if (e.getValue().isEmpty()) {
                        continue;
                    }
                    io.github.bitecheckpz.core.Verifier.Status status = actions.indexStatus(e.getValue());
                    if (status == io.github.bitecheckpz.core.Verifier.Status.BLOCKED || status == io.github.bitecheckpz.core.Verifier.Status.INVALID) {
                        blocked.add(new String[] {e.getKey(), "BiteCheck blocks " + e.getKey() + " (" + status.name().toLowerCase(java.util.Locale.ROOT) + ")"});
                    } else if (status == io.github.bitecheckpz.core.Verifier.Status.FLAGGED) {
                        System.out.println("[Jumpstart] multiplayer: BiteCheck flags " + e.getKey() + "; an admin can switch it off for everybody (X in the Server tab)");
                    }
                }
            }
        }, "Jumpstart index watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    @Override
    public void beginSnapshot() {
        actions.beginServerSnapshot();
    }

    @Override
    public boolean snapshotRunning() {
        return actions.serverSnapshotRunning();
    }

    @Override
    public String luaChecksum() {
        return luaChecked && LuaChecksum.trusted() ? LuaChecksum.current() : "";
    }

    @Override
    public String scriptChecksum() {
        return luaChecked && ScriptLive.checksumTrusted() ? ScriptLive.currentChecksum() : "";
    }

    /** An install: the ids the server's world dictionary gave the mod's new items, for the clients. */
    @Override
    public Map<String, String> commitData(ServerCoordinator.Action action, String mod) {
        if (action != ServerCoordinator.Action.INSTALL || ScriptLive.lastIds().isEmpty()) {
            return Map.of();
        }
        return Map.of("items", ScriptLive.encode(ScriptLive.lastIds()));
    }

    /** The Lua checksum self test ran (on the first tick: the game has worked out its own by then). */
    private boolean luaChecked;

    private static Object connectionOf(Object player) {
        try {
            return Class.forName("zombie.network.GameServer").getMethod("getConnectionFromPlayer",
                    Class.forName("zombie.characters.IsoPlayer")).invoke(null, player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void send(Object connection, String command, Map<String, String> args) {
        try {
            Net.sendToClient(connection, command, args);
        } catch (ReflectiveOperationException e) {
            System.out.println("[Jumpstart] cannot send " + command + ": " + e);
        }
    }

    @Override
    public void pauseWorld(boolean paused) {
        Net.pauseWorld(paused);
    }

    @Override
    public void kick(Object connection, String reason) {
        try {
            connection.getClass().getMethod("forceDisconnect", String.class).invoke(connection, reason);
        } catch (ReflectiveOperationException e) {
            System.out.println("[Jumpstart] cannot kick: " + e);
        }
    }

    @Override
    public boolean isAdmin(Object connection) {
        try {
            Object[] players = (Object[]) connection.getClass().getField("players").get(connection);
            for (Object p : players) {
                if (p != null && (Boolean) p.getClass().getMethod("isAccessLevel", String.class).invoke(p, "admin")) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // not an admin then
        }
        return false;
    }

    @Override
    public List<Object> connections() {
        List<Object> result = new ArrayList<>();
        try {
            Object engine = Class.forName("zombie.network.GameServer").getField("udpEngine").get(null);
            List<?> all = (List<?>) engine.getClass().getField("connections").get(engine);
            Method full = Class.forName("zombie.core.raknet.UdpConnection").getMethod("isFullyConnected");
            for (Object c : List.copyOf(all)) {
                if ((Boolean) full.invoke(c)) {
                    result.add(c);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot list connections: " + e);
        }
        return result;
    }

    @Override
    public String name(Object connection) {
        try {
            return String.valueOf(connection.getClass().getMethod("getUserName").invoke(connection));
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    @Override
    public List<ServerCoordinator.ModInfo> manifest() {
        List<ServerCoordinator.ModInfo> mods = new ArrayList<>();
        List<String> java = new ArrayList<>();
        for (ModEntry mod : clientJars()) {
            String state = actions.stateOf(mod.id());
            java.add(mod.id());
            mods.add(new ServerCoordinator.ModInfo(mod.id(), UsageSharing.Jar.workshopIdOf(mod.jar()),
                    loadedSha.getOrDefault(mod.id(), ""), hash(mod.jar()), state.equals("not loaded") ? "client only" : state));
        }
        for (String id : serverModIds()) {
            if (!java.contains(id)) {
                String dir = actions.modDirOf(id);
                long item = dir == null ? 0 : GameActions.workshopIdOf(dir);
                mods.add(new ServerCoordinator.ModInfo(id, item > 0 ? Long.toString(item) : "", "", "", ServerCoordinator.LUA));
            }
        }
        return mods;
    }

    /** The server's mod list (GameServer.ServerMods) without ZombieBuddy and Jumpstart. */
    private static List<String> serverModIds() {
        List<String> ids = new ArrayList<>();
        try {
            for (Object id : List.copyOf((List<?>) Class.forName("zombie.network.GameServer").getField("ServerMods").get(null))) {
                String modId = String.valueOf(id).replace("\\", "");
                if (!modId.equals("ZombieBuddy") && !modId.equals("Jumpstart") && !ids.contains(modId)) {
                    ids.add(modId);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot list the server's mods: " + e);
        }
        return ids;
    }

    // --- install from the Workshop ------------------------------------------------------------------

    @Override
    public String fetch(String workshopId) {
        Workshop ws = actions.workshop();
        String unavailable = ws.unavailableReason();
        if (unavailable != null) {
            return unavailable;
        }
        long item = Long.parseLong(workshopId);
        long state = ws.state(item);
        boolean ready = (state & Workshop.INSTALLED) != 0 && (state & Workshop.NEEDS_UPDATE) == 0;
        if (!ready && !ws.download(item)) {
            return "Steam did not start the download";
        }
        downloading.put(workshopId, System.currentTimeMillis());
        System.out.println("[Jumpstart] multiplayer: downloading Workshop item " + workshopId);
        return null;
    }

    @Override
    public ServerCoordinator.Fetched fetched(String workshopId, String wanted) {
        if (!downloading.containsKey(workshopId)) {
            return ServerCoordinator.Fetched.failed("not downloading");
        }
        Workshop ws = actions.workshop();
        long item = Long.parseLong(workshopId);
        long state = ws.state(item);
        boolean ready = (state & Workshop.INSTALLED) != 0
                && (state & (Workshop.DOWNLOADING | Workshop.DOWNLOAD_PENDING | Workshop.NEEDS_UPDATE)) == 0;
        String folder = ready ? ws.installFolder(item) : null;
        if (folder == null) {
            return null;
        }
        downloading.remove(workshopId);
        List<Actions.FolderMod> found = actions.modsInFolder(folder);
        if (found.isEmpty()) {
            return ServerCoordinator.Fetched.failed("no mod for this game version in it");
        }
        Actions.FolderMod mod;
        if (wanted != null && !wanted.isEmpty()) {
            mod = found.stream().filter(m -> m.id().equals(wanted)).findFirst().orElse(null);
            if (mod == null) {
                return ServerCoordinator.Fetched.failed("no mod " + wanted + " in it");
            }
        } else if (found.size() > 1) {
            // variants (60/70/80 %) or a mod with its add-ons: the admin chooses
            List<String> choices = new ArrayList<>();
            for (Actions.FolderMod m : found) {
                choices.add(m.id() + "\t" + m.name().replace('\t', ' ').replace('\n', ' ') + (m.java() != null ? " (Java)" : ""));
            }
            return ServerCoordinator.Fetched.choose(choices);
        } else {
            mod = found.get(0);
        }
        downloaded.put(mod.id(), new Downloaded(workshopId, folder, mod));
        if (mod.java() == null) {
            return new ServerCoordinator.Fetched(null, mod.id(), "", "", false);
        }
        try {
            BiteCheckGate.Check check = actions.check(mod.java().jar());
            return new ServerCoordinator.Fetched(null, mod.id(), check.sha256(), check.verdict().name(), true);
        } catch (IOException e) {
            return ServerCoordinator.Fetched.failed("cannot check " + mod.java().jar() + ": " + e.getMessage());
        }
    }

    private String install(String modId, String sha256, boolean adminConfirmed) {
        Downloaded d = downloaded.remove(modId);
        if (d == null) {
            return "nothing downloaded for " + modId;
        }
        String dir = actions.modDirOf(modId); // modsInFolder remembered the folder of the download
        if (dir != null) {
            actions.registerModDir(modId, dir);
        }
        addWorkshopFolder(d.folder());
        if (d.mod().java() != null) {
            BiteCheckGate.Consent consent = adminConfirmed ? BiteCheckGate.Consent.CONFIRMED_TWICE : BiteCheckGate.Consent.NONE;
            LiveInstaller.ConsentSource answer = check -> check.sha256().equals(sha256) ? consent : null;
            LiveInstaller.Outcome outcome = actions.install(d.mod().java(), answer);
            if (outcome.kind() != LiveInstaller.Kind.DONE) {
                return outcome.kind() + " " + outcome.message();
            }
            loadedSha.put(modId, sha256);
        }
        LuaLive.Result lua = actions.loadLuaForServer(modId);
        System.out.println("[Jumpstart] multiplayer: " + modId + " installed on the server; Lua " + lua);
        String problem = changeServerLists(modId, d.item(), true);
        actions.recomputeLuaChecksum("installing " + modId); // so players who join later match the server
        return problem;
    }

    private String remove(String modId) {
        String dir = actions.modDirOf(modId);
        long item = dir == null ? 0 : GameActions.workshopIdOf(dir);
        if (actions.stateOf(modId).equals("on")) {
            String r = actions.off(modId);
            if (!r.startsWith("OK")) {
                return r;
            }
        }
        int stopped = actions.unloadLuaForServer(modId);
        loadedSha.remove(modId);
        System.out.println("[Jumpstart] multiplayer: " + modId + " removed on the server; " + stopped + " Lua event handlers stopped");
        boolean itemStillUsed = false;
        for (String other : serverModIds()) {
            String otherDir = other.equals(modId) ? null : actions.modDirOf(other);
            if (otherDir != null && item > 0 && GameActions.workshopIdOf(otherDir) == item) {
                itemStillUsed = true;
            }
        }
        String problem = changeServerLists(modId, item > 0 && !itemStillUsed ? Long.toString(item) : null, false);
        actions.dropFromModList(modId);
        actions.recomputeLuaChecksum("removing " + modId);
        return problem;
    }

    /**
     * Jumpstart was a mod before 0.2; a server whose list still names it turns every player away
     * ("mod Jumpstart is not installed"). Out of the list and the .ini, once at start.
     */
    private static void dropOldModEntry() {
        try {
            List<?> mods = (List<?>) Class.forName("zombie.network.GameServer").getField("ServerMods").get(null);
            if (mods.stream().anyMatch(m -> String.valueOf(m).replace("\\", "").equals("Jumpstart"))) {
                String problem = changeServerLists("Jumpstart", null, false);
                System.out.println("[Jumpstart] multiplayer: the old mod entry Jumpstart "
                        + (problem == null ? "is out of the server's mod list now" : "is still in the mod list: " + problem));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot check the server's mod list: " + e);
        }
    }

    /** The server's mod lists (for players who join later) and its .ini: the mod (and its item) in or out. */
    @SuppressWarnings("unchecked")
    private static String changeServerLists(String modId, String item, boolean add) {
        try {
            Class<?> server = Class.forName("zombie.network.GameServer");
            List<String> mods = (List<String>) server.getField("ServerMods").get(null);
            List<Long> items = (List<Long>) server.getField("WorkshopItems").get(null);
            boolean backslash = mods.stream().anyMatch(m -> m.startsWith("\\"));
            mods.removeIf(m -> m.replace("\\", "").equals(modId));
            if (add) {
                mods.add((backslash ? "\\" : "") + modId);
            }
            if (item != null) {
                Long id = Long.parseLong(item);
                items.remove(id);
                if (add) {
                    items.add(id);
                }
            }
            Object options = Class.forName("zombie.network.ServerOptions").getField("instance").get(null);
            setOption(options, "Mods", modId, add, backslash ? "\\" : "");
            if (item != null) {
                setOption(options, "WorkshopItems", item, add, "");
            }
            String name = (String) server.getField("serverName").get(null);
            options.getClass().getMethod("saveServerTextFile", String.class).invoke(options, name);
            System.out.println("[Jumpstart] multiplayer: server mod list " + (add ? "+ " : "- ") + modId
                    + (item == null ? "" : " (item " + item + ")") + ", saved to " + name + ".ini");
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "cannot change the server's mod list: " + e;
        }
    }

    private static void setOption(Object options, String name, String entry, boolean add, String prefix) throws ReflectiveOperationException {
        Object option = options.getClass().getMethod("getOptionByName", String.class).invoke(options, name);
        Object config = option.getClass().getMethod("asConfigOption").invoke(option);
        String value = (String) config.getClass().getMethod("getValueAsString").invoke(config);
        List<String> parts = new ArrayList<>();
        for (String p : value.split(";")) {
            if (!p.isBlank() && !p.replace("\\", "").equals(entry)) {
                parts.add(p);
            }
        }
        if (add) {
            parts.add(prefix + entry);
        }
        config.getClass().getMethod("parse", String.class).invoke(config, String.join(";", parts));
    }

    /** So the server's own mod list finds a freshly downloaded item (GameServer.workshopInstallFolders). */
    private static void addWorkshopFolder(String folder) {
        try {
            Class<?> server = Class.forName("zombie.network.GameServer");
            java.lang.reflect.Field field = server.getField("workshopInstallFolders");
            String[] folders = (String[]) field.get(null);
            List<String> list = new ArrayList<>(folders == null ? List.of() : List.of(folders));
            if (!list.contains(folder)) {
                list.add(folder);
                field.set(null, list.toArray(new String[0]));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot add the Workshop folder: " + e);
        }
    }

    @Override
    public ServerCoordinator.Candidate candidate(String modId) {
        ModEntry mod = find(modId);
        if (mod == null) {
            return null;
        }
        try {
            BiteCheckGate.Check check = actions.check(mod.jar());
            return new ServerCoordinator.Candidate(check.sha256(), UsageSharing.Jar.workshopIdOf(mod.jar()), check.verdict().name());
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String apply(ServerCoordinator.Action action, String modId, String sha256, boolean adminConfirmed) {
        if (action == ServerCoordinator.Action.INSTALL) {
            return install(modId, sha256, adminConfirmed);
        }
        if (action == ServerCoordinator.Action.REMOVE) {
            return remove(modId);
        }
        ModEntry mod = find(modId);
        if (mod == null) {
            return modId + " is gone from the server";
        }
        String state = actions.stateOf(modId);
        if (state.equals("not loaded")) {
            // a client-only mod: the server does not run it; the clients have changed it
            if (action == ServerCoordinator.Action.UPDATE) {
                loadedSha.put(modId, sha256);
            }
            return null;
        }
        switch (action) {
            case OFF -> {
                String r = state.equals("off") ? "OK" : actions.off(modId);
                return r.startsWith("OK") ? null : r;
            }
            case ON -> {
                String r = state.equals("on") ? "OK" : actions.on(modId);
                return r.startsWith("OK") ? null : r;
            }
            default -> {
                BiteCheckGate.Consent consent = adminConfirmed ? BiteCheckGate.Consent.CONFIRMED_TWICE : BiteCheckGate.Consent.NONE;
                LiveInstaller.ConsentSource answer = check -> check.sha256().equals(sha256) ? consent : null;
                LiveInstaller.Outcome outcome = actions.update(mod, answer);
                if (outcome.kind() != LiveInstaller.Kind.DONE) {
                    return outcome.kind() + " " + outcome.message();
                }
                loadedSha.put(modId, sha256);
                return null;
            }
        }
    }

    private ModEntry find(String modId) {
        for (ModEntry mod : clientJars()) {
            if (mod.id().equals(modId)) {
                return mod;
            }
        }
        return null;
    }

    private static String hash(Path jar) {
        try {
            return Hashing.sha256Hex(jar);
        } catch (IOException e) {
            return "";
        }
    }
}
