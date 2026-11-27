package io.github.jumpstartpz.mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@link LuaLive.Game} for Project Zomboid, through reflection (Build 42). Main thread only. */
final class ReflectiveLuaGame implements LuaLive.Game {

    @Override
    public boolean inGame() {
        if (isServer()) {
            return true;
        }
        try {
            return Class.forName("zombie.characters.IsoPlayer").getMethod("getInstance").invoke(null) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean isServer() {
        try {
            return Class.forName("zombie.network.GameServer").getField("server").getBoolean(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean multiplayer() {
        try {
            return Class.forName("zombie.network.GameClient").getField("client").getBoolean(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean isActive(String modId) {
        try {
            return modIds(fileSystem()).contains(modId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public Map<String, String> register(String modId, String modDir) throws Exception {
        Object fs = fileSystem();
        Class<?> fsClass = fs.getClass();
        if (modDir != null) {
            fsClass.getMethod("setModIdToDir", String.class, String.class).invoke(fs, modId, modDir);
        }
        forgetMissing(modId);
        Object mod = Class.forName("zombie.gameStates.ChooseGameInfo").getMethod("getAvailableModDetails", String.class).invoke(null, modId);
        if (mod == null) {
            throw new IllegalStateException("the game does not know the mod " + modId + " (folder " + modDir + ")");
        }
        List<String> dirs = new ArrayList<>();
        for (String getter : List.of("getCommonDir", "getVersionDir")) {
            String dir = (String) mod.getClass().getMethod(getter).invoke(mod);
            if (dir != null && !dir.isBlank()) {
                dirs.add(normalize(new java.io.File(dir).getAbsolutePath()) + "/");
            }
        }
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) modIds(fs);
        if (!ids.contains(modId)) {
            ids.add(modId); // the running game's mod list, as if the save had started with it
        }
        fsClass.getMethod("loadMod", String.class).invoke(fs, modId);
        @SuppressWarnings("unchecked")
        Map<String, String> active = (Map<String, String>) fsClass.getField("activeFileMap").get(fs);
        Map<String, String> files = new HashMap<>();
        synchronized (fs) {
            for (Map.Entry<String, String> e : active.entrySet()) {
                String absolute = normalize(e.getValue());
                for (String dir : dirs) {
                    if (absolute.startsWith(dir)) {
                        files.put(e.getKey().replace('\\', '/').toLowerCase(Locale.ROOT), e.getValue());
                        break;
                    }
                }
            }
        }
        return files;
    }

    /**
     * The game remembers mod ids it once looked for in vain (ChooseGameInfo.MissingMods) and never
     * looks again: a mod that was asked for before its download finished stayed unknown until the
     * game restarted ("the game does not know the mod Bandits2").
     */
    static void forgetMissing(String modId) {
        try {
            Field missing = Class.forName("zombie.gameStates.ChooseGameInfo").getDeclaredField("MissingMods");
            missing.setAccessible(true);
            ((java.util.Set<?>) missing.get(null)).remove(modId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot clear the game's missing-mod note for " + modId + ": " + e);
        }
    }

    @Override
    public void run(String absolutePath) throws Exception {
        Class.forName("zombie.Lua.LuaManager").getMethod("RunLua", String.class).invoke(null, absolutePath);
    }

    @Override
    public Map<String, List<Object>> handlers(Collection<String> events) {
        Map<String, List<Object>> result = new HashMap<>();
        try {
            Field mapField = Class.forName("zombie.Lua.LuaEventManager").getDeclaredField("EventMap");
            mapField.setAccessible(true);
            Map<?, ?> eventMap = (Map<?, ?>) mapField.get(null);
            for (String name : events) {
                Object event = eventMap.get(name);
                if (event != null) {
                    List<?> callbacks = (List<?>) event.getClass().getField("callbacks").get(event);
                    result.put(name, new ArrayList<>(callbacks));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot read the Lua events: " + e);
        }
        return result;
    }

    @Override
    public void call(Object handler, Object[] args) throws Exception {
        Class<?> lua = Class.forName("zombie.Lua.LuaManager");
        Object caller = lua.getField("caller").get(null);
        Object thread = lua.getField("thread").get(null);
        Method pcall = caller.getClass().getMethod("pcallvoid", Class.forName("se.krka.kahlua.vm.KahluaThread"), Object.class, Object[].class);
        pcall.invoke(caller, thread, handler, args);
    }

    @Override
    public Object[] argsFor(String event) {
        return switch (event) {
            case "OnInitGlobalModData" -> new Object[] {Boolean.FALSE};
            case "OnCreatePlayer" -> new Object[] {0.0, player()};
            default -> new Object[0];
        };
    }

    @Override
    public void reloadTranslations() throws Exception {
        Class.forName("zombie.core.Translator").getMethod("loadFiles").invoke(null);
    }

    // --- Lua's globals (LuaJournal) ------------------------------------------------------------------

    private static final String TABLE = "se.krka.kahlua.vm.KahluaTable";

    @Override
    public Object globalTable() {
        try {
            return Class.forName("zombie.Lua.LuaManager").getField("env").get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean isTable(Object value) {
        try {
            return value != null && Class.forName(TABLE).isInstance(value);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public Map<Object, Object> entries(Object table) {
        if (!isTable(table)) {
            return Map.of();
        }
        try {
            // KahluaTableImpl keeps its entries in a public map: one copy, no Lua iteration
            Object delegate = table.getClass().getField("delegate").get(table);
            if (delegate instanceof Map<?, ?> map) {
                return new HashMap<>(map);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // another table type: iterate
        }
        Map<Object, Object> result = new HashMap<>();
        try {
            Object it = Class.forName(TABLE).getMethod("iterator").invoke(table);
            Class<?> itClass = Class.forName("se.krka.kahlua.vm.KahluaTableIterator");
            Method advance = itClass.getMethod("advance");
            Method key = itClass.getMethod("getKey");
            Method value = itClass.getMethod("getValue");
            while ((Boolean) advance.invoke(it)) {
                result.put(key.invoke(it), value.invoke(it));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot read a Lua table: " + e);
        }
        return result;
    }

    @Override
    public Object get(Object table, Object key) {
        try {
            return Class.forName(TABLE).getMethod("rawget", Object.class).invoke(table, key);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void set(Object table, Object key, Object value) {
        try {
            Class.forName(TABLE).getMethod("rawset", Object.class, Object.class).invoke(table, key, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot set the Lua value " + key + ": " + e);
        }
    }

    @Override
    public String sourceOf(Object value) {
        return value != null && value.getClass().getName().equals("se.krka.kahlua.vm.LuaClosure") ? sourceFile(value) : null;
    }

    /**
     * Closes the windows of classes a mod added: elements in the game's UI list whose Lua class
     * (metatable, up to three levels, which is how ISPanel:derive builds classes) is one of
     * {@code modClasses}. Only classes the mod added count, not game classes it patched: a mod that
     * replaced ISPanel.render must not close every window. Returns how many.
     */
    int closeWindows(java.util.Set<Object> modClasses) {
        if (modClasses.isEmpty()) {
            return 0;
        }
        int closed = 0;
        try {
            Class<?> manager = Class.forName("zombie.ui.UIManager");
            Class<?> element = Class.forName("zombie.ui.UIElement");
            Field tableField = element.getField("table");
            Method remove = manager.getMethod("RemoveElement", Class.forName("zombie.ui.UIElementInterface"));
            Method metatable = Class.forName(TABLE).getMethod("getMetatable");
            List<?> ui = new ArrayList<>((List<?>) manager.getField("UI").get(null));
            for (Object e : ui) {
                if (!element.isInstance(e)) {
                    continue;
                }
                Object t = tableField.get(e);
                for (int level = 0; level < 4 && t != null; level++) {
                    Object meta = metatable.invoke(t);
                    if (meta != null && modClasses.contains(meta)) {
                        remove.invoke(null, e);
                        closed++;
                        break;
                    }
                    t = meta == t ? null : meta;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot close the windows of a mod: " + e);
        }
        return closed;
    }

    /**
     * Every player's hotbar again, built by the game's own code (as ISPlayerDataObject does when a
     * player is created). A hotbar mod changes the ISHotbar class and puts its own fields on the
     * hotbar that exists; taking its functions back leaves that object half the mod's (an empty box).
     */
    static final String REBUILD_HOTBARS = """
            -- Jumpstart: the hotbar again, with the game's own code, after a hotbar mod was switched off
            if ISHotbar and getPlayerData then
                for i = 0, getNumActivePlayers() - 1 do
                    local playerObj = getSpecificPlayer(i)
                    local data = getPlayerData(i)
                    if playerObj and data and data.playerHotbar then
                        local old = data.playerHotbar
                        local visible = old:isVisible()
                        old:setVisible(false)
                        old:removeFromUIManager()
                        local hotbar = ISHotbar:new(playerObj)
                        hotbar:initialise()
                        hotbar:addToUIManager()
                        hotbar:setVisible(visible)
                        data.playerHotbar = hotbar
                    end
                end
            end
            """;

    boolean rebuildHotbars() {
        Path file = null;
        try {
            file = Files.createTempFile("jumpstart-hotbar", ".lua");
            Files.writeString(file, REBUILD_HOTBARS);
            run(file.toAbsolutePath().toString());
            return true;
        } catch (Exception e) {
            System.out.println("[Jumpstart] cannot rebuild the hotbar: " + e);
            return false;
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (java.io.IOException ignored) {
                    // a temp file
                }
            }
        }
    }

    /** The folders a mod's files come from (common and version folder), normalized, ending in '/'. */
    List<String> modDirs(String modId) {
        List<String> dirs = new ArrayList<>();
        for (java.io.File dir : modFolders(modId)) {
            dirs.add(normalize(dir.getPath()) + "/");
        }
        return dirs;
    }

    /** The same folders as they are on disk (absolute, original case), for reading them. */
    List<java.io.File> modFolders(String modId) {
        List<java.io.File> dirs = new ArrayList<>();
        try {
            Object mod = Class.forName("zombie.gameStates.ChooseGameInfo").getMethod("getAvailableModDetails", String.class).invoke(null, modId);
            if (mod != null) {
                for (String getter : List.of("getCommonDir", "getVersionDir")) {
                    String dir = (String) mod.getClass().getMethod(getter).invoke(mod);
                    if (dir != null && !dir.isBlank()) {
                        dirs.add(new java.io.File(dir).getAbsoluteFile());
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot find the folders of " + modId + ": " + e);
        }
        return dirs;
    }

    /**
     * Takes away every Lua event handler defined in the given folders' files and returns them by
     * event, so {@link #giveBack} can put them back. What the mod changed otherwise (UI it opened,
     * functions it replaced) stays until the save loads again.
     */
    Map<String, List<Object>> takeHandlers(List<String> dirs) {
        Map<String, List<Object>> taken = new HashMap<>();
        if (dirs.isEmpty()) {
            return taken;
        }
        try {
            Field mapField = Class.forName("zombie.Lua.LuaEventManager").getDeclaredField("EventMap");
            mapField.setAccessible(true);
            Map<?, ?> eventMap = (Map<?, ?>) mapField.get(null);
            for (Map.Entry<?, ?> e : eventMap.entrySet()) {
                @SuppressWarnings("unchecked")
                List<Object> callbacks = (List<Object>) e.getValue().getClass().getField("callbacks").get(e.getValue());
                List<Object> mine = new ArrayList<>();
                for (Object closure : callbacks) {
                    String file = sourceFile(closure);
                    if (file != null && dirs.stream().anyMatch(file::startsWith)) {
                        mine.add(closure);
                    }
                }
                if (!mine.isEmpty()) {
                    callbacks.removeAll(mine);
                    taken.put(String.valueOf(e.getKey()), mine);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot take the Lua event handlers away: " + e);
        }
        return taken;
    }

    void giveBack(Map<String, List<Object>> handlers) {
        try {
            Field mapField = Class.forName("zombie.Lua.LuaEventManager").getDeclaredField("EventMap");
            mapField.setAccessible(true);
            Map<?, ?> eventMap = (Map<?, ?>) mapField.get(null);
            for (Map.Entry<String, List<Object>> e : handlers.entrySet()) {
                Object event = eventMap.get(e.getKey());
                if (event != null) {
                    @SuppressWarnings("unchecked")
                    List<Object> callbacks = (List<Object>) event.getClass().getField("callbacks").get(event);
                    for (Object closure : e.getValue()) {
                        if (!callbacks.contains(closure)) {
                            callbacks.add(closure);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot give the Lua event handlers back: " + e);
        }
    }

    /** The file a Lua function was compiled from (Kahlua: closure.prototype.filename), normalized. */
    private static String sourceFile(Object closure) {
        try {
            Object prototype = closure.getClass().getField("prototype").get(closure);
            Object file = prototype.getClass().getField("filename").get(prototype);
            return file == null ? null : normalize(file.toString());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Object player() {
        try {
            return Class.forName("zombie.characters.IsoPlayer").getMethod("getInstance").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Object fileSystem() throws ReflectiveOperationException {
        return Class.forName("zombie.ZomboidFileSystem").getField("instance").get(null);
    }

    @Override
    public LuaLive.Scripts loadScripts(String modId, List<String> scriptFiles) throws Exception {
        return ScriptLive.load(modId, scriptFiles);
    }

    @Override
    public void afterLua(boolean distributions) throws Exception {
        ScriptLive.afterLua(distributions);
    }

    @Override
    public boolean reloadAnimations() throws Exception {
        return ScriptLive.reloadAnimations();
    }

    /**
     * Takes a removed mod out of the running game's mod list, as if the game had started without it
     * (the Lua checksum and the next load go by this list). True if it was in it.
     */
    boolean dropFromModList(String modId) {
        try {
            return modIds(fileSystem()).remove(modId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] cannot take " + modId + " out of the game's mod list: " + e);
            return false;
        }
    }

    private static List<?> modIds(Object fs) throws ReflectiveOperationException {
        Field field = fs.getClass().getDeclaredField("mods");
        field.setAccessible(true);
        return (List<?>) field.get(fs);
    }

    private static String normalize(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
