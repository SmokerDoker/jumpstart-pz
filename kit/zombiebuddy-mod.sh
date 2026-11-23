#!/usr/bin/env bash
# Builds the ZombieBuddy mod folder of the Jumpstart fork: what the Workshop item 3619862853 holds
# (mod.info, Lua, translations, libs/ZombieBuddy.jar), but with the fork's jar. Jumpstart ships it
# into Zomboid/mods, so players need no Workshop subscription and no ZombieBuddy installer.
#   bash kit/zombiebuddy-mod.sh <fork checkout> <fork jar> <output folder>   -> <output folder>/ZombieBuddy
set -euo pipefail
[ -f "$1/42/mod.info" ] || { echo "no ZombieBuddy checkout (42/mod.info): $1"; exit 1; }
[ -f "$2" ] || { echo "no ZombieBuddy fork jar: $2"; exit 1; }
src="$(cd "$1" && pwd)"
jar="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
mkdir -p "$3"
out="$(cd "$3" && pwd)/ZombieBuddy"
rm -rf "$out"
mkdir -p "$out/libs"
cp -R "$src/42" "$out/42" # 41 is only a link to 42 (Build 41 is not supported)
cp -R "$src/common" "$out/common"
cp "$src/LICENSE.txt" "$src/FORK.md" "$src/42/icon_128.png" "$src/42/icon_256.png" "$out/"
cp "$jar" "$out/libs/ZombieBuddy.jar"
# the fork's name and address, the normal icons instead of upstream's beta ones
sed -i.orig \
  -e 's/^name=.*/name=ZombieBuddy (Jumpstart fork)/' \
  -e 's#^url=.*#url=https://github.com/SmokerDoker/zombiebuddy-jumpstart#' \
  -e 's/^poster=.*/poster=icon_256.png/' \
  -e 's/^icon=.*/icon=icon_128.png/' \
  "$out/42/mod.info"
rm -f "$out/42/mod.info.orig"
# the marker Jumpstart's installers look for: only a folder with it is replaced or removed by them
cat > "$out/jumpstart-fork.txt" <<'EOF'
ZombieBuddy, Jumpstart fork: installed by Jumpstart (https://github.com/SmokerDoker/jumpstart-pz).
Source: https://github.com/SmokerDoker/zombiebuddy-jumpstart (branch jumpstart/hotswap), MIT license.
Jumpstart's installer replaces and removes this folder; delete this file to keep it untouched.
EOF
grep -q '^id=ZombieBuddy$' "$out/42/mod.info" || { echo "mod.info has no id=ZombieBuddy"; exit 1; }
echo "$out"
