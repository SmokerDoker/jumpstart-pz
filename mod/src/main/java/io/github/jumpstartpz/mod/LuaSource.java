package io.github.jumpstartpz.mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Which Lua file is running right now: the innermost Lua function on the main Lua thread's call
 * stack (LuaManager.thread.currentCoroutine). Lets the game hooks credit a file write or a ModData
 * table to the mod whose code asked for it. Main thread only; null when no Lua function runs (Java
 * called the game itself).
 */
final class LuaSource {
    private static volatile Lookup lookup;

    private LuaSource() {
    }

    /** The normalized file name (lower case, '/'), or null. */
    static String currentFile() {
        try {
            Lookup l = lookup;
            if (l == null) {
                lookup = l = new Lookup();
            }
            Object thread = l.thread.get(null);
            Object coroutine = thread == null ? null : l.currentCoroutine.get(thread);
            if (coroutine == null) {
                return null;
            }
            int top = (Integer) l.callframeTop.invoke(coroutine);
            for (int i = top - 1; i >= 0; i--) {
                Object frame = l.callFrame.invoke(coroutine, i);
                Object closure = frame == null ? null : l.closure.get(frame);
                if (closure != null) {
                    Object prototype = l.prototype.get(closure);
                    Object file = l.filename.get(prototype);
                    return file == null ? null : file.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // no Lua running, or another game version: nothing to credit
        }
        return null;
    }

    private static final class Lookup {
        final Field thread;
        final Field currentCoroutine;
        final Method callframeTop;
        final Method callFrame;
        final Field closure;
        final Field prototype;
        final Field filename;

        Lookup() throws ReflectiveOperationException {
            thread = Class.forName("zombie.Lua.LuaManager").getField("thread");
            currentCoroutine = Class.forName("se.krka.kahlua.vm.KahluaThread").getField("currentCoroutine");
            Class<?> coroutine = Class.forName("se.krka.kahlua.vm.Coroutine");
            callframeTop = coroutine.getMethod("getCallframeTop");
            callFrame = coroutine.getMethod("getCallFrame", int.class);
            closure = Class.forName("se.krka.kahlua.vm.LuaCallFrame").getField("closure");
            Class<?> luaClosure = Class.forName("se.krka.kahlua.vm.LuaClosure");
            prototype = luaClosure.getField("prototype");
            filename = Class.forName("se.krka.kahlua.vm.Prototype").getField("filename");
        }
    }
}
