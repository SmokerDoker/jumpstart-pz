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
  yours. In multiplayer nobody switches Java mods alone, not even an admin: an admin loads/updates
  (Enter) or switches off/on (A) a mod for everybody at once. The game pauses, every other player
  has to agree, for an update every client gets exactly the server's jar and BiteCheck checks it
  (the player answers its questions); whoever says no or is not ready within 3 minutes is
  disconnected, then the server follows. Test plan for Windows, macOS
  and Ubuntu: [kit/TESTPLAN.md](kit/TESTPLAN.md)
- [x] One-click installers like BiteCheck's (`installer/`, `kit/package-installer.sh`): `.msi`, `.dmg`
  (Apple Silicon and Intel), `.deb`, `.rpm` with their own Java runtime, and `JumpstartSetup.jar`.
  They ship the ZombieBuddy fork inside Jumpstart.jar plus its mod folder, so nobody needs
  ZombieBuddy from the Workshop or its installer any more; macOS/Linux launch options are set in
  Steam's `localconfig.vdf` while Steam is closed
- [x] **One jar:** `Jumpstart.jar` is the game's only Java agent: the ZombieBuddy fork with Jumpstart
  and its BiteCheck classes built in (the fork loads Jumpstart from the jar's `embedded-mod/` entries,
  plain classes BiteCheck scans like any other). BiteCheck asks about this one jar
- [x] The cloud Worker runs at <https://jumpstart-cloud.smokerdoker.workers.dev> (D1 database in
  Western Europe) and is the default for Jumpstart and BiteCheck (`cloud_url` overrides it)

## Downloads

