# Jumpstart on a dedicated server

Hosting from the game's main menu ("Host") needs nothing of this: Jumpstart.jar starts the hosted
server with itself (ZombieBuddy and Jumpstart in one jar). This is for dedicated servers (SteamCMD app
380870, or "Project Zomboid Dedicated Server" in Steam).

Players who join need Jumpstart too (the one-click installer). Nobody needs ZombieBuddy from the
Workshop: the fork is in Jumpstart.jar, its mod folder comes along, on the server and on every client.

## Recommended: JumpstartSetup.jar with the server's own Java

`JumpstartSetup.jar` is one file and runs with the Java that comes with the server (`jre64`), on
Linux and Windows. It puts in place:

- `Jumpstart.jar` (the ZombieBuddy fork with Jumpstart built in) as the only Java agent (Linux:
  `java/Jumpstart.jar` and `-javaagent` in `ProjectZomboid64.json`; Windows: `Jumpstart.jar` and
  `-javaagent` in `StartServer64.bat`); a ZombieBuddy agent there is taken out
- the fork's mod folder `ZombieBuddy` (Lua, no jar) in the server's `Zomboid/mods`
- `ZombieBuddy` in `Mods=` of the server's `.ini` (no `WorkshopItems=` entry needed; Jumpstart is no
  mod of its own any more, an old `Jumpstart` entry goes)
- with `--headless`: ZombieBuddy's `policy=deny-new` (a server without console cannot answer
  questions). Jumpstart itself needs no approval: it is the agent, not a mod.

A server set up before 0.2 (the fork as `ZombieBuddy.jar`, a `Jumpstart` mod folder) is moved to this
layout by the next `--update` or install.

### Linux (systemd)

```sh
sudo mkdir -p /opt/jumpstart
sudo curl -fsSL -o /opt/jumpstart/JumpstartSetup.jar \
  https://github.com/SmokerDoker/jumpstart-pz/releases/latest/download/JumpstartSetup.jar
sudo chown -R <server user> /opt/jumpstart     # so the jar can update itself

# once, as the user the server runs as:
<server>/jre64/bin/java -jar /opt/jumpstart/JumpstartSetup.jar --server <server> --name <servername> --headless
```

With a data folder (`-cachedir=...`): add `--zomboid-dir <folder>`; ZombieBuddy's own options:
`--agent-args "hotswap=true,policy=deny-new,config_dir=<folder>/.zombie_buddy"`.

**Automatic updates and repair before every start**: a drop-in for the server's unit
(`systemctl edit <unit>`):

```ini
[Service]
ExecStartPre=-<server>/jre64/bin/java -jar /opt/jumpstart/JumpstartSetup.jar --server <server> --name <servername> --headless --update
```

Before every start it installs a newer **signed** Jumpstart release (if there is one), replaces
`JumpstartSetup.jar` for the next run, and puts the `-javaagent` back into `ProjectZomboid64.json` if a
game update through SteamCMD replaced the file. The `-` in front keeps the server starting if anything
goes wrong (for example no network). If SteamCMD runs in `ExecStartPre` too, put this line after it.

The setup writes this drop-in itself with `--systemd <unit>` (as root; it runs
`systemctl daemon-reload`): `/etc/systemd/system/<unit>.service.d/jumpstart.conf`, with the same
options as the install. systemd runs drop-in lines after the unit's own `ExecStartPre`, so after a
SteamCMD update there.

### A game update through SteamCMD

SteamCMD replaces the game's start files with every game update (`ProjectZomboid64.json` on Linux,
`StartServer64.bat` on Windows); Jumpstart's `-javaagent` is gone then, and the server starts without
Jumpstart. Every install and `--update` therefore also writes a start script of Jumpstart's own next
to the game's, which SteamCMD leaves alone:

- Linux: `start-server-jumpstart.sh` (runs the setup with `--update`, then `./start-server.sh "$@"`)
- Windows: `StartServer64-Jumpstart.bat` (the same, then `StartServer64.bat %*`)

Start the server with that script instead of the game's, or use the systemd drop-in above. A start
script of your own (or a wrapper like a wake-on-connect service) can run the same `--update` line
first instead.

### Windows

In PowerShell, in the server folder (`...\steamapps\common\Project Zomboid Dedicated Server`):

```powershell
Invoke-WebRequest -UseBasicParsing https://github.com/SmokerDoker/jumpstart-pz/releases/latest/download/JumpstartSetup.jar -OutFile JumpstartSetup.jar
.\jre64\bin\java.exe -jar JumpstartSetup.jar --server . --name servertest
```

