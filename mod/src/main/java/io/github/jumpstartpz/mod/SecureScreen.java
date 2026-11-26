package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import io.github.bitecheckpz.core.Verifier;
import io.github.bitecheckpz.launcher.UsageSharing;
import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.gate.BiteCheckGate;
import io.github.jumpstartpz.core.market.LibraryClient;

/**
 * Jumpstart's secure screen: the only place where Java mods are loaded, updated or switched, where
 * mods are installed from the marketplace, and where the player answers BiteCheck's questions.
 *
 * <p>Lua mods are not checked by anyone and draw everything else the player sees, so none of this
 * may depend on Lua. The screen is drawn by Java at the very end of the game's UI pass (after
 * every Lua window and OnPostUIDraw), opens only on the physical keys Ctrl+Shift+J, and reads
 * only the physical keyboard through GLFW, never the game's key queue that Lua can fill. While it
 * is open, the game and Lua get no keyboard and no UI input. A question can only come up after the
 * player opened the screen and pressed Enter on a mod there, and the answer key works only after a
 * few seconds, so a reflex cannot confirm it.
 *
 * <p>Tabs: installed Java mods, the Java and the Lua library (Steam Workshop, searched through the
 * Jumpstart cloud), and voluntary sharing of unverified mods with BiteCheck.
 *
 * <p>The game calls {@link #onRender()} and {@link #blocksInput()} through {@link GameHooks};
 * everything runs on the game's main thread except network and hashing, which run on the
 * background executor and hand their results back to the next frame.
 */
public final class SecureScreen {
    // GLFW key codes
    static final int KEY_SPACE = 32;
    static final int KEY_MINUS = 45;
    static final int KEY_ESCAPE = 256;
    static final int KEY_ENTER = 257;
    static final int KEY_TAB = 258;
    static final int KEY_BACKSPACE = 259;
    static final int KEY_KP_ENTER = 335;
    static final int KEY_UP = 265;
    static final int KEY_DOWN = 264;
    static final int KEY_PAGE_UP = 266;
    static final int KEY_PAGE_DOWN = 267;
    static final int KEY_A = 65;
    static final int KEY_J = 74;
    static final int KEY_N = 78;
    static final int KEY_R = 82;
    static final int KEY_U = 85;
    static final int KEY_Y = 89;
    static final int KEY_LEFT_SHIFT = 340;
    static final int KEY_LEFT_CONTROL = 341;
    static final int KEY_RIGHT_SHIFT = 344;
    static final int KEY_RIGHT_CONTROL = 345;
    private static final int[] WATCHED = watched();
    /** A pretend key for tests: the Ctrl+Shift+J combination. */
    static final int OPEN = -1;

    /** How long the answer key does nothing after a question comes up. */
    static final long FIRST_DELAY_MS = 3000;
    static final long SECOND_DELAY_MS = 5000;
    /** How long a Workshop download may take. */
    static final long DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000;

    enum Mode { CLOSED, LIST, CONFIRM_ONCE, CONFIRM_TWICE, SERVER_CONSENT, UNINSTALL_ONCE, UNINSTALL_TWICE, DEPENDENCY }

    /** How long the answer key does nothing in the two uninstall questions. */
    static final long UNINSTALL_FIRST_DELAY_MS = 1500;
    static final long UNINSTALL_SECOND_DELAY_MS = 3000;

    /** How long the player's "yes" to an admin's change does nothing. */
    static final long CONSENT_DELAY_MS = 2000;

    enum Tab { INSTALLED, JAVA_LIBRARY, LUA_LIBRARY, SHARING, SNAPSHOTS, SERVER }

    /** Jumpstart's own mod id: listed on the installed tab, never switched from in here. */
    static final String SELF = "Jumpstart";

    private static volatile SecureScreen current;
    private static volatile boolean reportedError;

    private final GameScreen game;
    private final Actions actions;
    private final boolean testInput;
    private final Executor background;
    private final ClientSession session;
    private final Set<Integer> held = new HashSet<>();
    private final Deque<Integer> injected = new ArrayDeque<>();
    private final Deque<Runnable> toMainThread = new ArrayDeque<>();
    private final Map<Path, BiteCheckGate.Check> checks = new HashMap<>();
    private Texts texts;

    private Mode mode = Mode.CLOSED;
    private Tab tab = Tab.INSTALLED;
    private boolean waitForRelease;
    private String message = "";
    private boolean messageGood;

    // installed tab
    private List<Actions.InstalledMod> mods = List.of();
    /** The mod the uninstall questions are about. */
    private Actions.InstalledMod uninstalling;
    private final Map<String, String> states = new HashMap<>();
    private int selected;
    private Pending pending;
    private long questionSince;

    // library tabs
    private final Map<Tab, Library> libraries = new HashMap<>();
    private Download download;
    /** A downloaded Java mod whose Lua part follows once its Java part loaded: mod id, Workshop title. */
    private String[] luaAfterJava;
    /** The other mods of that Workshop item: their Lua follows too (they may use the Java part). */
    private List<String> luaAlsoAfterJava = List.of();
    /** Mods that were on in the mod list but not running, which A already tried to load live. */
    private final Set<String> liveTried = new HashSet<>();

    // crash protection: errors the ZombieBuddy fork caught in mods
    private final GuardFeed guard = new GuardFeed();
    /** Mod id to the fork's "count TAB state", for the list. */
    private final Map<String, String> guardStates = new HashMap<>();
    private String banner;
    private long bannerUntil;
    static final long BANNER_MS = 10_000;

    // snapshots tab (main menu only) and the snapshot taken before switching
    private List<String> snapshotList = List.of();
    private int snapshotSelected;
    /** The snapshot the first Enter chose; a second Enter restores it. */
    private int snapshotConfirm = -1;
    private String lastSnapshotState;

    // sharing tab
    private boolean sharing;
    private String sharingPreview = "";
    private boolean sharingStarted;

    // detail pane (installed mods and libraries): description scrolled with Shift+Up/Down
    private int detailScroll;
    private String detailKey = "";
    private boolean shiftHeld;
    /** Workshop pages from Steam (full description, preview), by item id. */
    private final Map<Long, Actions.Details> pages = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<Long> pageRequested = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private long pageCandidate;
    private long pageCandidateSince;
    /** Wrapped description lines, kept while the text and width stay the same. */
    private String wrappedText = "";
    private int wrappedWidth = -1;
    private List<String> wrappedLines = List.of();

    // --- the mouse: physical (GLFW), a click on a button is the key it shows ---------------------------
    /** A clickable area: the key it stands for (every rule and delay of that key applies), or an action. */
    private record Hit(int x, int y, int w, int h, int key, Runnable action) {
        boolean contains(int[] p) {
            return p != null && p[0] >= x && p[0] < x + w && p[1] >= y && p[1] < y + h;
        }

        /** The same button in another frame: same place, size and key (actions are new objects every frame). */
        boolean same(Hit o) {
            return o != null && x == o.x && y == o.y && w == o.w && h == o.h && key == o.key;
        }
    }

    private List<Hit> hits = new ArrayList<>();
    private List<Hit> lastHits = List.of();
    private int[] mouse;
    private boolean mouseDown;
    private Hit pressedHit;
    /** {x, y, w, h} of the list and the detail pane, for the wheel. */
    private int[] listArea;
    private int[] detailArea;
    private int[] lastListArea;
    private int[] lastDetailArea;

    /** {@code server}: the server asked for this mod (multiplayer update); the answer goes back to it. */
    private record Pending(ModEntry mod, BiteCheckGate.Check check, boolean update, boolean server) {
    }

    // multiplayer
    private int serverSelected;
    /** Phase 2 of a server change: what this client prepared in phase 1, run on the server's commit. */
    private Runnable serverCommit;
    private boolean lookBusy;
    /** The time of the current frame, for work handed over from other threads. */
    private long frameTime;

    /** One library tab: the search text, the results and where the next page starts. */
    private static final class Library {
        final LibraryClient.Kind kind;
        final StringBuilder query = new StringBuilder();
        String searched; // the query of the shown results, null before the first search
        List<LibraryClient.Item> items = new ArrayList<>();
        String next = "";
        int selected;
        boolean busy;
        String status = "";

        Library(LibraryClient.Kind kind) {
            this.kind = kind;
        }
    }

    /** A Workshop item being subscribed and downloaded. */
    private record Download(LibraryClient.Item item, long since) {
    }

    /**
     * @param testInput  whether {@link #inject} works (JVM option -Djumpstart.testInput=true, only
     *                   for automated tests; read once at start, so Lua cannot turn it on)
     * @param background runs network and hashing work off the main thread
     */
    SecureScreen(GameScreen game, Actions actions, boolean testInput, Executor background, ServerLink link) {
        this.game = game;
        this.actions = actions;
        this.testInput = testInput;
        this.background = background;
        this.session = new ClientSession(link);
        libraries.put(Tab.JAVA_LIBRARY, new Library(LibraryClient.Kind.JAVA));
        libraries.put(Tab.LUA_LIBRARY, new Library(LibraryClient.Kind.LUA));
    }

    private static int[] watched() {
        List<Integer> keys = new ArrayList<>(List.of(KEY_SPACE, KEY_MINUS, KEY_ESCAPE, KEY_ENTER, KEY_TAB, KEY_BACKSPACE,
                KEY_KP_ENTER, KEY_UP, KEY_DOWN, KEY_PAGE_UP, KEY_PAGE_DOWN, KEY_LEFT_SHIFT, KEY_LEFT_CONTROL,
                KEY_RIGHT_SHIFT, KEY_RIGHT_CONTROL));
        for (int k = '0'; k <= '9'; k++) {
            keys.add(k);
        }
        for (int k = 'A'; k <= 'Z'; k++) {
            keys.add(k);
        }
        return keys.stream().mapToInt(Integer::intValue).toArray();
    }

    static void install(SecureScreen screen) {
        current = screen;
    }

    static SecureScreen current() {
        return current;
    }

    /** Called by the game at the end of every UI render pass. */
    public static void onRender() {
        LuaStartJournal.onFrame(); // the first frame after the game loaded its Lua ends that record
        SecureScreen screen = current;
        if (screen == null) {
            return;
        }
        try {
            screen.frame(System.currentTimeMillis());
        } catch (Throwable t) { // never break the game's render loop
            if (!reportedError) {
                reportedError = true;
                System.out.println("[Jumpstart] secure screen error: " + t);
                t.printStackTrace(System.out);
            }
        }
    }

    /** Called by the game before it handles keyboard and UI input: true skips that input. */
    public static boolean blocksInput() {
        SecureScreen screen = current;
        return screen != null && (screen.mode != Mode.CLOSED || screen.waitForRelease);
    }

    // --- one frame -----------------------------------------------------------------------------

    // --- work that holds the game for a moment: shown first, done in the next frame ----------------

    /** What is about to run (switching, loading, uninstalling...): drawn this frame, run in the next. */
    private Runnable busyWork;
    private String busyText;
    /** The busy notice has been on screen for one frame: the work may run (the game stands still meanwhile). */
    private boolean busyShown;

    /**
     * Runs {@code work} after the screen showed {@code text} for one frame. The work runs on the game's
     * main thread and may hold the game for a moment; the last picture before that is the notice,
     * not the old state. Keys and clicks wait meanwhile.
     */
    private void busy(String text, Runnable work) {
        if (busyWork != null) {
            return; // one at a time; the keys that would start another wait anyway
        }
        busyText = text;
        busyWork = work;
        busyShown = false;
    }

    String busyText() {
        return busyText;
    }

    private void runBusyWork() {
        if (busyWork == null || !busyShown) {
            return;
        }
        Runnable work = busyWork;
        busyWork = null;
        busyText = null;
        try {
            work.run();
        } catch (RuntimeException e) {
            say(String.valueOf(e), false);
        }
    }

    /** The notice in the middle of the screen, over everything. */
    private void drawBusy(long now) {
        if (busyText == null) {
            return;
        }
        String wait = texts.get("busy.wait");
        int w = Math.min(game.width() - 40, Math.max(game.textWidth(GameScreen.Font.LARGE, busyText),
                game.textWidth(GameScreen.Font.SMALL, wait)) + 64);
        int h = game.fontHeight(GameScreen.Font.LARGE) + game.fontHeight(GameScreen.Font.SMALL) + 40;
        int x = (game.width() - w) / 2;
        int y = (game.height() - h) / 2;
        game.rect(0, 0, game.width(), game.height(), 0f, 0f, 0f, 0.35f);
        game.rect(x, y, w, h, 0.09f, 0.11f, 0.14f, 0.98f);
        game.rect(x, y, w, 4, 0.35f, 0.75f, 0.45f, 1f);
        game.text(GameScreen.Font.LARGE, x + 32, y + 14, fit(GameScreen.Font.LARGE, busyText, w - 64), 1f, 1f, 1f, 1f);
        game.text(GameScreen.Font.SMALL, x + 32, y + 22 + game.fontHeight(GameScreen.Font.LARGE),
                fit(GameScreen.Font.SMALL, wait, w - 64), 0.7f, 0.7f, 0.7f, 1f);
    }

    /** "Downloading <title> 45 %  (2 more waiting)", or null when nothing downloads. */
    String downloadStatus() {
        Download d = download;
        if (d == null) {
            return null;
        }
        long[] p = actions.workshop().progress(d.item().id());
        String percent = p != null && p[1] > 0 ? "  " + Math.min(100, p[0] * 100 / p[1]) + " %" : "";
        String more = downloadQueue.isEmpty() ? "" : "  " + String.format(Locale.ROOT, texts.get("download.more"), downloadQueue.size());
        return String.format(Locale.ROOT, texts.get("download.status"), d.item().title()) + percent + more;
    }

