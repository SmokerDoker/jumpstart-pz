#!/usr/bin/env bash
# Double-click in Finder (the first time: right-click > Open): installs Jumpstart and the ZombieBuddy fork.
cd "$(dirname "$0")" && bash ./install-unix.sh
echo
read -r -p "Press Enter to close this window."
