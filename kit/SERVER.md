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

### Windows

In PowerShell, in the server folder (`...\steamapps\common\Project Zomboid Dedicated Server`):

```powershell
Invoke-WebRequest -UseBasicParsing https://github.com/SmokerDoker/jumpstart-pz/releases/latest/download/JumpstartSetup.jar -OutFile JumpstartSetup.jar
.\jre64\bin\java.exe -jar JumpstartSetup.jar --server . --name servertest
```

Updates and repair: run the same line with `--update` before starting the server (for example as the
first line of your own start script). On Windows the jar does not replace itself; download it again
for a new installer version.

### After installing

1. First start without `--headless`, in a console: ZombieBuddy asks once per new Java mod jar (answer `y`).
2. The log shows `[Jumpstart] multiplayer: server side ready; Java mods for the clients: [...]`.
3. Give yourself admin rights (`setaccesslevel <name> admin` in the server console). Only admins switch
   Java mods in multiplayer: in the game, Ctrl+Shift+J, tab **Server**, Enter = load/update for
   everybody, A = off/on for everybody, U = remove. Every other player has to agree; whoever says no or
   is not ready within 3 minutes is disconnected.

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

Remove: the same line with `--uninstall` instead of `--headless --update`.
