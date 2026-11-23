#!/usr/bin/env bash
# Jumpstart test kit for macOS and Linux (Ubuntu): puts the ZombieBuddy fork (agent jar and mod
# folder) and the Jumpstart mod in place; no ZombieBuddy from the Workshop needed. Run:  bash install-unix.sh            (bash install-unix.sh --uninstall to undo)
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
zb_backup="$HOME/Zomboid/jumpstart-backup/ZombieBuddy"

if [ "${1:-}" = "--uninstall" ]; then
  [ -f "$game/ZombieBuddy.jar.before-jumpstart" ] && mv -f "$game/ZombieBuddy.jar.before-jumpstart" "$game/ZombieBuddy.jar" && echo "ZombieBuddy.jar restored"
  rm -rf "$mods/Jumpstart" && echo "Jumpstart mod removed"
  [ -f "$mods/ZombieBuddy/jumpstart-fork.txt" ] && rm -rf "$mods/ZombieBuddy" && echo "ZombieBuddy mod (fork) removed"
  if [ -d "$zb_backup" ] && [ ! -e "$mods/ZombieBuddy" ]; then mv "$zb_backup" "$mods/ZombieBuddy" && echo "your own ZombieBuddy mod folder is back"; fi
  echo "Remove hotswap=true from the Steam launch options yourself."
  exit 0
fi

if [ -f "$game/ZombieBuddy.jar" ] && [ ! -f "$game/ZombieBuddy.jar.before-jumpstart" ]; then
  cp "$game/ZombieBuddy.jar" "$game/ZombieBuddy.jar.before-jumpstart"
fi
cp "$kit/ZombieBuddy/libs/ZombieBuddy.jar" "$game/ZombieBuddy.jar"
echo "ZombieBuddy.jar (Jumpstart fork) -> $game"
mkdir -p "$mods"
rm -rf "$mods/Jumpstart"
cp -R "$kit/Jumpstart" "$mods/Jumpstart"
echo "Jumpstart mod -> $mods/Jumpstart"
# ZombieBuddy's mod folder (the fork), so no Workshop subscription is needed. A ZombieBuddy folder of
# your own (without jumpstart-fork.txt) moves to ~/Zomboid/jumpstart-backup and comes back with --uninstall.
if [ -d "$mods/ZombieBuddy" ] && [ ! -f "$mods/ZombieBuddy/jumpstart-fork.txt" ]; then
  target="$zb_backup"; [ -e "$target" ] && target="$zb_backup-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$(dirname "$zb_backup")" && mv "$mods/ZombieBuddy" "$target"
  echo "your own ZombieBuddy mod folder moved to $target"
fi
rm -rf "$mods/ZombieBuddy"
cp -R "$kit/ZombieBuddy" "$mods/ZombieBuddy"
echo "ZombieBuddy mod (Jumpstart fork) -> $mods/ZombieBuddy"

# ZombieBuddy and Jumpstart on in the main menu's mod list (a copy of the old list stays next to it)
list="$mods/default.txt"
if [ ! -f "$list" ]; then
  printf 'VERSION = 1,\n\nmods\n{\n}\n\nmaps\n{\n}\n' > "$list"
fi
for id in Jumpstart ZombieBuddy; do # inserted at the top: ZombieBuddy ends up first
  if ! grep -Fq "mod = $id," "$list" && ! grep -Fq "mod = \\$id," "$list"; then
    [ -f "$list.before-jumpstart" ] || cp "$list" "$list.before-jumpstart"
    awk -v id="$id" '{print} /^mods/{m=1} m && /^\{/{print "    mod = " id ","; m=0}' "$list" > "$list.tmp" && mv "$list.tmp" "$list"
  fi
done
echo "ZombieBuddy and Jumpstart enabled in $list"

option="-javaagent:ZombieBuddy.jar=hotswap=true --"
case "$(uname -s)" in
  Darwin) printf '%s' "$option" | pbcopy 2>/dev/null && copied=1 || copied= ;;
  *)      if command -v wl-copy >/dev/null; then printf '%s' "$option" | wl-copy && copied=1 || copied=
          elif command -v xclip >/dev/null; then printf '%s' "$option" | xclip -selection clipboard && copied=1 || copied=
          else copied=; fi ;;
esac
echo
echo "One step left, in Steam: Project Zomboid > Properties > General > Launch Options:"
echo "    $option"
[ -n "$copied" ] && echo "(already in your clipboard: just paste it)"
echo
echo "Then start the game. ZombieBuddy asks once whether Jumpstart.jar may load: say yes."
echo "Ctrl+Shift+J opens Jumpstart's screen."
