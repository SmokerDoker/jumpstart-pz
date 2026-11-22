package io.github.jumpstartpz.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * {@link HotswapBackend} on top of the Jumpstart fork of ZombieBuddy
 * (github.com/SmokerDoker/zombiebuddy-jumpstart), reached by reflection: with upstream ZombieBuddy,
 * or without hotswap=true, Jumpstart still loads and says what is missing instead of failing to link.
 */
public final class ZombieBuddyFork implements HotswapBackend {
    private static final String HOTSWAP = "me.zed_0xff.zombie_buddy.Hotswap";
    private static final String AGENT = "me.zed_0xff.zombie_buddy.Agent";

    private final ClassLoader loader;

    public ZombieBuddyFork(ClassLoader loader) {
        this.loader = loader;
    }

    /** Looks for ZombieBuddy through the loader that loaded Jumpstart. */
    public static ZombieBuddyFork current() {
        return new ZombieBuddyFork(ZombieBuddyFork.class.getClassLoader());
    }

    @Override
    public String unavailableReason() {
        Class<?> hotswap;
        try {
            hotswap = Class.forName(HOTSWAP, true, loader);
        } catch (ClassNotFoundException e) {
            return "this ZombieBuddy cannot switch mods live; install the Jumpstart fork of ZombieBuddy";
        }
        try {
            if (!Boolean.TRUE.equals(hotswap.getMethod("isEnabled").invoke(null))) {
                return "live switching is off; start the game with the ZombieBuddy option hotswap=true";
            }
        } catch (ReflectiveOperationException e) {
            return "unexpected ZombieBuddy version: " + e;
        }
        return null;
    }

    @Override
    public String load(Path jar, String javaPackage, String modId) {
        return call("load", jar, javaPackage, modId);
    }

    @Override
    public String update(Path jar, String javaPackage, String modId) {
        return call("update", jar, javaPackage, modId);
    }

    @Override
    public Path approvalsDir() {
        try {
            return (Path) Class.forName(AGENT, true, loader).getMethod("configDir").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Path.of(System.getProperty("user.home"), ".zombie_buddy");
        }
    }

    private String call(String name, Path jar, String javaPackage, String modId) {
        String unavailable = unavailableReason();
        if (unavailable != null) {
            return unavailable;
        }
        try {
            Method method = Class.forName(HOTSWAP, true, loader).getMethod(name, Path.class, String.class, String.class);
            return (String) method.invoke(null, jar, javaPackage, modId);
        } catch (InvocationTargetException e) {
            return "ZombieBuddy failed: " + e.getCause();
        } catch (ReflectiveOperationException e) {
            return "this fork of ZombieBuddy has no Hotswap." + name + "; update it";
        }
    }
}