**Install with one click** (newest test build; Jumpstart.jar has the
[ZombieBuddy fork](https://github.com/SmokerDoker/zombiebuddy-jumpstart) built in, so no ZombieBuddy
from the Workshop and no ZombieBuddy installer are needed):

| System | Download | Note |
| --- | --- | --- |
| Windows 10/11 | [Jumpstart-windows-x64.msi](https://github.com/SmokerDoker/jumpstart-pz/releases/download/test-kit/Jumpstart-windows-x64.msi) | for your user only, no admin rights; SmartScreen warns once (not code-signed): "More info" → "Run anyway" |
| macOS | [Apple Silicon](https://github.com/SmokerDoker/jumpstart-pz/releases/download/test-kit/Jumpstart-macos-arm64.dmg) · [Intel](https://github.com/SmokerDoker/jumpstart-pz/releases/download/test-kit/Jumpstart-macos-x64.dmg) | not notarized: open once via System Settings → Privacy & Security → "Open anyway" |
| Linux | [Debian/Ubuntu (.deb)](https://github.com/SmokerDoker/jumpstart-pz/releases/download/test-kit/Jumpstart-linux-x64.deb) · [Fedora/openSUSE (.rpm)](https://github.com/SmokerDoker/jumpstart-pz/releases/download/test-kit/Jumpstart-linux-x64.rpm) | |
| Any, incl. Steam Deck | [JumpstartSetup.jar](https://github.com/SmokerDoker/jumpstart-pz/releases/download/test-kit/JumpstartSetup.jar) | runs with any Java 21+, e.g. the game's own: `…/ProjectZomboid/projectzomboid/jre64/bin/java -jar JumpstartSetup.jar` |

Open **Jumpstart** and press **Install Jumpstart**. It finds the game in every Steam library and puts
in place:

- `Jumpstart.jar` in the game folder, started as `-javaagent:Jumpstart.jar=hotswap=true`: on Windows
  in `ProjectZomboid64.json`, on macOS and Linux in Steam's launch options (Steam has to be closed
  for that; the installer offers to close it, or copies the line for you)
- the fork's mod folder `Zomboid/mods/ZombieBuddy` (Lua and translations, no jar; other mods require
  ZombieBuddy by id), switched on in the mod list

A ZombieBuddy of your own makes room, since two agents would load every mod twice: its
`ZombieBuddy.jar` is kept as `ZombieBuddy.jar.before-jumpstart`, its start option becomes
Jumpstart's (its options stay, e.g. `policy=`), and uninstalling puts both back. An install from
before 0.2 (the fork as `ZombieBuddy.jar`, a mod folder `Zomboid/mods/Jumpstart`) is moved to this
layout. Your other launch options, BiteCheck's `--wrap` included, stay. The same window updates,
repairs and removes Jumpstart; on the command line:
`JumpstartSetup.jar --install | --uninstall | --update | --status`.

**Automatic updates** (box ticked by default): Jumpstart puts itself in front of the game in Steam's
launch options and, before every game start, installs the newest **signed** release (tags `v*`, never
`test-kit`) and repairs what a game update undid; then the game starts. The launch option runs
`~/.jumpstart/JumpstartSetup.jar` with the game's own Java, so it never depends on the installed app:
remove the app and the game still starts. The installer updates itself the same way (signed). Setting
the launch option needs Steam closed once; the installer closes and restarts Steam for you. Details
and the signing key: [kit/RELEASING.md](kit/RELEASING.md).

**Dedicated servers**: `JumpstartSetup.jar --server <folder> --name <server> --headless`, with
`--update` before every start (systemd `ExecStartPre`): [kit/SERVER.md](kit/SERVER.md).

[Releases](https://github.com/SmokerDoker/jumpstart-pz/releases): `test-kit` is rebuilt on every
push to main (workflow *Kits*): the installers above and, for tags, the signed update files;
`SHA256SUMS.txt` lists every file's hash.

## Build

```sh
git submodule update --init      # BiteCheck and the ZombieBuddy fork, pinned in third_party/
./gradlew check                  # tests
# the fork's agent jar, built against the game (Gradle 9, JDK 25):
(cd third_party/zombiebuddy-jumpstart/java && gradle -PgameClasspath=<game>/projectzomboid.jar shadowJar)
# Jumpstart.jar: the fork with Jumpstart inside -> mod/build/agent/Jumpstart.jar
./gradlew :mod:agentJar -PforkJar=<fork>/java/build/jdk25/libs/zombiebuddy-jumpstart.jar
# the installer, with the fork's mod folder (no jar) from kit/zombiebuddy-mod.sh:
bash kit/zombiebuddy-mod.sh third_party/zombiebuddy-jumpstart build/mods
./gradlew :installer:installerJar -PforkJar=<...> -PzombieBuddyMod=build/mods/ZombieBuddy   # installer/build/setup/JumpstartSetup.jar
```

Needs JDK 21 to 24 with the wrapper (Gradle 8.14); with JDK 25 use Gradle 9. Project Zomboid
itself runs on Java 25. In-game test (Windows, Steam
running, the fork built next to this repository): `tools\gametest.ps1` (it loads Jumpstart as a
separate mod folder next to the fork's experimental jar: `./gradlew :mod:modFolder`).

## Repository layout

```
core/          BiteCheck gate, LiveInstaller, hotswap engine, library client (planned: multiplayer)
mod/           The Jumpstart mod for Project Zomboid (secure screen, read-only Lua API)
third_party/   BiteCheck and the ZombieBuddy fork (git submodules)
tools/         In-game tests (single player, dedicated server), a stand-in for the cloud
installer/     One-click installer (window and command line), packaged with jpackage as .msi, .dmg, .deb, .rpm
kit/           Packaging and release scripts, test plan and server guide
cloud/         Cloudflare Worker: mod libraries (Steam Workshop search) and voluntary reports
docs/          Design notes and spike results
```

## License

Jumpstart is licensed under the [GNU General Public License v3.0](LICENSE), like BiteCheck,
whose verification library it embeds. `Jumpstart.jar` contains the
[ZombieBuddy fork](https://github.com/SmokerDoker/zombiebuddy-jumpstart) by Andrey "Zed" Zaikin
under the MIT license (`META-INF/LICENSE-ZombieBuddy.txt` in the jar); the jar as a whole is
GPL-3.0 (`META-INF/LICENSE-Jumpstart.txt`).
