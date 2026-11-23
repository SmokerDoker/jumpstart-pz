#!/usr/bin/env bash
# Builds a native Jumpstart installer with jpackage (JDK 21+) for the current OS, the same way as
# BiteCheck's installers. It brings its own small Java runtime, so players need nothing else.
#
#   kit/package-installer.sh <JumpstartSetup.jar> <version> <type> <outdir>
#
#   type: msi (Windows) | dmg (macOS) | deb | rpm (Linux)
#
# The installed app "Jumpstart" opens a window with one button that installs (or updates, or
# removes) Jumpstart and the ZombieBuddy fork. Runs in CI (kits.yml) on a runner of the matching OS.
# Not code-signed: Windows SmartScreen and macOS Gatekeeper warn on first start.
set -euo pipefail

JAR="$1"
VERSION="$2"
TYPE="$3"
OUT="$4"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# jpackage wants a plain x.y.z; drop a pre-release suffix like -alpha.1.
APP_VERSION="${VERSION%%-*}"
APP_VERSION="${APP_VERSION%%+*}"
if [ "$TYPE" = "dmg" ] && [ "${APP_VERSION%%.*}" = "0" ]; then
    # macOS bundles need a first version number of at least 1; 0.x.y is stored as 1.x.y in the bundle only.
    APP_VERSION="1.${APP_VERSION#0.}"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/input" "$OUT"
cp "$JAR" "$WORK/input/JumpstartSetup.jar"

# On Windows (Git Bash) jpackage.exe needs Windows paths.
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

common=(
    --dest "$(win "$WORK/out")"
    --app-version "$APP_VERSION"
    --input "$(win "$WORK/input")"
    --main-jar JumpstartSetup.jar
    --main-class io.github.jumpstartpz.installer.InstallerMain
    --vendor "Jumpstart project"
    --description "Installs Jumpstart (live mods for Project Zomboid) and the ZombieBuddy fork"
    --copyright "GPL-3.0-or-later, https://github.com/SmokerDoker/jumpstart-pz"
    --license-file "$(win "$ROOT/LICENSE")"
    # the window (Swing); HTTP and Ed25519 (jdk.crypto.ec on JDK 21) for the signed automatic updates
    --add-modules java.base,java.desktop,java.net.http,jdk.crypto.ec
    --jlink-options "--strip-debug --no-header-files --no-man-pages"
)

case "$TYPE" in
    msi)
        # A second launcher with a console, for the command line (JumpstartConsole.exe --status).
        # Only the .exe in the install folder: no Start menu entry or desktop shortcut, so players
        # see a single Jumpstart app (as BiteCheck does).
        printf 'win-console=true\nwin-menu=false\nwin-shortcut=false\n' > "$WORK/console.properties"
        jpackage --type msi --name Jumpstart "${common[@]}" \
            --win-per-user-install --win-menu --win-menu-group Jumpstart --win-shortcut \
            --win-upgrade-uuid 6d0f3b8e-4a51-4c1e-9b7a-2f5c8e1d9a43 \
            --add-launcher JumpstartConsole="$(win "$WORK/console.properties")"
        ;;
    dmg)
        jpackage --type dmg --name Jumpstart "${common[@]}" \
            --mac-package-identifier io.github.jumpstartpz.installer --mac-package-name Jumpstart
        ;;
    deb | rpm)
        jpackage --type "$TYPE" --name jumpstart "${common[@]}" \
            --linux-package-name jumpstart-pz --linux-shortcut --linux-menu-group Game \
            --linux-app-category games
        ;;
    *)
        echo "unknown installer type: $TYPE" >&2
        exit 2
        ;;
esac

ls -la "$WORK/out"
cp "$WORK/out"/* "$OUT/"
