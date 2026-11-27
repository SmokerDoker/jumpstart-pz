package io.github.jumpstartpz.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading ZombieBuddy's @Patch annotations from real class files (compiled here with the JDK's compiler). */
class PatchTargetsTest {
    @TempDir
    Path tmp;

    private static final String PATCH = """
            package me.zed_0xff.zombie_buddy.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
            public @interface Patch {
                String className();
                String methodName();
                boolean isAdvice() default true;
                boolean warmUp() default false;
                String[] tags() default {};
            }
            """;

    private Path jar(String name, String... sources) throws IOException {
        Path src = tmp.resolve(name + "-src");
        Path out = tmp.resolve(name + "-classes");
        Files.createDirectories(src.resolve("me/zed_0xff/zombie_buddy/annotations"));
        Files.createDirectories(out);
        Files.writeString(src.resolve("me/zed_0xff/zombie_buddy/annotations/Patch.java"), PATCH);
        for (int i = 0; i < sources.length; i++) {
            Files.writeString(src.resolve("P" + i + ".java"), sources[i]);
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> args = new java.util.ArrayList<>(List.of("-d", out.toString()));
        try (Stream<Path> files = Files.walk(src)) {
            files.filter(f -> f.toString().endsWith(".java")).forEach(f -> args.add(f.toString()));
        }
        assertEquals(0, javac.run(null, null, null, args.toArray(new String[0])));
        Path jar = tmp.resolve(name + ".jar");
        try (OutputStream o = Files.newOutputStream(jar); JarOutputStream j = new JarOutputStream(o); Stream<Path> files = Files.walk(out)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                j.putNextEntry(new JarEntry(out.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, j);
                j.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void readsThePatchedMethodsAndWhetherTheyReplaceThem() throws IOException {
        Path a = jar("a",
                "import me.zed_0xff.zombie_buddy.annotations.Patch;\n"
                        + "@Patch(className = \"zombie.iso.IsoWorld\", methodName = \"update\", tags = {\"x\"}) public class P0 { long l = 5L; double d = 1.5; }",
                "import me.zed_0xff.zombie_buddy.annotations.Patch;\n"
                        + "@Patch(className = \"zombie.Lua.LuaManager\", methodName = \"RunLua\", isAdvice = false) public class P1 { public static class Inner {} }");
        Set<PatchTargets.Target> targets = PatchTargets.of(a);
        assertEquals(Set.of(new PatchTargets.Target("zombie.iso.IsoWorld", "update", true),
                new PatchTargets.Target("zombie.Lua.LuaManager", "RunLua", false)), targets);
    }

    @Test
    void twoModsOnTheSameMethodAreNamedAndADelegationIsAHardConflict() throws IOException {
        Set<PatchTargets.Target> mine = Set.of(new PatchTargets.Target("zombie.iso.IsoWorld", "update", true),
                new PatchTargets.Target("zombie.Lua.LuaManager", "RunLua", true));
        Map<String, Set<PatchTargets.Target>> others = Map.of(
                "Mine", mine,
                "FpsMod", Set.of(new PatchTargets.Target("zombie.iso.IsoWorld", "update", true)),
                "LuaHack", Set.of(new PatchTargets.Target("zombie.Lua.LuaManager", "RunLua", false)),
                "Other", Set.of(new PatchTargets.Target("zombie.core.Core", "EndFrame", true)));
        List<PatchTargets.Conflict> c = PatchTargets.conflicts("Mine", mine, others);
        assertEquals(2, c.size());
        assertTrue(c.contains(new PatchTargets.Conflict("zombie.iso.IsoWorld.update", "FpsMod", false)));
        assertTrue(c.contains(new PatchTargets.Conflict("zombie.Lua.LuaManager.RunLua", "LuaHack", true)));
    }

    @Test
    void aJarWithoutPatchesHasNone() throws IOException {
        assertEquals(Set.of(), PatchTargets.of(jar("plain", "public class P0 { }")));
    }
}
