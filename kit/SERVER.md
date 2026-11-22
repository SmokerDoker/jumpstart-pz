# Jumpstart on a dedicated server

Hosting from the game's main menu ("Host") needs nothing of this: the ZombieBuddy fork starts the
hosted server with ZombieBuddy and Jumpstart by itself. This is for dedicated servers.

1. Install, one step:
   - Linux: `curl -fsSL https://raw.githubusercontent.com/SmokerDoker/jumpstart-pz/main/kit/get.sh | bash -s -- server /path/to/server [servername]`
   - Windows (PowerShell): `$env:JUMPSTART_SERVER = "C:\path\to\server"; $env:JUMPSTART_SERVER_NAME = "servertest"; irm https://raw.githubusercontent.com/SmokerDoker/jumpstart-pz/main/kit/get.ps1 | iex`
   - or from this package: `bash install-server.sh /path/to/server [servername]`, on Windows double-click
     `Server einrichten.cmd` (Steam's "Project Zomboid Dedicated Server" folder; another one:
     `Server einrichten.cmd -ServerDir "D:\pzserver" -ServerName myserver`).
2. With a server name (or when there is just one `.ini` in `~/Zomboid/Server`, Windows
   `%USERPROFILE%\Zomboid\Server`) the installer adds `ZombieBuddy;Jumpstart` to `Mods=` and
   `3619862853` (ZombieBuddy) to `WorkshopItems=`; a new name creates the `.ini`. Add your own Java
   mods there as usual.
3. First start in a console: ZombieBuddy asks there once per new Java jar whether it may load
   (answer `y` for Jumpstart and your mods). The answers are kept in `~/.zombie_buddy/mod_approvals.json`.
4. The log shows `[Jumpstart] multiplayer: server side ready; Java mods for the clients: [...]`.
5. Give yourself admin rights in the game (`setaccesslevel <name> admin` in the server console).
   Only admins switch Java mods in multiplayer: in the game, Ctrl+Shift+J, tab **Server**,
   Enter = load/update for everybody, A = off/on for everybody. Every other player has to agree;
   whoever says no or is not ready within 3 minutes is disconnected.

Clients need the client kit (Windows, macOS or Linux) for Jumpstart and the ZombieBuddy fork.
