#!/usr/bin/env bash
# Jumpstart for a dedicated Linux server (SteamCMD app 380870). Run in the server folder or pass it:
#   bash install-server.sh /path/to/pzserver        (--uninstall as second argument to undo)
# Then add the mods to your server's .ini (see SERVER.md).
set -euo pipefail
kit="$(cd "$(dirname "$0")" && pwd)"
dir="$(cd "${1:-.}" && pwd)"
json="$dir/ProjectZomboid64.json"
[ -f "$json" ] && [ -d "$dir/java" ] || { echo "no Project Zomboid server in $dir (ProjectZomboid64.json and java/ expected)"; exit 1; }
mods="${HOME}/Zomboid/mods"
if [ "${2:-}" = "--uninstall" ]; then
  [ -f "$json.before-jumpstart" ] && mv -f "$json.before-jumpstart" "$json" && echo "ProjectZomboid64.json restored"
  rm -f "$dir/java/ZombieBuddy.jar"; rm -rf "$mods/Jumpstart"; echo "removed"; exit 0
fi
cp "$kit/ZombieBuddy.jar" "$dir/java/ZombieBuddy.jar"
if ! grep -q 'ZombieBuddy.jar' "$json"; then
  cp "$json" "$json.before-jumpstart"
  sed -i "s#\"vmArgs\": *\[#\"vmArgs\": [\n\t\t\"-javaagent:$dir/java/ZombieBuddy.jar=hotswap=true\",#" "$json"
fi
grep -q 'ZombieBuddy.jar' "$json" || { echo "could not add -javaagent to $json; add it to vmArgs by hand"; exit 1; }
mkdir -p "$mods"; rm -rf "$mods/Jumpstart"; cp -R "$kit/Jumpstart" "$mods/Jumpstart"
echo "ZombieBuddy (Jumpstart fork) -> $dir/java, -javaagent in ProjectZomboid64.json, Jumpstart -> $mods/Jumpstart"
echo "Now: Mods=...;ZombieBuddy;Jumpstart and WorkshopItems=...;3619862853 in ~/Zomboid/Server/<name>.ini (see SERVER.md)"
