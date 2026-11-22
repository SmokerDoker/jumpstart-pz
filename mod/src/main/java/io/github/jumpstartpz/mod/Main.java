package io.github.jumpstartpz.mod;

import java.lang.reflect.Method;

/** Called by ZombieBuddy when the mod loads: makes the Lua API available as {@code Jumpstart}. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        try {
            Class<?> exposer = Class.forName("me.zed_0xff.zombie_buddy.Exposer", true, Main.class.getClassLoader());
            Method expose = exposer.getMethod("exposeClass", Class.class, String.class);
            expose.invoke(null, JumpstartLua.class, "Jumpstart");
            System.out.println("[Jumpstart] ready; " + JumpstartLua.status());
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Jumpstart] could not expose the Lua API: " + e);
        }
    }
}
