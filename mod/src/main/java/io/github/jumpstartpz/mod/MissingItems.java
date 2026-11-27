package io.github.jumpstartpz.mod;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Items of a mod that is not loaded wait in the save instead of being lost. The game drops an item
 * whose type it does not know while it loads a container ({@code InventoryItem.loadItem} skips its
 * bytes and returns nothing); a mod switched off, or removed in the menu, loses every item of its
 * kind that way at the next load. Jumpstart keeps those bytes: the game hooks call {@link
 * #itemEnter}/{@link #itemExit} around {@code loadItem} and {@link #containerEnter}/{@link
 * #containerExit} around {@code ItemContainer.load}, so a dropped item is known with the container
 * it was in. A moment later a placeholder takes its place in the container: a vanilla item ({@value
 * #PLACEHOLDER}, so it survives without Jumpstart too) named after the missing item and its mod,
 * with the item's bytes in its ModData under {@value #KEY}. The player sees it, can move it to
 * another container, and the game saves the bytes with it. A container keeps identical items once,
 * with their count and the ids of the copies ({@code CompressIdenticalItems}); those come along in
 * the same placeholder, so every copy comes back.
 *
 * <p>When a container loads that holds a placeholder whose item's type exists again (the mod is
 * back), the item is loaded from its bytes, exactly as it was, and takes the placeholder's place.
 * Kept items of older versions (in the ModData of the container's owner) come back the same way.
 * In multiplayer the server does it (it owns the containers, its tick drives this) and sends each
 * placeholder and each item back to the clients; a client changes nothing. Not covered: items lying on
 * the floor and parts of vehicles (they are not in a container; a placeholder dropped on the floor
 * changes back once it is in a container at a load), and an item a mod's script now defines as
 * another kind (its bytes do not fit any more: it keeps waiting).
 */
public final class MissingItems {

    static final String KEY = "jumpstartMissing";
    /** The placeholder's type: small, not used by any recipe, in every game version. */
    static final String PLACEHOLDER = "Base.GamePieceRed";
    /** Captures and loaded containers are handled this long after their load (on the main thread). */
    static final long SETTLE_MS = 1000;

    /**
     * One dropped item: its type, its mod, the save version it was written with, its bytes (size
     * first), and the ids of identical copies the container kept with it (each comes back too).
     */
    record Kept(String type, String mod, int version, boolean littleEndian, byte[] bytes, int[] copyIds) {
    }

    /** The game as this needs it beyond the hooks. Reflective in the game, a fake in tests. */
    interface Game {
        /** {full type, mod id} of a registry id, or null. */
        String[] typeOf(short registryId);

        /** The registry id of a type that exists now (its script is loaded), or -1. */
        short idOf(String fullType);

        /** Loads an item from its bytes (size first); null when the game cannot. */
        Object load(ByteBuffer record, int version) throws Exception;

        /** Puts a loaded item into the container. */
        void add(Object container, Object item) throws Exception;

        /** Gives a loaded copy its own item id. */
        void setId(Object item, int id) throws Exception;

        /** Who keeps the container's data in the save: the bag it is in, else its parent object; null if none. */
        Object ownerOf(Object container);

        /** The owner's kept items (as written by {@link #write}), or an empty list when it has none. */
        List<Kept> read(Object owner);

        /** Replaces the owner's kept items; an empty list removes the key. */
        void write(Object owner, List<Kept> kept);

        /** Multiplayer client: nothing is kept or put back here (the server does it). */
        boolean multiplayerClient();

        /** A new placeholder item named {@code name} that carries {@code kept}; null if the game cannot make one. */
        Object placeholder(Kept kept, String name);

        /** What a placeholder carries, or null for any other item. */
        Kept keptIn(Object item);

        /** The container's items (a copy). */
        List<Object> items(Object container);

        /** Takes an item out of the container. */
        void remove(Object container, Object item) throws Exception;

        /** The game's language ("EN", "DE", ...). */
        String language();
    }

    private static volatile Game game = new ReflectiveGame();
    /** A container being loaded, and how many items were loading around it (its own items are one deeper). */
    private record Frame(Object container, int depth) {
    }

    private static final ThreadLocal<ArrayDeque<Frame>> CONTAINERS = ThreadLocal.withInitial(ArrayDeque::new);
    /** Where the items being loaded begin, innermost last (no boxing: every item of every chunk passes here). */
    private static final class Starts {
        private int[] at = new int[16];
        private int size;

        void push(int position) {
            if (size == at.length) {
                at = java.util.Arrays.copyOf(at, size * 2);
            }
            at[size++] = position;
        }

        /** The innermost start, or -1 when there is none. */
        int pop() {
            return size == 0 ? -1 : at[--size];
        }

        int size() {
            return size;
        }

        void clear() {
            size = 0;
        }
    }

    private static final ThreadLocal<Starts> STARTS = ThreadLocal.withInitial(Starts::new);
    private static final ThreadLocal<Boolean> RESTORING = ThreadLocal.withInitial(() -> false);

    private record Pending(List<Kept> kept, long since) {
    }

    private record Loaded(WeakReference<Object> container, long since) {
    }

    /** Container -> what was dropped while it loaded, until it goes into the owner's ModData. */
    private static final Map<Object, Pending> CAPTURED = new IdentityHashMap<>();
    /** Containers loaded lately: the main thread looks for kept items to put back. */
    private static final ConcurrentLinkedQueue<Loaded> LOADED = new ConcurrentLinkedQueue<>();
    private static final Set<String> TOLD = ConcurrentHashMap.newKeySet();

    private MissingItems() {
    }

    // --- hooks (game code calls these; they must never throw) -------------------------------------

    /** ItemContainer.load, at the start. */
    public static void containerEnter(Object container) {
        try {
            ArrayDeque<Frame> stack = CONTAINERS.get();
            if (stack.isEmpty()) {
                STARTS.get().clear(); // nothing left over from a load that threw
            }
            stack.push(new Frame(container, STARTS.get().size()));
        } catch (Throwable t) {
            // never in the game's way
        }
    }

    /** ItemContainer.load, before it returns or throws. */
    public static void containerExit(Object container) {
        try {
            ArrayDeque<Frame> stack = CONTAINERS.get();
            if (stack.peek() != null && stack.peek().container() == container) {
                stack.pop();
            } else {
                stack.removeIf(f -> f.container() == container);
            }
            if (!RESTORING.get()) {
                LOADED.add(new Loaded(new WeakReference<>(container), System.currentTimeMillis()));
            }
        } catch (Throwable t) {
            // never in the game's way
        }
    }

    /** InventoryItem.loadItem, at the start: where the item's bytes begin. */
    public static void itemEnter(ByteBuffer buffer) {
        try {
            STARTS.get().push(buffer.position());
        } catch (Throwable t) {
            // never in the game's way
        }
    }

    /** InventoryItem.loadItem, before it throws. */
    public static void itemAbort() {
        try {
            STARTS.get().pop();
        } catch (Throwable t) {
            // never in the game's way
        }
    }

    /** InventoryItem.loadItem, before it returns: nothing came out, so the game drops these bytes. */
    public static void itemExit(Object item, ByteBuffer buffer, int version) {
        try {
            int start = STARTS.get().pop();
            if (item != null || start < 0 || RESTORING.get()) {
                return;
            }
            captured(buffer, start, buffer.position(), version);
        } catch (Throwable t) {
            // never in the game's way
        }
    }

    private static void captured(ByteBuffer buffer, int start, int end, int version) {
        if (end - start < 7) {
            return; // not even size and type
        }
        Game g = game;
        if (g.multiplayerClient()) {
            return;
        }
        short id = buffer.getShort(start + 4);
        String[] type = g.typeOf(id);
        if (type == null || type[0] == null) {
            return; // nothing to know it by when its mod comes back
        }
        Frame frame = CONTAINERS.get().peek();
        Object container = frame == null ? null : frame.container();
        if (container == null) {
            if (TOLD.add("lost " + type[0])) {
                System.out.println("[Jumpstart] item of a missing mod not in a container (floor, vehicle), the game drops it: " + type[0]);
            }
            return;
        }
        byte[] bytes = new byte[end - start];
        buffer.get(start, bytes);
        // one of the container's own items (not one inside another item): the count before it, the copies' ids after it
        int[] copies = frame.depth() == STARTS.get().size() ? copyIds(buffer, start, end) : new int[0];
        Kept kept = new Kept(type[0], type[1] == null ? "" : type[1], version, buffer.order() == ByteOrder.LITTLE_ENDIAN, bytes, copies);
        synchronized (CAPTURED) {
            CAPTURED.computeIfAbsent(container, c -> new Pending(new ArrayList<>(), System.currentTimeMillis())).kept().add(kept);
        }
        if (TOLD.add("kept " + type[0])) {
            System.out.println("[Jumpstart] items of a missing mod wait in the save: " + type[0] + " (" + kept.mod() + ")");
        }
    }

    /**
     * The ids of the identical copies of the item in [start, end): CompressIdenticalItems writes a
     * count before the item and count - 1 ids after it. Nothing when the numbers do not fit.
     */
    static int[] copyIds(ByteBuffer buffer, int start, int end) {
        if (start < 4) {
            return new int[0];
        }
        int count = buffer.getInt(start - 4);
        if (count <= 1 || count > 100_000 || end + (long) (count - 1) * 4 > buffer.limit()) {
            return new int[0];
        }
        int[] ids = new int[count - 1];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = buffer.getInt(end + i * 4);
        }
        return ids;
    }

    // --- main thread ----------------------------------------------------------------------------

    /** Every frame on the main thread: captures into ModData, kept items back where their type exists again. */
    static void onFrame(long now) {
        try {
            flush(now);
            restore(now);
        } catch (Throwable t) {
            System.out.println("[Jumpstart] items of missing mods: " + t);
        }
    }

    private static void flush(long now) {
        List<Map.Entry<Object, Pending>> ready = new ArrayList<>();
        synchronized (CAPTURED) {
            Iterator<Map.Entry<Object, Pending>> it = CAPTURED.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Object, Pending> e = it.next();
                if (now - e.getValue().since() >= SETTLE_MS) {
                    ready.add(Map.entry(e.getKey(), e.getValue())); // the entry itself is gone after remove()
                    it.remove();
                }
            }
        }
        Game g = game;
        for (Map.Entry<Object, Pending> e : ready) {
            List<Kept> unplaced = new ArrayList<>();
            for (Kept k : e.getValue().kept()) {
                Object holder = g.placeholder(k, placeholderName(k, g.language()));
                try {
                    if (holder == null) {
                        unplaced.add(k);
                    } else {
                        g.add(e.getKey(), holder);
                    }
                } catch (Exception ex) {
                    unplaced.add(k);
                }
            }
            if (unplaced.isEmpty()) {
                continue;
            }
            // no placeholder: the bytes wait invisibly with the container's owner, as before
            Object owner = g.ownerOf(e.getKey());
            if (owner == null) {
                System.out.println("[Jumpstart] " + unplaced.size() + " items of missing mods: their container has no owner, the game drops them");
                continue;
            }
            List<Kept> all = new ArrayList<>(g.read(owner));
            all.addAll(unplaced);
            g.write(owner, all);
        }
    }

    /** "Missing mod (SwordMod): Katana x3" in the game's language. */
    static String placeholderName(Kept k, String language) {
        boolean german = language != null && language.toUpperCase(java.util.Locale.ROOT).startsWith("DE");
        String shortType = k.type().substring(k.type().indexOf('.') + 1);
        String mod = k.mod() == null || k.mod().isBlank() ? "?" : k.mod();
        int count = 1 + k.copyIds().length;
        return (german ? "Mod fehlt (" : "Missing mod (") + mod + "): " + shortType + (count > 1 ? " x" + count : "");
    }

    private static void restore(long now) {
        Game g = game;
        for (Loaded l = LOADED.peek(); l != null && now - l.since() >= SETTLE_MS; l = LOADED.peek()) {
            LOADED.poll();
            Object container = l.container().get();
            if (container == null || g.multiplayerClient()) {
                continue;
            }
            int back = replacePlaceholders(g, container);
            Object owner = g.ownerOf(container);
            List<Kept> kept = owner == null ? List.of() : g.read(owner);
            if (!kept.isEmpty()) {
                back += putBack(g, container, owner, kept);
            }
            if (back > 0) {
                System.out.println("[Jumpstart] " + back + " items of a mod that is back are in their place again");
            }
        }
    }

    /** Placeholders in the container whose item's type exists again give way to the item. Returns how many came back. */
    static int replacePlaceholders(Game g, Object container) {
        int back = 0;
        for (Object item : g.items(container)) {
            Kept k = g.keptIn(item);
            if (k == null) {
                continue;
            }
            int n = load(g, container, k);
            if (n > 0) {
                try {
                    g.remove(container, item);
                } catch (Exception e) {
                    System.out.println("[Jumpstart] a placeholder stays next to its item: " + e);
                }
                back += n;
            }
        }
        return back;
    }

    /** Loads the kept items whose type exists again into the container; the rest stay kept. Returns how many came back. */
    static int putBack(Game g, Object container, Object owner, List<Kept> kept) {
        List<Kept> still = new ArrayList<>();
        int back = 0;
        for (Kept k : kept) {
            int n = load(g, container, k);
            if (n == 0) {
                still.add(k);
            }
            back += n;
        }
        if (back > 0) {
            g.write(owner, still);
        }
        return back;
    }

    /** Loads a kept item and its copies into the container if its type exists. Returns how many; 0 when it keeps waiting. */
    static int load(Game g, Object container, Kept k) {
        short id = g.idOf(k.type());
        if (id < 0) {
            return 0;
        }
        Object item;
        RESTORING.set(true);
        try {
            item = g.load(withId(k, id), k.version());
        } catch (Exception e) {
            item = null;
        } finally {
            RESTORING.set(false);
        }
        if (item == null) {
            return 0;
        }
        try {
            g.add(container, item);
        } catch (Exception e) {
            return 0;
        }
        int back = 1;
        for (int copyId : k.copyIds()) {
            RESTORING.set(true);
            try {
                Object copy = g.load(withId(k, id), k.version());
                if (copy != null) {
                    g.setId(copy, copyId);
                    g.add(container, copy);
                    back++;
                }
            } catch (Exception e) {
                // that copy stays lost; the first one is back
            } finally {
                RESTORING.set(false);
            }
        }
        return back;
    }

    /** The kept bytes with the type's registry id of this save (it may differ from when they were kept). */
    static ByteBuffer withId(Kept k, short id) {
        ByteBuffer b = ByteBuffer.wrap(k.bytes().clone()).order(k.littleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        b.putShort(4, id);
        return b;
    }

    static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    static byte[] decode(String text) {
        return Base64.getDecoder().decode(text);
    }

    static String ids(int[] ids) {
        StringBuilder s = new StringBuilder();
        for (int id : ids) {
            if (s.length() > 0) {
                s.append(',');
            }
            s.append(id);
        }
        return s.toString();
    }

    static int[] ids(String text) {
        if (text.isBlank()) {
            return new int[0];
        }
        String[] parts = text.split(",");
        int[] ids = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            ids[i] = Integer.parseInt(parts[i].strip());
        }
        return ids;
    }

    /** Tests. */
    static void useForTest(Game fake) {
        game = fake;
        synchronized (CAPTURED) {
            CAPTURED.clear();
        }
        LOADED.clear();
        CONTAINERS.get().clear();
        STARTS.get().clear();
    }

    // --- the game ---------------------------------------------------------------------------------

    /** PZ through reflection; looked up on first use. */
    private static final class ReflectiveGame implements Game {
        private Method typeFromId;
        private Method modFromId;
        private Method registryId;
        private Method getItem;
        private Object scripts;
        private Method loadItem;
        private Method addItem;
        private Method setId;
        private Field containingItem;
        private Field parent;
        private Field client;
        private Field server;
        private Method sendAdd;
        private Method sendRemove;
        private Method newTable;
        private Object platform;
        private Class<?> table;

        private void lookup() throws ReflectiveOperationException {
            if (typeFromId != null) {
                return;
            }
            Class<?> dictionary = Class.forName("zombie.world.WorldDictionary");
            modFromId = dictionary.getMethod("getItemModID", short.class);
            registryId = dictionary.getMethod("getItemRegistryID", String.class);
            Class<?> manager = Class.forName("zombie.scripting.ScriptManager");
            scripts = manager.getField("instance").get(null);
            getItem = manager.getMethod("getItem", String.class);
            loadItem = Class.forName("zombie.inventory.InventoryItem").getMethod("loadItem", ByteBuffer.class, int.class);
            Class<?> container = Class.forName("zombie.inventory.ItemContainer");
            addItem = container.getMethod("AddItem", Class.forName("zombie.inventory.InventoryItem"));
            setId = Class.forName("zombie.inventory.InventoryItem").getMethod("setID", int.class);
            containingItem = container.getField("containingItem");
            parent = container.getField("parent");
            client = Class.forName("zombie.network.GameClient").getField("client");
            Class<?> gameServer = Class.forName("zombie.network.GameServer");
            server = gameServer.getField("server");
            Class<?> itemType = Class.forName("zombie.inventory.InventoryItem");
            sendAdd = gameServer.getMethod("sendAddItemToContainer", container, itemType);
            sendRemove = gameServer.getMethod("sendRemoveItemFromContainer", container, itemType);
            Class<?> lua = Class.forName("zombie.Lua.LuaManager");
            platform = lua.getField("platform").get(null);
            newTable = platform.getClass().getMethod("newTable");
            table = Class.forName("se.krka.kahlua.vm.KahluaTable");
            typeFromId = dictionary.getMethod("getItemTypeFromID", short.class);
        }

        @Override
        public String[] typeOf(short registryId) {
            try {
                lookup();
                return new String[] {(String) typeFromId.invoke(null, registryId), (String) modFromId.invoke(null, registryId)};
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public short idOf(String fullType) {
            try {
                lookup();
                if (getItem.invoke(scripts, fullType) == null) {
                    return -1;
                }
                return (Short) registryId.invoke(null, fullType);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return -1;
            }
        }

        @Override
        public Object load(ByteBuffer record, int version) throws Exception {
            lookup();
            return loadItem.invoke(null, record, version);
        }

        @Override
        public void add(Object container, Object item) throws Exception {
            lookup();
            addItem.invoke(container, item);
            tellClients(sendAdd, container, item);
        }

        /** On a server: the clients near the container get the change. */
        private void tellClients(Method send, Object container, Object item) {
            try {
                if (server.getBoolean(null)) {
                    send.invoke(null, container, item);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // the server has it; clients see it when they get the chunk again
                System.out.println("[Jumpstart] items of missing mods: clients not told: " + e);
            }
        }

        @Override
        public void setId(Object item, int id) throws Exception {
            lookup();
            setId.invoke(item, id);
        }

        @Override
        public Object ownerOf(Object container) {
            try {
                lookup();
                Object bag = containingItem.get(container);
                return bag != null ? bag : parent.get(container);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        /** hasModData and getModData per class: every loaded container's owner and items pass here. */
        private final Map<Class<?>, Method[]> modDataMethods = new ConcurrentHashMap<>();
        private Method fullType;
        private Method getItems;
        private Method rawgetMethod;
        private Method rawsetMethod;

        private Object modData(Object owner, boolean create) throws ReflectiveOperationException {
            Method[] m = modDataMethods.get(owner.getClass());
            if (m == null) {
                m = new Method[] {owner.getClass().getMethod("hasModData"), owner.getClass().getMethod("getModData")};
                modDataMethods.put(owner.getClass(), m);
            }
            if (!create && !(Boolean) m[0].invoke(owner)) {
                return null;
            }
            return m[1].invoke(owner);
        }

        private Object rawget(Object t, Object key) throws ReflectiveOperationException {
            if (rawgetMethod == null) {
                rawgetMethod = table.getMethod("rawget", Object.class);
            }
            return rawgetMethod.invoke(t, key);
        }

        private void rawset(Object t, Object key, Object value) throws ReflectiveOperationException {
            if (rawsetMethod == null) {
                rawsetMethod = table.getMethod("rawset", Object.class, Object.class);
            }
            rawsetMethod.invoke(t, key, value);
        }

        @Override
        public List<Kept> read(Object owner) {
            List<Kept> kept = new ArrayList<>();
            try {
                lookup();
                Object data = modData(owner, false);
                Object list = data == null ? null : rawget(data, KEY);
                if (list == null || !table.isInstance(list)) {
                    return kept;
                }
                for (int i = 1; ; i++) {
                    Object e = rawget(list, (double) i);
                    if (e == null || !table.isInstance(e)) {
                        return kept;
                    }
                    Kept k = fromTable(e);
                    if (k != null) {
                        kept.add(k);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                return kept;
            }
        }

        @Override
        public void write(Object owner, List<Kept> kept) {
            try {
                lookup();
                Object data = modData(owner, !kept.isEmpty());
                if (data == null) {
                    return;
                }
                if (kept.isEmpty()) {
                    rawset(data, KEY, null);
                    return;
                }
                Object list = newTable.invoke(platform);
                int i = 1;
                for (Kept k : kept) {
                    rawset(list, (double) i++, toTable(k));
                }
                rawset(data, KEY, list);
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.out.println("[Jumpstart] cannot keep items of a missing mod: " + e);
            }
        }

        @Override
        public boolean multiplayerClient() {
            try {
                lookup();
                return client.getBoolean(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return true; // unknown: change nothing
            }
        }

        private Object toTable(Kept k) throws ReflectiveOperationException {
            Object e = newTable.invoke(platform);
            rawset(e, "type", k.type());
            rawset(e, "mod", k.mod());
            rawset(e, "version", (double) k.version());
            rawset(e, "littleEndian", k.littleEndian());
            rawset(e, "bytes", encode(k.bytes()));
            if (k.copyIds().length > 0) {
                rawset(e, "copies", ids(k.copyIds()));
            }
            return e;
        }

        private Kept fromTable(Object e) throws ReflectiveOperationException {
            Object type = rawget(e, "type");
            Object mod = rawget(e, "mod");
            Object version = rawget(e, "version");
            Object little = rawget(e, "littleEndian");
            Object bytes = rawget(e, "bytes");
            Object copies = rawget(e, "copies");
            if (type instanceof String t && version instanceof Number v && bytes instanceof String b) {
                return new Kept(t, mod instanceof String m ? m : "", v.intValue(), Boolean.TRUE.equals(little), decode(b),
                        copies instanceof String c ? ids(c) : new int[0]);
            }
            return null;
        }

        @Override
        public Object placeholder(Kept kept, String name) {
            try {
                lookup();
                Object item = Class.forName("zombie.inventory.InventoryItemFactory").getMethod("CreateItem", String.class)
                        .invoke(null, PLACEHOLDER);
                if (item == null) {
                    return null;
                }
                Class<?> type = Class.forName("zombie.inventory.InventoryItem");
                type.getMethod("setName", String.class).invoke(item, name);
                type.getMethod("setCustomName", boolean.class).invoke(item, true);
                Object data = type.getMethod("getModData").invoke(item);
                rawset(data, KEY, toTable(kept));
                return item;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.out.println("[Jumpstart] no placeholder for an item of a missing mod: " + e);
                return null;
            }
        }

        @Override
        public Kept keptIn(Object item) {
            try {
                lookup();
                if (fullType == null) {
                    fullType = Class.forName("zombie.inventory.InventoryItem").getMethod("getFullType");
                }
                if (!PLACEHOLDER.equals(fullType.invoke(item))) {
                    return null; // almost every item: no ModData touched
                }
                Object data = modData(item, false);
                Object e = data == null ? null : rawget(data, KEY);
                return e != null && table.isInstance(e) ? fromTable(e) : null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public List<Object> items(Object container) {
            try {
                if (getItems == null) {
                    getItems = Class.forName("zombie.inventory.ItemContainer").getMethod("getItems");
                }
                Object items = getItems.invoke(container);
                return items instanceof List<?> l ? new ArrayList<>(l) : List.of();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return List.of();
            }
        }

        @Override
        public void remove(Object container, Object item) throws Exception {
            lookup();
            container.getClass().getMethod("Remove", Class.forName("zombie.inventory.InventoryItem")).invoke(container, item);
            tellClients(sendRemove, container, item);
        }

        @Override
        public String language() {
            try {
                return String.valueOf(Class.forName("zombie.core.Translator").getMethod("getLanguage").invoke(null));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "EN";
            }
        }
    }
}
