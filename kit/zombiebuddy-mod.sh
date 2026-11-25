#!/usr/bin/env bash
# Builds the ZombieBuddy mod folder of the Jumpstart fork: what the Workshop item 3619862853 holds
# (mod.info, Lua, translations), but without a jar: the fork's code is in Jumpstart.jar, the game's
# Java agent, and ZombieBuddy never loads its own mod folder's jar anyway. The folder stays because
# other mods require ZombieBuddy by id. Jumpstart ships it into Zomboid/mods, so players need no
# Workshop subscription and no ZombieBuddy installer.
#   bash kit/zombiebuddy-mod.sh <fork checkout> <output folder>   -> <output folder>/ZombieBuddy
set -euo pipefail
[ -f "$1/42/mod.info" ] || { echo "no ZombieBuddy checkout (42/mod.info): $1"; exit 1; }
src="$(cd "$1" && pwd)"
mkdir -p "$2"
out="$(cd "$2" && pwd)/ZombieBuddy"
rm -rf "$out"
mkdir -p "$out"
cp -R "$src/42" "$out/42" # 41 is only a link to 42 (Build 41 is not supported)
cp -R "$src/common" "$out/common"
cp "$src/LICENSE.txt" "$src/FORK.md" "$src/42/icon_128.png" "$src/42/icon_256.png" "$out/"
# the fork's name and address, the normal icons instead of upstream's beta ones; no Java part
sed -i.orig   -e '/^javaJarFile=/d'   -e '/^javaPkgName=/d' \
  -e 's/^name=.*/name=ZombieBuddy (Jumpstart fork)/' \
  -e 's#^url=.*#url=https://github.com/SmokerDoker/zombiebuddy-jumpstart#' \
  -e 's/^poster=.*/poster=icon_256.png/' \
  -e 's/^icon=.*/icon=icon_128.png/' \
  "$out/42/mod.info"
rm -f "$out/42/mod.info.orig"
# the marker Jumpstart's installers look for: only a folder with it is replaced or removed by them
cat > "$out/jumpstart-fork.txt" <<'EOF'
ZombieBuddy, Jumpstart fork: installed by Jumpstart (https://github.com/SmokerDoker/jumpstart-pz).
Its Java part is in Jumpstart.jar in the game folder; this folder holds its Lua and translations.
Source: https://github.com/SmokerDoker/zombiebuddy-jumpstart (branch jumpstart/hotswap), MIT license.
Jumpstart's installer replaces and removes this folder; delete this file to keep it untouched.
EOF
grep -q '^id=ZombieBuddy$' "$out/42/mod.info" || { echo "mod.info has no id=ZombieBuddy"; exit 1; }
! grep -q '^javaJarFile=' "$out/42/mod.info" || { echo "mod.info still names a jar"; exit 1; }
echo "$out"
