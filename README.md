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
- [ ] BiteCheck gate

## Build

```sh
./gradlew check
```

Needs JDK 21 or newer. Project Zomboid itself runs on Java 25.

## Repository layout

```
core/     Hotswap engine (planned next to it: bootstrap, market, mp)
docs/     Design notes and spike results
```

## License

Jumpstart is licensed under the [GNU General Public License v3.0](LICENSE), like BiteCheck,
whose verification library it will embed.
