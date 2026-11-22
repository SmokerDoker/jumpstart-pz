#!/usr/bin/env bash
# Jumpstart for a dedicated Linux server (SteamCMD app 380870). Run in the server folder or pass it:
#   bash install-server.sh /path/to/pzserver [servername]   (--uninstall as second argument to undo)
# With a server name (default: the only ~/Zomboid/Server/*.ini, if there is just one) it also puts
# ZombieBuddy and Jumpstart into that server's .ini (Mods= and WorkshopItems=).
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

inis="$HOME/Zomboid/Server"
name="${2:-}"
if [ -z "$name" ] && [ -d "$inis" ] && [ "$(find "$inis" -maxdepth 1 -name '*.ini' | wc -l)" -eq 1 ]; then
  name="$(basename "$(find "$inis" -maxdepth 1 -name '*.ini')" .ini)"
fi
ini="$inis/$name.ini"
if [ -n "$name" ] && [ ! -f "$ini" ]; then
  # a new server: the game fills in every other option on its first start
  mkdir -p "$inis" && printf 'Mods=\nWorkshopItems=\n' > "$ini"
fi
if [ -n "$name" ] && [ -f "$ini" ]; then
  [ -f "$ini.before-jumpstart" ] || cp "$ini" "$ini.before-jumpstart"
  # adds entries to a ;-list once, in the style of the list (B42 lists may write mods as \Id)
  awk '
    function add(line, key, items,    v, n, parts, i, j, have, sep, pre, out) {
      v = substr(line, length(key) + 2); n = split(v, parts, ";"); out = v
      pre = (key == "Mods" && v ~ /(^|;)\\/) ? "\\" : ""
      for (i = 1; i <= split(items, want, ","); i++) {
        have = 0
        for (j = 1; j <= n; j++) { p = parts[j]; sub(/^\\/, "", p); if (p == want[i]) have = 1 }
        if (!have) { sep = (out == "") ? "" : ";"; out = out sep pre want[i] }
      }
      return key "=" out
    }
    /^Mods=/ { print add($0, "Mods", "ZombieBuddy,Jumpstart"); next }
    /^WorkshopItems=/ { print add($0, "WorkshopItems", "3619862853"); next }
    { print }' "$ini" > "$ini.tmp" && mv "$ini.tmp" "$ini"
  echo "ZombieBuddy and Jumpstart added to $ini:"
  grep -E '^(Mods|WorkshopItems)=' "$ini" | sed 's/^/    /'
else
  echo "Now: Mods=...;ZombieBuddy;Jumpstart and WorkshopItems=...;3619862853 in ~/Zomboid/Server/<name>.ini (see SERVER.md),"
  echo "or run this again with the server name: bash install-server.sh $dir <name>"
fi
echo "First start in a console: ZombieBuddy asks there once per new Java jar (answer y)."
