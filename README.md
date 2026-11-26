# Jumpstart

**Install, update and switch Project Zomboid mods while the game runs.** Jumpstart brings live mod
management to Project Zomboid (Build 42): find a mod in the game, let
[BiteCheck](https://github.com/SmokerDoker/bitecheck-pz) check it, switch it on or off, update or
uninstall it, without restarting. On servers, every player switches at the same time.

> **Public test.** Jumpstart works, but it is new. Please tell us what breaks or confuses you:
> [open an issue](https://github.com/SmokerDoker/jumpstart-pz/issues/new) with your system, the
> Jumpstart version and what you saw.

Jumpstart is a community project. It is not affiliated with, endorsed by, or supported by The
Indie Stone. "Project Zomboid" is a trademark of The Indie Stone.

## What it does

Press **Ctrl+Shift+J** in the game (main menu or a running save) to open Jumpstart's screen.

- **Installed mods.** Every Java and Lua mod in your mod folders, with its state and BiteCheck's
  verdict. **A** switches a mod on or off live, **Enter** loads a Java mod or reloads its jar,
  **U** uninstalls it, **R** updates BiteCheck's index. The detail pane shows what a mod needs and
  which mods need it.
- **Libraries.** Search the Steam Workshop for Java and Lua mods without leaving the game. Enter
  subscribes and downloads the mod through Steam; a Java mod goes through BiteCheck's check, then
  the mod loads live. If a mod needs other Workshop items that are not installed, Jumpstart offers
  to install them first.
- **Dependencies.** Switching off a library asks to switch off the mods that need it too; switching
  on a mod switches on the libraries it needs first; a library nothing needs any more is offered for
  switching off; a library that comes back on offers the mods that went off with it.
- **Clean switching.** Switching a mod off takes back what its Lua changed (new globals, replaced
  game functions, event handlers, windows of its own classes), also for mods that came with the save,
  and restores static values its Java code changed in the game. A function another mod changed again
  since stays and is named. Switching it on puts it all back. Parts the game reads only while a save
  loads (items, animations, sounds, ...) follow with the next load of the save, and the screen says so.
- **Crash protection.** If a mod's Java patch throws, the game keeps running; after repeated errors
  (or at once right after a live load) the mod is switched off and a banner names it.
- **Clean uninstall.** Files the mod created move to `Zomboid/jumpstart/archive/`, ModData tables it
  created and nobody else used leave the save (they come back if you reinstall it).
- **Save snapshots** (single player). Before a switch Jumpstart saves the game and keeps a zip of
  the save (`Zomboid/jumpstart/snapshots/`, the newest 5); the main menu's *Snapshots* tab restores
  one. `snapshots=auto|on|off` in `Zomboid/jumpstart/settings.properties` (auto skips saves of 500 MB
  or more).
- **You always see what happens.** Before an action holds the game for a moment, the screen says what
  runs ("Switching off ..."); downloads show their progress, also as a line at the top while the
  screen is closed.
- **Sharing** (off by default). Once a day the hashes, Workshop ids and mod ids of your unverified
  Java mods go to the BiteCheck registry, so it reviews the mods people really play first. The tab
  shows exactly what would be sent.

## Safety

- **BiteCheck for every load.** No jar reaches the game, at the start, live, as an update or pushed
  by a server, without passing BiteCheck: verified mods load, unverified ones ask once, flagged or
  suspicious ones ask twice, blocked ones never load.
- **A screen Lua cannot touch.** Jumpstart's screen is drawn by Java after every Lua window, opens
  only on the physical keys and blocks the game's input while open. BiteCheck's questions appear only
  there, and their answer key works only after a few seconds. Lua mods can read Jumpstart's state
  (`Jumpstart.status()`, `stateOf`, `check`) but cannot install, switch or answer anything.

## Multiplayer

The server (dedicated, or a game you host) runs Jumpstart too, with a channel between server and
players that Lua can neither read nor fake. The screen's **Server** tab compares the server's Java mods
with yours. Nobody switches Java mods alone, not even an admin: an admin installs, updates, switches
or removes a mod for everybody at once. First everybody gets ready while the game goes on: every player
has to agree, every client gets exactly the server's jar and BiteCheck checks it on the player's
computer. Then the server stops its world for a moment, switches first, and the players follow; if the
server cannot, nobody changes anything. Whoever says no or is not ready in time (3 minutes, adjustable)
is disconnected. Dedicated servers:
[kit/SERVER.md](kit/SERVER.md).

## Faster start

The game loads every mod of the main menu's list at start, and then again for the game you start or
join, which loads its own list (a server's, a save's, a new game's). With Jumpstart the main menu loads
only ZombieBuddy; the list itself stays as it is, and your mods load once, with the game. Mod options
of mods that are not loaded are not in the main menu's options then; they are in the game. To load all
of them in the main menu again: add `-Dzombiebuddy.fullMenu=true` to the game's launch options.

## Install

| System | Download | Note |
| --- | --- | --- |
| Windows 10/11 | [Jumpstart-windows-x64.msi](https://github.com/SmokerDoker/jumpstart-pz/releases/latest/download/Jumpstart-windows-x64.msi) | for your user only, no admin rights; SmartScreen warns once (not code-signed): "More info" → "Run anyway" |
| macOS | [Apple Silicon](https://github.com/SmokerDoker/jumpstart-pz/releases/latest/download/Jumpstart-macos-arm64.dmg) · [Intel](https://github.com/SmokerDoker/jumpstart-pz/releases/latest/download/Jumpstart-macos-x64.dmg) | not notarized: open once via System Settings → Privacy & Security → "Open anyway" |
| Linux | [Debian/Ubuntu (.deb)](https://github.com/SmokerDoker/jumpstart-pz/releases/latest/download/Jumpstart-linux-x64.deb) · [Fedora/openSUSE (.rpm)](https://github.com/SmokerDoker/jumpstart-pz/releases/latest/download/Jumpstart-linux-x64.rpm) | |
| Any, incl. Steam Deck | [JumpstartSetup.jar](https://github.com/SmokerDoker/jumpstart-pz/releases/latest/download/JumpstartSetup.jar) | runs with any Java 21+, e.g. the game's own: `…/ProjectZomboid/projectzomboid/jre64/bin/java -jar JumpstartSetup.jar` |

Open **Jumpstart** and press **Install Jumpstart**. It finds the game in every Steam library and puts
in place:

- `Jumpstart.jar` in the game folder: the game's only Java agent, our
  [fork of ZombieBuddy](https://github.com/SmokerDoker/zombiebuddy-jumpstart) with Jumpstart built in,
  so you need no ZombieBuddy from the Workshop and no ZombieBuddy installer. On Windows it starts
  through the small loader `jsNative.dll` (`-agentlib:jsNative=hotswap=true` in
  `ProjectZomboid64.json`), as the game's start program cannot start a Java agent itself there; on
  macOS and Linux it is a Steam launch option (Steam has to be closed for that; the installer offers to
  close it, or copies the line for you)
- the fork's mod folder `Zomboid/mods/ZombieBuddy` (Lua and translations; other mods require
  ZombieBuddy by id), switched on in the mod list

A ZombieBuddy of your own makes room, since two agents would load every mod twice: its
`ZombieBuddy.jar` is kept as `ZombieBuddy.jar.before-jumpstart`, its start option becomes Jumpstart's
(its options stay, e.g. `policy=`), and uninstalling puts both back. Your other launch options,
BiteCheck's `--wrap` included, stay. The same window updates, repairs and removes Jumpstart; on the
command line: `JumpstartSetup.jar --install | --uninstall | --update | --status`.

**Automatic updates** (ticked by default): Jumpstart puts itself in front of the game in Steam's launch
options and, before every game start, installs the newest **signed** release and repairs what a game
update undid; then the game starts. The launch option runs `~/.jumpstart/JumpstartSetup.jar` with the
game's own Java, so the game still starts if you remove the app. Details and the signing key:
[kit/RELEASING.md](kit/RELEASING.md).

[Releases](https://github.com/SmokerDoker/jumpstart-pz/releases): tags `v*` are the signed releases the
automatic update installs; `test-kit` is rebuilt on every push to main. `SHA256SUMS.txt` lists every
file's hash.

## Roadmap

What is still to come is collected in one place: [docs/ROADMAP.md](docs/ROADMAP.md).

## Build

```sh
git submodule update --init      # BiteCheck and the ZombieBuddy fork, pinned in third_party/
./gradlew check                  # tests
# the fork's agent jar, built against the game (Gradle 9, JDK 25):
(cd third_party/zombiebuddy-jumpstart/java && gradle -PgameClasspath=<game>/projectzomboid.jar shadowJar)
# Jumpstart.jar: the fork with Jumpstart inside -> mod/build/agent/Jumpstart.jar
./gradlew :mod:agentJar -PforkJar=<fork>/java/build/jdk25/libs/zombiebuddy-jumpstart.jar
# Windows loader (MinGW): bash kit/native-loader.sh third_party/zombiebuddy-jumpstart build/native
# the installer, with the fork's mod folder (no jar) from kit/zombiebuddy-mod.sh:
bash kit/zombiebuddy-mod.sh third_party/zombiebuddy-jumpstart build/mods
./gradlew :installer:installerJar -PforkJar=<...> -PzombieBuddyMod=build/mods/ZombieBuddy -PnativeLoader=build/native/jsNative.dll
```

Needs JDK 21 to 24 with the wrapper (Gradle 8.14); with JDK 25 use Gradle 9. Project Zomboid itself
runs on Java 25. In-game tests (Windows, with Jumpstart.jar built on the fork's experimental jar, which
has a Lua test server): `tools\gametest.ps1` (also `-CrashMod`), `tools\cleanremovaltest.ps1`,
`tools\startmodtest.ps1`, `tools\servertest.ps1`.

## Repository layout

```
core/          BiteCheck gate, LiveInstaller, hotswap engine, library client, multiplayer coordinator
mod/           The Jumpstart mod for Project Zomboid (secure screen, read-only Lua API)
third_party/   BiteCheck and the ZombieBuddy fork (git submodules)
tools/         In-game tests (single player, dedicated server), test mods, a stand-in for the cloud
installer/     One-click installer (window and command line), packaged with jpackage as .msi, .dmg, .deb, .rpm
kit/           Packaging and release scripts, Windows loader, test plan and server guide
cloud/         Cloudflare Worker: mod libraries (Steam Workshop search) and voluntary reports
docs/          Roadmap, design notes and spike results
```

## License

Jumpstart is licensed under the [GNU General Public License v3.0](LICENSE), like BiteCheck, whose
verification library it embeds. `Jumpstart.jar` contains the
[ZombieBuddy fork](https://github.com/SmokerDoker/zombiebuddy-jumpstart) by Andrey "Zed" Zaikin under
the MIT license (`META-INF/LICENSE-ZombieBuddy.txt` in the jar); the jar as a whole is GPL-3.0
(`META-INF/LICENSE-Jumpstart.txt`).
