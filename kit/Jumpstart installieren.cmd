@echo off
rem Double-click: installs Jumpstart and the ZombieBuddy fork into Project Zomboid (Steam).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-windows.ps1" %*
echo.
pause
