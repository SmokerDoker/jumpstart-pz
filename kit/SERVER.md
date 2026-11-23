# Jumpstart on a dedicated server

Hosting from the game's main menu ("Host") needs nothing of this: the ZombieBuddy fork starts the
hosted server with ZombieBuddy and Jumpstart by itself. This is for dedicated servers (SteamCMD app
380870, or "Project Zomboid Dedicated Server" in Steam).

Players who join need Jumpstart too (the one-click installer). Nobody needs ZombieBuddy from the
Workshop: the fork comes along as mod folder, on the server and on every client.

## Recommended: JumpstartSetup.jar with the server's own Java

`JumpstartSetup.jar` is one file and runs with the Java that comes with the server (`jre64`), on
Linux and Windows. It puts in place:

- the fork as Java agent (Linux: `java/ZombieBuddy.jar` and `-javaagent` in `ProjectZomboid64.json`;
  Windows: `ZombieBuddy.jar` and `-javaagent` in `StartServer64.bat`)
- the mod folders `ZombieBuddy` (fork) and `Jumpstart` in the server's `Zomboid/mods`
- `ZombieBuddy;Jumpstart` in `Mods=` of the server's `.ini` (no `WorkshopItems=` entry needed)
- with `--headless`: ZombieBuddy's `policy=deny-new` (a server without console cannot answer
  questions) and Jumpstart.jar approved ahead in `mod_approvals.json`

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

1. First start without `--headless`, in a console: ZombieBuddy asks once per new Java jar (answer `y`).
2. The log shows `[Jumpstart] multiplayer: server side ready; Java mods for the clients: [...]`.
3. Give yourself admin rights (`setaccesslevel <name> admin` in the server console). Only admins switch
   Java mods in multiplayer: in the game, Ctrl+Shift+J, tab **Server**, Enter = load/update for
   everybody, A = off/on for everybody, U = remove. Every other player has to agree; whoever says no or
   is not ready within 3 minutes is disconnected.

Remove: the same line with `--uninstall` instead of `--headless --update`.

## Alternative: the server kit (scripts)

`jumpstart-server-<version>.zip` from the releases, or in one step:

- Linux: `curl -fsSL https://raw.githubusercontent.com/SmokerDoker/jumpstart-pz/main/kit/get.sh | bash -s -- server /path/to/server [servername]`
- Windows (PowerShell): `$env:JUMPSTART_SERVER = "C:\path\to\server"; $env:JUMPSTART_SERVER_NAME = "servertest"; irm https://raw.githubusercontent.com/SmokerDoker/jumpstart-pz/main/kit/get.ps1 | iex`
- or from the package: `bash install-server.sh /path/to/server [servername]`, on Windows double-click
  `Server einrichten.cmd` (another folder or name: `Server einrichten.cmd -ServerDir "D:\pzserver" -ServerName myserver`).

The scripts do the same as the jar without `--headless` and without updates.