Updates and repair: start the server with `StartServer64-Jumpstart.bat` (see above), or run the same
line with `--update` before starting it. On Windows the jar does not replace itself; download it again
for a new installer version.

### After installing

1. First start without `--headless`, in a console: ZombieBuddy asks once per new Java mod jar (answer `y`).
2. The log shows `[Jumpstart] multiplayer: server side ready; Java mods for the clients: [...]`.
3. Give yourself admin rights (`setaccesslevel <name> admin` in the server console). Only admins switch
   Java mods in multiplayer: in the game, Ctrl+Shift+J, tab **Server**, Enter = load/update for
   everybody, A = off/on for everybody, U = remove, X twice = off now for everybody (emergency, see
   below). Every other player has to agree; whoever says no or is not ready within 3 minutes is
   disconnected. Install: from the Java or Lua library tab (Enter); the server downloads the item first.
   A Workshop item with several mods (variants like 60 / 80 %, or a mod with add-ons) shows its mods
   in the Server tab: choose the one to install (Enter), Backspace cancels.

### Joining

Every player who joins tells the server which Java mods run on their side, with their hashes. Where
they differ from the server's, the player's game follows the server one mod at a time: it fetches the
server's version (BiteCheck checks it, the player answers its questions), switches mods on or off, and
switches off Java mods the server does not run. Whoever cannot follow in time is disconnected. Lua mods
are checked by the game itself when a player joins. Who joins while a change for everybody runs takes
part in it, or is compared right after it.

### How a change for everybody runs

1. **Getting ready, the game goes on.** Every player gets a line at the top of the screen, opens
   Jumpstart (Ctrl+Shift+J) and agrees; their game fetches the server's exact jar and BiteCheck checks
   it. Nothing changes yet.
2. **The change, the world stops.** When everybody is ready (or the time is up, then whoever is not
   ready is disconnected), the server stops its world (zombies, time, players; the network keeps
   running) and changes the mod first. If that fails, nobody changes anything and the game goes on.
   Else every player changes it too; whoever cannot is disconnected. Then the world runs again.

The time for step 1 is 3 minutes. To change it (30 to 900 seconds), put
`server_countdown_seconds = 120` into BiteCheck's `config.toml` on the server, or start the server
with `-Djumpstart.countdown=120`. The world never stays stopped longer than 3 minutes.

After an install or a remove, every player's game works out the game's Lua checksum again and the
server compares it with its own (whose differs is disconnected; the game would turn them away at their
next join anyway). The server works its own out again too, so players who join later are not turned
away with "Lua checksum" for a mod installed live. At its start the server checks that it works the
checksum out exactly as the game does (log: `Lua checksum: worked out again, same as the game's`); if
not, it leaves the game's alone, and after a live install a restart is the safe way for later players.
Item scripts and animations of a live-installed mod still load only with the next start of the server
(the game checks their checksums too: restart the server after installing such a mod).

### Snapshots

At the start of every change for everybody (not an emergency switch-off), the server saves its world
and copies the save into `<data folder>/jumpstart/snapshots/Multiplayer_<servername>/<time>.zip` (the
last 5), while the game goes on; step 2 waits for the copy (at most 2 minutes more). Setting:
`snapshots=auto|on|off` in `<data folder>/jumpstart/settings.properties`; `auto` (the default) skips
saves of 500 MB or more. To go back: stop the server, then

```sh
<server>/jre64/bin/java -jar JumpstartSetup.jar --server <server> --name <servername> --snapshots
<server>/jre64/bin/java -jar JumpstartSetup.jar --server <server> --name <servername> --restore-snapshot latest
```

(`--zomboid-dir <data folder>` as for the install; a file name from `--snapshots` instead of `latest`).
The save it replaces moves next to the snapshots (`replaced-<time>`), nothing is deleted. By hand:
move `Saves/Multiplayer/<servername>` away and unzip the snapshot into `Saves/`.

### Emergency switch-off

A mod that breaks the game, or that BiteCheck blocks, has to go now. An admin presses X twice on it in
the Server tab: everybody switches it off within 20 seconds, nobody is asked, no snapshot; whoever
cannot is disconnected. A change that runs already finishes first. The server does the same by itself:
every 15 minutes it fetches BiteCheck's index again, and a running Java mod that is blocked now (its key
revoked, found to be malware) goes off for everybody. A mod that is only flagged is written to the log;
an admin decides.

Remove: the same line with `--uninstall` instead of `--headless --update`.
