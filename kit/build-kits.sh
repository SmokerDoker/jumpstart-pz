#!/usr/bin/env bash
# Builds the four download packages: Windows, macOS and Linux clients, and dedicated servers.
#   bash kit/build-kits.sh <ZombieBuddy fork jar> <Jumpstart mod folder> <output folder> [version]
set -euo pipefail
jar="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
mod="$(cd "$2" && pwd)"
out="$(mkdir -p "$3" && cd "$3" && pwd)"
version="${4:-dev}"
kit="$(cd "$(dirname "$0")" && pwd)"
stage="$(mktemp -d)"
make() { # name, files...
  local name="$1"; shift
  local d="$stage/jumpstart-$name-$version"
  mkdir -p "$d"
  cp "$jar" "$d/ZombieBuddy.jar"
  cp -R "$mod" "$d/Jumpstart"
  for f in "$@"; do cp "$kit/$f" "$d/"; done
  echo "$d"
}
w="$(make windows install-windows.ps1 TESTPLAN.md)"
(cd "$stage" && zip -qr "$out/$(basename "$w").zip" "$(basename "$w")")
for os in macos linux; do
  d="$(make "$os" install-unix.sh TESTPLAN.md)"
  chmod +x "$d/install-unix.sh"
  (cd "$stage" && tar -czf "$out/$(basename "$d").tar.gz" "$(basename "$d")")
done
s="$(make server install-server.sh install-server.ps1 SERVER.md)"
chmod +x "$s/install-server.sh"
(cd "$stage" && zip -qr "$out/$(basename "$s").zip" "$(basename "$s")")
rm -rf "$stage"
(cd "$out" && sha256sum jumpstart-* > SHA256SUMS.txt && ls -l)
