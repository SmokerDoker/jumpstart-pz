package io.github.jumpstartpz.mod.net;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Jumpstart's Java channel between server and clients, on top of PZ's client and server commands
 * with the module name "Jumpstart". The game's packet handlers are patched (see GameHooks) so that
 * these commands come here and never reach Lua's OnClientCommand / OnServerCommand: Lua mods can
 * neither read nor fake what the server tells a client. Commands Lua sends with this module name do
 * reach the server, which accepts them only with the connection's secret token.
 *
 * <p>Values are strings in both directions; PZ carries them in a KahluaTable.
 */
public final class Net {
    public static final String MODULE = "Jumpstart";

    private static volatile BiConsumer<Object[], Map<String, String>> serverHandler;
    private static volatile BiConsumer<String, Map<String, String>> clientHandler;
    private static volatile Runnable serverTick;
    private static MethodHandle triggerEvent3;
    private static MethodHandle triggerEvent4;

    private Net() {
    }

    /** Server: handler(new Object[] {player, command}, args). */
    public static void onServer(BiConsumer<Object[], Map<String, String>> handler, Runnable tick) {
        serverHandler = handler;
        serverTick = tick;
    }

    /** Client: handler(command, args). */
    public static void onClient(BiConsumer<String, Map<String, String>> handler) {
        clientHandler = handler;
    }

    // --- called by the patched game code ------------------------------------------------------------

    /** Replaces LuaEventManager.triggerEvent("OnClientCommand", module, command, player, table) on the server. */
    public static void serverEvent(String event, Object module, Object command, Object player, Object table) {
        BiConsumer<Object[], Map<String, String>> handler = serverHandler;
        if (handler != null && MODULE.equals(module)) {
            try {
                handler.accept(new Object[] {player, String.valueOf(command)}, read(table));
            } catch (Throwable t) {
                System.out.println("[Jumpstart] server command failed: " + t);
            }
            return;
        }
        try {
            if (triggerEvent4 == null) {
                triggerEvent4 = MethodHandles.publicLookup().findStatic(Class.forName("zombie.Lua.LuaEventManager"), "triggerEvent",
                        MethodType.methodType(void.class, String.class, Object.class, Object.class, Object.class, Object.class));
            }
            triggerEvent4.invoke(event, module, command, player, table);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    /** Replaces LuaEventManager.triggerEvent("OnServerCommand", module, command, table) on the client. */
    public static void clientEvent(String event, Object module, Object command, Object table) {
        BiConsumer<String, Map<String, String>> handler = clientHandler;
        if (handler != null && MODULE.equals(module)) {
            try {
                handler.accept(String.valueOf(command), read(table));
            } catch (Throwable t) {
                System.out.println("[Jumpstart] client command failed: " + t);
            }
            return;
        }
        try {
            if (triggerEvent3 == null) {
                triggerEvent3 = MethodHandles.publicLookup().findStatic(Class.forName("zombie.Lua.LuaEventManager"), "triggerEvent",
                        MethodType.methodType(void.class, String.class, Object.class, Object.class, Object.class));
            }
            triggerEvent3.invoke(event, module, command, table);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    /** Called at the start of every server tick (zombie.network.ServerMap.preupdate). */
    public static void serverTick() {
        Runnable tick = serverTick;
        if (tick != null) {
            try {
                tick.run();
            } catch (Throwable t) {
                System.out.println("[Jumpstart] server tick failed: " + t);
            }
        }
    }

    // --- sending ---------------------------------------------------------------------------------

    /** Server to one client. */
    public static void sendToClient(Object connection, String command, Map<String, String> args) throws ReflectiveOperationException {
        Class<?> table = Class.forName("se.krka.kahlua.vm.KahluaTable");
        Method send = Class.forName("zombie.network.GameServer").getMethod("sendServerCommand", String.class, String.class, table,
                Class.forName("zombie.core.raknet.UdpConnection"));
        send.invoke(null, MODULE, command, write(args), connection);
    }

    /** Client to server, as the local player. */
    public static void sendToServer(String command, Map<String, String> args) throws ReflectiveOperationException {
        Class<?> gameClient = Class.forName("zombie.network.GameClient");
        Object client = gameClient.getField("instance").get(null);
        Object player = Class.forName("zombie.characters.IsoPlayer").getMethod("getInstance").invoke(null);
        Method send = gameClient.getMethod("sendClientCommand", Class.forName("zombie.characters.IsoPlayer"), String.class, String.class,
                Class.forName("se.krka.kahlua.vm.KahluaTable"));
        send.invoke(client, player, MODULE, command, write(args));
    }

    private static Object write(Map<String, String> args) throws ReflectiveOperationException {
        Object platform = Class.forName("zombie.Lua.LuaManager").getField("platform").get(null);
        Object table = platform.getClass().getMethod("newTable").invoke(platform);
        Method rawset = Class.forName("se.krka.kahlua.vm.KahluaTable").getMethod("rawset", Object.class, Object.class);
        for (Map.Entry<String, String> e : args.entrySet()) {
            rawset.invoke(table, e.getKey(), e.getValue());
        }
        return table;
    }

    /** The string values of a KahluaTable (numbers become their text). */
    static Map<String, String> read(Object table) throws ReflectiveOperationException {
        Map<String, String> args = new LinkedHashMap<>();
        if (table == null) {
            return args;
        }
        Class<?> kt = Class.forName("se.krka.kahlua.vm.KahluaTable");
        Object it = kt.getMethod("iterator").invoke(table);
        Class<?> iterator = Class.forName("se.krka.kahlua.vm.KahluaTableIterator");
        Method advance = iterator.getMethod("advance");
        Method key = iterator.getMethod("getKey");
        Method value = iterator.getMethod("getValue");
        while ((Boolean) advance.invoke(it)) {
            Object k = key.invoke(it);
            Object v = value.invoke(it);
            if (k != null && v != null) {
                args.put(String.valueOf(k), v instanceof Double d && d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(v));
            }
        }
        return args;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
