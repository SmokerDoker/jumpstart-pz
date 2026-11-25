package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.bitecheckpz.launcher.UsageSharing;
import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.gate.BiteCheckGate;
import io.github.jumpstartpz.core.market.LibraryClient;

/** What the secure screen can do. {@link GameActions} is the game; tests use a fake. */
interface Actions {
    /** Every Java mod in the mod folders, Jumpstart itself first; not ZombieBuddy (the Java agent). */
    List<ModEntry> javaMods();

    /**
     * Every mod in the mod folders this game version can load, Java and Lua: Jumpstart first, then
     * the enabled ones, then by name; not ZombieBuddy (the Java agent).
     */
    List<InstalledMod> installedMods();

    /** "on", "on, until restart", "off" or "not loaded". */
    String stateOf(String modId);

    /**
     * A mod's Lua side: "on" (enabled, running), "off", "on, next load" (enabled, runs with the next
     * load of the save) or "off, next load" (switched off, gone completely with the next load).
     */
    String luaState(String modId);

    BiteCheckGate.Check check(Path jar) throws IOException;

    LiveInstaller.Outcome install(ModEntry mod, LiveInstaller.ConsentSource player);

    LiveInstaller.Outcome update(ModEntry mod, LiveInstaller.ConsentSource player);

    String off(String modId);

    String on(String modId);

    /** Fetches BiteCheck's index again; returns what happened. */
    String refreshIndex();

    // --- marketplace ---

    /** Searches a library of the Jumpstart cloud; throws when there is none or it cannot be reached. */
    LibraryClient.Page search(LibraryClient.Kind kind, String query, String cursor) throws IOException;

    Workshop workshop();

    /** The mods in a downloaded Workshop item folder, with their Java part (null for Lua-only mods). */
    List<FolderMod> modsInFolder(String itemFolder);

    /** Marks a mod active: in the running save's mod list, or in the main menu's list. */
    String activate(String modId);

    /** Takes a mod out of the mod list that counts now (the running save's, else the main menu's). */
    String deactivate(String modId);

    /**
     * Loads a just enabled mod's Lua part into the running single player game ({@link LuaLive}), or
     * gives back the event handlers {@link #unloadLua} took away.
     */
    LuaLive.Result loadLua(String modId);

    /** Stops a running mod's Lua: takes its event handlers away (kept for {@link #loadLua}); returns how many. */
    int unloadLua(String modId);

    /**
     * What stayed of a mod's Lua after the last {@link #unloadLua}: "" if nothing,
     * "kept:N:a, b" (changed again by another mod since) or "start:N:a, b" (it came with the save,
     * so its functions could only be named).
     */
    default String luaLeftovers(String modId) {
        return "";
    }

    /** {@link #loadLua} in a multiplayer round the server started (install for everybody). */
    LuaLive.Result loadLuaForServer(String modId);

    /** {@link #unloadLua} in a multiplayer round the server started (remove for everybody). */
    int unloadLuaForServer(String modId);

    /**
     * Uninstalls a mod after the player's two yeses: off, out of every mod list, then the files go:
     * a Workshop item's Steam page opens to unsubscribe (Steam deletes it), a local mod folder moves
     * to mods-uninstalled. "OK text" or what went wrong.
     */
    String uninstall(String modId);

    /** The titles of Workshop items (for "needs ..." questions); items it cannot name are left out. Off the main thread. */
    default Map<Long, String> workshopTitles(List<Long> ids) {
        return Map.of();
    }

    /**
     * A mod in the mod folders; {@code java} is null for a Lua-only mod, {@code workshopId} 0 for a
     * local one; {@code requires}: the mods it needs (mod.info require=, see {@link ModDeps#cleaned}).
     */
    record InstalledMod(String id, String name, ModEntry java, long workshopId, List<String> requires) {
        public InstalledMod {
            requires = requires == null ? List.of() : List.copyOf(requires);
        }

        public InstalledMod(String id, String name, ModEntry java, long workshopId) {
            this(id, name, java, workshopId, List.of());
        }
    }

    record FolderMod(String id, String name, ModEntry java) {
    }

    /**
     * What the detail pane shows about a mod: its description and a picture (an absolute path to a
     * PNG, or null).
     */
    record Details(String description, String image) {
        static final Details NONE = new Details("", null);
    }

    /** An installed mod's description and icon (or poster) from its mod.info; quick, no network. */
    default Details details(String modId) {
        return Details.NONE;
    }

    /**
     * A Workshop item's full description and preview from Steam; network, call off the main thread.
     * Empty description when Steam does not know the item.
     */
    default Details workshopDetails(long workshopId) {
        return Details.NONE;
    }

    // --- crash protection (the ZombieBuddy fork's guard) ---

    /** The newest guard event's number, "0" if none; null when this ZombieBuddy has no guard. Cheap. */
    default String guardLastSeq() {
        return null;
    }

    /** Guard events after {@code sinceSeq}, one per line: seq, mod id, kind, where, exception, action (tabs). */
    default String guardEvents(String sinceSeq) {
        return "";
    }

    /** "count TAB state" (ok, off-by-guard, probation), or "" when the mod had no error. */
    default String guardStatus(String modId) {
        return "";
    }

    // --- save snapshots (single player) ---

    /**
     * Before the first switch: saves the game and copies the save in the background (see
     * {@link SaveSnapshots}). Does nothing outside a single player game or when snapshots are off.
     */
    default void beginSnapshot() {
    }

    /** null (none running or wanted), "running", "done", "skipped" or "failed reason". */
    default String snapshotState() {
        return null;
    }

    /** The snapshots there are, newest first, as the player reads them. Main menu. */
    default List<String> snapshots() {
        return List.of();
    }

    /** Restores snapshot {@code index} of {@link #snapshots()}; main menu only. "OK text" or what went wrong. */
    default String restoreSnapshot(int index) {
        return "not available";
    }

    // --- voluntary sharing (BiteCheck's UsageSharing) ---

    boolean sharingEnabled();

    void setSharing(boolean enabled) throws IOException;

    /** The unverified jars a report would list now (hashes the mods; call off the main thread). */
    List<UsageSharing.Jar> unverifiedJars();

    /** Sends today's report if sharing is on and it is due; returns what happened. */
    String shareIfDue(List<UsageSharing.Jar> jars);
}
