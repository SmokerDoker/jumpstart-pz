#!/usr/bin/env bash
# Jumpstart in one step on macOS and Linux: downloads the newest test kit from GitHub, checks its
# SHA-256 and installs it.
#   Game (macOS/Linux):  curl -fsSL https://raw.githubusercontent.com/SmokerDoker/jumpstart-pz/main/kit/get.sh | bash
#   Dedicated server:    curl -fsSL https://raw.githubusercontent.com/SmokerDoker/jumpstart-pz/main/kit/get.sh | bash -s -- server /path/to/pzserver [servername]
set -euo pipefail
repo="SmokerDoker/jumpstart-pz"
tag="${JUMPSTART_TAG:-test-kit}"
mode="${1:-client}"
case "$(uname -s)" in
  Darwin) os=macos ;;
  *) os=linux ;;
esac
[ "$mode" = server ] && pattern="jumpstart-server-" || pattern="jumpstart-$os-"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
echo "Jumpstart: looking for the newest kit ($tag) ..."
urls="$(curl -fsSL "https://api.github.com/repos/$repo/releases/tags/$tag" | grep -o '"browser_download_url": *"[^"]*"' | sed 's/.*"\(https[^"]*\)"/\1/')"
url="$(printf '%s\n' "$urls" | grep "/$pattern" | head -n 1)"
sums="$(printf '%s\n' "$urls" | grep '/SHA256SUMS.txt$' | head -n 1)"
[ -n "$url" ] && [ -n "$sums" ] || { echo "no $pattern package in the release $tag"; exit 1; }
file="$(basename "$url")"
echo "downloading $file"
curl -fsSL -o "$work/$file" "$url"
curl -fsSL -o "$work/SHA256SUMS.txt" "$sums"
expected="$(grep " $file\$" "$work/SHA256SUMS.txt" | cut -d' ' -f1)"
if command -v sha256sum >/dev/null; then actual="$(sha256sum "$work/$file" | cut -d' ' -f1)"; else actual="$(shasum -a 256 "$work/$file" | cut -d' ' -f1)"; fi
[ -n "$expected" ] && [ "$expected" = "$actual" ] || { echo "SHA-256 does not match: expected $expected, got $actual"; exit 1; }
echo "SHA-256 ok: $actual"
case "$file" in
  *.zip) (cd "$work" && unzip -q "$file") ;;
  *) tar -xzf "$work/$file" -C "$work" ;;
esac
kit="$(find "$work" -mindepth 1 -maxdepth 1 -type d -name 'jumpstart-*' | head -n 1)"

if [ "$mode" = server ]; then
  shift || true
  bash "$kit/install-server.sh" "$@"
else
  bash "$kit/install-unix.sh"
fi
