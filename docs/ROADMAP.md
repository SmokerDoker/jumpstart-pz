# Jumpstart roadmap

Everything Jumpstart still wants to deliver, in one place. The [README](../README.md) describes what
Jumpstart does today. Items are checked off here when they are released.

Last audit of the plans against the code: 2026-11-26.

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
  with automatic signed updates, the Windows loader `jsNative.dll`
- **2. Clean removal (mostly):** crash protection, Lua globals, event handlers and windows taken back
  (also for mods that came with the save), static Java values restored, file archive and ModData stash on
  uninstall, save snapshots
- **3. Marketplace (partly):** Workshop search for Java and Lua mods, mod pages, dependencies and
  required items, download progress
- **4. Multiplayer (first version):** server-side Jumpstart, a channel Lua cannot touch, install, update,
  switch and remove for everybody at once, Server tab

## 2. Clean removal: what is left

Next after the current multiplayer block, before the marketplace:

- [ ] Switching on loads what today waits for the next load, in single player first, with the game's own reload functions:
  item and recipe scripts (`ScriptManager.LoadFile` / `ReloadScripts`, then recipe links, loot distribution, icons),
  animations (`AnimationSet.Reset`, `AdvancedAnimator.load`), texture packs (new pictures load on first use already).
  Switching off: animations reload without the mod; replaced pictures go back to the originals; items stay until
  placeholder items exist (their instances are in containers and inventories). Multiplayer: on server and clients
  together, in the two-phase switch (the server checks script checksums). Tests against the real game jar, since
  these are the game's internal functions.

- [ ] In-process restart for mods that cannot go live (items, scripts, animations), instead of "with the next load"
- [ ] Classify each mod as live, reload or restart (declaration, bytecode analysis, BiteCheck's scan)
- [ ] An own class loader per mod (patches are merged into the shared loader today)
- [ ] A trial run before a mod is switched on
- [ ] Crash report as a zip
- [ ] After switching off: check that no code or reference of the mod is still alive
- [ ] Move structural class changes out (nestmates, invokedynamic)
- [ ] Registration journal: key bindings, console commands and non-function values in tables
- [ ] Files a mod created are archived also when it is only switched off (today only on uninstall)
- [ ] Java writes into instance fields (today static fields only)
- [ ] ModData on single objects; placeholder items for items of a removed mod
- [ ] Trip wire in every mod method, not only at the start of each patch

## 3. Marketplace and updates

- [ ] BiteCheck badge on every library item (not only for installed mods)
- [ ] "Lua is not checked" note on Lua mods
- [ ] Conflicts: two mods that patch the same method
- [ ] Updates overview and a channel "verified updates only"
- [ ] Own cache by hash, so Steam cannot swap files under the running game; rollback to earlier versions
- [ ] Emergency switch-off: a running mod that BiteCheck flags goes off at once (today R only refreshes the index)
- [ ] Sort by new and trending (the cloud offers it), filter by BiteCheck status
- [ ] A Workshop item with several mods (variants like 60/70/80 %) shown as one entry with its variants
- [ ] Mods without Lua (item scripts, textures) labelled as such, with "only after reloading the save"

## 4. Multiplayer

- [x] Download in the background, pause only when everybody is ready
- [x] A real server pause (the world stops like on an empty server with PauseEmpty; the network runs on)
- [x] Two-phase switch with rollback (the server switches first; if it cannot, nobody changes anything)
- [x] Players who join during a switch: in phase 1 they take part, in phase 2 they are compared after it
- [ ] Checksums compared after a switch, including the game's Lua checksum for live-installed files
- [x] Check the Java mods on every join (and after every change) and bring the player in line: the server's version, on or off, off for Java the server does not run
- [x] The server compares its players' Java mod hashes (they report them: this keeps honest clients in line)
- [ ] Against manipulated clients that lie about their hashes: a check the client cannot answer from a list (BiteCheck's point 5, research)
- [x] Countdown configurable (`server_countdown_seconds`, see kit/SERVER.md)
- [ ] Emergency switch-off from the server
- [ ] Save snapshots on servers
- [ ] Workshop items with several mods (the server takes only the first today)
- [ ] After a SteamCMD game update: repair from the server's start script (documented in kit/SERVER.md, not yet wired into existing scripts)

## 5. More

- [ ] Native libraries in a helper process
- [ ] Placeholder items in saves
- [ ] Performance measurement per mod
- [ ] Join time: measure what Jumpstart adds to loading (Lua recording); slow PCs lose the connection while the game loads its tiles
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
