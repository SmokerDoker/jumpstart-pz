package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MissingItemsTest {

    /** A loaded item: its bytes as the game got them, and the id it was given. */
    private static final class Item {
        final byte[] bytes;
        int id;

        Item(byte[] bytes) {
            this.bytes = bytes;
        }
    }

    /** A placeholder the fake game made: the name it shows, what it carries. */
    private record Holder(String name, MissingItems.Kept kept) {
    }

    private static final class FakeGame implements MissingItems.Game {
        boolean placeholders = true;
        final Map<Short, String[]> types = new HashMap<>();
        final Map<String, Short> loadedTypes = new HashMap<>();
        final Map<Object, Object> owners = new IdentityHashMap<>();
        final Map<Object, List<MissingItems.Kept>> modData = new IdentityHashMap<>();
        final Map<Object, List<Object>> contents = new IdentityHashMap<>();
        boolean client;

        @Override public String[] typeOf(short id) { return types.get(id); }
        @Override public short idOf(String type) { return loadedTypes.getOrDefault(type, (short) -1); }

        @Override
        public Object load(ByteBuffer record, int version) {
            byte[] b = new byte[record.remaining()];
            record.duplicate().get(b);
            return new Item(b);
        }

        @Override public void add(Object container, Object item) { contents.computeIfAbsent(container, c -> new ArrayList<>()).add(item); }
        @Override public void setId(Object item, int id) { ((Item) item).id = id; }
        @Override public Object ownerOf(Object container) { return owners.get(container); }
        @Override public List<MissingItems.Kept> read(Object owner) { return modData.getOrDefault(owner, List.of()); }

        @Override
        public void write(Object owner, List<MissingItems.Kept> kept) {
            if (kept.isEmpty()) {
                modData.remove(owner);
            } else {
                modData.put(owner, new ArrayList<>(kept));
            }
        }

        @Override public boolean multiplayerClient() { return client; }
        @Override public Object placeholder(MissingItems.Kept kept, String name) { return placeholders ? new Holder(name, kept) : null; }
        @Override public MissingItems.Kept keptIn(Object item) { return item instanceof Holder h ? h.kept() : null; }
        @Override public List<Object> items(Object container) { return new ArrayList<>(contents.getOrDefault(container, List.of())); }
        @Override public void remove(Object container, Object item) { contents.get(container).remove(item); }
        @Override public String language() { return "DE"; }
    }

    private final FakeGame game = new FakeGame();
    private final Object crate = new Object();
    private final Object crateObject = new Object();

    @BeforeEach
    void setUp() {
        MissingItems.useForTest(game);
        game.types.put((short) 812, new String[] {"SwordMod.Katana", "SwordMod"});
        game.owners.put(crate, crateObject);
    }

    @AfterEach
    void tearDown() {
        MissingItems.useForTest(new FakeGame());
    }

    /** What a container's items look like in the save: count, the item (size, registry id, data), the copies' ids. */
    private static ByteBuffer containerWith(int count, short registryId, int... copyIds) {
        ByteBuffer b = ByteBuffer.allocate(64);
        b.putInt(count);
        b.putInt(7); // size of what follows
        b.putShort(registryId);
        b.put(new byte[] {1, 2, 3, 4, 5});
        for (int id : copyIds) {
            b.putInt(id);
        }
        b.flip();
        return b;
    }

    /** The game's InventoryItem.loadItem for an unknown type: reads the size, skips the item, returns nothing. */
    private static void loadUnknownItem(ByteBuffer b) {
        MissingItems.itemEnter(b);
        int size = b.getInt();
        b.position(b.position() + size);
        MissingItems.itemExit(null, b, 219);
    }

    private static void settle() {
        MissingItems.onFrame(System.currentTimeMillis() + MissingItems.SETTLE_MS + 1);
    }

    @Test
    void aPlaceholderTakesTheMissingItemsPlaceAndCarriesItsCopies() {
        ByteBuffer b = containerWith(3, (short) 812, 1001, 1002);
        MissingItems.containerEnter(crate);
        b.getInt(); // the count
        loadUnknownItem(b);
        MissingItems.containerExit(crate);
        assertTrue(game.contents.isEmpty(), "a moment later, on the main thread");
        settle();

        assertEquals(1, game.contents.get(crate).size());
        Holder holder = (Holder) game.contents.get(crate).get(0);
        assertEquals("Mod fehlt (SwordMod): Katana x3", holder.name());
        assertArrayEquals(new int[] {1001, 1002}, holder.kept().copyIds());
        assertTrue(game.modData.isEmpty(), "nothing invisible with the owner");

        // the placeholder moved to the player's bag; the mod is back; the bag loads
        Object bag = new Object();
        game.contents.get(crate).clear();
        game.contents.computeIfAbsent(bag, c -> new ArrayList<>()).add(holder);
        game.loadedTypes.put("SwordMod.Katana", (short) 40);
        MissingItems.containerEnter(bag);
        MissingItems.containerExit(bag);
        settle();
        List<Object> items = game.contents.get(bag);
        assertEquals(3, items.size(), "the item and both copies; the placeholder is gone");
        assertEquals(List.of(0, 1001, 1002), items.stream().map(i -> ((Item) i).id).toList());
        assertEquals(40, ByteBuffer.wrap(((Item) items.get(0)).bytes).getShort(4), "the registry id of this save");
    }

    @Test
    void withoutAPlaceholderItWaitsInItsOwnersDataWithItsCopies() {
        game.placeholders = false;
        ByteBuffer b = containerWith(3, (short) 812, 1001, 1002);
        MissingItems.containerEnter(crate);
        b.getInt(); // the count
        loadUnknownItem(b);
        MissingItems.containerExit(crate);

        assertTrue(game.modData.isEmpty(), "a moment later: the owner's own data loads after its containers");
        settle();
        List<MissingItems.Kept> kept = game.modData.get(crateObject);
        assertEquals(1, kept.size());
        assertEquals("SwordMod.Katana", kept.get(0).type());
        assertEquals("SwordMod", kept.get(0).mod());
        assertEquals(219, kept.get(0).version());
        assertEquals(11, kept.get(0).bytes().length, "size and item");
        assertArrayEquals(new int[] {1001, 1002}, kept.get(0).copyIds());
    }

    @Test
    void whenTheModIsBackEveryCopyIsInItsPlaceAgain() {
        game.modData.put(crateObject, new ArrayList<>(List.of(
                new MissingItems.Kept("SwordMod.Katana", "SwordMod", 219, false, containerBytes(), new int[] {1001, 1002}))));
        MissingItems.containerEnter(crate);
        MissingItems.containerExit(crate);
        settle();
        assertTrue(game.contents.isEmpty(), "the mod is still off: they keep waiting");
        assertEquals(1, game.modData.get(crateObject).size());

        game.loadedTypes.put("SwordMod.Katana", (short) 40); // back, with another registry id in this save
        MissingItems.containerEnter(crate);
        MissingItems.containerExit(crate);
        settle();
        List<Object> items = game.contents.get(crate);
        assertEquals(3, items.size());
        assertEquals(40, ByteBuffer.wrap(((Item) items.get(0)).bytes).getShort(4), "the registry id of this save");
        assertEquals(List.of(0, 1001, 1002), items.stream().map(i -> ((Item) i).id).toList(), "the copies keep their own ids");
        assertTrue(!game.modData.containsKey(crateObject), "nothing left waiting");
    }

    private static byte[] containerBytes() {
        ByteBuffer b = containerWith(1, (short) 812);
        b.getInt();
        byte[] record = new byte[b.remaining()];
        b.get(record);
        return record;
    }

    @Test
    void aPlaceholderWaitsWhileTheModIsStillOff() {
        Holder holder = new Holder("x", new MissingItems.Kept("SwordMod.Katana", "SwordMod", 219, false, containerBytes(), new int[0]));
        game.contents.computeIfAbsent(crate, c -> new ArrayList<>()).add(holder);
        MissingItems.containerEnter(crate);
        MissingItems.containerExit(crate);
        settle();
        assertEquals(List.of(holder), game.contents.get(crate));
        assertEquals("Missing mod (?): Katana", MissingItems.placeholderName(
                new MissingItems.Kept("SwordMod.Katana", "", 219, false, new byte[0], new int[0]), "EN"));
    }

    @Test
    void anItemInsideAnotherItemHasNoCopies() {
        game.placeholders = false;
        ByteBuffer b = containerWith(3, (short) 812, 1001, 1002);
        MissingItems.containerEnter(crate);
        b.getInt();
        MissingItems.itemEnter(b); // a known item that holds the missing one
        loadUnknownItem(b);
        MissingItems.itemExit(new Object(), b, 219);
        MissingItems.containerExit(crate);
        settle();
        assertArrayEquals(new int[0], game.modData.get(crateObject).get(0).copyIds());
    }

    @Test
    void notInAContainerOrInMultiplayerNothingIsKept() {
        game.placeholders = false;
        ByteBuffer b = containerWith(1, (short) 812);
        b.getInt();
        loadUnknownItem(b); // a floor item: no container around it
        settle();
        assertTrue(game.modData.isEmpty());

        game.client = true;
        ByteBuffer c = containerWith(1, (short) 812);
        MissingItems.containerEnter(crate);
        c.getInt();
        loadUnknownItem(c);
        MissingItems.containerExit(crate);
        settle();
        assertTrue(game.modData.isEmpty(), "in multiplayer the server owns the containers");
    }

    @Test
    void anItemTheGameLoadsIsLeftAlone() {
        game.placeholders = false;
        ByteBuffer b = containerWith(1, (short) 812);
        MissingItems.containerEnter(crate);
        b.getInt();
        MissingItems.itemEnter(b);
        b.position(b.limit());
        MissingItems.itemExit(new Object(), b, 219);
        MissingItems.containerExit(crate);
        settle();
        assertTrue(game.modData.isEmpty());
    }

    @Test
    void idsAndBytesSurviveTheModData() {
        assertArrayEquals(new int[] {5, -3, 70000}, MissingItems.ids(MissingItems.ids(new int[] {5, -3, 70000})));
        assertArrayEquals(new int[0], MissingItems.ids(""));
        byte[] bytes = {0, -1, 127, 3};
        assertArrayEquals(bytes, MissingItems.decode(MissingItems.encode(bytes)));
    }
}
