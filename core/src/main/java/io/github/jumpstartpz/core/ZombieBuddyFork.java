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

    /** "on", "on, until restart", "off" or "not loaded"; null when this ZombieBuddy cannot tell. */
    public String stateOf(String modId) {
        return callStrings("stateOf", modId);
    }

    /** Switches a live-switchable mod off; returns ZombieBuddy's one-line result. */
    public String off(String modId) {
        return callStrings("off", modId);
    }

    /** Switches a mod back on; returns ZombieBuddy's one-line result. */
    public String on(String modId) {
        return callStrings("on", modId);
    }

    /**
     * The Java part of a mod folder as ZombieBuddy would load it: "jar path TAB package", "" when
     * there is none, null when this ZombieBuddy cannot tell.
     */
    public String javaModOf(String commonDir, String versionDir) {
        return callStrings("javaModOf", commonDir, versionDir);
    }

    /** Like {@link #javaModOf}, but the jar a game client loads, also when asked on a server. */
    public String clientJavaModOf(String commonDir, String versionDir) {
        return callStrings("clientJavaModOf", commonDir, versionDir);
    }

    // --- crash protection (the fork's ModGuard); null when this ZombieBuddy has none -------------------

    /** The newest guard event's sequence number ("0" if none), or null without a guard. */
    public String guardLastSeq() {
        return callStrings("guardLastSeq");
    }

    /** Guard events after {@code sinceSeq}: lines "seq TAB modId TAB kind TAB where TAB exception TAB action". */
    public String guardEvents(String sinceSeq) {
        return callStrings("guardEvents", sinceSeq);
    }

    /** "count TAB state" (ok, off-by-guard, probation) for a mod, "" if it had no error, null without a guard. */
    public String guardStatus(String modId) {
        return callStrings("guardStatus", modId);
    }

    /**
     * Calls into a switched-off mod's code since (the trip wire in every method returns at once and
     * counts): "0" when nothing holds it, "" for an unknown mod, null when this ZombieBuddy cannot tell.
     */
    public String callsWhileOff(String modId) {
        return callStrings("callsWhileOff", modId);
    }

    /**
     * Classes a mod's jars brought that another mod jar had loaded already, with other contents: one
     * line per other mod, tab separated (what happened, other mod, count, a class, own copy's prefix).
     * "" when there are none, null when this ZombieBuddy cannot tell.
     */
    public String classClashes(String modId) {
        return callStrings("classClashes", modId);
    }

    /** After uninstalling: the fork drops what it recorded for the mod (its journal). */
    public String forget(String modId) {
        return callStrings("forget", modId);
    }

    private String callStrings(String name, String... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            java.util.Arrays.fill(types, String.class);
            Method method = Class.forName(HOTSWAP, true, loader).getMethod(name, types);
            return (String) method.invoke(null, (Object[]) args);
        } catch (InvocationTargetException e) {
            return "FAILED ZombieBuddy: " + e.getCause();
        } catch (ReflectiveOperationException e) {
            return null;
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
