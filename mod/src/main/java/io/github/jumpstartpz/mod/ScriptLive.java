package io.github.jumpstartpz.mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A mod's item, recipe and other scripts into the running game, in the game's own steps but for the
 * mod's files only.
 *
 * <p>The game's {@code ScriptManager.ReloadScripts} cannot be used: it calls {@code PreReload} on every
 * script of a type (a craft recipe forgets everything then) and expects every file to be parsed again,
 * and it would put the script text over items that Lua changed since (DoParam). So:
 *
 * <ol>
 *   <li>Each of the mod's files is parsed like at the start ({@code LoadFile} with
 *       {@code ScriptLoadMode.Init}, with the file's mod set as the game sets it): a new script gets its
 *       entry, a script the mod changes gets the mod's text after the earlier ones, as at the start.</li>
 *   <li>Exactly those entries are marked {@code reloaded} (new ones also {@code addedOnReload}) and
 *       {@code LoadScripts(Reload)} runs on their collections: it loads only marked entries, puts new
 *       scripts into the lists and maps, and loads a changed one from all its texts again.</li>
 *   <li>The new and changed scripts get {@code OnScriptsLoaded}, the game's lists built from all items
 *       (tags, types, clothing, recipes, models) are built again.</li>
 *   <li>World dictionary: the new items get their ids like at a world's start
 *       ({@code DictionaryData.parseInfoLoadList}, {@code parseCurrentInfoSet}, saved to the save's
 *       WorldDictionary.bin). A multiplayer client does not make ids: it takes the server's
 *       ({@link #expectServerIds}).</li>
 *   <li>In multiplayer the game's script checksum is worked out again (players who join later).</li>
 * </ol>
 *
 * Main thread (server thread) only.
 */
final class ScriptLive {
    private ScriptLive() {
    }

    /** Scripts loaded by the last {@link #load}, for their after-Lua step. */
    private static List<Object> pendingAfterLua = List.of();
    /** Server: the ids the last load gave its new items ("Module.Item" to id), for the clients. */
    private static Map<String, Short> lastIds = Map.of();
    /** Client: the ids the server gave the items of the mod it is installing. */
    private static Map<String, Short> serverIds = Map.of();

    static Map<String, Short> lastIds() {
        return lastIds;
    }

    static void expectServerIds(Map<String, Short> ids) {
        serverIds = ids == null ? Map.of() : Map.copyOf(ids);
    }

    /** "Module.Item=123" lines, as the commit carries them. */
    static String encode(Map<String, Short> ids) {
        StringBuilder sb = new StringBuilder();
        ids.forEach((type, id) -> sb.append(type).append('=').append(id).append('\n'));
        return sb.toString();
    }

    static Map<String, Short> decode(String lines) {
        Map<String, Short> ids = new LinkedHashMap<>();
        if (lines != null) {
            for (String line : lines.split("\n")) {
                int eq = line.lastIndexOf('=');
                if (eq > 0) {
                    try {
                        ids.put(line.substring(0, eq).strip(), Short.parseShort(line.substring(eq + 1).strip()));
                    } catch (NumberFormatException e) {
                        // not an id
                    }
                }
            }
        }
        return ids;
    }

    static LuaLive.Scripts load(String modId, List<String> files) throws ReflectiveOperationException {
        Class<?> sm = Class.forName("zombie.scripting.ScriptManager");
        Object manager = sm.getField("instance").get(null);
        Class<?> modeClass = Class.forName("zombie.scripting.ScriptLoadMode");
        Object init = enumValue(modeClass, "Init");
        Object reload = enumValue(modeClass, "Reload");
        Object fs = Class.forName("zombie.ZomboidFileSystem").getField("instance").get(null);
        Method absolute = fs.getClass().getMethod("getAbsolutePath", String.class);
        Method loadFile = sm.getMethod("LoadFile", modeClass, String.class, boolean.class);
        Method fileName = Class.forName("zombie.scripting.FileName").getMethod("getName", String.class);
        List<?> collections = (List<?>) declared(sm, "bucketCollectionList").get(manager);
        @SuppressWarnings("unchecked")
        Map<String, String> fileToMod = (Map<String, String>) declared(sm, "tempFileToModMap").get(manager);
        @SuppressWarnings("unchecked")
        List<String> loadFileNames = (List<String>) declared(sm, "loadFileNames").get(manager);

        Map<Object, Integer> before = new IdentityHashMap<>();
        forEachEntry(collections, (collection, entry) -> before.put(entry, bodies(entry).size()));

        List<String> errors = new ArrayList<>();
        Class<?> dictionary = Class.forName("zombie.world.WorldDictionary");
        Field allow = declared(dictionary, "allowScriptItemLoading");
        boolean allowed = allow.getBoolean(null);
        allow.setBoolean(null, true); // else the world dictionary drops items loaded after the start
        List<Object> touched = new ArrayList<>();
        List<Object> newItems = new ArrayList<>();
        try {
            for (String rel : files) {
                String abs = (String) absolute.invoke(fs, rel);
                if (abs == null) {
                    errors.add(rel + ": not found");
                    continue;
                }
                declared(sm, "currentLoadFileAbsPath").set(null, abs);
                declared(sm, "currentLoadFileName").set(null, fileName.invoke(null, abs));
                declared(sm, "currentLoadFileMod").set(null, modId);
                if (fileToMod != null) {
                    fileToMod.put(abs, modId);
                }
                try {
                    loadFile.invoke(manager, init, rel, false); // as ScriptManager.Load does
                } catch (java.lang.reflect.InvocationTargetException e) {
                    errors.add(rel + ": " + e.getCause());
                }
                if (!loadFileNames.contains(rel)) {
                    loadFileNames.add(rel);
                }
            }

            Set<Object> changedCollections = new LinkedHashSet<>();
            Class<?> itemClass = Class.forName("zombie.scripting.objects.Item");
            forEachEntry(collections, (collection, entry) -> {
                Integer was = before.get(entry);
                int now = bodies(entry).size();
                if (was == null || now > was) {
                    declared(entry.getClass(), "reloaded").setBoolean(entry, true);
                    if (was == null) {
                        declared(entry.getClass(), "addedOnReload").setBoolean(entry, true);
                    }
                    Object script = declared(entry.getClass(), "script").get(entry);
                    touched.add(script);
                    if (was == null && itemClass.isInstance(script)) {
                        newItems.add(script);
                    }
                    changedCollections.add(collection);
                }
            });
            for (Object collection : changedCollections) {
                collection.getClass().getMethod("LoadScripts", modeClass).invoke(collection, reload);
                ((List<?>) declared(Class.forName("zombie.scripting.ScriptBucketCollection"), "allScripts").get(collection)).clear();
            }
            for (Object script : touched) {
                call(script, "OnScriptsLoaded", errors, new Class<?>[] {modeClass}, reload);
            }
            rebuildLists(sm, manager, errors);
            registerItems(dictionary, newItems, errors);
        } finally {
            allow.setBoolean(null, allowed);
            declared(sm, "currentLoadFileMod").set(null, null);
        }
        for (Object script : touched) {
            call(script, "OnPostWorldDictionaryInit", errors, new Class<?>[0]);
        }
        pendingAfterLua = touched;
        if (checksumTrusted) {
            recomputeChecksum(errors);
        }
        List<String> types = new ArrayList<>();
        for (Object item : newItems) {
            types.add((String) item.getClass().getMethod("getFullName").invoke(item));
        }
        System.out.println("[Jumpstart] scripts of " + modId + " live: " + touched.size() + " scripts from " + files.size()
                + " files, new items " + types + (errors.isEmpty() ? "" : "; problems: " + errors));
        return new LuaLive.Scripts(touched.size(), types, errors);
    }

    /** After the mod's Lua: the new scripts' after-Lua step, then (where loot is made) the loot tables. */
    static void afterLua(boolean distributions) throws ReflectiveOperationException {
        List<String> errors = new ArrayList<>();
        for (Object script : pendingAfterLua) {
            call(script, "OnLoadedAfterLua", errors, new Class<?>[0]);
        }
        pendingAfterLua = List.of();
        if (distributions) {
            // the game's own: ItemPickerJava.Parse() and the sandbox loot settings, from the Lua tables
            Class.forName("zombie.iso.IsoWorld").getMethod("parseDistributions").invoke(null);
        }
        if (!errors.isEmpty()) {
            System.out.println("[Jumpstart] after-Lua step of the new scripts: " + errors);
        }
    }

    /**
     * The mod's animation clips (ModelManager.loadModAnimations goes through the running mod list and
     * adds what is new), then the animation sets and action groups read again (the game's own refresh,
     * as its debug menu does it).
     */
    static boolean reloadAnimations() throws ReflectiveOperationException {
        Object models = Class.forName("zombie.core.skinnedmodel.ModelManager").getField("instance").get(null);
        if (models != null) {
            models.getClass().getMethod("loadModAnimations").invoke(models);
        }
        Class<?> global = Class.forName("zombie.Lua.LuaManager$GlobalObject");
        global.getMethod("refreshAnimSets", boolean.class).invoke(null, true);
        global.getMethod("reloadActionGroups").invoke(null);
        boolean server = Class.forName("zombie.network.GameServer").getField("server").getBoolean(null);
        boolean client = Class.forName("zombie.network.GameClient").getField("client").getBoolean(null);
        if (server || client) {
            // the animation checksum a joining player is compared with: AdvancedAnimator.load works it out
            // from the game's and the running mods' animation files; the game's file groups stay as they are
            Class<?> groupClass = Class.forName("zombie.network.NetChecksum$GroupOfFiles");
            @SuppressWarnings("unchecked")
            List<Object> groups = (List<Object>) groupClass.getField("groups").get(null);
            List<Object> saved = new ArrayList<>(groups);
            Field current = declared(groupClass, "currentGroup");
            Object savedCurrent = current.get(null);
            Class<?> animator = Class.forName("zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator");
            String before = String.valueOf(animator.getMethod("getChecksum").invoke(null));
            try {
                current.set(null, null);
                animator.getMethod("load").invoke(null);
            } finally {
                groups.clear();
                groups.addAll(saved);
                current.set(null, savedCurrent);
            }
            System.out.println("[Jumpstart] animation checksum: " + before + " -> " + animator.getMethod("getChecksum").invoke(null));
        }
        return true;
    }

    // --- the game's lists built from all scripts ---------------------------------------------------

    private static void rebuildLists(Class<?> sm, Object manager, List<String> errors) {
        for (String cache : List.of("tagToItemMap", "typeToItemMap")) {
            try {
                ((Map<?, ?>) declared(sm, cache).get(manager)).clear(); // built again when asked
            } catch (ReflectiveOperationException | RuntimeException e) {
                errors.add(cache + ": " + e);
            }
        }
        for (String step : List.of("resolveItemTypes", "createClothingItemMap", "createZedDmgMap")) {
            try {
                Method m = sm.getDeclaredMethod(step);
                m.setAccessible(true);
                m.invoke(manager);
            } catch (ReflectiveOperationException | RuntimeException e) {
                errors.add(step + ": " + e);
            }
        }
        for (String owner : List.of("zombie.inventory.RecipeManager", "zombie.scripting.objects.ModelScript")) {
            try {
                Class.forName(owner).getMethod("ScriptsLoaded").invoke(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                errors.add(owner + ".ScriptsLoaded: " + e);
            }
        }
    }

    // --- world dictionary ----------------------------------------------------------------------------

    private static void registerItems(Class<?> dictionary, List<Object> newItems, List<String> errors) {
        lastIds = Map.of();
        try {
            Method items = declared(dictionary, "getItemLoadList", new Class<?>[0]);
            Method entities = declared(dictionary, "getEntityLoadList", new Class<?>[0]);
            Map<?, ?> itemList = (Map<?, ?>) items.invoke(null);
            Map<?, ?> entityList = (Map<?, ?>) entities.invoke(null);
            Object data = declared(dictionary, "data").get(null);
            boolean client = Class.forName("zombie.network.GameClient").getField("client").getBoolean(null);
            if (client) {
                itemList.clear();
                entityList.clear();
                takeServerIds(data, newItems, errors);
                return;
            }
            if (itemList.isEmpty() && entityList.isEmpty()) {
                return;
            }
            Class<?> dataClass = Class.forName("zombie.world.DictionaryData");
            Method parse = declared(dataClass, "parseInfoLoadList", new Class<?>[] {Map.class});
            parse.invoke(data, itemList);
            parse.invoke(data, entityList);
            declared(dataClass, "parseCurrentInfoSet", new Class<?>[0]).invoke(data);
            itemList.clear();
            entityList.clear();
            declared(dataClass, "save", new Class<?>[0]).invoke(data);
            Map<String, Short> ids = new LinkedHashMap<>();
            Method id = dictionary.getMethod("getItemRegistryID", String.class);
            for (Object item : newItems) {
                String type = (String) item.getClass().getMethod("getFullName").invoke(item);
                ids.put(type, (Short) id.invoke(null, type));
            }
            lastIds = ids;
            System.out.println("[Jumpstart] world dictionary: new items " + ids);
        } catch (ReflectiveOperationException | RuntimeException e) {
            errors.add("world dictionary: " + (e instanceof java.lang.reflect.InvocationTargetException t ? t.getCause() : e));
        }
    }

    /** A multiplayer client: the server's ids for the new items, put into the client's dictionary as the game would. */
    private static void takeServerIds(Object data, List<Object> newItems, List<String> errors) throws ReflectiveOperationException {
        Map<String, Short> ids = serverIds;
        serverIds = Map.of();
        Class<?> dataClass = Class.forName("zombie.world.DictionaryData");
        @SuppressWarnings("unchecked")
        Map<String, Object> byType = (Map<String, Object>) declared(dataClass, "itemTypeToInfoMap").get(data);
        @SuppressWarnings("unchecked")
        Map<Short, Object> byId = (Map<Short, Object>) declared(dataClass, "itemIdToInfoMap").get(data);
        Class<?> info = Class.forName("zombie.world.ItemInfo");
        Class<?> base = Class.forName("zombie.world.DictionaryInfo");
        for (Object item : newItems) {
            String type = (String) item.getClass().getMethod("getFullName").invoke(item);
            Short id = ids.get(type);
            if (id == null) {
                errors.add("no id from the server for " + type);
                continue;
            }
            Object entry = info.getConstructor().newInstance();
            declared(base, "name").set(entry, item.getClass().getMethod("getName").invoke(item));
            declared(base, "moduleName").set(entry, item.getClass().getMethod("getModuleName").invoke(item));
            declared(base, "fullType").set(entry, type);
            declared(base, "registryId").setShort(entry, id);
            declared(base, "modId").set(entry, item.getClass().getMethod("getModID").invoke(item));
            declared(base, "isModded").setBoolean(entry, true);
            declared(base, "isLoaded").setBoolean(entry, true);
            declared(base, "entityScript").set(entry, item);
            declared(info, "scriptItem").set(entry, item);
            byType.put(type, entry);
            byId.put(id, entry);
            item.getClass().getMethod("setRegistry_id", short.class).invoke(item, id);
        }
    }

    // --- the script checksum in multiplayer ------------------------------------------------------

    /**
     * Like ScriptManager.Load: every script file once, the game's files first, then all mods' files
     * sorted by the game's own comparator, each into the checksummer; the sum is the manager's
     * {@code checksum}, compared when a player joins.
     */
    private static volatile boolean checksumTrusted = true;

    static boolean checksumTrusted() {
        return checksumTrusted;
    }

    /**
     * Server start: works the script checksum out again with nothing changed; it must come out as the
     * game's. If not, the game's (and its file order) goes back and live loads leave it alone.
     */
    static boolean checksumSelfTest() {
        try {
            Class<?> sm = Class.forName("zombie.scripting.ScriptManager");
            Object manager = sm.getField("instance").get(null);
            @SuppressWarnings("unchecked")
            List<String> names = (List<String>) declared(sm, "loadFileNames").get(manager);
            List<String> savedNames = new ArrayList<>(names);
            String before = currentChecksum();
            List<String> errors = new ArrayList<>();
            String after = recomputeChecksum(errors);
            if (!before.isEmpty() && before.equals(after)) {
                System.out.println("[Jumpstart] script checksum: worked out again, same as the game's (" + before + ")");
                return true;
            }
            checksumTrusted = false;
            names.clear();
            names.addAll(savedNames);
            declared(sm, "checksum").set(manager, before);
            System.out.println("[Jumpstart] script checksum: worked out again it is " + after + ", the game's is " + before
                    + (errors.isEmpty() ? "" : " " + errors) + "; left as the game made it");
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            checksumTrusted = false;
            System.out.println("[Jumpstart] script checksum: cannot check it (" + e + "); left as the game made it");
            return false;
        }
    }

    /** Works it out again (multiplayer only); returns it, "" when not in multiplayer or on errors. */
    static String recomputeChecksum(List<String> errors) {
        try {
            boolean server = Class.forName("zombie.network.GameServer").getField("server").getBoolean(null);
            boolean client = Class.forName("zombie.network.GameClient").getField("client").getBoolean(null);
            if (!server && !client) {
                return "";
            }
            Class<?> sm = Class.forName("zombie.scripting.ScriptManager");
            Object manager = sm.getField("instance").get(null);
            @SuppressWarnings("unchecked")
            List<String> loadFileNames = (List<String>) declared(sm, "loadFileNames").get(manager);
            @SuppressWarnings("unchecked")
            Map<String, String> fileToMod = (Map<String, String>) declared(sm, "tempFileToModMap").get(manager);
            Object fs = Class.forName("zombie.ZomboidFileSystem").getField("instance").get(null);
            Method absolute = fs.getClass().getMethod("getAbsolutePath", String.class);
            List<String> vanilla = new ArrayList<>();
            List<String> mods = new ArrayList<>();
            for (String name : loadFileNames) {
                String abs = (String) absolute.invoke(fs, name);
                String mod = fileToMod == null || abs == null ? null : fileToMod.get(abs);
                (mod == null || mod.equals("pz-vanilla") ? vanilla : mods).add(name);
            }
            mods.sort(fileOrder(sm, manager));
            loadFileNames.clear();
            loadFileNames.addAll(vanilla);
            loadFileNames.addAll(mods);
            Class<?> net = Class.forName("zombie.network.NetChecksum");
            Object checksummer = net.getField("checksummer").get(null);
            // the checksummer also files every file into the game's groups (its file-by-file comparison):
            // those stay as they are
            Class<?> groupClass = Class.forName("zombie.network.NetChecksum$GroupOfFiles");
            @SuppressWarnings("unchecked")
            List<Object> groups = (List<Object>) groupClass.getField("groups").get(null);
            List<Object> savedGroups = new ArrayList<>(groups);
            Field current = declared(groupClass, "currentGroup");
            Object savedCurrent = current.get(null);
            checksummer.getClass().getMethod("reset").invoke(checksummer);
            Method add = checksummer.getClass().getMethod("addFile", String.class, String.class);
            Set<String> seen = new java.util.HashSet<>();
            try {
                current.set(null, null);
                for (String name : loadFileNames) {
                    if (seen.add(name)) {
                        add.invoke(checksummer, name, absolute.invoke(fs, name));
                    }
                }
            } finally {
                groups.clear();
                groups.addAll(savedGroups);
                current.set(null, savedCurrent);
            }
            String before = (String) declared(sm, "checksum").get(manager);
            String after = (String) checksummer.getClass().getMethod("checksumToString").invoke(checksummer);
            declared(sm, "checksum").set(manager, after);
            if (!after.equals(before)) {
                System.out.println("[Jumpstart] script checksum: " + before + " -> " + after);
            }
            return after;
        } catch (ReflectiveOperationException | RuntimeException e) {
            errors.add("script checksum: " + e);
            return "";
        }
    }

    /**
     * The order ScriptManager.Load sorts the mods' script files in: its own comparator (an anonymous
     * class, ScriptManager$38 in this build: by file name); by file name when it is not there.
     */
    @SuppressWarnings("unchecked")
    private static java.util.Comparator<String> fileOrder(Class<?> sm, Object manager) {
        try {
            Class<?> c = Class.forName("zombie.scripting.ScriptManager$38");
            if (java.util.Comparator.class.isAssignableFrom(c)) {
                java.lang.reflect.Constructor<?> constructor = c.getDeclaredConstructor(sm);
                constructor.setAccessible(true);
                return (java.util.Comparator<String>) constructor.newInstance(manager);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // another build of the game: the same rule by hand
        }
        return java.util.Comparator.comparing(name -> new java.io.File(name).getName());
    }

    static String currentChecksum() {
        try {
            Object manager = Class.forName("zombie.scripting.ScriptManager").getField("instance").get(null);
            Object sum = manager.getClass().getMethod("getChecksum").invoke(manager);
            return sum == null ? "" : sum.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    // --- reflection ----------------------------------------------------------------------------------

    private interface EntryVisitor {
        void visit(Object collection, Object entry) throws ReflectiveOperationException;
    }

    /** Every load entry (ScriptBucket$LoadData) of every bucket of every collection. */
    private static void forEachEntry(List<?> collections, EntryVisitor visitor) throws ReflectiveOperationException {
        Class<?> collectionClass = Class.forName("zombie.scripting.ScriptBucketCollection");
        Class<?> bucketClass = Class.forName("zombie.scripting.ScriptBucket");
        Field buckets = declared(collectionClass, "scriptBuckets");
        Field loadData = declared(bucketClass, "loadData");
        for (Object collection : List.copyOf(collections)) {
            for (Object bucket : List.copyOf((List<?>) buckets.get(collection))) {
                for (Object entry : List.copyOf(((Map<?, ?>) loadData.get(bucket)).values())) {
                    visitor.visit(collection, entry);
                }
            }
        }
    }

    private static List<?> bodies(Object entry) throws ReflectiveOperationException {
        return (List<?>) declared(entry.getClass(), "scriptBodies").get(entry);
    }

    private static void call(Object script, String method, List<String> errors, Class<?>[] types, Object... args) {
        try {
            script.getClass().getMethod(method, types).invoke(script, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            errors.add(method + " " + script + ": " + e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            errors.add(method + ": " + e);
        }
    }

    private static Object enumValue(Class<?> enumClass, String name) {
        for (Object c : enumClass.getEnumConstants()) {
            if (((Enum<?>) c).name().equals(name)) {
                return c;
            }
        }
        throw new IllegalStateException(enumClass.getName() + " has no " + name);
    }

    static Field declared(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    private static Method declared(Class<?> owner, String name, Class<?>[] types) throws NoSuchMethodException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // look in the superclass
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }
}
