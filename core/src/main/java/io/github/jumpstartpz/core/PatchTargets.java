package io.github.jumpstartpz.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which game methods a ZombieBuddy mod jar patches: the {@code @Patch(className, methodName, isAdvice)}
 * annotations on its classes, read straight from the class files (no class is loaded).
 *
 * <p>Two mods that patch the same method: with advices both run (ZombieBuddy chains them; their order
 * can still matter), a delegation replaces the method and only one can win ("only one delegation per
 * method EVER", ZombieBuddy's Patch).
 */
public final class PatchTargets {
    private PatchTargets() {
    }

    /** One patched method. {@code advice}: false for a delegation (replaces the method). */
    public record Target(String className, String methodName, boolean advice) {
        public String method() {
            return className + "." + methodName;
        }
    }

    /** Two enabled mods patch the same method; {@code hard} when one replaces it (a delegation). */
    public record Conflict(String method, String otherMod, boolean hard) {
    }

    /** The patches in a jar (in no particular order, each once). */
    public static Set<Target> of(Path jar) throws IOException {
        Set<Target> targets = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                if (e.isDirectory() || !e.getName().endsWith(".class") || e.getSize() > 4_000_000) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(e)) {
                    targets.addAll(ofClass(in.readAllBytes()));
                } catch (IOException | RuntimeException bad) {
                    // a class file this reader does not understand has no patch for it
                }
            }
        }
        return targets;
    }

    /**
     * What clashes with {@code mod}: the methods it patches that other mods patch too.
     * {@code others}: mod id to its targets (the mod itself may be in it; it is skipped).
     */
    public static List<Conflict> conflicts(String mod, Set<Target> mine, Map<String, Set<Target>> others) {
        List<Conflict> found = new ArrayList<>();
        for (Map.Entry<String, Set<Target>> other : others.entrySet()) {
            if (other.getKey().equals(mod)) {
                continue;
            }
            Map<String, Boolean> theirs = new LinkedHashMap<>();
            for (Target t : other.getValue()) {
                theirs.merge(t.method(), t.advice(), Boolean::logicalAnd);
            }
            Set<String> seen = new LinkedHashSet<>();
            for (Target t : mine) {
                Boolean theirAdvice = theirs.get(t.method());
                if (theirAdvice != null && seen.add(t.method())) {
                    found.add(new Conflict(t.method(), other.getKey(), !t.advice() || !theirAdvice));
                }
            }
        }
        return found;
    }

    // --- the class file ----------------------------------------------------------------------------

    static List<Target> ofClass(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        Object[] pool = new Object[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 3 -> pool[i] = in.readInt();
                case 4 -> in.readFloat();
                case 5, 6 -> {
                    in.readLong();
                    i++; // takes two slots
                }
                case 7, 8, 16, 19, 20 -> pool[i] = new int[] {tag, in.readUnsignedShort()};
                case 9, 10, 11, 12, 17, 18 -> {
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                }
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("constant pool tag " + tag);
            }
        }
        in.readUnsignedShort(); // access
        in.readUnsignedShort(); // this
        in.readUnsignedShort(); // super
        skip(in, in.readUnsignedShort() * 2); // interfaces
        for (int members = 0; members < 2; members++) { // fields, then methods
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) {
                skip(in, 6);
                skipAttributes(in);
            }
        }
        List<Target> targets = new ArrayList<>();
        int attributes = in.readUnsignedShort();
        for (int a = 0; a < attributes; a++) {
            String name = utf(pool, in.readUnsignedShort());
            int length = in.readInt();
            if (!"RuntimeVisibleAnnotations".equals(name) && !"RuntimeInvisibleAnnotations".equals(name)) {
                skip(in, length);
                continue;
            }
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) {
                Map<String, Object> values = new LinkedHashMap<>();
                String type = annotation(in, pool, values);
                if (type != null && type.endsWith("/Patch;") && values.get("className") instanceof String c
                        && values.get("methodName") instanceof String m) {
                    boolean advice = !(values.get("isAdvice") instanceof Integer z) || z != 0;
                    targets.add(new Target(c, m, advice));
                }
            }
        }
        return targets;
    }

    /** Reads one annotation; its constant element values (strings, booleans as 0/1) go into {@code values}. */
    private static String annotation(DataInputStream in, Object[] pool, Map<String, Object> values) throws IOException {
        String type = utf(pool, in.readUnsignedShort());
        int pairs = in.readUnsignedShort();
        for (int p = 0; p < pairs; p++) {
            String name = utf(pool, in.readUnsignedShort());
            Object value = elementValue(in, pool);
            if (values != null && value != null) {
                values.put(name, value);
            }
        }
        return type;
    }

    private static Object elementValue(DataInputStream in, Object[] pool) throws IOException {
        int tag = in.readUnsignedByte();
        switch (tag) {
            case 's' -> {
                return utf(pool, in.readUnsignedShort());
            }
            case 'Z', 'B', 'C', 'I', 'S' -> {
                int i = in.readUnsignedShort();
                return i < pool.length && pool[i] instanceof Integer v ? v : null;
            }
            case 'D', 'F', 'J', 'c' -> {
                in.readUnsignedShort();
                return null;
            }
            case 'e' -> {
                in.readUnsignedShort();
                in.readUnsignedShort();
                return null;
            }
            case '@' -> {
                annotation(in, pool, null);
                return null;
            }
            case '[' -> {
                int n = in.readUnsignedShort();
                for (int i = 0; i < n; i++) {
                    elementValue(in, pool);
                }
                return null;
            }
            default -> throw new IOException("element value tag " + (char) tag);
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            in.readUnsignedShort();
            skip(in, in.readInt());
        }
    }

    private static void skip(DataInputStream in, int bytes) throws IOException {
        in.skipNBytes(bytes);
    }

    private static String utf(Object[] pool, int index) {
        return index > 0 && index < pool.length && pool[index] instanceof String s ? s : null;
    }
}