    /** With the screen closed: during phase 1 of a server change, what the player has to do. */
    private void drawServerLine(long now) {
        ClientSession.Update u = session.update;
        if (u == null || u.phase == ClientSession.Phase.APPLYING) {
            return;
        }
        long left = Math.max(0, (u.deadline - now) / 1000);
        String text;
        boolean act = false;
        if (u.phase == ClientSession.Phase.CONSENT) {
            text = String.format(Locale.ROOT, texts.get("mp.line.consent"), u.by, actionText(u.action), u.mod, left);
            act = true;
        } else if (pending != null && pending.server()) {
            text = String.format(Locale.ROOT, texts.get("mp.line.question"), u.mod, left);
            act = true;
        } else if (session.answered()) {
            text = texts.get(u.phase == ClientSession.Phase.FAILED ? "mp.line.failed" : "mp.line.ready");
        } else {
            text = (u.status.isEmpty() ? texts.get("mp.getting") : u.status) + "  " + String.format(Locale.ROOT, texts.get("mp.left"), left);
        }
        text = "Jumpstart: " + text;
        int w = Math.min(game.width() / 2 - 30, game.textWidth(GameScreen.Font.SMALL, text) + 24);
        int h = game.fontHeight(GameScreen.Font.SMALL) + 10;
        if (act) {
            game.rect(20, 12, w, h, 0.35f, 0.25f, 0.05f, 0.92f);
        } else {
            game.rect(20, 12, w, h, 0.07f, 0.09f, 0.11f, 0.85f);
        }
        game.text(GameScreen.Font.SMALL, 32, 17, fit(GameScreen.Font.SMALL, text, w - 24), 1f, act ? 0.9f : 0.95f, act ? 0.5f : 0.8f, 1f);
    }

    /** With the screen closed: a small line at the top while a download runs. */
    private void drawDownloadLine() {
        String status = downloadStatus();
        if (status == null) {
            return;
        }
        String text = "Jumpstart: " + status;
        int w = Math.min(game.width() - 40, game.textWidth(GameScreen.Font.SMALL, text) + 24);
        int h = game.fontHeight(GameScreen.Font.SMALL) + 10;
        int x = game.width() - w - 20;
        game.rect(x, 12, w, h, 0.07f, 0.09f, 0.11f, 0.85f);
        game.text(GameScreen.Font.SMALL, x + 12, 17, fit(GameScreen.Font.SMALL, text, w - 24), 0.75f, 0.95f, 0.8f, 1f);
    }

    void frame(long now) {
        if (texts == null) {
            texts = new Texts(game.language());
        }
        frameTime = now;
        runMainThreadWork();
        runBusyWork();
        followGuard(now);
        session.tick();
        Set<Integer> pressed = poll();
        if (mode != Mode.CLOSED) {
            pollMouse(pressed);
        }
        if (busyWork != null) {
            pressed.clear(); // something is about to run: no second action on top of it
        }
        if (download != null && busyWork == null) {
            followDownload(now);
        }
        if (download == null && busyWork == null && !downloadQueue.isEmpty() && (mode == Mode.LIST || mode == Mode.CLOSED)) {
            nextDownload(now); // the next required item (or the item itself) once the last one is in
        }
        if (session.update != null) {
            followServerUpdate(now);
        }
        switch (mode) {
            case CLOSED -> {
                if (waitForRelease && held.isEmpty()) {
                    waitForRelease = false;
                }
                if (pressed.contains(OPEN)) {
                    open();
                } else {
                    if (!game.inGame()) {
                        drawHint();
                    }
                    drawBanner(now);
                }
            }
            case LIST -> onKeys(pressed, now);
            case CONFIRM_ONCE, CONFIRM_TWICE -> onQuestion(pressed, now);
            case SERVER_CONSENT -> onServerConsent(pressed, now);
            case UNINSTALL_ONCE, UNINSTALL_TWICE -> onUninstallQuestion(pressed, now);
            case DEPENDENCY -> onDepQuestion(pressed);
        }
        if (mode != Mode.CLOSED) {
            hits = new ArrayList<>();
            listArea = null;
            detailArea = null;
            draw(now);
            drawBusy(now);
            game.drawCursor(); // the game's cursor (if it draws one) on top of the screen
            lastHits = hits;
            lastListArea = listArea;
            lastDetailArea = detailArea;
        } else {
            lastHits = List.of();
            mouseDown = false;
            pressedHit = null;
            drawDownloadLine();
            drawServerLine(now);
            drawBusy(now); // work started with the screen closed (a download that came in) shows too
        }
        if (busyWork != null) {
            busyShown = true; // on screen now: the next frame runs it
        }
    }

    /**
     * The physical mouse: a click counts when the button goes down and up on the same button, which
     * then acts like its key; the wheel scrolls the list or the description under the cursor.
     */
    private void pollMouse(Set<Integer> pressed) {
        mouse = game.mousePosition();
        boolean down = game.physicalMouseDown(0);
        if (down && !mouseDown) {
            pressedHit = hitAt(mouse);
        } else if (!down && mouseDown) {
            Hit h = hitAt(mouse);
            if (h != null && h.same(pressedHit)) {
                if (h.action() != null) {
                    h.action().run();
                } else {
                    pressed.add(h.key());
                }
            }
            pressedHit = null;
        }
        mouseDown = down;
        int wheel = game.wheel();
        if (wheel != 0 && mode == Mode.LIST) {
            scroll(wheel);
        }
    }

    private Hit hitAt(int[] p) {
        for (int i = lastHits.size() - 1; i >= 0; i--) {
            if (lastHits.get(i).contains(p)) {
                return lastHits.get(i);
            }
        }
        return null;
    }

    private static boolean inside(int[] area, int[] p) {
        return area != null && p != null && p[0] >= area[0] && p[0] < area[0] + area[2] && p[1] >= area[1] && p[1] < area[1] + area[3];
    }

    /** The wheel: the description under the cursor, else the list's selection. */
    private void scroll(int wheel) {
        if (inside(lastDetailArea, mouse)) {
            detailScroll = Math.max(0, detailScroll - wheel * 3);
        } else if (inside(lastListArea, mouse)) {
            switch (tab) {
                case INSTALLED -> selected = Math.max(0, Math.min(mods.size() - 1, selected - wheel));
                case JAVA_LIBRARY, LUA_LIBRARY -> {
                    Library lib = libraries.get(tab);
                    lib.selected = Math.max(0, Math.min(lib.items.size() - 1, lib.selected - wheel));
                }
                case SERVER -> serverSelected = Math.max(0, Math.min(session.serverMods.size() - 1, serverSelected - wheel));
                default -> { }
            }
        }
    }

    /** A button that acts like {@code key} (or runs {@code action}); returns its width. */
    private int button(int x, int y, String label, int key, Runnable action, boolean enabled) {
        int h = buttonHeight();
        int w = game.textWidth(GameScreen.Font.MEDIUM, label) + 24;
        boolean over = enabled && new Hit(x, y, w, h, 0, null).contains(mouse);
        game.rect(x, y, w, h, enabled ? (over ? 0.32f : 0.2f) : 0.12f, enabled ? (over ? 0.58f : 0.4f) : 0.12f,
                enabled ? (over ? 0.38f : 0.27f) : 0.13f, 1f);
        float c = enabled ? 1f : 0.5f;
        game.text(GameScreen.Font.MEDIUM, x + 12, y + 5, label, c, c, c, 1f);
        hits.add(new Hit(x, y, w, h, key, action)); // also when greyed out: the key's own rule decides (J too early)
        return w;
    }

    private int buttonHeight() {
        return game.fontHeight(GameScreen.Font.MEDIUM) + 10;
    }

    /** Yes and No under a question; Yes looks off until its delay is over (the J key's rule applies either way). */
    private void answerButtons(int x, int bottom, long waitMs) {
        int y = bottom - game.fontHeight(GameScreen.Font.MEDIUM) - buttonHeight() - 12;
        String yes = waitMs > 0 ? String.format(Locale.ROOT, texts.get("btn.yesWait"), (waitMs + 999) / 1000) : texts.get("btn.yes");
        int w = button(x, y, yes, KEY_J, null, waitMs <= 0);
        button(x + w + 12, y, texts.get("btn.no"), KEY_N, null, true);
    }

    private void switchTab(Tab to) {
        tab = to;
        if (tab == Tab.SERVER) {
            session.refresh();
        }
        message = "";
        if (tab == Tab.SHARING) {
            showSharing();
        }
        if (tab == Tab.SNAPSHOTS) {
            snapshotList = actions.snapshots();
            snapshotSelected = 0;
            snapshotConfirm = -1;
        }
    }

    /**
     * The fork switched a mod off (or refused it) after it threw: tell the player for a few seconds,
     * and stop the mod's Lua side too, so nothing of it keeps running half way. Runs every frame;
     * without new events it costs one call.
     */
    private void followGuard(long now) {
        List<GuardFeed.Event> events;
        try {
            events = guard.poll(actions);
        } catch (RuntimeException e) {
            return;
        }
        for (GuardFeed.Event e : events) {
            System.out.println("[Jumpstart] crash protection: " + e.modId() + " " + e.kind() + " " + e.where()
                    + " " + e.exception() + " -> " + e.action());
            if (!e.notable()) {
                continue;
            }
            banner = String.format(texts.get(e.action().equals("off") ? "guard.off" : "guard.refused"),
                    e.modId(), shortException(e.exception()), e.where());
            bannerUntil = now + BANNER_MS;
            if (e.action().equals("off")) {
                try {
                    actions.unloadLua(e.modId()); // main thread: this is the render pass
                } catch (RuntimeException ex) {
                    System.out.println("[Jumpstart] cannot stop the Lua of " + e.modId() + ": " + ex);
                }
            }
        }
        if (!events.isEmpty() && mode != Mode.CLOSED) {
            refreshStates();
        }
    }

    /** What stayed of a mod's Lua after switching it off ({@link Actions#luaLeftovers}), for the player. */
    String leftoversNote(String name, String leftovers) {
        if (leftovers == null || leftovers.isEmpty()) {
            return "";
        }
        String[] p = leftovers.split(":", 3);
        if (p.length < 3) {
            return "";
        }
        int count;
        try {
            count = Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return "";
        }
        return String.format(Locale.ROOT, texts.get(p[0].equals("kept") ? "lua.kept" : "lua.startLoaded"), name, count, p[2]);
    }

