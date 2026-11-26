#!/usr/bin/env bash
# Starts a JVM with Jumpstart.jar as its agent and the game's projectzomboid.jar, loads the game
# classes ZombieBuddy patches first, and fails when a patch cannot be applied. A patch that does not
# apply leaves the game running without ZombieBuddy's mod loading, so without any Java mod and
# without Jumpstart ("No active Java mods"); unit tests do not see that, the real game classes do.
#
#   kit/agent-check.sh <Jumpstart.jar> <projectzomboid.jar> [java]
set -euo pipefail
agent="$1"
game="$2"
java="${3:-java}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

cat > "$work/LoadGameClasses.java" <<'EOF'
public class LoadGameClasses {
    public static void main(String[] args) throws Exception {
        for (String name : new String[] {"zombie.ZomboidFileSystem", "zombie.modding.ActiveMods",
                "zombie.network.NetChecksum$Checksummer", "zombie.gameStates.GameLoadingState"}) {
            Class.forName(name);
        }
        System.out.println("game classes loaded");
    }
}
EOF
"${java%java}javac" -d "$work" "$work/LoadGameClasses.java" 2>/dev/null || javac -d "$work" "$work/LoadGameClasses.java"
sep=":"
dir="$work"
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) # a Windows Java: Windows paths and ';'
        sep=";"
        dir="$(cygpath -w "$work")"
        agent="$(cygpath -w "$agent")"
        ;;
esac
out="$("$java" "-javaagent:$agent=hotswap=true,config_dir=$dir/config" -cp "$dir$sep$game" LoadGameClasses 2>&1 || true)"
echo "$out" | grep -E '^\[ZB\] (patching|Transformed)|ERROR|game classes loaded' || true
if echo "$out" | grep -q 'ERROR transforming\|could not be patched'; then
    echo "agent check: a ZombieBuddy patch does not apply to the game's classes" >&2
    exit 1
fi
for expected in 'patching zombie.ZomboidFileSystem.loadMods' 'game classes loaded'; do
    if ! echo "$out" | grep -q "$expected"; then
        echo "agent check: missing \"$expected\"" >&2
        exit 1
    fi
done
echo "agent check: ok"
