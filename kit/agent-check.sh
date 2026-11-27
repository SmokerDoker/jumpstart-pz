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
        // game members Jumpstart reaches by reflection (LuaChecksum, server snapshots, ScriptLive):
        // a game update that renames one breaks only that feature, so it is caught here, before a release.
        // "Class#field" (any visibility, also in superclasses), "Class.method(param types)" (any visibility).
        String[] members = {
            "zombie.Lua.LuaManager#checksumDone", "zombie.Lua.LuaManager.initChecksum()", "zombie.Lua.LuaManager.finishChecksum()",
            "zombie.Lua.LuaManager.LoadDirBase(java.lang.String,boolean)",
            "zombie.network.NetChecksum$GroupOfFiles#groups", "zombie.network.NetChecksum$GroupOfFiles#currentGroup",
            "zombie.network.NetChecksum$GroupOfFiles.initChecksum()",
            "zombie.network.NetChecksum$GroupOfFiles#fileCount", "zombie.network.NetChecksum$GroupOfFiles#relPaths",
            "zombie.network.NetChecksum#checksummer", "zombie.network.NetChecksum$Checksummer.reset()",
            "zombie.network.NetChecksum$Checksummer.addFile(java.lang.String,java.lang.String)",
            "zombie.network.NetChecksum$Checksummer.checksumToString()",
            "zombie.network.GameServer#checksum", "zombie.network.GameServer#server", "zombie.network.GameClient#checksum",
            "zombie.network.GameClient#client",
            "zombie.network.ServerMap#instance", "zombie.network.ServerMap.QueuedSaveAll(boolean)",
            "zombie.network.ServerMap$ServerCell#chunkLoader", "zombie.network.ServerChunkLoader#threadSave",
            "zombie.network.ServerChunkLoader$SaveChunkThread#toThread", "zombie.network.ServerChunkLoader$SaveChunkThread#currentTask",
            "zombie.ZomboidFileSystem#mods", "zombie.ZomboidFileSystem.getAbsolutePath(java.lang.String)",
            // ScriptLive: the mod's scripts live
            "zombie.scripting.ScriptManager#instance", "zombie.scripting.ScriptManager#bucketCollectionList",
            "zombie.scripting.ScriptManager#tempFileToModMap", "zombie.scripting.ScriptManager#loadFileNames",
            "zombie.scripting.ScriptManager#currentLoadFileAbsPath", "zombie.scripting.ScriptManager#currentLoadFileName",
            "zombie.scripting.ScriptManager#currentLoadFileMod", "zombie.scripting.ScriptManager#checksum",
            "zombie.scripting.ScriptManager#tagToItemMap", "zombie.scripting.ScriptManager#typeToItemMap",
            "zombie.scripting.ScriptManager.LoadFile(zombie.scripting.ScriptLoadMode,java.lang.String,boolean)",
            "zombie.scripting.ScriptManager.resolveItemTypes()", "zombie.scripting.ScriptManager.createClothingItemMap()",
            "zombie.scripting.ScriptManager.createZedDmgMap()", "zombie.scripting.ScriptManager.getChecksum()",
            "zombie.scripting.ScriptBucketCollection#scriptBuckets", "zombie.scripting.ScriptBucketCollection#allScripts",
            "zombie.scripting.ScriptBucketCollection.LoadScripts(zombie.scripting.ScriptLoadMode)",
            "zombie.scripting.ScriptBucket#loadData", "zombie.scripting.ScriptBucket$LoadData#scriptBodies",
            "zombie.scripting.ScriptBucket$LoadData#reloaded", "zombie.scripting.ScriptBucket$LoadData#addedOnReload",
            "zombie.scripting.ScriptBucket$LoadData#script", "zombie.scripting.FileName.getName(java.lang.String)",
            "zombie.scripting.objects.BaseScriptObject.OnScriptsLoaded(zombie.scripting.ScriptLoadMode)",
            "zombie.scripting.objects.BaseScriptObject.OnLoadedAfterLua()",
            "zombie.scripting.objects.BaseScriptObject.OnPostWorldDictionaryInit()",
            "zombie.scripting.objects.Item.getFullName()", "zombie.scripting.objects.Item.getName()",
            "zombie.scripting.objects.Item.getModuleName()", "zombie.scripting.objects.Item.getModID()",
            "zombie.scripting.entity.GameEntityScript.setRegistry_id(short)",
            "zombie.world.WorldDictionary#allowScriptItemLoading", "zombie.world.WorldDictionary#data",
            "zombie.world.WorldDictionary.getItemLoadList()", "zombie.world.WorldDictionary.getEntityLoadList()",
            "zombie.world.WorldDictionary.getItemRegistryID(java.lang.String)",
            "zombie.world.DictionaryData.parseInfoLoadList(java.util.Map)", "zombie.world.DictionaryData.parseCurrentInfoSet()",
            "zombie.world.DictionaryData.save()", "zombie.world.DictionaryData#itemTypeToInfoMap",
            "zombie.world.DictionaryData#itemIdToInfoMap", "zombie.world.ItemInfo#scriptItem",
            "zombie.world.DictionaryInfo#name", "zombie.world.DictionaryInfo#moduleName", "zombie.world.DictionaryInfo#fullType",
            "zombie.world.DictionaryInfo#registryId", "zombie.world.DictionaryInfo#modId", "zombie.world.DictionaryInfo#isModded",
            "zombie.world.DictionaryInfo#isLoaded", "zombie.world.DictionaryInfo#entityScript",
            "zombie.inventory.RecipeManager.ScriptsLoaded()", "zombie.scripting.objects.ModelScript.ScriptsLoaded()",
            "zombie.iso.IsoWorld.parseDistributions()",
            "zombie.core.skinnedmodel.ModelManager#instance", "zombie.core.skinnedmodel.ModelManager.loadModAnimations()",
            "zombie.Lua.LuaManager$GlobalObject.refreshAnimSets(boolean)", "zombie.Lua.LuaManager$GlobalObject.reloadActionGroups()",
            "zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator.load()", "zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator.getChecksum()",
            // Lua's globals and the running file (LuaStartJournal, LuaSource, ReflectiveLuaGame)
            "zombie.Lua.LuaManager#env", "zombie.Lua.LuaManager#thread", "se.krka.kahlua.j2se.KahluaTableImpl#delegate",
            "se.krka.kahlua.j2se.KahluaTableImpl.rawget(java.lang.Object)",
            "se.krka.kahlua.vm.LuaClosure#prototype", "se.krka.kahlua.vm.Prototype#filename",
            "se.krka.kahlua.vm.KahluaThread#currentCoroutine", "se.krka.kahlua.vm.Coroutine.getCallframeTop()",
            "se.krka.kahlua.vm.Coroutine.getCallFrame(int)", "se.krka.kahlua.vm.LuaCallFrame#closure",
            // loading the save again (GameActions.reloadSave, SaveReload)
            "zombie.iso.IsoWorld#instance", "zombie.iso.IsoWorld.getWorld()", "zombie.core.Core.getInstance()", "zombie.core.Core.quit()",
            "zombie.GameWindow#states", "zombie.gameStates.GameStateMachine#current",
            // items of missing mods (MissingItems; its hooks: InventoryItem.loadItem with 4 arguments, ItemContainer.load)
            "zombie.inventory.InventoryItem.loadItem(java.nio.ByteBuffer,int,boolean,zombie.inventory.InventoryItem)",
            "zombie.inventory.ItemContainer.load(java.nio.ByteBuffer,int)",
            "zombie.world.WorldDictionary.getItemTypeFromID(short)", "zombie.world.WorldDictionary.getItemModID(short)",
            "zombie.world.WorldDictionary.getItemRegistryID(java.lang.String)", "zombie.scripting.ScriptManager.getItem(java.lang.String)",
            "zombie.inventory.InventoryItem.loadItem(java.nio.ByteBuffer,int)", "zombie.inventory.InventoryItem.setID(int)",
            "zombie.inventory.ItemContainer.AddItem(zombie.inventory.InventoryItem)", "zombie.inventory.ItemContainer#containingItem",
            "zombie.inventory.ItemContainer#parent", "zombie.network.GameClient#client", "zombie.Lua.LuaManager#platform",
            "zombie.iso.IsoObject.hasModData()", "zombie.inventory.InventoryItem.hasModData()",
            // placeholders for items of a missing mod
            "zombie.inventory.InventoryItemFactory.CreateItem(java.lang.String)", "zombie.inventory.InventoryItem.setName(java.lang.String)",
            "zombie.inventory.InventoryItem.setCustomName(boolean)", "zombie.inventory.InventoryItem.getModData()",
            "zombie.inventory.InventoryItem.getFullType()", "zombie.inventory.ItemContainer.getItems()",
            "zombie.inventory.ItemContainer.Remove(zombie.inventory.InventoryItem)", "zombie.core.Translator.getLanguage()",
            "zombie.network.GameServer#server",
            "zombie.network.GameServer.sendAddItemToContainer(zombie.inventory.ItemContainer,zombie.inventory.InventoryItem)",
            "zombie.network.GameServer.sendRemoveItemFromContainer(zombie.inventory.ItemContainer,zombie.inventory.InventoryItem)",
        };
        ClassLoader cl = LoadGameClasses.class.getClassLoader();
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String m : members) {
            try {
                if (m.contains("#")) {
                    field(Class.forName(m.substring(0, m.indexOf('#')), false, cl), m.substring(m.indexOf('#') + 1));
                } else {
                    int open = m.indexOf('(');
                    int dot = m.lastIndexOf('.', open);
                    String[] names = m.substring(open + 1, m.length() - 1).split(",");
                    java.util.List<Class<?>> types = new java.util.ArrayList<>();
                    for (String n : names) {
                        if (!n.isEmpty()) {
                            types.add(switch (n) {
                                case "boolean" -> boolean.class;
                                case "short" -> short.class;
                                case "int" -> int.class;
                                default -> Class.forName(n, false, cl);
                            });
                        }
                    }
                    method(Class.forName(m.substring(0, dot), false, cl), m.substring(dot + 1, open), types.toArray(new Class<?>[0]));
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                missing.add(m + " (" + e + ")");
            }
        }
        // the order ScriptManager.Load sorts mod script files in (ScriptLive falls back to by file name)
        if (!java.util.Comparator.class.isAssignableFrom(Class.forName("zombie.scripting.ScriptManager$38", false, cl))) {
            missing.add("zombie.scripting.ScriptManager$38 is no Comparator");
        }
        if (!missing.isEmpty()) {
            System.out.println("ERROR game members missing: " + missing);
            return;
        }
        System.out.println("game members found");
    }

    static void field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                k.getDeclaredField(name);
                return;
            } catch (NoSuchFieldException e) {
                // superclass
            }
        }
        throw new NoSuchFieldException(c.getName() + "#" + name);
    }

    static void method(Class<?> c, String name, Class<?>[] types) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                k.getDeclaredMethod(name, types);
                return;
            } catch (NoSuchMethodException e) {
                // superclass
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
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
echo "$out" | grep -E '^\[ZB\] (patching|Transformed)|ERROR|Exception|game classes loaded|game members found' || true
if echo "$out" | grep -q 'ERROR transforming\|could not be patched\|ERROR game members'; then
    echo "agent check: a ZombieBuddy patch does not apply to the game's classes" >&2
    exit 1
fi
for expected in 'patching zombie.ZomboidFileSystem.loadMods' 'game classes loaded' 'game members found'; do
    if ! echo "$out" | grep -q "$expected"; then
        echo "agent check: missing \"$expected\"" >&2
        exit 1
    fi
done
echo "agent check: ok"