    /** "java.lang.NullPointerException: x" -> "NullPointerException". */
    static String shortException(String exception) {
        String s = exception == null ? "" : exception.strip();
        int colon = s.indexOf(':');
        if (colon > 0) {
            s = s.substring(0, colon);
        }
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    /** The banner line while it shows, else null. */
    String banner(long now) {
        return banner != null && now < bannerUntil ? banner : null;
    }

    private void drawBanner(long now) {
        String text = banner(now);
        if (text == null) {
            return;
        }
        int w = Math.min(game.width() - 40, game.textWidth(GameScreen.Font.MEDIUM, text) + 32);
        int h = game.fontHeight(GameScreen.Font.MEDIUM) + 16;
        int x = (game.width() - w) / 2;
        game.rect(x, 40, w, h, 0.25f, 0.06f, 0.05f, 0.92f);
        game.text(GameScreen.Font.MEDIUM, x + 16, 48, fit(GameScreen.Font.MEDIUM, text, w - 32), 1f, 0.85f, 0.8f, 1f);
    }

    /** The list's state label, with what the crash protection did. */
    private String stateLabel(String modId, String state) {
        String guardStatus = guardStates.get(modId);
        int count = GuardFeed.count(guardStatus);
        if (GuardFeed.offByGuard(guardStatus) && state.equals("off")) {
            return String.format(texts.get("guard.state"), count);
        }
        String label = texts.get("state." + state);
        return count > 0 ? label + "  (" + String.format(texts.get("guard.errors"), count) + ")" : label;
    }

    private void runMainThreadWork() {
        List<Runnable> work;
        synchronized (toMainThread) {
            work = new ArrayList<>(toMainThread);
            toMainThread.clear();
        }
        work.forEach(Runnable::run);
    }

    /** Runs work in the background and its result handler in a later frame on the main thread. */
    private void inBackground(Callable<?> work, Consumer<Object> onMainThread) {
        background.execute(() -> {
            Object result;
            try {
                result = work.call();
            } catch (Exception e) {
                result = e;
            }
            Object done = result;
            synchronized (toMainThread) {
                toMainThread.add(() -> onMainThread.accept(done));
            }
        });
    }

    /** Keys pressed since the last frame (physical, plus injected ones in tests). */
    private Set<Integer> poll() {
        Set<Integer> pressed = new LinkedHashSet<>(); // keeps the typing order of injected keys
        for (int key : WATCHED) {
            boolean down = game.physicalKeyDown(key);
            if (down && held.add(key)) {
                pressed.add(key);
            } else if (!down) {
                held.remove(key);
            }
        }
        boolean ctrl = held.contains(KEY_LEFT_CONTROL) || held.contains(KEY_RIGHT_CONTROL);
        boolean shift = held.contains(KEY_LEFT_SHIFT) || held.contains(KEY_RIGHT_SHIFT);
        shiftHeld = shift;
        if (pressed.contains(KEY_J) && ctrl && shift) {
            pressed.remove(KEY_J);
            pressed.add(OPEN);
        }
        synchronized (injected) {
            pressed.addAll(injected);
            injected.clear();
        }
        return pressed;
    }

    /** Tests only (-Djumpstart.testInput=true): a key press for the next frame. */
    boolean inject(int key) {
        if (!testInput) {
            return false;
        }
        synchronized (injected) {
            injected.add(key);
        }
        return true;
    }

    private void open() {
        mode = Mode.LIST;
        tab = Tab.INSTALLED;
        message = "";
        checks.clear();
        reload();
        selected = 0;
        sharing = actions.sharingEnabled();
        if (sharing && !sharingStarted) {
            sharingStarted = true; // once per game: send today's report if it is due
            inBackground(() -> actions.shareIfDue(actions.unverifiedJars()), r -> System.out.println("[Jumpstart] sharing: " + r));
        }
        game.pause(true);
        if (game.inGame()) {
            actions.beginSnapshot(); // a copy of the save before anything is switched
        }
        ClientSession.Update u = session.update;
        if (u != null && pending != null && pending.server()) {
            tab = Tab.SERVER; // BiteCheck's question about the server's jar waited for the player
            mode = Mode.CONFIRM_ONCE;
            questionSince = frameTime;
        } else if (u != null && u.phase == ClientSession.Phase.CONSENT) {
            tab = Tab.SERVER;
            mode = Mode.SERVER_CONSENT;
            u.since = frameTime; // the delay before J counts from now: no key pressed in the game answers it
        }
    }

    private void close() {
        mode = Mode.CLOSED;
        pending = null;
        waitForRelease = true; // keep the game's input off until Esc is released
        game.pause(false);
    }

    private void reload() {
        mods = actions.installedMods();
        refreshStates();
        if (selected >= mods.size()) {
            selected = Math.max(0, mods.size() - 1);
        }
    }

    private void refreshStates() {
        states.clear();
        for (Actions.InstalledMod mod : mods) {
            states.put(mod.id(), mod.java() != null ? actions.stateOf(mod.id()) : "lua:" + actions.luaState(mod.id()));
            if (mod.java() != null) {
                guardStates.put(mod.id(), actions.guardStatus(mod.id()));
            }
        }
    }

    private Actions.InstalledMod selectedMod() {
        return mods.isEmpty() ? null : mods.get(selected);
    }

    private String stateOf(ModEntry mod) {
        return stateOf(mod.id());
    }

    private String stateOf(String modId) {
        return states.getOrDefault(modId, "not loaded");
    }

    /** Whether a tab is offered now: Server only in multiplayer, Snapshots only in the main menu. */
    private boolean tabShown(Tab t) {
        return switch (t) {
            case SERVER -> session.connected();
            case SNAPSHOTS -> !game.inGame() && !session.connected();
            default -> true;
        };
    }

    /**
     * While the save is being copied, keys that switch mods (or close the screen, which lets the
     * game run and write its save again) wait; the player hears why.
     */
    private Set<Integer> waitForSnapshot(Set<Integer> pressed) {
        String state = actions.snapshotState();
        if (state != null && !state.equals(lastSnapshotState)) {
            lastSnapshotState = state;
            if (state.equals("done")) {
                say(texts.get("snapshot.done"), true);
            } else if (state.startsWith("failed")) {
                say(String.format(texts.get("snapshot.failed"), state.substring("failed".length()).strip()), false);
            }
        }
        if (!"running".equals(state)) {
            return pressed;
        }
        Set<Integer> waiting = new java.util.HashSet<>(pressed);
        if (waiting.removeAll(Set.of(KEY_ESCAPE, KEY_ENTER, KEY_KP_ENTER, KEY_A, KEY_U, KEY_Y))) {
            say(texts.get("snapshot.wait"), false);
        }
        return waiting;
    }

    private void onKeys(Set<Integer> pressed, long now) {
        pressed = waitForSnapshot(pressed);
        // during phase 1 the game goes on and the player may go back to it; during phase 2 it waits
        if (pressed.contains(KEY_ESCAPE) && (session.update == null || session.update.phase != ClientSession.Phase.APPLYING)) {
            close();
            return;
        }
        if (pressed.contains(KEY_TAB)) {
            Tab next = tab;
            do {
                next = Tab.values()[(next.ordinal() + 1) % Tab.values().length];
            } while (!tabShown(next));
            switchTab(next);
            return;
        }
        if (shiftHeld && (tab == Tab.INSTALLED || tab == Tab.JAVA_LIBRARY || tab == Tab.LUA_LIBRARY)) {
            // Shift with Up/Down (PgUp/PgDn: a page) scrolls the detail pane's description, not the list
            int step = (pressed.contains(KEY_DOWN) ? 3 : 0) - (pressed.contains(KEY_UP) ? 3 : 0)
                    + (pressed.contains(KEY_PAGE_DOWN) ? 15 : 0) - (pressed.contains(KEY_PAGE_UP) ? 15 : 0);
            if (step != 0) {
                detailScroll = Math.max(0, detailScroll + step);
                pressed = new java.util.HashSet<>(pressed);
                pressed.removeAll(Set.of(KEY_UP, KEY_DOWN, KEY_PAGE_UP, KEY_PAGE_DOWN));
            }
        }
        switch (tab) {
            case INSTALLED -> onInstalled(pressed, now);
            case JAVA_LIBRARY, LUA_LIBRARY -> onLibrary(libraries.get(tab), pressed, now);
            case SHARING -> onSharing(pressed);
            case SNAPSHOTS -> onSnapshots(pressed);
            case SERVER -> onServer(pressed);
        }
    }

    // --- snapshots tab (main menu) -----------------------------------------------------------------

    private void onSnapshots(Set<Integer> pressed) {
        int before = snapshotSelected;
        if (pressed.contains(KEY_UP) && snapshotSelected > 0) {
            snapshotSelected--;
        }
        if (pressed.contains(KEY_DOWN) && snapshotSelected < snapshotList.size() - 1) {
            snapshotSelected++;
        }
        if (snapshotSelected != before) {
            snapshotConfirm = -1;
        }
        if ((pressed.contains(KEY_ENTER) || pressed.contains(KEY_KP_ENTER)) && !snapshotList.isEmpty()) {
            String label = snapshotList.get(snapshotSelected);
            if (snapshotConfirm != snapshotSelected) {
                snapshotConfirm = snapshotSelected; // the first Enter only asks
                say(String.format(texts.get("snapshot.confirm"), label), false);
                return;
            }
            snapshotConfirm = -1;
            int index = snapshotSelected;
            busy(String.format(Locale.ROOT, texts.get("busy.restore"), label), () -> {
                String r = actions.restoreSnapshot(index);
                say(r.startsWith("OK") ? String.format(texts.get("snapshot.restored"), label) : r, r.startsWith("OK"));
                snapshotList = actions.snapshots();
            });
        }
    }

    private void drawSnapshots(int x, int y, int width, int bottom) {
        if (snapshotList.isEmpty()) {
            wrapped(GameScreen.Font.MEDIUM, x, y, width, texts.get("snapshot.none"), 0.75f, 0.75f, 0.75f);
            return;
        }
        int line = game.fontHeight(GameScreen.Font.MEDIUM) + 8;
        for (int i = 0; i < snapshotList.size() && y + line <= bottom; i++) {
            boolean sel = i == snapshotSelected;
            if (sel) {
                game.rect(x, y, width, line, 0.2f, 0.32f, 0.24f, 1f);
            }
            game.text(GameScreen.Font.MEDIUM, x + 8, y + 4, fit(GameScreen.Font.MEDIUM, snapshotList.get(i), width - 16), 1f, 1f, 1f, sel ? 1f : 0.8f);
            int index = i;
            hits.add(new Hit(x, y, width, line, 0, () -> {
                snapshotSelected = index;
                snapshotConfirm = -1;
            }));
            y += line;
        }
    }

    // --- installed tab ---------------------------------------------------------------------------

    private void onInstalled(Set<Integer> pressed, long now) {
        if (pressed.contains(KEY_UP) && selected > 0) {
            selected--;
        }
        if (pressed.contains(KEY_DOWN) && selected < mods.size() - 1) {
            selected++;
        }
        if (pressed.contains(KEY_PAGE_UP)) {
            selected = Math.max(0, selected - 10);
        }
        if (pressed.contains(KEY_PAGE_DOWN)) {
            selected = Math.max(0, Math.min(mods.size() - 1, selected + 10));
        }
        if (pressed.contains(KEY_R)) {
            busy(texts.get("busy.index"), () -> {
                say(actions.refreshIndex(), true);
                checks.clear();
            });
            return;
        }
        Actions.InstalledMod mod = selectedMod();
        if (mod == null) {
            return;
        }
        boolean enter = pressed.contains(KEY_ENTER) || pressed.contains(KEY_KP_ENTER);
        boolean toggle = pressed.contains(KEY_A);
        boolean remove = pressed.contains(KEY_U);
        if ((enter || toggle || remove) && mod.id().equals(SELF)) {
            say(texts.get("self.locked"), false); // the screen that switches mods does not switch itself off
            return;
        }
        if ((enter || toggle || remove) && session.inMultiplayer()) {
            say(texts.get("mp.locked"), false); // in multiplayer only an admin switches, for everybody
            return;
        }
        if (remove) {
            uninstalling = mod;
            mode = Mode.UNINSTALL_ONCE;
            questionSince = now;
            return;
        }
        if (toggle) {
            String what = texts.get(switchesOff(mod) ? "busy.off" : "busy.on");
            busy(String.format(Locale.ROOT, what, mod.name()), () -> toggleWithDeps(mod, frameTime));
            return;
        }
        if (mod.java() == null) {
            if (enter && switchesOff(mod)) {
                toggleLua(mod, true); // running already: Enter only says so
            } else if (enter) {
                // Enter on a Lua mod that is off switches it on, with what it needs
                busy(String.format(Locale.ROOT, texts.get("busy.on"), mod.name()), () -> toggleWithDeps(mod, frameTime));
            }
            return;
        }
        if (enter) {
            busy(String.format(Locale.ROOT, texts.get("busy.check"), mod.name()), () -> start(mod.java(), frameTime, false));
        }
    }

    /** A on one mod, as before the dependency questions: Lua on/off, or Java on/off/load. */
    private void plainToggle(Actions.InstalledMod mod, long now) {
        if (mod.java() == null) {
            toggleLua(mod, false);
            return;
        }
        {
            String state = stateOf(mod.id());
            if (state.equals("on")) {
                String r = actions.off(mod.id());
                boolean ok = r.startsWith("OK");
                if (ok) {
                    actions.deactivate(mod.id());
                    actions.unloadLua(mod.id());
                }
                say(ok ? String.format(Locale.ROOT, texts.get("java.off"), mod.name()) : r, ok);
            } else if (state.equals("off")) {
                String r = actions.on(mod.id());
                boolean ok = r.startsWith("OK");
                if (ok) {
                    actions.activate(mod.id());
                    String lua = luaNote(mod.name(), actions.loadLua(mod.id()));
                    say(String.format(Locale.ROOT, texts.get("java.on"), mod.name()) + (lua.isEmpty() ? "" : "  " + lua), true);
                } else {
                    say(r, false);
                }
            } else if (state.equals("not loaded")) {
                start(mod.java(), now, false);
            } else {
                say(texts.get("java.untilRestart"), false); // loaded at start without hotswap: no switch
            }
            refreshStates();
        }
    }

    // --- dependencies (mod.info require=) --------------------------------------------------------

    /** A yes/no question about mods that need each other. */
    private record DepQuestion(String title, String text, Runnable yes, Runnable no) {
    }

    private DepQuestion depQuestion;
    /** Mods switched off together with a library (library id -> those mods), offered back when it comes on. */
    private final Map<String, List<String>> offWith = new HashMap<>();

    private Map<String, List<String>> depMap() {
        Map<String, List<String>> map = new java.util.LinkedHashMap<>();
        for (Actions.InstalledMod m : mods) {
            map.put(m.id(), m.requires());
        }
        return map;
    }

    private Actions.InstalledMod modById(String id) {
        for (Actions.InstalledMod m : mods) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    /** In use: in the mod list (Lua) or loaded (Java). */
    private boolean isOn(String id) {
        Actions.InstalledMod m = modById(id);
        if (m == null) {
            return false;
        }
        return m.java() == null ? actions.luaState(id).startsWith("on") : actions.stateOf(id).startsWith("on");
    }

    /** A toggles this mod off (not on: also not a Lua mod that is listed but waits for its live load). */
    private boolean switchesOff(Actions.InstalledMod mod) {
        if (mod.java() == null) {
            String s = actions.luaState(mod.id());
            return s.startsWith("on") && !(s.equals("on, next load") && !liveTried.contains(mod.id()));
        }
        return actions.stateOf(mod.id()).equals("on");
    }

    /** One mod off without questions; null when done, else why not. */
    private String switchOff(Actions.InstalledMod m) {
        if (m.java() != null) {
            if (!actions.stateOf(m.id()).equals("on")) {
                return String.format(Locale.ROOT, texts.get("deps.cannotSwitch"), m.name());
            }
            String r = actions.off(m.id());
            if (!r.startsWith("OK")) {
                return r;
            }
        }
        String problem = actions.deactivate(m.id());
        if (problem != null) {
            return problem;
        }
        actions.unloadLua(m.id());
        return null;
    }

    /** One mod on without questions; null when done, else why not (a Java mod not loaded yet needs its check). */
    private String switchOn(Actions.InstalledMod m) {
        if (m.java() != null) {
            String state = actions.stateOf(m.id());
            if (state.equals("not loaded")) {
                return String.format(Locale.ROOT, texts.get("deps.javaFirst"), m.name());
            }
            if (state.equals("off")) {
                String r = actions.on(m.id());
                if (!r.startsWith("OK")) {
                    return r;
                }
            }
        }
        String problem = actions.activate(m.id());
        if (problem != null) {
            return problem;
        }
        actions.loadLua(m.id());
        return null;
    }

    private String names(List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            Actions.InstalledMod m = modById(id);
            out.add(m == null ? id : m.name());
        }
        return String.join(", ", out);
    }

    private void ask(String title, String text, Runnable yes, Runnable no) {
        depQuestion = new DepQuestion(title, text, yes, no);
        mode = Mode.DEPENDENCY;
    }

    /**
     * A on a mod, with its dependencies: off asks to take the mods that need it along, on switches
     * the mods it needs on first; afterwards libraries nobody needs any more, or mods that went off
     * with this one, are offered.
     */
    private void toggleWithDeps(Actions.InstalledMod mod, long now) {
        Map<String, List<String>> deps = depMap();
        if (switchesOff(mod)) {
            List<String> dependents = ModDeps.dependentsOn(mod.id(), deps, this::isOn);
            if (dependents.isEmpty()) {
                plainToggle(mod, now);
                if (!isOn(mod.id())) {
                    afterOff(List.of(mod.id()));
                }
                return;
            }
            ask(texts.get("deps.offTitle"),
                    String.format(Locale.ROOT, texts.get("deps.offText"), mod.name(), names(dependents)),
                    () -> {
                        List<String> gone = new ArrayList<>();
                        List<String> problems = new ArrayList<>();
                        for (String id : dependents) {
                            String p = switchOff(modById(id));
                            if (p == null) {
                                gone.add(id);
                            } else {
                                problems.add(p);
                            }
                        }
                        if (problems.isEmpty()) {
                            String p = switchOff(mod);
                            if (p == null) {
                                gone.add(mod.id());
                                offWith.put(mod.id(), new ArrayList<>(dependents));
                            } else {
                                problems.add(p);
                            }
                        }
                        refreshStates();
                        say(problems.isEmpty()
                                ? String.format(Locale.ROOT, texts.get("deps.offDone"), mod.name(), names(dependents))
                                : String.join("; ", problems), problems.isEmpty());
                        afterOff(gone);
                    },
                    () -> say(texts.get("deps.cancelled"), false));
            return;
        }
        ModDeps.Needs needs = ModDeps.needs(mod.id(), deps, this::isOn);
        if (!needs.missing().isEmpty()) {
            say(String.format(Locale.ROOT, texts.get("deps.missing"), mod.name(), String.join(", ", needs.missing())), false);
            return;
        }
        for (String id : needs.off()) {
            String p = switchOn(modById(id));
            if (p != null) {
                refreshStates();
                say(p, false);
                return;
            }
        }
        plainToggle(mod, now);
        if (!needs.off().isEmpty()) {
            String said = message;
            say(String.format(Locale.ROOT, texts.get("deps.onWith"), names(needs.off())) + "  " + said, messageGood);
        }
        if (isOn(mod.id())) {
            afterOn(mod);
        }
    }

    /** After mods went off: the libraries they needed that no mod that is on needs any more, offered. */
    private void afterOff(List<String> gone) {
        List<String> unneeded = ModDeps.unneeded(gone, depMap(), this::isOn);
        if (unneeded.isEmpty()) {
            return;
        }
        String before = message;
        boolean good = messageGood;
        ask(texts.get("deps.unneededTitle"), String.format(Locale.ROOT, texts.get("deps.unneededText"), names(unneeded)),
                () -> {
                    List<String> problems = new ArrayList<>();
                    for (String id : unneeded) {
                        String p = switchOff(modById(id));
                        if (p != null) {
                            problems.add(p);
                        }
                    }
                    refreshStates();
                    say(problems.isEmpty() ? String.format(Locale.ROOT, texts.get("deps.unneededDone"), names(unneeded))
                            : String.join("; ", problems), problems.isEmpty());
                },
                () -> say(before, good));
    }

    /** After a mod came on: the mods that went off together with it, offered back. */
    private void afterOn(Actions.InstalledMod mod) {
        List<String> group = offWith.remove(mod.id());
        if (group == null) {
            return;
        }
        List<String> back = new ArrayList<>();
        for (String id : group) {
            if (modById(id) != null && !isOn(id)) {
                back.add(id);
            }
        }
        if (back.isEmpty()) {
            return;
        }
        String before = message;
        boolean good = messageGood;
        ask(texts.get("deps.againTitle"), String.format(Locale.ROOT, texts.get("deps.againText"), mod.name(), names(back)),
                () -> {
                    List<String> problems = new ArrayList<>();
                    for (String id : back) {
                        ModDeps.Needs n = ModDeps.needs(id, depMap(), this::isOn);
                        for (String lib : n.off()) {
                            String p = switchOn(modById(lib));
                            if (p != null) {
                                problems.add(p);
                            }
                        }
                        String p = switchOn(modById(id));
                        if (p != null) {
                            problems.add(p);
                        }
                    }
                    refreshStates();
                    say(problems.isEmpty() ? String.format(Locale.ROOT, texts.get("deps.againDone"), names(back))
                            : String.join("; ", problems), problems.isEmpty());
                },
                () -> say(before, good));
    }

    private void onDepQuestion(Set<Integer> pressed) {
        DepQuestion q = depQuestion;
        if (q == null) {
            mode = Mode.LIST;
            return;
        }
        boolean yes = pressed.contains(KEY_J) || pressed.contains(KEY_Y);
        boolean no = pressed.contains(KEY_N) || pressed.contains(KEY_ESCAPE);
        if (!yes && !no) {
            return;
        }
        depQuestion = null;
        mode = Mode.LIST;
        if (yes) {
            busy(texts.get("busy.switching"), q.yes()); // may ask the next question
        } else {
            q.no().run();
        }
    }

    private void drawDepQuestion(int x, int y, int width, int bottom) {
        DepQuestion q = depQuestion;
        if (q == null) {
            return;
        }
        game.rect(x - 6, y - 4, width + 12, 3, 1f, 0.75f, 0.25f, 1f);
        y += 8;
        game.text(GameScreen.Font.LARGE, x, y, q.title(), 1f, 0.75f, 0.25f, 1f);
        y += game.fontHeight(GameScreen.Font.LARGE) + 10;
        wrapped(GameScreen.Font.MEDIUM, x, y, width, q.text(), 1f, 1f, 1f);
        game.text(GameScreen.Font.MEDIUM, x, bottom - game.fontHeight(GameScreen.Font.MEDIUM), texts.get("deps.keys"), 1f, 1f, 1f, 1f);
        answerButtons(x, bottom, 0);
    }

    /** A (or Enter on a mod that is off): a Lua mod on or off, in the mod list and, in a game, live. */
    private void toggleLua(Actions.InstalledMod mod, boolean enter) {
        String state = actions.luaState(mod.id());
        // on in the mod list, but its Lua does not run yet (enabled, not loaded live): A loads it now,
        // once; if that cannot work, the next A switches it off
        boolean waiting = state.equals("on, next load") && liveTried.add(mod.id());
        if (state.startsWith("on") && !waiting) {
            if (enter) {
                say(String.format(Locale.ROOT, texts.get("lua.running"), mod.name()), true);
                return;
            }
            String problem = actions.deactivate(mod.id());
            if (problem != null) {
                say(problem, false);
                return;
            }
            int stopped = actions.unloadLua(mod.id());
            boolean complete = "off".equals(actions.luaState(mod.id()));
            String key = complete && stopped > 0 ? "lua.offNow" : stopped > 0 ? "lua.stopped" : "lua.off";
            String said = String.format(Locale.ROOT, texts.get(key), mod.name(), stopped);
            String left = leftoversNote(mod.name(), actions.luaLeftovers(mod.id()));
            say(left.isEmpty() ? said : said + " " + left, true);
        } else {
            String problem = actions.activate(mod.id());
            if (problem != null) {
                say(problem, false);
                return;
            }
            LuaLive.Result r = actions.loadLua(mod.id());
            say(describeLua(mod.name(), r), r.status() != LuaLive.Status.FAILED && r.errors().isEmpty());
        }
        refreshStates();
    }

    // --- uninstall: two questions, each with a delay -----------------------------------------------

    private void onUninstallQuestion(Set<Integer> pressed, long now) {
        if (pressed.contains(KEY_ESCAPE) || pressed.contains(KEY_N)) {
            uninstalling = null;
            mode = Mode.LIST;
            say(texts.get("uninstall.cancelled"), false);
            return;
        }
        boolean yes = pressed.contains(KEY_J) || pressed.contains(KEY_Y);
        long wait = mode == Mode.UNINSTALL_ONCE ? UNINSTALL_FIRST_DELAY_MS : UNINSTALL_SECOND_DELAY_MS;
        if (!yes || now - questionSince < wait) {
            return;
        }
        if (mode == Mode.UNINSTALL_ONCE) {
            mode = Mode.UNINSTALL_TWICE;
            questionSince = now;
            return;
        }
        Actions.InstalledMod mod = uninstalling;
        uninstalling = null;
        mode = Mode.LIST;
        busy(String.format(Locale.ROOT, texts.get("busy.uninstall"), mod.name()), () -> uninstallNow(mod));
    }

    private void uninstallNow(Actions.InstalledMod mod) {
        String result = actions.uninstall(mod.id());
        if (result.startsWith("OK workshop")) {
            say(String.format(Locale.ROOT, texts.get("uninstalled.workshop"), mod.name()) + result.substring("OK workshop".length()).replaceFirst("^ \\d+", ""), true);
        } else if (result.startsWith("OK local ")) {
            say(String.format(Locale.ROOT, texts.get("uninstalled.local"), mod.name(), result.substring("OK local ".length())), true);
        } else {
            say(result, false);
        }
        checks.clear();
        reload();
    }

    private void drawUninstallQuestion(int x, int y, int width, int bottom, long now) {
        Actions.InstalledMod mod = uninstalling;
        boolean second = mode == Mode.UNINSTALL_TWICE;
        float[] c = second ? new float[] {1f, 0.35f, 0.3f} : new float[] {1f, 0.75f, 0.25f};
        game.rect(x - 6, y - 4, width + 12, 3, c[0], c[1], c[2], 1f);
        y += 8;
        game.text(GameScreen.Font.LARGE, x, y, texts.get(second ? "uninstall2.title" : "uninstall1.title"), c[0], c[1], c[2], 1f);
        y += game.fontHeight(GameScreen.Font.LARGE) + 10;
        game.text(GameScreen.Font.MEDIUM, x, y, mod.name() + "  [" + mod.id() + "]  " + (mod.java() != null ? "Java" : "Lua"), 1f, 1f, 1f, 1f);
        y += game.fontHeight(GameScreen.Font.MEDIUM) + 8;
        String how = mod.workshopId() > 0 ? texts.get("uninstall.workshop") : texts.get("uninstall.local");
        y = wrapped(GameScreen.Font.MEDIUM, x, y, width, texts.get(second ? "uninstall2.text" : "uninstall1.text") + " " + how, 1f, 1f, 1f);
        long wait = (second ? UNINSTALL_SECOND_DELAY_MS : UNINSTALL_FIRST_DELAY_MS) - (now - questionSince);
        String keys = wait > 0
                ? String.format(Locale.ROOT, texts.get("uninstall.wait"), (wait + 999) / 1000)
                : texts.get("uninstall.ready");
        game.text(GameScreen.Font.MEDIUM, x, bottom - game.fontHeight(GameScreen.Font.MEDIUM), keys,
                wait > 0 ? 0.6f : 1f, wait > 0 ? 0.6f : 1f, wait > 0 ? 0.6f : 1f, 1f);
        answerButtons(x, bottom, wait);
    }

    /** Enter on a mod: load it, or reload its jar when it is loaded already. */
    private void start(ModEntry mod, long now, boolean server) {
        BiteCheckGate.Check check;
        try {
            check = actions.check(mod.jar());
        } catch (IOException e) {
            say(texts.get("cannotCheck") + e.getMessage(), false);
            if (server) {
                session.failed("cannot check the jar: " + e.getMessage());
            }
            return;
        }
        checks.put(mod.jar(), check);
        pending = new Pending(mod, check, !stateOf(mod).equals("not loaded"), server);
        switch (check.verdict()) {
            case REFUSE -> {
                pending = null;
                say(texts.get("refused"), false);
                if (server) {
                    session.failed("BiteCheck blocks the jar");
                }
            }
            case ALLOW -> run(BiteCheckGate.Consent.NONE);
            case CONFIRM, CONFIRM_TWICE -> {
                if (server && mode == Mode.CLOSED) {
                    // the game goes on during phase 1: the question waits until the player opens Jumpstart
                    session.update.status = texts.get("mp.openToAnswer");
                    return;
                }
                mode = Mode.CONFIRM_ONCE;
                questionSince = now;
            }
        }
    }

    private void onQuestion(Set<Integer> pressed, long now) {
        if (pressed.contains(KEY_ESCAPE) || pressed.contains(KEY_N)) {
            if (pending.server()) {
                session.failed("the player said no");
            }
            pending = null;
            mode = Mode.LIST;
            say(texts.get("cancelled"), false);
            return;
        }
        boolean yes = pressed.contains(KEY_J) || pressed.contains(KEY_Y);
        if (!yes || now - questionSince < delay()) {
            return; // too early: the key does nothing, the player has to press it again
        }
        if (mode == Mode.CONFIRM_ONCE && pending.check().verdict() == BiteCheckGate.Verdict.CONFIRM_TWICE) {
            mode = Mode.CONFIRM_TWICE;
            questionSince = now;
            return;
        }
        run(mode == Mode.CONFIRM_TWICE ? BiteCheckGate.Consent.CONFIRMED_TWICE : BiteCheckGate.Consent.CONFIRMED);
    }

    private long delay() {
        return mode == Mode.CONFIRM_TWICE ? SECOND_DELAY_MS : FIRST_DELAY_MS;
    }

    /**
     * Loads or updates the pending mod. The player's answer counts only for the exact file they were
     * shown: if the jar changed in the meantime, the installer's new check has another hash and
     * gets no consent.
     */
    private void run(BiteCheckGate.Consent consent) {
        Pending p = pending;
        pending = null;
        if (mode != Mode.CLOSED) {
            mode = Mode.LIST; // a server change BiteCheck allows runs on with the screen closed
        }
        if (p.server()) {
            // phase 1: answered and checked, nothing changes until every player is ready
            serverCommit = () -> runNow(p, consent);
            session.ready();
            say(texts.get("mp.waiting"), true);
            return;
        }
        busy(String.format(Locale.ROOT, texts.get(p.update() ? "busy.update" : "busy.load"), p.mod().name()), () -> runNow(p, consent));
    }

    private void runNow(Pending p, BiteCheckGate.Consent consent) {
        String shownHash = p.check().sha256();
        LiveInstaller.ConsentSource answer = check -> check.sha256().equals(shownHash) ? consent : null;
        LiveInstaller.Outcome outcome = p.update() ? actions.update(p.mod(), answer) : actions.install(p.mod(), answer);
        checks.remove(p.mod().jar());
        refreshStates();
        say(outcome.kind() + " " + outcome.message(), outcome.kind() == LiveInstaller.Kind.DONE);
        String[] lua = luaAfterJava;
        List<String> also = luaAlsoAfterJava;
        luaAfterJava = null;
        luaAlsoAfterJava = List.of();
        if (!p.server() && outcome.kind() == LiveInstaller.Kind.DONE) {
            // the Java part runs: the mod joins the mod list, then its Lua part, which may use the Java part
            boolean downloaded = lua != null && lua[0].equals(p.mod().id());
            if (!downloaded) {
                actions.activate(p.mod().id()); // a download enabled it already
            }
            String title = downloaded ? lua[1] : p.mod().name();
            String note = luaNote(title, actions.loadLua(p.mod().id()));
            if (downloaded) {
                for (String other : also) { // the item's other mods, enabled by the download too
                    String n = luaNote(other, actions.loadLua(other));
                    note = n.isEmpty() ? note : note.isEmpty() ? n : note + "  " + n;
                }
            }
            if (!note.isEmpty()) {
                say(outcome.kind() + " " + outcome.message() + "  " + note, true);
            }
            refreshStates();
        }
        if (p.server()) {
            if (outcome.kind() == LiveInstaller.Kind.DONE) {
                ClientSession.Update u = session.update;
                if (u != null && u.action.equals("install")) {
                    actions.loadLuaForServer(p.mod().id()); // its Lua, which may use the Java part just loaded
                }
                session.applied();
                say(texts.get("mp.applied"), true);
            } else {
                session.failed(outcome.kind() + " " + outcome.message());
            }
        }
    }

    /** Like {@link #describeLua}, but empty when there is nothing new to say (no game, already running). */
    private String luaNote(String title, LuaLive.Result r) {
        return switch (r.status()) {
            case NOT_IN_GAME, MULTIPLAYER, ALREADY_ACTIVE -> "";
            default -> describeLua(title, r);
        };
    }

    /** One line on a mod's Lua part after loading it live (or why it waits for the next load). */
    private String describeLua(String title, LuaLive.Result r) {
        String text = switch (r.status()) {
            case LOADED -> String.format(Locale.ROOT, texts.get("lua.loaded"), title, r.files());
            case NO_LUA -> String.format(Locale.ROOT, texts.get("lua.none"), title);
            case ALREADY_ACTIVE -> String.format(Locale.ROOT, texts.get("lua.active"), title);
            case RESUMED -> String.format(Locale.ROOT, texts.get("lua.resumed"), title);
            case FAILED -> String.format(Locale.ROOT, texts.get("lua.failed"), title, String.join("; ", r.errors()));
            case NOT_IN_GAME, MULTIPLAYER -> String.format(Locale.ROOT, texts.get("enabledLua"), title);
        };
        if (r.status() == LuaLive.Status.LOADED && !r.errors().isEmpty()) {
            text += " " + String.format(Locale.ROOT, texts.get("lua.errors"), r.errors().size());
        }
        if (!r.needsRestart().isEmpty() && r.status() != LuaLive.Status.NOT_IN_GAME && r.status() != LuaLive.Status.FAILED) {
            List<String> names = new ArrayList<>();
            for (String part : r.needsRestart()) {
                names.add(texts.get("part." + part));
            }
            text += " " + String.format(Locale.ROOT, texts.get("lua.restart"), String.join(", ", names));
        }
        return text;
    }

    private void say(String text, boolean good) {
        message = text == null ? "" : text;
        messageGood = good;
    }

    // --- library tabs ----------------------------------------------------------------------------

    private void onLibrary(Library lib, Set<Integer> pressed, long now) {
        for (int key : pressed) {
            if (key == KEY_BACKSPACE && lib.query.length() > 0) {
                lib.query.setLength(lib.query.length() - 1);
            } else if (key == KEY_SPACE) {
                type(lib, " ");
            } else if ((key >= 'A' && key <= 'Z') || (key >= '0' && key <= '9') || key == KEY_MINUS) {
                type(lib, game.keyName(key));
            }
        }
        if (pressed.contains(KEY_UP) && lib.selected > 0) {
            lib.selected--;
        }
        if (pressed.contains(KEY_DOWN) && lib.selected < lib.items.size() - 1) {
            lib.selected++;
        }
        if (pressed.contains(KEY_PAGE_DOWN) && !lib.next.isEmpty() && !lib.busy) {
            search(lib, true);
        }
        if (pressed.contains(KEY_ENTER) || pressed.contains(KEY_KP_ENTER)) {
            String query = lib.query.toString().strip();
            if (lib.searched == null || !query.equals(lib.searched)) {
                search(lib, false);
            } else if (!lib.items.isEmpty()) {
                installWithRequirements(lib.items.get(lib.selected), now);
            }
        }
    }

    private void type(Library lib, String text) {
        if (text != null && lib.query.length() < 60) {
            lib.query.append(text);
        }
    }

    private void search(Library lib, boolean nextPage) {
        if (lib.busy) {
            return;
        }
        lib.busy = true;
        lib.status = texts.get("searching");
        String query = nextPage ? lib.searched : lib.query.toString().strip();
        String cursor = nextPage ? lib.next : null;
        inBackground(() -> actions.search(lib.kind, query, cursor), result -> {
            lib.busy = false;
            if (result instanceof LibraryClient.Page page) {
                if (!nextPage) {
                    lib.items = new ArrayList<>();
                    lib.selected = 0;
                }
                lib.items.addAll(page.items());
                lib.next = page.next();
                lib.searched = query;
                lib.status = lib.items.isEmpty() ? texts.get("noResults")
                        : String.format(Locale.ROOT, texts.get("results"), lib.items.size(), page.total());
            } else {
                lib.status = texts.get("searchFailed") + ((Exception) result).getMessage();
            }
        });
    }

    /** Downloads waiting their turn: an item's required items first, then the item. */
    private final Deque<LibraryClient.Item> downloadQueue = new ArrayDeque<>();

    /**
     * Enter on a library item: if it needs Workshop items that are not installed, asks whether to
     * install them too (first); then downloads.
     */
    private void installWithRequirements(LibraryClient.Item item, long now) {
        if (session.inMultiplayer() || download != null || !downloadQueue.isEmpty()
                || actions.workshop().unavailableReason() != null) {
            startDownload(item, now); // says why not, or the admin installs for everybody
            return;
        }
        List<Long> missing = new ArrayList<>();
        for (long id : item.requires()) {
            if ((actions.workshop().state(id) & Workshop.INSTALLED) == 0) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            startDownload(item, now);
            return;
        }
        say(texts.get("deps.lookingUp"), true);
        inBackground(() -> actions.workshopTitles(missing), result -> {
            @SuppressWarnings("unchecked")
            Map<Long, String> titles = result instanceof Map<?, ?> m ? (Map<Long, String>) m : Map.of();
            List<LibraryClient.Item> needed = new ArrayList<>();
            List<String> shown = new ArrayList<>();
            for (long id : missing) {
                String title = titles.getOrDefault(id, "Workshop " + id);
                needed.add(new LibraryClient.Item(id, title, 0, 0, false, List.of(), ""));
                shown.add(title);
            }
            ask(texts.get("deps.installTitle"),
                    String.format(Locale.ROOT, texts.get("deps.installText"), item.title(), String.join(", ", shown)),
                    () -> {
                        downloadQueue.addAll(needed);
                        downloadQueue.add(item);
                        nextDownload(frameTime);
                    },
                    () -> startDownload(item, frameTime));
        });
    }

    /** Starts the next waiting download, if none runs; a download that cannot start ends the queue. */
    private void nextDownload(long now) {
        if (download != null || downloadQueue.isEmpty()) {
            return;
        }
        startDownload(downloadQueue.poll(), now);
        if (download == null) {
            downloadQueue.clear();
        }
    }

    /** Enter on a library item: subscribe and download it through Steam, then install it. */
    private void startDownload(LibraryClient.Item item, long now) {
        if (session.inMultiplayer()) {
            if (session.connected() && session.admin && session.update == null) {
                // an admin installs for everybody: the server downloads the item, then everybody takes part
                session.requestInstall(item.id());
                say(String.format(Locale.ROOT, texts.get("mp.installRequested"), item.title()), true);
            } else {
                say(texts.get(session.connected() && !session.admin ? "mp.installAdmin" : "mp.locked"), false);
            }
            return;
        }
        if (download != null) {
            say(texts.get("downloadBusy"), false);
            return;
        }
        String unavailable = actions.workshop().unavailableReason();
        if (unavailable != null) {
            say(texts.get("noSteam") + unavailable, false);
            return;
        }
        long state = actions.workshop().state(item.id());
        if ((state & Workshop.INSTALLED) == 0 || (state & Workshop.NEEDS_UPDATE) != 0) {
            if (!actions.workshop().subscribeAndDownload(item.id())) {
                say(texts.get("subscribeFailed"), false);
                return;
            }
        }
        download = new Download(item, now);
        say(String.format(Locale.ROOT, texts.get("downloading"), item.title()), true);
    }

    /** Every frame while a download runs: when Steam has the item, install what is in it. */
    private void followDownload(long now) {
        Download d = download;
        long state = actions.workshop().state(d.item().id());
        boolean ready = (state & Workshop.INSTALLED) != 0
                && (state & (Workshop.DOWNLOADING | Workshop.DOWNLOAD_PENDING | Workshop.NEEDS_UPDATE)) == 0;
        if (!ready) {
            if (now - d.since() > DOWNLOAD_TIMEOUT_MS) {
                download = null;
                downloadQueue.clear();
                say(texts.get("downloadTimeout"), false);
            }
            return;
        }
        download = null;
        busy(String.format(Locale.ROOT, texts.get("busy.install"), d.item().title()), () -> installDownloaded(d, frameTime));
    }

    /** Steam has the item: its mods join the mod list and load (a Java mod through BiteCheck's check). */
    private void installDownloaded(Download d, long now) {
        String folder = actions.workshop().installFolder(d.item().id());
        List<Actions.FolderMod> found = folder == null ? List.of() : actions.modsInFolder(folder);
        if (found.isEmpty()) {
            say(texts.get("noModInItem"), false);
            return;
        }
        List<String> problems = new ArrayList<>();
        ModEntry java = null;
        for (Actions.FolderMod mod : found) {
            String problem = actions.activate(mod.id());
            if (problem != null) {
                problems.add(problem);
            }
            if (java == null && mod.java() != null) {
                java = mod.java();
            }
        }
        if (!problems.isEmpty()) {
            say(String.join("; ", problems), false);
            return;
        }
        if (java != null && mode == Mode.LIST) {
            // Java: straight into BiteCheck's check, on the installed tab; its Lua part follows
            tab = Tab.INSTALLED;
            reload();
            for (int i = 0; i < mods.size(); i++) {
                if (mods.get(i).id().equals(java.id())) {
                    selected = i;
                }
            }
            say(String.format(Locale.ROOT, texts.get("downloadedJava"), d.item().title()), true);
            luaAfterJava = new String[] {java.id(), d.item().title()};
            List<String> others = new ArrayList<>();
            for (Actions.FolderMod mod : found) {
                if (!mod.id().equals(java.id())) {
                    others.add(mod.id());
                }
            }
            luaAlsoAfterJava = others;
            start(java, now, false);
        } else if (java != null) {
            say(String.format(Locale.ROOT, texts.get("downloadedJava"), d.item().title()), true);
        } else {
            List<String> parts = new ArrayList<>();
            boolean good = true;
            for (Actions.FolderMod mod : found) {
                LuaLive.Result r = actions.loadLua(mod.id());
                good &= r.status() != LuaLive.Status.FAILED && r.errors().isEmpty();
                parts.add(describeLua(found.size() == 1 ? d.item().title() : mod.name(), r));
            }
            say(String.join("  ", parts), good);
        }
    }

    // --- sharing tab -------------------------------------------------------------------------------

    private void showSharing() {
        sharing = actions.sharingEnabled();
        sharingPreview = texts.get("previewBusy");
        inBackground(() -> UsageSharing.preview(GameActions.CLIENT, actions.unverifiedJars()), r ->
                sharingPreview = r instanceof String text ? text : texts.get("previewFailed"));
    }

    private void onSharing(Set<Integer> pressed) {
        if (pressed.contains(KEY_ENTER) || pressed.contains(KEY_KP_ENTER)) {
            try {
                actions.setSharing(!sharing);
                sharing = !sharing;
                say(texts.get(sharing ? "sharingOn" : "sharingOff"), true);
                if (sharing) {
                    inBackground(() -> actions.shareIfDue(actions.unverifiedJars()), r -> say(String.valueOf(r), true));
                }
            } catch (IOException e) {
                say(e.getMessage(), false);
            }
        }
    }

    // --- multiplayer ---------------------------------------------------------------------------------

    /** Called by Jumpstart's Java channel (net.Net) with a command from the server. */
    void onServerCommand(String command, java.util.Map<String, String> args) {
        synchronized (toMainThread) {
            toMainThread.add(() -> handleServerCommand(command, args, frameTime));
        }
    }

    private void handleServerCommand(String command, java.util.Map<String, String> args, long now) {
        String what = session.onCommand(command, args, now);
        if (what == null) {
            return;
        }
        switch (what) {
            case "prepare" -> {
                serverCommit = null;
                ClientSession.Update u = session.update;
                say(String.format(Locale.ROOT, texts.get("mp.prepare"), u.by, actionText(u.action), u.mod), true);
                if (mode != Mode.CLOSED) {
                    if (mode != Mode.LIST) {
                        pending = null; // a question of the player's own is dropped for the server's
                        mode = Mode.LIST;
                    }
                    tab = Tab.SERVER;
                    if (u.phase == ClientSession.Phase.CONSENT) {
                        mode = Mode.SERVER_CONSENT;
                    }
                }
                // with the screen closed the game goes on; a line at the top asks the player to open it
                if (u.phase != ClientSession.Phase.CONSENT && !fetches(u)) {
                    switchForServer(u);
                }
            }
            case "commit" -> {
                ClientSession.Update u = session.update;
                Runnable work = serverCommit;
                serverCommit = null;
                if (work == null) {
                    session.failed("nothing prepared");
                    return;
                }
                u.phase = ClientSession.Phase.APPLYING;
                if (busyWork != null) {
                    work.run(); // something of the player's own waits to run: the server's change goes first
                } else {
                    busy(String.format(Locale.ROOT, texts.get("busy.server"), actionText(u.action), u.mod), work);
                }
            }
            case "abort" -> {
                serverCommit = null;
                if (pending != null && pending.server()) {
                    pending = null;
                    if (mode != Mode.CLOSED) {
                        mode = Mode.LIST;
                    }
                }
                say(texts.get("mp.aborted") + (session.lastNote.isEmpty() ? "" : " " + session.lastNote), false);
            }
            case "done" -> {
                say(session.lastNote.isEmpty() ? texts.get("mp.done") : texts.get("mp.done") + " " + session.lastNote, true);
                refreshStates();
            }
            default -> say(session.lastNote, false);
        }
    }

    /** Every frame during a server update: get the exact jar the server wants, then BiteCheck. */
    private void followServerUpdate(long now) {
        ClientSession.Update u = session.update;
        if (session.answered()) {
            return; // phase 1 done here; the server decides when phase 2 starts
        }
        if (now > u.deadline) {
            if ((pending != null && pending.server()) || mode == Mode.SERVER_CONSENT) {
                pending = null;
                if (mode != Mode.CLOSED) {
                    mode = Mode.LIST;
                }
            }
            session.failed("not ready in time");
            say(texts.get("mp.tooLate"), false);
            return;
        }
        if (u.phase != ClientSession.Phase.GETTING || !fetches(u) || lookBusy || now < u.nextLook) {
            return;
        }
        if (u.action.equals("install")) {
            followServerInstall(u, now);
            return;
        }
        u.nextLook = now + 2000;
        lookBusy = true;
        inBackground(() -> {
            for (ModEntry mod : actions.javaMods()) {
                if (mod.id().equals(u.mod)) {
                    return new Object[] {mod, actions.check(mod.jar())};
                }
            }
            return null;
        }, result -> {
            lookBusy = false;
            if (session.update != u || u.phase != ClientSession.Phase.GETTING) {
                return;
            }
            if (result instanceof Object[] found && ((BiteCheckGate.Check) found[1]).sha256().equals(u.sha256)) {
                u.phase = ClientSession.Phase.ASKING;
                u.status = texts.get("mp.checking");
                reload();
                start((ModEntry) found[0], frameTime, true);
                return;
            }
            if (!u.downloadRequested && !u.workshopId.isEmpty() && actions.workshop().unavailableReason() == null) {
                u.downloadRequested = true;
                try {
                    actions.workshop().subscribeAndDownload(Long.parseLong(u.workshopId));
                } catch (NumberFormatException e) {
                    // no Workshop item: wait for the files
                }
            }
            u.status = result == null ? texts.get("mp.missing") : texts.get("mp.otherVersion");
        });
    }

    /**
     * An install round: get the Workshop item through Steam, then a Java part through BiteCheck
     * (exactly the server's hash; its Lua follows in run()), or a Lua-only mod straight in.
     */
    private void followServerInstall(ClientSession.Update u, long now) {
        u.nextLook = now + 2000;
        long item;
        try {
            item = Long.parseLong(u.workshopId);
        } catch (NumberFormatException e) {
            session.failed("no Workshop item");
            return;
        }
        Workshop ws = actions.workshop();
        long state = ws.state(item);
        boolean ready = (state & Workshop.INSTALLED) != 0
                && (state & (Workshop.DOWNLOADING | Workshop.DOWNLOAD_PENDING | Workshop.NEEDS_UPDATE)) == 0;
        if (!ready) {
            if (!u.downloadRequested && ws.unavailableReason() == null) {
                u.downloadRequested = true;
                ws.subscribeAndDownload(item);
            }
            u.status = texts.get("mp.missing");
            return;
        }
        String folder = ws.installFolder(item);
        Actions.FolderMod mod = folder == null ? null
                : actions.modsInFolder(folder).stream().filter(m -> m.id().equals(u.mod)).findFirst().orElse(null);
        if (mod == null) {
            u.status = texts.get("mp.missing");
            return;
        }
        if (!u.java || mod.java() == null) {
            serverCommit = () -> {
                LuaLive.Result r = actions.loadLuaForServer(u.mod);
                refreshStates();
                if (r.status() == LuaLive.Status.FAILED) {
                    session.failed("Lua: " + String.join("; ", r.errors()));
                    say(describeLua(mod.name(), r), false);
                } else {
                    session.applied();
                    say(describeLua(mod.name(), r) + "  " + texts.get("mp.applied"), true);
                }
            };
            session.ready();
            say(texts.get("mp.waiting"), true);
            return;
        }
        lookBusy = true;
        inBackground(() -> actions.check(mod.java().jar()), result -> {
            lookBusy = false;
            if (session.update != u || u.phase != ClientSession.Phase.GETTING) {
                return;
            }
            if (result instanceof BiteCheckGate.Check check && check.sha256().equals(u.sha256)) {
                u.phase = ClientSession.Phase.ASKING;
                u.status = texts.get("mp.checking");
                start(mod.java(), frameTime, true);
            } else {
                u.status = texts.get("mp.otherVersion");
            }
        });
    }

    private void onServer(Set<Integer> pressed) {
        List<ClientSession.ServerMod> list = session.serverMods;
        if (pressed.contains(KEY_UP) && serverSelected > 0) {
            serverSelected--;
        }
        if (pressed.contains(KEY_DOWN) && serverSelected < list.size() - 1) {
            serverSelected++;
        }
        if (pressed.contains(KEY_R)) {
            session.refresh();
        }
        boolean enter = pressed.contains(KEY_ENTER) || pressed.contains(KEY_KP_ENTER);
        boolean remove = pressed.contains(KEY_U);
        if ((enter || pressed.contains(KEY_A) || remove) && !list.isEmpty() && session.update == null) {
            if (!session.admin) {
                say(texts.get("mp.notAdmin"), false);
            } else {
                ClientSession.ServerMod mod = list.get(Math.min(serverSelected, list.size() - 1));
                if (!remove && mod.lua()) {
                    say(texts.get("mp.luaOnlyRemove"), false);
                    return;
                }
                String action = remove ? "remove" : enter ? "update" : mod.state().equals("off") ? "on" : "off";
                session.requestChange(action, mod.id());
                say(String.format(Locale.ROOT, texts.get("mp.requested"), actionText(action), mod.id()), true);
            }
        }
    }

    private String actionText(String action) {
        return texts.get("mp.action." + action);
    }

    /** The player answers an admin's change: J agrees (after a short delay), N or Esc refuses. */
    private void onServerConsent(Set<Integer> pressed, long now) {
        ClientSession.Update u = session.update;
        if (u == null) {
            mode = Mode.LIST;
            return;
        }
        if (pressed.contains(KEY_ESCAPE) || pressed.contains(KEY_N)) {
            session.failed("the player said no");
            mode = Mode.LIST;
            say(texts.get("mp.refused"), false);
            return;
        }
        boolean yes = pressed.contains(KEY_J) || pressed.contains(KEY_Y);
        if (!yes || now - u.since < CONSENT_DELAY_MS) {
            return;
        }
        mode = Mode.LIST;
        if (fetches(u)) {
            u.phase = ClientSession.Phase.GETTING;
            u.nextLook = 0;
        } else {
            switchForServer(u);
        }
    }

    /** Update and install need the server's files first; off, on and remove switch right away. */
    private static boolean fetches(ClientSession.Update u) {
        return u.action.equals("update") || u.action.equals("install");
    }

    /** Off / on / remove for the server, phase 1: can it be done here? Then wait for the commit. */
    private void switchForServer(ClientSession.Update u) {
        if (u.action.equals("on") && actions.stateOf(u.mod).equals("not loaded")) {
            String result = "FAILED " + u.mod + " is not loaded here";
            session.failed(result);
            say(result, false);
            return;
        }
        serverCommit = () -> switchNow(u);
        session.ready();
        say(texts.get("mp.waiting"), true);
    }

    /** Phase 2: the same switch as the installed tab, then the answer. */
    private void switchNow(ClientSession.Update u) {
        String state = actions.stateOf(u.mod);
        String result;
        if (u.action.equals("remove")) {
            // off here (Java switched off, Lua's event handlers stopped); the files stay
            result = state.equals("on") ? actions.off(u.mod) : "OK";
            if (result.startsWith("OK")) {
                actions.unloadLuaForServer(u.mod);
            }
        } else if (state.equals("not loaded")) {
            result = u.action.equals("off") ? "OK" : "FAILED " + u.mod + " is not loaded here";
        } else if (u.action.equals("off")) {
            result = state.equals("off") ? "OK" : actions.off(u.mod);
        } else {
            result = state.equals("on") ? "OK" : actions.on(u.mod);
        }
        refreshStates();
        if (result.startsWith("OK")) {
            session.applied();
            say(texts.get("mp.applied"), true);
        } else {
            session.failed(result);
            say(result, false);
        }
    }

    private void drawServerConsent(int x, int y, int width, int bottom, long now) {
        ClientSession.Update u = session.update;
        if (u == null) {
            return;
        }
        game.rect(x - 6, y - 4, width + 12, 3, 1f, 0.75f, 0.25f, 1f);
        y += 8;
        game.text(GameScreen.Font.LARGE, x, y, String.format(Locale.ROOT, texts.get("mp.consent.title"), u.by), 1f, 0.75f, 0.25f, 1f);
        y += game.fontHeight(GameScreen.Font.LARGE) + 10;
        game.text(GameScreen.Font.MEDIUM, x, y, actionText(u.action) + ": " + u.mod, 1f, 1f, 1f, 1f);
        y += game.fontHeight(GameScreen.Font.MEDIUM) + 8;
        y = wrapped(GameScreen.Font.MEDIUM, x, y, width, texts.get("mp.consent.text"), 0.9f, 0.9f, 0.9f);
        y += 6;
        long secondsLeft = Math.max(0, (u.deadline - now) / 1000);
        game.text(GameScreen.Font.SMALL, x, y, String.format(Locale.ROOT, texts.get("mp.left"), secondsLeft), 0.8f, 0.8f, 0.8f, 1f);
        long wait = CONSENT_DELAY_MS - (now - u.since);
        String keys = wait > 0 ? String.format(Locale.ROOT, texts.get("mp.consent.wait"), (wait + 999) / 1000) : texts.get("mp.consent.keys");
        game.text(GameScreen.Font.MEDIUM, x, bottom - game.fontHeight(GameScreen.Font.MEDIUM), keys,
                wait > 0 ? 0.6f : 1f, wait > 0 ? 0.6f : 1f, wait > 0 ? 0.6f : 1f, 1f);
        answerButtons(x, bottom, wait);
    }

    private void drawServer(int x, int y, int width, int bottom) {
        int medium = game.fontHeight(GameScreen.Font.MEDIUM);
        int small = game.fontHeight(GameScreen.Font.SMALL) + 3;
        ClientSession.Update u = session.update;
        if (u != null) {
            game.rect(x - 6, y - 4, width + 12, medium * 2 + small + 16, 0.35f, 0.25f, 0.05f, 1f);
            game.text(GameScreen.Font.MEDIUM, x, y, String.format(Locale.ROOT, texts.get("mp.banner"), actionText(u.action), u.mod, u.by), 1f, 0.9f, 0.5f, 1f);
            long left = Math.max(0, (u.deadline - frameTime) / 1000);
            String status = session.answered() ? texts.get(u.phase == ClientSession.Phase.FAILED ? "mp.line.failed" : "mp.line.ready")
                    : u.status.isEmpty() ? texts.get("mp.getting") : u.status;
            game.text(GameScreen.Font.MEDIUM, x, y + medium + 4, status
                    + "   " + String.format(Locale.ROOT, texts.get("mp.left"), left), 1f, 1f, 1f, 1f);
            game.text(GameScreen.Font.SMALL, x, y + medium * 2 + 8, texts.get("mp.paused"), 0.85f, 0.85f, 0.85f, 1f);
            y += medium * 2 + small + 24;
        }
        game.text(GameScreen.Font.SMALL, x, y, texts.get(session.admin ? "mp.youAdmin" : "mp.youPlayer"), 0.7f, 0.7f, 0.7f, 1f);
        y += small + 4;
        List<ClientSession.ServerMod> list = session.serverMods;
        if (list.isEmpty()) {
            game.text(GameScreen.Font.MEDIUM, x, y, texts.get("mp.noMods"), 0.8f, 0.8f, 0.8f, 1f);
            return;
        }
        Map<String, ModEntry> local = new HashMap<>();
        for (Actions.InstalledMod m : mods) {
            if (m.java() != null) {
                local.put(m.id(), m.java());
            }
        }
        int line = medium + 8;
        int rowsBottom = bottom - buttonHeight() - 10;
        int bx = x;
        if (session.admin) {
            bx += button(bx, rowsBottom + 6, texts.get("btn.serverUpdate"), KEY_ENTER, null, true) + 10;
            bx += button(bx, rowsBottom + 6, texts.get("btn.serverToggle"), KEY_A, null, true) + 10;
            bx += button(bx, rowsBottom + 6, texts.get("btn.serverRemove"), KEY_U, null, true) + 10;
        }
        button(bx, rowsBottom + 6, texts.get("btn.refresh"), KEY_R, null, true);
        listArea = new int[] {x - 6, y - 4, width + 12, rowsBottom - y};
        for (int i = 0; i < list.size() && y + line < rowsBottom; i++) {
            ClientSession.ServerMod m = list.get(i);
            if (i == serverSelected) {
                game.rect(x - 6, y - 3, width + 12, line - 2, 0.25f, 0.45f, 0.3f, 0.6f);
            }
            int row = i;
            hits.add(new Hit(x - 6, y - 3, width + 12, line - 2, 0, () -> serverSelected = row));
            game.text(GameScreen.Font.MEDIUM, x, y, m.id(), 1f, 1f, 1f, 1f);
            if (m.lua()) {
                String right = "Lua";
                int w = game.textWidth(GameScreen.Font.SMALL, right);
                game.text(GameScreen.Font.SMALL, x + width - w, y + 2, right, 0.55f, 0.8f, 1f, 1f);
                y += line;
                continue;
            }
            ModEntry mine = local.get(m.id());
            BiteCheckGate.Check c = mine == null ? null : checks.computeIfAbsent(mine.jar(), this::checkQuietly);
            String here = mine == null ? texts.get("mp.missingHere")
                    : c != null && c.sha256().equals(m.sha256()) ? texts.get("mp.same") : texts.get("mp.different");
            String right = here + (m.updateWaiting() ? "   " + texts.get("mp.updateWaiting") : "") + "   " + texts.get("state." + m.state());
            int w = game.textWidth(GameScreen.Font.SMALL, right);
            game.text(GameScreen.Font.SMALL, x + width - w, y + 2, right, 0.7f, 0.85f, 0.7f, 1f);
            y += line;
        }
    }

    // --- drawing ---------------------------------------------------------------------------------

    private void drawHint() {
        String hint = texts.get("hint");
        int w = game.textWidth(GameScreen.Font.SMALL, hint);
        game.text(GameScreen.Font.SMALL, game.width() - w - 16, 8, hint, 0.8f, 0.8f, 0.8f, 0.6f);
    }

    private void draw(long now) {
        int sw = game.width();
        int sh = game.height();
        game.rect(0, 0, sw, sh, 0f, 0f, 0f, 0.8f);
        // most of the screen, up to 1600 x 1000
        int pw = Math.min(1600, Math.max(Math.min(900, sw - 40), sw * 92 / 100));
        int ph = Math.min(1000, Math.max(Math.min(600, sh - 40), sh * 90 / 100));
        int px = (sw - pw) / 2;
        int py = (sh - ph) / 2;
        game.rect(px, py, pw, ph, 0.07f, 0.08f, 0.1f, 0.98f);
        game.rect(px, py, pw, 4, 0.35f, 0.75f, 0.45f, 1f);

        int x = px + 24;
        int width = pw - 48;
        int y = py + 18;
        game.text(GameScreen.Font.LARGE, x, y, texts.get("title"), 1f, 1f, 1f, 1f);
        int closeWidth = game.textWidth(GameScreen.Font.MEDIUM, texts.get("btn.close")) + 24;
        button(px + pw - closeWidth - 16, py + 14, texts.get("btn.close"), KEY_ESCAPE, null, true);
        y += game.fontHeight(GameScreen.Font.LARGE) + 4;
        y = wrapped(GameScreen.Font.SMALL, x, y, width, texts.get("secure"), 0.55f, 0.85f, 0.6f);
        y += 8;

        int bottom = py + ph - 18;
        int small = game.fontHeight(GameScreen.Font.SMALL);
        if (mode == Mode.SERVER_CONSENT) {
            drawServerConsent(x, y, width, bottom, now);
            return;
        }
        if (mode == Mode.UNINSTALL_ONCE || mode == Mode.UNINSTALL_TWICE) {
            drawUninstallQuestion(x, y, width, bottom, now);
            return;
        }
        if (mode == Mode.DEPENDENCY) {
            drawDepQuestion(x, y, width, bottom);
            return;
        }
        if (mode != Mode.LIST) {
            drawQuestion(x, y, width, bottom, now);
            return;
        }
        y = drawTabs(x, y);
        game.text(GameScreen.Font.SMALL, x, bottom - small, texts.get("help." + tab.name()), 0.7f, 0.7f, 0.7f, 1f);
        int contentBottom = bottom - small - 12;
        String downloading = downloadStatus();
        if (downloading != null) { // on every tab: what Steam is downloading for Jumpstart right now
            contentBottom -= small + 6;
            game.text(GameScreen.Font.SMALL, x, contentBottom + 6, fit(GameScreen.Font.SMALL, downloading, width), 0.6f, 1f, 0.7f, 1f);
        }
        if (!message.isEmpty()) {
            int lines = Math.min(3, game.textWidth(GameScreen.Font.SMALL, message) / Math.max(1, width) + 1);
            contentBottom -= lines * (small + 2) + 6;
            wrapped(GameScreen.Font.SMALL, x, contentBottom + 6, width, message,
                    messageGood ? 0.5f : 1f, messageGood ? 1f : 0.65f, messageGood ? 0.5f : 0.4f);
        }
        switch (tab) {
            case INSTALLED -> drawList(x, y, width, contentBottom);
            case JAVA_LIBRARY, LUA_LIBRARY -> drawLibrary(libraries.get(tab), x, y, width, contentBottom, now);
            case SHARING -> drawSharing(x, y, width, contentBottom);
            case SNAPSHOTS -> drawSnapshots(x, y, width, contentBottom);
            case SERVER -> drawServer(x, y, width, contentBottom);
        }
    }

    private int drawTabs(int x, int y) {
        int cx = x;
        int h = game.fontHeight(GameScreen.Font.MEDIUM) + 8;
        for (Tab t : Tab.values()) {
            if (!tabShown(t)) {
                continue;
            }
            String label = texts.get("tab." + t.name());
            int w = game.textWidth(GameScreen.Font.MEDIUM, label) + 24;
            boolean active = t == tab;
            game.rect(cx, y, w, h, active ? 0.25f : 0.12f, active ? 0.45f : 0.13f, active ? 0.3f : 0.15f, 1f);
            float c = active ? 1f : 0.7f;
            game.text(GameScreen.Font.MEDIUM, cx + 12, y + 4, label, c, c, c, 1f);
            Tab target = t;
            hits.add(new Hit(cx, y, w, h, 0, () -> switchTab(target)));
            cx += w + 6;
        }
        return y + h + 12;
    }

    /** The list's share of the width; the detail pane gets the rest. */
    private static int listWidth(int width) {
        return width * 42 / 100;
    }

    private void drawList(int x, int top, int width, int bottom) {
        if (mods.isEmpty()) {
            game.text(GameScreen.Font.MEDIUM, x, top, texts.get("noMods"), 0.8f, 0.8f, 0.8f, 1f);
            return;
        }
        int listWidth = listWidth(width);
        int line = Math.max(game.fontHeight(GameScreen.Font.MEDIUM) + 12, 32);
        int icon = line - 6;
        int visible = Math.max(1, (bottom - top) / line);
        int first = Math.max(0, Math.min(selected - visible / 2, mods.size() - visible));
        int kindWidth = game.textWidth(GameScreen.Font.SMALL, "Java  ");
        for (int i = first; i < Math.min(mods.size(), first + visible); i++) {
            Actions.InstalledMod mod = mods.get(i);
            int y = top + (i - first) * line;
            if (i == selected) {
                game.rect(x - 6, y - 3, listWidth + 12, line - 2, 0.25f, 0.45f, 0.3f, 0.6f);
            }
            int row = i;
            hits.add(new Hit(x - 6, y - 3, listWidth + 12, line - 2, 0, () -> selected = row));
            String image = actions.details(mod.id()).image();
            if (image == null || !game.image(image, x, y, icon, icon)) {
                game.rect(x, y, icon, icon, 0.14f, 0.15f, 0.17f, 1f);
            }
            int tx = x + icon + 10;
            boolean java = mod.java() != null;
            game.text(GameScreen.Font.SMALL, tx, y + 4, java ? "Java" : "Lua", java ? 0.95f : 0.55f, java ? 0.75f : 0.8f, java ? 0.35f : 1f, 1f);
            String state = mod.id().equals(SELF) ? "self" : stateOf(mod.id());
            String label = stateLabel(mod.id(), state);
            float[] c = stateColor(state);
            int w = game.textWidth(GameScreen.Font.SMALL, label);
            game.text(GameScreen.Font.SMALL, x + listWidth - w, y + 4, label, c[0], c[1], c[2], 1f);
            game.text(GameScreen.Font.MEDIUM, tx + kindWidth, y + 2, fit(GameScreen.Font.MEDIUM, mod.name(), listWidth - icon - 10 - kindWidth - w - 12),
                    1f, 1f, 1f, 1f);
        }
        int dx = x + listWidth + 26;
        game.rect(dx - 14, top - 4, 1, bottom - top + 4, 0.3f, 0.3f, 0.3f, 1f);
        listArea = new int[] {x - 6, top - 4, listWidth + 12, bottom - top + 4};
        detailArea = new int[] {dx, top, x + width - dx, bottom - top};
        drawInstalledDetails(selectedMod(), dx, top, x + width - dx, bottom);
    }

    private static float[] stateColor(String state) {
        return switch (state) {
            case "self", "on", "lua:on" -> new float[] {0.45f, 0.95f, 0.45f};
            case "off", "lua:off" -> new float[] {0.95f, 0.7f, 0.3f};
            case "on, until restart", "lua:on, next load" -> new float[] {0.6f, 0.8f, 0.6f};
            case "lua:off, next load" -> new float[] {0.85f, 0.75f, 0.5f};
            default -> new float[] {0.65f, 0.65f, 0.65f};
        };
    }

    /** The selected installed mod: picture, name, state, BiteCheck's verdict, and its description. */
    private void drawInstalledDetails(Actions.InstalledMod mod, int x, int top, int width, int bottom) {
        if (mod == null) {
            return;
        }
        detailFor("mod:" + mod.id());
        Actions.Details d = actions.details(mod.id());
        int box = Math.min(128, width / 3);
        boolean picture = d.image() != null && game.image(d.image(), x, top, box, box);
        int tx = picture ? x + box + 16 : x;
        int tw = x + width - tx;
        int y = top;
        game.text(GameScreen.Font.LARGE, tx, y, fit(GameScreen.Font.LARGE, mod.name(), tw), 1f, 1f, 1f, 1f);
        y += game.fontHeight(GameScreen.Font.LARGE) + 6;
        String state = mod.id().equals(SELF) ? "self" : stateOf(mod.id());
        float[] sc = stateColor(state);
        game.text(GameScreen.Font.MEDIUM, tx, y, (mod.java() != null ? "Java   " : "Lua   ") + stateLabel(mod.id(), state), sc[0], sc[1], sc[2], 1f);
        y += game.fontHeight(GameScreen.Font.MEDIUM) + 6;
        if (mod.java() != null) {
            BiteCheckGate.Check check = checks.computeIfAbsent(mod.java().jar(), this::checkQuietly);
            if (check != null) {
                String verdict = check.verdict().name();
                float[] c = verdict.equals("ALLOW") ? new float[] {0.5f, 1f, 0.5f}
                        : verdict.equals("REFUSE") ? new float[] {1f, 0.4f, 0.4f} : new float[] {1f, 0.8f, 0.3f};
                y = wrapped(GameScreen.Font.SMALL, tx, y, tw, texts.get(verdict) + "  (" + check.status() + ": " + check.info() + ")", c[0], c[1], c[2]);
            }
            y = wrapped(GameScreen.Font.SMALL, tx, y, tw, texts.get("mod") + mod.id()
                    + (mod.workshopId() > 0 ? "   Workshop " + mod.workshopId() : ""), 0.65f, 0.65f, 0.65f);
            y = wrapped(GameScreen.Font.SMALL, tx, y, tw, texts.get("file") + shorten(mod.java().jar().toString(), 90), 0.55f, 0.55f, 0.55f);
        } else {
            y = wrapped(GameScreen.Font.SMALL, tx, y, tw, texts.get("luaMod"), 0.55f, 0.8f, 1f);
            y = wrapped(GameScreen.Font.SMALL, tx, y, tw, texts.get("mod") + mod.id()
                    + (mod.workshopId() > 0 ? "   Workshop " + mod.workshopId() : ""), 0.65f, 0.65f, 0.65f);
        }
        if (!mod.requires().isEmpty()) {
            y = wrapped(GameScreen.Font.SMALL, tx, y, tw, texts.get("deps.requires") + names(mod.requires()), 0.6f, 0.8f, 1f);
        }
        List<String> neededBy = ModDeps.neededBy(mod.id(), depMap());
        if (!neededBy.isEmpty()) {
            y = wrapped(GameScreen.Font.SMALL, tx, y, tw, texts.get("deps.neededBy") + names(neededBy), 0.6f, 0.8f, 1f);
        }
        y = Math.max(y, picture ? top + box : top) + 10;
        if (!mod.id().equals(SELF)) {
            int bx = x;
            if (mod.java() != null) {
                bx += button(bx, y, texts.get("btn.load"), KEY_ENTER, null, true) + 10;
            }
            bx += button(bx, y, texts.get("btn.toggle"), KEY_A, null, true) + 10;
            button(bx, y, texts.get("btn.uninstall"), KEY_U, null, true);
            y += buttonHeight() + 10;
        }
        int noteTop = bottom - wrapLines(GameScreen.Font.SMALL, texts.get("luaNote"), width).size() * (game.fontHeight(GameScreen.Font.SMALL) + 2);
        wrapped(GameScreen.Font.SMALL, x, noteTop, width, texts.get("luaNote"), 0.5f, 0.5f, 0.5f);
        game.rect(x, y, width, 1, 0.3f, 0.3f, 0.3f, 1f);
        String description = d.description().isBlank() ? texts.get("details.none") : d.description();
        drawScrolled(description, x, y + 10, width, noteTop - 10);
    }

    private void drawLibrary(Library lib, int x, int top, int width, int bottom, long now) {
        int medium = game.fontHeight(GameScreen.Font.MEDIUM);
        int small = game.fontHeight(GameScreen.Font.SMALL) + 3;
        game.rect(x - 6, top - 4, width + 12, medium + 10, 0.03f, 0.03f, 0.04f, 1f);
        game.text(GameScreen.Font.MEDIUM, x, top, texts.get("search") + lib.query + "_", 1f, 1f, 1f, 1f);
        int searchWidth = game.textWidth(GameScreen.Font.MEDIUM, texts.get("btn.search")) + 24;
        button(x + width - searchWidth + 6, top - 4, texts.get("btn.search"), 0, () -> search(lib, false), !lib.busy);
        int y = top + medium + 14;
        game.text(GameScreen.Font.SMALL, x, y, lib.status.isEmpty() ? texts.get("searchHint") : lib.status, 0.7f, 0.7f, 0.7f, 1f);
        y += small + 6;

        int listWidth = listWidth(width);
        int line = medium + small + 10;
        int listBottom = lib.next.isEmpty() ? bottom : bottom - buttonHeight() - 8;
        if (!lib.next.isEmpty()) {
            button(x, listBottom + 4, texts.get("btn.more"), 0, () -> search(lib, true), !lib.busy);
        }
        listArea = new int[] {x - 6, y - 4, listWidth + 12, listBottom - y + 4};
        int visible = Math.max(1, (listBottom - y) / line);
        int first = Math.max(0, Math.min(lib.selected - visible / 2, lib.items.size() - visible));
        for (int i = first; i < Math.min(lib.items.size(), first + visible); i++) {
            LibraryClient.Item item = lib.items.get(i);
            int ry = y + (i - first) * line;
            if (i == lib.selected) {
                game.rect(x - 6, ry - 3, listWidth + 12, line - 2, 0.25f, 0.45f, 0.3f, 0.6f);
            }
            int row = i;
            hits.add(new Hit(x - 6, ry - 3, listWidth + 12, line - 2, 0, () -> lib.selected = row));
            game.text(GameScreen.Font.MEDIUM, x, ry, fit(GameScreen.Font.MEDIUM, item.title(), listWidth), 1f, 1f, 1f, 1f);
            String info = String.format(Locale.ROOT, texts.get("subscribers"), item.subscriptions());
            String ws = workshopLabel(item);
            game.text(GameScreen.Font.SMALL, x + 12, ry + medium + 2, fit(GameScreen.Font.SMALL, (ws.isEmpty() ? "" : ws + "   ") + info, listWidth - 12),
                    0.7f, 0.85f, 0.7f, 1f);
        }
        if (lib.items.isEmpty()) {
            return;
        }
        int dx = x + listWidth + 26;
        game.rect(dx - 14, y - 4, 1, bottom - y + 4, 0.3f, 0.3f, 0.3f, 1f);
        detailArea = new int[] {dx, y, x + width - dx, bottom - y};
        drawWorkshopDetails(lib.items.get(lib.selected), dx, y, x + width - dx, bottom, now);
    }

    /** A library item: preview, title, Workshop id and tags, and the full description from Steam. */
    private void drawWorkshopDetails(LibraryClient.Item item, int x, int top, int width, int bottom, long now) {
        detailFor("ws:" + item.id());
        // ask Steam for the page once the selection has stayed on the item for a moment
        if (pageCandidate != item.id()) {
            pageCandidate = item.id();
            pageCandidateSince = now;
        } else if (now - pageCandidateSince > 350 && pageRequested.add(item.id())) {
            long id = item.id();
            inBackground(() -> actions.workshopDetails(id), r -> {
                if (r instanceof Actions.Details page && (!page.description().isBlank() || page.image() != null)) {
                    pages.put(id, page);
                } else if (!(r instanceof Actions.Details)) {
                    pageRequested.remove(id); // failed: try again later
                }
            });
        }
        Actions.Details page = pages.get(item.id());
        int y = top;
        int boxH = Math.min(220, (bottom - top) / 3);
        if (page != null && page.image() != null && game.image(page.image(), x, y, width, boxH)) {
            y += boxH + 10;
        }
        game.text(GameScreen.Font.LARGE, x, y, fit(GameScreen.Font.LARGE, item.title(), width), 1f, 1f, 1f, 1f);
        y += game.fontHeight(GameScreen.Font.LARGE) + 6;
        y = wrapped(GameScreen.Font.SMALL, x, y, width, "Workshop " + item.id() + "   " + String.join(", ", item.tags()), 0.65f, 0.65f, 0.65f);
        String ws = workshopLabel(item);
        if (!ws.isEmpty()) {
            y = wrapped(GameScreen.Font.SMALL, x, y, width, ws, 0.6f, 0.9f, 0.6f);
        }
        y = wrapped(GameScreen.Font.SMALL, x, y, width, texts.get(item.java() ? "javaNote" : "luaLibraryNote"), 0.55f, 0.55f, 0.55f);
        y += 6;
        button(x, y, texts.get("btn.install"), 0, () -> installWithRequirements(item, frameTime), download == null);
        y += buttonHeight() + 8;
        game.rect(x, y, width, 1, 0.3f, 0.3f, 0.3f, 1f);
        String description = page != null && !page.description().isBlank() ? page.description()
                : item.description() + (pageRequested.contains(item.id()) && page == null ? "\n\n" + texts.get("details.loading") : "");
        drawScrolled(description.isBlank() ? texts.get("details.none") : description, x, y + 10, width, bottom);
    }

    /** A new item in the detail pane starts at the top of its description. */
    private void detailFor(String key) {
        if (!key.equals(detailKey)) {
            detailKey = key;
            detailScroll = 0;
        }
    }

    /** Draws a text wrapped to the width, from line detailScroll on, with a scroll bar when it is longer. */
    private void drawScrolled(String text, int x, int y, int width, int bottom) {
        int line = game.fontHeight(GameScreen.Font.SMALL) + 2;
        if (!text.equals(wrappedText) || width - 14 != wrappedWidth) {
            wrappedText = text;
            wrappedWidth = width - 14;
            wrappedLines = wrapLines(GameScreen.Font.SMALL, text, wrappedWidth);
        }
        int visible = Math.max(1, (bottom - y) / line);
        int max = Math.max(0, wrappedLines.size() - visible);
        detailScroll = Math.min(detailScroll, max);
        for (int i = detailScroll; i < Math.min(wrappedLines.size(), detailScroll + visible); i++) {
            game.text(GameScreen.Font.SMALL, x, y + (i - detailScroll) * line, wrappedLines.get(i), 0.85f, 0.85f, 0.85f, 1f);
        }
        if (max > 0) {
            int track = visible * line;
            int thumb = Math.max(20, track * visible / wrappedLines.size());
            int pos = (track - thumb) * detailScroll / max;
            game.rect(x + width - 6, y, 4, track, 0.18f, 0.18f, 0.2f, 1f);
            game.rect(x + width - 6, y + pos, 4, thumb, 0.45f, 0.7f, 0.5f, 1f);
        }
    }

    /** Paragraphs (lines) wrapped at word boundaries; a word longer than the width is cut. */
    private List<String> wrapLines(GameScreen.Font font, String text, int width) {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (current.length() > 0 && game.textWidth(font, candidate) > width) {
                    out.add(current.toString());
                    current = new StringBuilder(fit(font, word, width));
                } else {
                    current = new StringBuilder(current.length() == 0 ? fit(font, word, width) : candidate);
                }
            }
            out.add(current.toString());
        }
        return out;
    }

    /** The text, shortened with "..." until it fits the width. */
    private String fit(GameScreen.Font font, String text, int width) {
        if (width <= 0 || game.textWidth(font, text) <= width) {
            return text;
        }
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (game.textWidth(font, text.substring(0, mid) + "...") <= width) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + "...";
    }

    private String workshopLabel(LibraryClient.Item item) {
        if (download != null && download.item().id() == item.id()) {
            return texts.get("ws.downloading");
        }
        long state = actions.workshop().unavailableReason() == null ? actions.workshop().state(item.id()) : 0;
        if ((state & Workshop.INSTALLED) != 0) {
            return texts.get((state & Workshop.NEEDS_UPDATE) != 0 ? "ws.update" : "ws.installed");
        }
        return (state & Workshop.SUBSCRIBED) != 0 ? texts.get("ws.subscribed") : "";
    }

    private void drawSharing(int x, int y, int width, int bottom) {
        game.text(GameScreen.Font.MEDIUM, x, y, texts.get(sharing ? "sharing.on" : "sharing.off"),
                sharing ? 0.5f : 1f, sharing ? 1f : 0.8f, sharing ? 0.5f : 0.4f, 1f);
        y += game.fontHeight(GameScreen.Font.MEDIUM) + 8;
        y = wrapped(GameScreen.Font.SMALL, x, y, width, texts.get("sharing.what"), 0.85f, 0.85f, 0.85f);
        y += 8;
        button(x, y, texts.get(sharing ? "btn.sharingOff" : "btn.sharingOn"), KEY_ENTER, null, true);
        y += buttonHeight() + 10;
        game.text(GameScreen.Font.SMALL, x, y, texts.get("sharing.preview"), 0.7f, 0.7f, 0.7f, 1f);
        y += game.fontHeight(GameScreen.Font.SMALL) + 6;
        int line = game.fontHeight(GameScreen.Font.SMALL) + 2;
        for (String l : sharingPreview.split("\n")) {
            if (y + line > bottom) {
                game.text(GameScreen.Font.SMALL, x, y, "...", 0.6f, 0.6f, 0.6f, 1f);
                break;
            }
            game.text(GameScreen.Font.SMALL, x + 12, y, cut(l.replace('\t', ' '), 150), 0.6f, 0.8f, 0.9f, 1f);
            y += line;
        }
    }

    private BiteCheckGate.Check checkQuietly(Path jar) {
        try {
            return actions.check(jar);
        } catch (IOException e) {
            return null;
        }
    }

    private void drawQuestion(int x, int y, int width, int bottom, long now) {
        Pending p = pending;
        boolean second = mode == Mode.CONFIRM_TWICE;
        float[] c = second ? new float[] {1f, 0.35f, 0.3f} : new float[] {1f, 0.75f, 0.25f};
        game.rect(x - 6, y - 4, width + 12, 3, c[0], c[1], c[2], 1f);
        y += 8;
        game.text(GameScreen.Font.LARGE, x, y, texts.get(second ? "confirm2.title" : "confirm1.title"), c[0], c[1], c[2], 1f);
        y += game.fontHeight(GameScreen.Font.LARGE) + 10;
        game.text(GameScreen.Font.MEDIUM, x, y, p.mod().name() + "  [" + p.mod().id() + "]", 1f, 1f, 1f, 1f);
        y += game.fontHeight(GameScreen.Font.MEDIUM) + 8;
        String reason;
        if (!second) {
            reason = texts.get("confirm1.text");
        } else if (p.check().status() == Verifier.Status.FLAGGED) {
            reason = texts.get("confirm2.flagged") + " " + p.check().info();
        } else {
            reason = texts.get("confirm2.suspicious");
        }
        y = wrapped(GameScreen.Font.MEDIUM, x, y, width, reason, 1f, 1f, 1f);
        y += 10;
        int small = game.fontHeight(GameScreen.Font.SMALL) + 3;
        game.text(GameScreen.Font.SMALL, x, y, "BiteCheck: " + p.check().status() + " (" + p.check().info() + ")", 0.8f, 0.8f, 0.8f, 1f);
        y += small;
        game.text(GameScreen.Font.SMALL, x, y, texts.get("file") + shorten(p.mod().jar().toString(), 120), 0.8f, 0.8f, 0.8f, 1f);
        y += small;
        game.text(GameScreen.Font.SMALL, x, y, "SHA-256: " + p.check().sha256(), 0.8f, 0.8f, 0.8f, 1f);

        long left = delay() - (now - questionSince);
        String keys = left > 0
                ? String.format(Locale.ROOT, texts.get("confirm.wait"), (left + 999) / 1000)
                : texts.get("confirm.ready");
        game.text(GameScreen.Font.MEDIUM, x, bottom - game.fontHeight(GameScreen.Font.MEDIUM), keys,
                left > 0 ? 0.6f : 1f, left > 0 ? 0.6f : 1f, left > 0 ? 0.6f : 1f, 1f);
        answerButtons(x, bottom, left);
    }

    /** Draws text wrapped at word boundaries; returns the y below it. */
    private int wrapped(GameScreen.Font font, int x, int y, int width, String text, float r, float g, float b) {
        int line = game.fontHeight(font) + 2;
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (current.length() > 0 && game.textWidth(font, candidate) > width) {
                game.text(font, x, y, current.toString(), r, g, b, 1f);
                y += line;
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) {
            game.text(font, x, y, current.toString(), r, g, b, 1f);
            y += line;
        }
        return y;
    }

    /** The end of a long text (paths). */
    private static String shorten(String text, int max) {
        return text.length() <= max ? text : "..." + text.substring(text.length() - max + 3);
    }

    /** The start of a long text (descriptions). */
    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    // --- for tests -------------------------------------------------------------------------------

    /** One line about the current state, for automated tests. */
    String describe() {
        StringBuilder sb = new StringBuilder(mode.name()).append(" tab=").append(tab);
        if (tab == Tab.JAVA_LIBRARY || tab == Tab.LUA_LIBRARY) {
            Library lib = libraries.get(tab);
            sb.append(" query=").append(lib.query).append(" results=").append(lib.items.size());
            if (!lib.items.isEmpty()) {
                sb.append(" selected=").append(lib.items.get(lib.selected).id());
            }
            sb.append(" status=").append(lib.status);
        } else if (tab == Tab.SHARING) {
            sb.append(" sharing=").append(sharing);
        } else if (tab == Tab.SNAPSHOTS) {
            sb.append(" snapshots=").append(snapshotList.size()).append(" selected=").append(snapshotSelected);
        } else if (tab == Tab.SERVER) {
            sb.append(" admin=").append(session.admin).append(" serverMods=").append(session.serverMods.size());
            if (session.update != null) {
                sb.append(" update=").append(session.update.mod).append(" phase=").append(session.update.phase);
            }
        } else {
            Actions.InstalledMod mod = selectedMod();
            sb.append(" selected=").append(mod == null ? "-" : mod.id());
            if (mod != null) {
                sb.append(" state=").append(stateOf(mod.id()));
            }
        }
        if (pending != null) {
            sb.append(" asking=").append(pending.check().verdict());
        }
        if (busyText != null) {
            sb.append(" busy=").append(busyText);
        }
        if (download != null) {
            sb.append(" downloading=").append(download.item().id());
        }
        sb.append(" message=").append(message);
        String shown = banner(frameTime);
        if (shown != null) {
            sb.append(" banner=").append(shown);
        }
        return sb.toString();
    }

    Mode mode() {
        return mode;
    }

    Tab tab() {
        return tab;
    }

    ClientSession session() {
        return session;
    }

    String sharingPreview() {
        return sharingPreview;
    }

    List<String> modIds() {
        List<String> ids = new ArrayList<>();
        for (Actions.InstalledMod mod : mods) {
            ids.add(mod.id());
        }
        return ids;
    }
}
