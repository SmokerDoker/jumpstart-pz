#!/usr/bin/env bash
# Builds jsNative.dll: the fork's Windows loader (c/windows/zbNative.c, ZombieBuddy's zbNative) for
# Jumpstart.jar. The game's ProjectZomboid64.exe cannot start -javaagent itself on Windows, as
# instrument.dll's DLLs are not on the load path; the loader puts them there and starts the jar.
# ProjectZomboid64.json then has -agentlib:jsNative=hotswap=true. Needs MinGW (Ubuntu:
# gcc-mingw-w64-x86-64).
#   bash kit/native-loader.sh <fork checkout> <output folder>   -> <output folder>/jsNative.dll
set -euo pipefail
[ -f "$1/c/windows/zbNative.c" ] || { echo "no fork checkout (c/windows/zbNative.c): $1"; exit 1; }
src="$(cd "$1/c/windows" && pwd)"
mkdir -p "$2"
out="$(cd "$2" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp "$src/zbNative.c" "$src/jsNative.def" "$src/Makefile" "$work/"
make -C "$work" jsNative.dll
dll="$work/jsNative.dll"
# the three agent entry points, and the jar it starts
exports="$(x86_64-w64-mingw32-objdump -p "$dll")"
for f in Agent_OnLoad Agent_OnAttach Agent_OnUnload; do
  grep -q "$f" <<<"$exports" || { echo "jsNative.dll does not export $f"; exit 1; }
done
grep -aq 'Jumpstart.jar' "$dll" || { echo "jsNative.dll does not start Jumpstart.jar"; exit 1; }
if grep -aq 'ZombieBuddy.jar' "$dll"; then echo "jsNative.dll still names ZombieBuddy.jar"; exit 1; fi
cp "$dll" "$out/jsNative.dll"
sha256sum "$out/jsNative.dll"
