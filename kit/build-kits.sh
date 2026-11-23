#!/usr/bin/env bash
# Builds the four download packages: Windows, macOS and Linux clients, and dedicated servers.
#   bash kit/build-kits.sh <ZombieBuddy mod folder> <Jumpstart mod folder> <output folder> [version]
# The ZombieBuddy mod folder comes from kit/zombiebuddy-mod.sh and holds the fork's jar in libs/.
set -euo pipefail
shopt -s inherit_errexit
[ -f "$1/42/mod.info" ] && [ -f "$1/libs/ZombieBuddy.jar" ] || { echo "no ZombieBuddy mod folder (kit/zombiebuddy-mod.sh): $1"; exit 1; }
[ -f "$2/42/mod.info" ] || { echo "no Jumpstart mod folder: $2"; exit 1; }
zb="$(cd "$1" && pwd)"
mod="$(cd "$2" && pwd)"
out="$(mkdir -p "$3" && cd "$3" && pwd)"
version="${4:-dev}"
kit="$(cd "$(dirname "$0")" && pwd)"
stage="$(mktemp -d)"
make() { # name, files...
  local name="$1"; shift
  local d="$stage/jumpstart-$name-$version"
  mkdir -p "$d"
  cp -R "$zb" "$d/ZombieBuddy"
  cp -R "$mod" "$d/Jumpstart"
  for f in "$@"; do cp "$kit/$f" "$d/"; done
  echo "$d"
}
w="$(make windows install-windows.ps1 "Jumpstart installieren.cmd" TESTPLAN.md)"
(cd "$stage" && zip -qr "$out/$(basename "$w").zip" "$(basename "$w")")
for os in macos linux; do
  d="$(make "$os" install-unix.sh "Jumpstart installieren.command" TESTPLAN.md)"
  chmod +x "$d/install-unix.sh" "$d/Jumpstart installieren.command"
  (cd "$stage" && tar -czf "$out/$(basename "$d").tar.gz" "$(basename "$d")")
done
s="$(make server install-server.sh install-server.ps1 "Server einrichten.cmd" SERVER.md)"
chmod +x "$s/install-server.sh"
(cd "$stage" && zip -qr "$out/$(basename "$s").zip" "$(basename "$s")")
rm -rf "$stage"
(cd "$out" && sha256sum jumpstart-* > SHA256SUMS.txt && ls -l)
for f in "$out"/jumpstart-*; do # every package must have all three parts
  case "$f" in
    *.zip) list="$(unzip -l "$f")" ;;
    *.tar.gz) list="$(tar -tzf "$f")" ;;
    *) continue ;;
  esac
  # a listing in a variable, not a pipe into grep -q: that would end tar early (pipefail)
  [[ "$list" == *ZombieBuddy/libs/ZombieBuddy.jar* && "$list" == *ZombieBuddy/42/mod.info* && "$list" == *Jumpstart.jar* ]] \
    || { echo "incomplete package: $f"; exit 1; }
done
