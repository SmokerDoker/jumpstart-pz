@echo off
rem Double-click: puts Jumpstart and the ZombieBuddy fork into the Project Zomboid Dedicated Server (Steam).
rem Another folder or server name: Server einrichten.cmd -ServerDir "D:\pzserver" -ServerName myserver
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-server.ps1" %*
echo.
pause
