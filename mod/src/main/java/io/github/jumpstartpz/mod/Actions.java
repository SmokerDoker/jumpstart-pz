package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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

    /** A mod in the mod folders; {@code java} is null for a Lua-only mod, {@code workshopId} 0 for a local one. */
    record InstalledMod(String id, String name, ModEntry java, long workshopId) {
    }

    record FolderMod(String id, String name, ModEntry java) {
    }

    // --- voluntary sharing (BiteCheck's UsageSharing) ---

    boolean sharingEnabled();

    void setSharing(boolean enabled) throws IOException;

    /** The unverified jars a report would list now (hashes the mods; call off the main thread). */
    List<UsageSharing.Jar> unverifiedJars();

    /** Sends today's report if sharing is on and it is due; returns what happened. */
    String shareIfDue(List<UsageSharing.Jar> jars);
}
