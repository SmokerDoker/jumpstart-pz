#!/usr/bin/env bash
# Jumpstart test kit for macOS and Linux (Ubuntu): puts the ZombieBuddy fork and the Jumpstart mod
# in place. Run:  bash install-unix.sh            (bash install-unix.sh --uninstall to undo)
# The Steam launch options must then be:   -javaagent:ZombieBuddy.jar=hotswap=true --
set -euo pipefail
kit="$(cd "$(dirname "$0")" && pwd)"
case "$(uname -s)" in
  Darwin) candidates=("$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java") ;;
  *)      candidates=("$HOME/.steam/steam/steamapps/common/ProjectZomboid/projectzomboid"
                      "$HOME/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid"
                      "$HOME/.var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid"
                      "$HOME/snap/steam/common/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid") ;;
esac
game="${PZ_JAVA_DIR:-}"
if [ -z "$game" ]; then
  for c in "${candidates[@]}"; do if [ -f "$c/projectzomboid.jar" ]; then game="$c"; break; fi; done
fi
[ -n "$game" ] || { echo "Project Zomboid's Java folder (with projectzomboid.jar) not found; run again with PZ_JAVA_DIR=/path/to/it"; exit 1; }
mods="$HOME/Zomboid/mods"

if [ "${1:-}" = "--uninstall" ]; then
  [ -f "$game/ZombieBuddy.jar.before-jumpstart" ] && mv -f "$game/ZombieBuddy.jar.before-jumpstart" "$game/ZombieBuddy.jar" && echo "ZombieBuddy.jar restored"
  rm -rf "$mods/Jumpstart" && echo "Jumpstart mod removed"
  echo "Remove hotswap=true from the Steam launch options yourself."
  exit 0
fi

if [ -f "$game/ZombieBuddy.jar" ] && [ ! -f "$game/ZombieBuddy.jar.before-jumpstart" ]; then
  cp "$game/ZombieBuddy.jar" "$game/ZombieBuddy.jar.before-jumpstart"
fi
cp "$kit/ZombieBuddy.jar" "$game/ZombieBuddy.jar"
echo "ZombieBuddy.jar (Jumpstart fork) -> $game"
mkdir -p "$mods"
rm -rf "$mods/Jumpstart"
cp -R "$kit/Jumpstart" "$mods/Jumpstart"
echo "Jumpstart mod -> $mods/Jumpstart"
echo
echo "Now in Steam: Project Zomboid > Properties > Launch Options:"
echo "    -javaagent:ZombieBuddy.jar=hotswap=true --"
echo "In the game: enable ZombieBuddy and Jumpstart in the mod menu; ZombieBuddy asks once whether"
echo "Jumpstart.jar may load (say yes). Then Ctrl+Shift+J opens Jumpstart's screen."
