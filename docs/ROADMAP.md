# Jumpstart roadmap

Everything Jumpstart still wants to deliver, in one place. The [README](../README.md) describes what
Jumpstart does today. Items are checked off here when they are released.

Last audit of the plans against the code: 2026-11-28 (release 0.5.14).

## The idea

Hotwire showed for Minecraft that mods can be added, updated and toggled live. Jumpstart does the same
for Project Zomboid and goes further where the game needs it: a mod is never "unloaded", every class it
changed is rebuilt from its original bytes without its patches; what it wrote is tracked and restored;
on servers everybody switches together; and no jar reaches the game without BiteCheck. Not every mod
can be switched live: each one is meant to be classified as *live*, *reload* (Lua reset or a quick
in-process restart of the game's start phases) or *restart*.

## Done so far

- **1. Core, single player:** live on/off, load and update of Java mods through the ZombieBuddy fork,
  BiteCheck for every load, the secure screen, the read-only Lua API, one `Jumpstart.jar`, installers
  with automatic signed updates, the Windows loader `jsNative.dll`, lean main menu
- **2. Clean removal (mostly):** crash protection; Lua globals, event handlers, windows and registrations in
  the game's tables taken back (also for mods that came with the save); static and instance Java fields of
  the game restored; a trip wire in every method of a switched-off mod, with a count of calls that still
  reach it; files parked while off, archived on uninstall; ModData stash; save snapshots; crash report zip;
  items, recipes, loot tables and animations load live (0.5.12)
- **3. Marketplace:** Workshop search for Java and Lua mods with sorting, mod pages, dependencies and
  required items, download progress, Lua/Data labels, conflicts between Java mods, updates overview and
  the channel "verified updates only", own jar copies by hash with going back to a kept version, emergency
  switch-off when BiteCheck blocks a running mod, choosing one mod of an item with several (0.5.12)
- **4. Multiplayer:** server-side Jumpstart, a channel Lua cannot touch, install, update, switch and
  remove for everybody in two phases with a real server pause, join sync, Lua and script checksums,
  emergency switch-off, server snapshots (restore from the setup), start scripts that survive SteamCMD
  updates (0.5.11 to 0.5.13)

## 2. Clean removal: what is left

- [x] Switching on loads item, recipe and other scripts live (single player and multiplayer, server and
  clients in the same round): only the mod's own script files, parsed like at the start and loaded with the
  game's own steps (the game's `ReloadScripts` would reset every craft recipe and Lua's changes to items); new
  items get world dictionary ids (the server's go to the clients with the commit); the game's item, tag,
  clothing, recipe and model lists are built again; the loot tables are read again
  (`IsoWorld.parseDistributions`, after the missed distribution-merge events); animation clips, sets and action
  groups load (`ModelManager.loadModAnimations`, the game's `refreshAnimSets`). In multiplayer the script
  checksum is worked out again (self test at server start) and compared after the round. Untested in the game.
- [ ] Switching off: animations reload without the mod, replaced pictures go back to the originals (the mod's
  files stay in the game's file list until the next load today); items stay until placeholder items exist
- [ ] Texture packs, sounds, 3D models, tiles, clothing, maps and sandbox options of a live-loaded mod (still with the next load)

- [ ] In-process restart for mods that cannot go live (items, scripts, animations), instead of "with the next load"
- [ ] Classify each mod as live, reload or restart (declaration, bytecode analysis, BiteCheck's scan)
- [ ] An own class loader per mod (patches are merged into the shared loader today)
- [ ] A trial run before a mod is switched on
- [x] Crash report as a zip (C in the installed tab: console log, the mods with state, hash and BiteCheck's word, what the crash protection caught, system, the JVM's crash files; written to `Zomboid/jumpstart/reports/`, sent nowhere)
- [x] After switching off: Lua functions from the mod's files still reachable from the globals are named (Lua side)
- [x] The same check for the Java side: every call into a switched-off mod's code is caught by the trip wire and counted; the details say when something still calls it
- [ ] Move structural class changes out (nestmates, invokedynamic)
- [x] Registration journal: entries a mod adds to the game's tables while it loads live (a key binding in `keyBinding`, list and lookup entries) go when it is switched off; lists close up (`table.remove`), the entry comes back at the end
- [ ] Registrations a mod made in a save it came with (no record from before it loaded), and values it changed in existing entries
- [x] Files a mod created wait in `Zomboid/jumpstart/parked/<mod>/` while it is off and come back when it is on (never over a new file); uninstalling archives them
- [x] Java writes into instance fields of game objects go back when the mod is switched off (per object, only where the field still holds the mod's value; not in the mod's constructors)
- [ ] ModData on single objects; placeholder items for items of a removed mod
- [x] Trip wire in every method of a mod's own classes, not only at the start of each patch; switching off trips it (not in constructors, equals/hashCode/toString, delegation patches, libraries a mod ships under their own name)

## 3. Marketplace and updates

- [ ] BiteCheck badge on every library item, and a filter by BiteCheck status: BiteCheck's signed index knows
  jar hashes only, not Workshop items; needs the Workshop id in BiteCheck's index (registry entries have it)
  or the cloud's usage reports per item (today the badge shows once a jar is on disk)
- [x] "Lua is not checked" note on Lua mods (library and installed)
- [x] Conflicts: two Java mods that are on patch the same game method (their @Patch annotations, read from
  the class files); red when one of them replaces the method (a delegation)
- [x] Updates overview (N: only mods with a newer jar on disk) and a channel "verified updates only" (V; an
  update BiteCheck has not verified does not load live)
- [x] Own copies of the jars by hash (`Zomboid/jumpstart/jar-cache`, the last 5 per mod): live loads read the
  copy, so Steam cannot swap the file under the running game; H lists the kept versions, Enter loads one
  live through BiteCheck. The game's own start still loads the Workshop file (ZombieBuddy's approvals decide)
- [x] Emergency switch-off in single player: a running Java mod BiteCheck blocks goes off at once (index fetched
  every 15 minutes), with a banner
- [x] Sort the libraries: most popular, newest, trending this week (Left/Right)
- [x] A Workshop item with several mods: the player chooses one (single player and multiplayer)
- [x] Mods without Lua (item scripts, textures) labelled "Data"; their items load live (section 2)

## 4. Multiplayer

- [x] Download in the background, pause only when everybody is ready
- [x] A real server pause (the world stops like on an empty server with PauseEmpty; the network runs on)
- [x] Two-phase switch with rollback (the server switches first; if it cannot, nobody changes anything)
- [x] Players who join during a switch: in phase 1 they take part, in phase 2 they are compared after it
- [x] Checksums compared after a switch, including the game's Lua checksum: server and players work it out again after an install or remove, the server compares (self test against the game's own at server start)
- [x] Check the Java mods on every join (and after every change) and bring the player in line: the server's version, on or off, off for Java the server does not run
- [x] The server compares its players' Java mod hashes (they report them: this keeps honest clients in line)
- [ ] Against manipulated clients that lie about their hashes: a check the client cannot answer from a list (BiteCheck's point 5, research)
- [x] Countdown configurable (`server_countdown_seconds`, see kit/SERVER.md)
- [x] Emergency switch-off from the server: an admin (X twice, nobody asked, 20 s), or the server itself when BiteCheck blocks a running mod (index fetched every 15 minutes)
- [x] Save snapshots on servers: at the start of every change, while the game goes on; phase 2 waits for it
- [x] Workshop items with several mods: the admin chooses which one to install
- [x] After a SteamCMD game update: every install writes a start script of Jumpstart's own (repair and update, then the game's script); `--systemd <unit>` writes the unit's drop-in
- [x] Item scripts and animations of a mod installed live on a server: loaded on server and clients in the round, script checksum worked out again (section 2)
- [x] Restore a server snapshot from the setup: `--snapshots`, `--restore-snapshot latest|FILE` (server stopped; the save it replaces is kept)

## 5. More

- [ ] Native libraries in a helper process
- [ ] Placeholder items in saves
- [ ] Performance measurement per mod
- [x] Lean main menu: it loads only ZombieBuddy, the mods load once with the game (saves 2 to 3 minutes at start with many mods)
- [x] Jumpstart's Lua recording reports its own time in the log ("recording took ... ms")
- [ ] Join: skip the game's second load of all mods when the menu already has the server's list (research; deep in the game's loading)
- [ ] Slow PCs lose the connection while the game loads its tiles on join (game side)
- [ ] A list of mods tested with Jumpstart
- [ ] An in-game test that gets into a save by itself (today the tests wait for a click)
- [ ] Self-update of the running core; releases checked through the BiteCheck registry

## Tests in the real game still to do

- [ ] Crash protection and clean removal with real mods in a normal save
- [ ] Dependencies (e.g. a library and the car mods that need it)
- [ ] Busy notice and download progress
- [ ] Restoring a snapshot; static Java values restored
- [x] Switching a mod off and on for everybody on a real server (Linux, 0.5.5)
- [ ] Install, update and remove for everybody on a real server
- [ ] `tools\startmodtest.ps1` after its last fix; `tools\gametest.ps1` with the current build
- [ ] Items live (0.5.12): `tools/testmods/JSTItemTest`, steps in kit/TESTPLAN.md; vanilla recipes still work after it
- [ ] Marketplace keys (0.5.12): sorting, N/V/H, a conflict shown, a version loaded back, "verified updates only"
- [ ] Multiplayer (0.5.11): emergency switch-off (X twice), a snapshot during a round, choosing a mod of an item,
  a player who joins after a live install is not turned away for a checksum
- [ ] Clean removal (0.5.13/0.5.14): a key binding taken back, parked files back, crash report (C), a switched-off
  Java mod no longer runs and "still called" shows when something holds it, instance fields restored
