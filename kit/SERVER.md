# Jumpstart on a dedicated server

Hosting from the game's main menu ("Host") needs nothing of this: the ZombieBuddy fork starts the
hosted server with ZombieBuddy and Jumpstart by itself. This is for dedicated servers.

1. Install: Linux `bash install-server.sh /path/to/server`, Windows
   `powershell -ExecutionPolicy Bypass -File install-server.ps1 -ServerDir "<server folder>"`.
2. In the server's `.ini` (`~/Zomboid/Server/<name>.ini`, Windows `%USERPROFILE%\Zomboid\Server\<name>.ini`):
   - `Mods=` add `ZombieBuddy;Jumpstart` (plus your Java mods)
   - `WorkshopItems=` add `3619862853` (ZombieBuddy) plus the Workshop ids of your mods
3. First start in a console: ZombieBuddy asks there once per new Java jar whether it may load
   (answer `y` for Jumpstart and your mods). The answers are kept in `~/.zombie_buddy/mod_approvals.json`.
4. The log shows `[Jumpstart] multiplayer: server side ready; Java mods for the clients: [...]`.
5. Give yourself admin rights in the game (`setaccesslevel <name> admin` in the server console).
   Only admins switch Java mods in multiplayer: in the game, Ctrl+Shift+J, tab **Server**,
   Enter = load/update for everybody, A = off/on for everybody. Every other player has to agree;
   whoever says no or is not ready within 3 minutes is disconnected.

Clients need the client kit (Windows, macOS or Linux) for Jumpstart and the ZombieBuddy fork.
