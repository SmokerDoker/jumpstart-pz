# Jumpstart

**Install, update and switch Project Zomboid mods while the game runs.** Jumpstart brings live
mod management to Project Zomboid (Build 42): find a mod in game, check it with
[BiteCheck](https://github.com/SmokerDoker/bitecheck-pz), switch it on, update it or roll it
back, without restarting. On servers, every player switches at the same time.

> **Early development.** Nothing here is ready to play with yet. The first milestone is a proof
> that a Java mod's patches can be switched off and on in the running game.

Jumpstart is a community project. It is not affiliated with, endorsed by, or supported by The
Indie Stone. "Project Zomboid" is a trademark of The Indie Stone.

## Idea

Hotwire showed for Minecraft that mods can be added, updated and toggled live. Jumpstart does
the same for Project Zomboid, on top of
[our fork of ZombieBuddy](https://github.com/SmokerDoker/zombiebuddy-jumpstart), which loads Java
mods, and goes further where Project Zomboid needs it:

- **Live switching.** A mod is never "unloaded": every class it changed is rebuilt from its
  original bytes with all other mods' patches and without its own.
- **Clean removal.** Files a mod wrote, values it changed, Lua functions it replaced and data it
  put into the save are tracked and restored where possible. A snapshot of the save is taken
  before every switch.
- **Multiplayer.** Players download and check new mods while they keep playing. The world then
  pauses for a few seconds, all players switch together, checksums are compared, and play
  goes on. Whoever is too slow is kicked with a message and can rejoin at once.
- **Marketplace and updates.** Browse the Steam Workshop and the BiteCheck registry in game,
  get updates once they are verified, roll back to any earlier version from a local cache.
- **BiteCheck for every load.** No jar reaches the game, at start, live, as an update or pushed
  by a server, without passing BiteCheck. A client checks every hash itself and never trusts
  the server's word for it.

Not every mod can be switched live. Each mod is classified as *live*, *reload* (Lua reset or a
quick in-process restart of the game's start phases) or *restart*, from what it declares, what
its bytecode does and what BiteCheck's scanner finds. The strictest wins.

## Roadmap

1. **Core, single player:** live on/off for Advice-only Java mods, BiteCheck built in.
   Gate: the BiteCheck test mods switch on and off live.
2. **Clean removal and Lua:** restore Lua globals, events, files and written values; snapshots;
   in-process restart for *reload* mods.
3. **Marketplace and updates:** Workshop search, registry badges, hash cache, rollback,
   emergency switch-off when BiteCheck flags a running mod.
4. **Multiplayer:** manifest, background download, pause, two-phase switch, checksums.
5. **More:** native code in a helper process, placeholder items in saves, self-update.

## Status

- [x] `core`: `HotswapEngine` switches Advice patches on and off with one retransformable
  transformer per mod; proven in tests ([spike notes](docs/SPIKE-1-zombiebuddy.md))
- [x] ZombieBuddy fork with a hotswap API:
  [zombiebuddy-jumpstart](https://github.com/SmokerDoker/zombiebuddy-jumpstart), tested with a Java agent
- [x] Same in the game with ZBHelloWorld: off/on five times and a live update, checked through
  the bytecode the game runs (automated: `tools\gametest.ps1` in the fork, 28 of 28 checks)
- [x] Load new mods and update loaded ones at runtime (fork: `Hotswap.load` / `Hotswap.update`,
  only with approval in `mod_approvals.json`)
- [x] BiteCheck gate: every live install and update goes through BiteCheck (verified loads,
  unverified asks once, flagged or suspicious asks twice, blocked never loads), with the
  launcher's keys, index, cache and state; tested in the game against the real registry index
  (`tools\gametest.ps1`, 18 of 18 checks)
- [x] The Jumpstart mod (`mod/`) with a **secure screen** (Ctrl+Shift+J): every Java mod in your
  mod folders with its state and BiteCheck verdict; Enter loads it live or reloads its jar,
  A switches it off and on, R updates the BiteCheck index. The screen is drawn by Java after every
  Lua window, opens only on the physical keys (read from GLFW, not from the game's key queue) and
  blocks the game's keyboard and UI input while open. BiteCheck's questions appear only there,
  and their answer key works only after 3 (second question: 5) seconds.
- [x] Lua mods cannot install, update or switch anything and cannot start a question: the Lua API
  `Jumpstart` only reads (`status`, `stateOf`, `check`), and the fork lets Lua change mods only with
  the test option `hotswap_lua=true`. Tested in the game (`tools\gametest.ps1`, 29 of 29 checks,
  including fake Ctrl+Shift+J events from Lua that must not open the screen)
- [x] Marketplace in the secure screen: a **Java library** and a **Lua library** to search the
  Steam Workshop (typed on the real keyboard), through the Jumpstart cloud Worker
  ([`cloud/`](cloud/README.md)), which holds the Steam Web API key. Enter subscribes and downloads
  the item through Steam; a Java mod then goes straight into BiteCheck's check and loads live, a
  Lua mod is enabled for the next game start. "update available" shows Workshop updates.
- [x] Voluntary sharing (tab *Sharing*, off by default, BiteCheck's `UsageSharing`): once a day the
  SHA-256, Workshop id, mod id and size of unverified jars go to the cloud, so the BiteCheck
  registry reviews the mods people really play first. The tab shows exactly what would be sent.
- [x] Multiplayer (first version, for an online test): the server (dedicated, and hosted games
  through the fork) runs Jumpstart too; a Java channel between server and clients that Lua can
  neither read nor fake; the secure screen's **Server** tab compares the server's Java mods with
  yours; an admin starts an update for everybody: the game pauses, every client gets exactly the
  server's jar and checks it with BiteCheck (the player answers its questions), whoever is not
  ready in time or says no is disconnected, then the server follows. Test kit for Windows, macOS
  and Ubuntu: [kit/](kit/TESTPLAN.md)
- [x] The cloud Worker runs at <https://jumpstart-cloud.smokerdoker.workers.dev> (D1 database in
  Western Europe) and is the default for Jumpstart and BiteCheck (`cloud_url` overrides it)

## Build

```sh
git submodule update --init      # BiteCheck, pinned in third_party/
./gradlew check :mod:modFolder   # tests, and the mod folder in mod/build/mod/Jumpstart
```

Needs JDK 21 to 24 with the wrapper (Gradle 8.14); with JDK 25 use Gradle 9. Project Zomboid
itself runs on Java 25. In-game test (Windows, Steam
running, the fork built next to this repository): `tools\gametest.ps1`.

## Repository layout

```
core/          BiteCheck gate, LiveInstaller, hotswap engine, library client (planned: multiplayer)
mod/           The Jumpstart mod for Project Zomboid (secure screen, read-only Lua API)
third_party/   BiteCheck (git submodule)
tools/         In-game tests (single player, dedicated server), a stand-in for the cloud, the kit builder
kit/           Install scripts and test plan for the online test with friends
cloud/         Cloudflare Worker: mod libraries (Steam Workshop search) and voluntary reports
docs/          Design notes and spike results
```

## License

Jumpstart is licensed under the [GNU General Public License v3.0](LICENSE), like BiteCheck,
whose verification library it will embed.
