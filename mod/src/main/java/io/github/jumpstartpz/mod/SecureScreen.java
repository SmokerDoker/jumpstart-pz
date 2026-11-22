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

    enum Mode { CLOSED, LIST, CONFIRM_ONCE, CONFIRM_TWICE, SERVER_CONSENT, UNINSTALL_ONCE, UNINSTALL_TWICE }

    /** How long the answer key does nothing in the two uninstall questions. */
    static final long UNINSTALL_FIRST_DELAY_MS = 1500;
    static final long UNINSTALL_SECOND_DELAY_MS = 3000;

    /** How long the player's "yes" to an admin's change does nothing. */
    static final long CONSENT_DELAY_MS = 2000;

    enum Tab { INSTALLED, JAVA_LIBRARY, LUA_LIBRARY, SHARING, SERVER }

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

    // sharing tab
    private boolean sharing;
    private String sharingPreview = "";
    private boolean sharingStarted;

    /** {@code server}: the server asked for this mod (multiplayer update); the answer goes back to it. */
    private record Pending(ModEntry mod, BiteCheckGate.Check check, boolean update, boolean server) {
    }

    // multiplayer
    private int serverSelected;
    private boolean openedByServer;
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

    void frame(long now) {
        if (texts == null) {
            texts = new Texts(game.language());
        }
        frameTime = now;
        runMainThreadWork();
        session.tick();
        Set<Integer> pressed = poll();
        if (download != null) {
            followDownload(now);
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
                } else if (!game.inGame()) {
                    drawHint();
                }
            }
            case LIST -> onKeys(pressed, now);
            case CONFIRM_ONCE, CONFIRM_TWICE -> onQuestion(pressed, now);
            case SERVER_CONSENT -> onServerConsent(pressed, now);
            case UNINSTALL_ONCE, UNINSTALL_TWICE -> onUninstallQuestion(pressed, now);
        }
        if (mode != Mode.CLOSED) {
            draw(now);
        }
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

    private void onKeys(Set<Integer> pressed, long now) {
        if (pressed.contains(KEY_ESCAPE) && session.update == null) {
            close();
            return;
        }
        if (pressed.contains(KEY_TAB)) {
            tab = Tab.values()[(tab.ordinal() + 1) % Tab.values().length];
            if (tab == Tab.SERVER && !session.connected()) {
                tab = Tab.INSTALLED; // the server tab exists only in multiplayer
            }
            if (tab == Tab.SERVER) {
                session.refresh();
            }
            message = "";
            if (tab == Tab.SHARING) {
                showSharing();
            }
            return;
        }
        switch (tab) {
            case INSTALLED -> onInstalled(pressed, now);
            case JAVA_LIBRARY, LUA_LIBRARY -> onLibrary(libraries.get(tab), pressed, now);
            case SHARING -> onSharing(pressed);
            case SERVER -> onServer(pressed);
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
            say(actions.refreshIndex(), true);
            checks.clear();
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
        if (mod.java() == null) {
            if (toggle || enter) {
                toggleLua(mod, enter);
            }
            return;
        }
        if (toggle) {
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
        } else if (enter) {
            start(mod.java(), now, false);
        }
    }

    /** A (or Enter on a mod that is off): a Lua mod on or off, in the mod list and, in a game, live. */
    private void toggleLua(Actions.InstalledMod mod, boolean enter) {
        String state = actions.luaState(mod.id());
        if (state.startsWith("on")) {
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
            say(String.format(Locale.ROOT, texts.get(stopped > 0 ? "lua.stopped" : "lua.off"), mod.name(), stopped), true);
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
        mode = Mode.LIST;
        String shownHash = p.check().sha256();
        LiveInstaller.ConsentSource answer = check -> check.sha256().equals(shownHash) ? consent : null;
        LiveInstaller.Outcome outcome = p.update() ? actions.update(p.mod(), answer) : actions.install(p.mod(), answer);
        checks.remove(p.mod().jar());
        refreshStates();
        say(outcome.kind() + " " + outcome.message(), outcome.kind() == LiveInstaller.Kind.DONE);
        String[] lua = luaAfterJava;
        luaAfterJava = null;
        if (!p.server() && outcome.kind() == LiveInstaller.Kind.DONE) {
            // the Java part runs: the mod joins the mod list, then its Lua part, which may use the Java part
            actions.activate(p.mod().id());
            String title = lua != null && lua[0].equals(p.mod().id()) ? lua[1] : p.mod().name();
            String note = luaNote(title, actions.loadLua(p.mod().id()));
            if (!note.isEmpty()) {
                say(outcome.kind() + " " + outcome.message() + "  " + note, true);
            }
            refreshStates();
        }
        if (p.server()) {
            if (outcome.kind() == LiveInstaller.Kind.DONE) {
                session.ready();
                say(texts.get("mp.waiting"), true);
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
                startDownload(lib.items.get(lib.selected), now);
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

    /** Enter on a library item: subscribe and download it through Steam, then install it. */
    private void startDownload(LibraryClient.Item item, long now) {
        if (session.inMultiplayer()) {
            say(texts.get("mp.locked"), false);
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
                say(texts.get("downloadTimeout"), false);
            }
            return;
        }
        download = null;
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
                if (mode == Mode.CLOSED) {
                    open();
                    openedByServer = true;
                } else if (mode != Mode.LIST) {
                    pending = null; // a question of the player's own is dropped for the server's
                    mode = Mode.LIST;
                }
                tab = Tab.SERVER;
                ClientSession.Update u = session.update;
                say(String.format(Locale.ROOT, texts.get("mp.prepare"), u.by, actionText(u.action), u.mod), true);
                if (u.phase == ClientSession.Phase.CONSENT) {
                    mode = Mode.SERVER_CONSENT;
                } else if (!u.action.equals("update")) {
                    switchForServer(u);
                }
            }
            case "done" -> {
                say(session.lastNote.isEmpty() ? texts.get("mp.done") : texts.get("mp.done") + " " + session.lastNote, true);
                refreshStates();
                if (openedByServer && mode == Mode.LIST) {
                    close();
                }
                openedByServer = false;
            }
            default -> say(session.lastNote, false);
        }
    }

    /** Every frame during a server update: get the exact jar the server wants, then BiteCheck. */
    private void followServerUpdate(long now) {
        ClientSession.Update u = session.update;
        if (u.phase == ClientSession.Phase.SENT) {
            return;
        }
        if (now > u.deadline) {
            if ((pending != null && pending.server()) || mode == Mode.SERVER_CONSENT) {
                pending = null;
                mode = Mode.LIST;
            }
            session.failed("not ready in time");
            say(texts.get("mp.tooLate"), false);
            return;
        }
        if (u.phase != ClientSession.Phase.GETTING || !u.action.equals("update") || lookBusy || now < u.nextLook) {
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
        if ((enter || pressed.contains(KEY_A)) && !list.isEmpty() && session.update == null) {
            if (!session.admin) {
                say(texts.get("mp.notAdmin"), false);
            } else {
                ClientSession.ServerMod mod = list.get(Math.min(serverSelected, list.size() - 1));
                String action = enter ? "update" : mod.state().equals("off") ? "on" : "off";
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
        if (u.action.equals("update")) {
            u.phase = ClientSession.Phase.GETTING;
            u.nextLook = 0;
        } else {
            switchForServer(u);
        }
    }

    /** Off / on for the server: the same switch as the installed tab, then the answer. */
    private void switchForServer(ClientSession.Update u) {
        String state = actions.stateOf(u.mod);
        String result;
        if (state.equals("not loaded")) {
            result = u.action.equals("off") ? "OK" : "FAILED " + u.mod + " is not loaded here";
        } else if (u.action.equals("off")) {
            result = state.equals("off") ? "OK" : actions.off(u.mod);
        } else {
            result = state.equals("on") ? "OK" : actions.on(u.mod);
        }
        refreshStates();
        if (result.startsWith("OK")) {
            session.ready();
            say(texts.get("mp.waiting"), true);
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
    }

    private void drawServer(int x, int y, int width, int bottom) {
        int medium = game.fontHeight(GameScreen.Font.MEDIUM);
        int small = game.fontHeight(GameScreen.Font.SMALL) + 3;
        ClientSession.Update u = session.update;
        if (u != null) {
            game.rect(x - 6, y - 4, width + 12, medium * 2 + small + 16, 0.35f, 0.25f, 0.05f, 1f);
            game.text(GameScreen.Font.MEDIUM, x, y, String.format(Locale.ROOT, texts.get("mp.banner"), actionText(u.action), u.mod, u.by), 1f, 0.9f, 0.5f, 1f);
            long left = Math.max(0, (u.deadline - frameTime) / 1000);
            game.text(GameScreen.Font.MEDIUM, x, y + medium + 4, (u.status.isEmpty() ? texts.get("mp.getting") : u.status)
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
        for (int i = 0; i < list.size() && y + line < bottom; i++) {
            ClientSession.ServerMod m = list.get(i);
            if (i == serverSelected) {
                game.rect(x - 6, y - 3, width + 12, line - 2, 0.25f, 0.45f, 0.3f, 0.6f);
            }
            game.text(GameScreen.Font.MEDIUM, x, y, m.id(), 1f, 1f, 1f, 1f);
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
        int pw = Math.min(1100, sw - 60);
        int ph = Math.min(720, sh - 60);
        int px = (sw - pw) / 2;
        int py = (sh - ph) / 2;
        game.rect(px, py, pw, ph, 0.07f, 0.08f, 0.1f, 0.98f);
        game.rect(px, py, pw, 4, 0.35f, 0.75f, 0.45f, 1f);

        int x = px + 24;
        int width = pw - 48;
        int y = py + 18;
        game.text(GameScreen.Font.LARGE, x, y, texts.get("title"), 1f, 1f, 1f, 1f);
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
        if (mode != Mode.LIST) {
            drawQuestion(x, y, width, bottom, now);
            return;
        }
        y = drawTabs(x, y);
        game.text(GameScreen.Font.SMALL, x, bottom - small, texts.get("help." + tab.name()), 0.7f, 0.7f, 0.7f, 1f);
        int contentBottom = bottom - small - 12;
        if (!message.isEmpty()) {
            int lines = Math.min(3, game.textWidth(GameScreen.Font.SMALL, message) / Math.max(1, width) + 1);
            contentBottom -= lines * (small + 2) + 6;
            wrapped(GameScreen.Font.SMALL, x, contentBottom + 6, width, message,
                    messageGood ? 0.5f : 1f, messageGood ? 1f : 0.65f, messageGood ? 0.5f : 0.4f);
        }
        switch (tab) {
            case INSTALLED -> drawList(x, y, width, contentBottom);
            case JAVA_LIBRARY, LUA_LIBRARY -> drawLibrary(libraries.get(tab), x, y, width, contentBottom);
            case SHARING -> drawSharing(x, y, width, contentBottom);
            case SERVER -> drawServer(x, y, width, contentBottom);
        }
    }

    private int drawTabs(int x, int y) {
        int cx = x;
        int h = game.fontHeight(GameScreen.Font.MEDIUM) + 8;
        for (Tab t : Tab.values()) {
            if (t == Tab.SERVER && !session.connected()) {
                continue;
            }
            String label = texts.get("tab." + t.name());
            int w = game.textWidth(GameScreen.Font.MEDIUM, label) + 24;
            boolean active = t == tab;
            game.rect(cx, y, w, h, active ? 0.25f : 0.12f, active ? 0.45f : 0.13f, active ? 0.3f : 0.15f, 1f);
            float c = active ? 1f : 0.7f;
            game.text(GameScreen.Font.MEDIUM, cx + 12, y + 4, label, c, c, c, 1f);
            cx += w + 6;
        }
        return y + h + 12;
    }

    private void drawList(int x, int top, int width, int bottom) {
        int line = game.fontHeight(GameScreen.Font.MEDIUM) + 8;
        int small = game.fontHeight(GameScreen.Font.SMALL) + 3;
        int listBottom = bottom - small * 3 - 10;
        if (mods.isEmpty()) {
            game.text(GameScreen.Font.MEDIUM, x, top, texts.get("noMods"), 0.8f, 0.8f, 0.8f, 1f);
        }
        int visible = Math.max(1, (listBottom - top) / line);
        int first = Math.max(0, Math.min(selected - visible / 2, mods.size() - visible));
        int kindWidth = game.textWidth(GameScreen.Font.SMALL, "Java  ");
        for (int i = first; i < Math.min(mods.size(), first + visible); i++) {
            Actions.InstalledMod mod = mods.get(i);
            int y = top + (i - first) * line;
            if (i == selected) {
                game.rect(x - 6, y - 3, width + 12, line - 2, 0.25f, 0.45f, 0.3f, 0.6f);
            }
            boolean java = mod.java() != null;
            game.text(GameScreen.Font.SMALL, x, y + 2, java ? "Java" : "Lua", java ? 0.95f : 0.55f, java ? 0.75f : 0.8f, java ? 0.35f : 1f, 1f);
            game.text(GameScreen.Font.MEDIUM, x + kindWidth, y, mod.name(), 1f, 1f, 1f, 1f);
            String state = mod.id().equals(SELF) ? "self" : stateOf(mod.id());
            String label = texts.get("state." + state);
            float[] c = switch (state) {
                case "self", "on", "lua:on" -> new float[] {0.45f, 0.95f, 0.45f};
                case "off", "lua:off" -> new float[] {0.95f, 0.7f, 0.3f};
                case "on, until restart", "lua:on, next load" -> new float[] {0.6f, 0.8f, 0.6f};
                case "lua:off, next load" -> new float[] {0.85f, 0.75f, 0.5f};
                default -> new float[] {0.65f, 0.65f, 0.65f};
            };
            int w = game.textWidth(GameScreen.Font.MEDIUM, label);
            game.text(GameScreen.Font.MEDIUM, x + width - w, y, label, c[0], c[1], c[2], 1f);
        }

        int y = listBottom + 6;
        game.rect(x - 6, y - 4, width + 12, 1, 0.4f, 0.4f, 0.4f, 1f);
        Actions.InstalledMod mod = selectedMod();
        if (mod == null) {
            return;
        }
        if (mod.java() != null) {
            BiteCheckGate.Check check = checks.computeIfAbsent(mod.java().jar(), this::checkQuietly);
            if (check != null) {
                String verdict = check.verdict().name();
                float[] c = verdict.equals("ALLOW") ? new float[] {0.5f, 1f, 0.5f}
                        : verdict.equals("REFUSE") ? new float[] {1f, 0.4f, 0.4f} : new float[] {1f, 0.8f, 0.3f};
                game.text(GameScreen.Font.SMALL, x, y, texts.get(verdict) + "  (" + check.status() + ": " + check.info() + ")",
                        c[0], c[1], c[2], 1f);
            }
            y += small;
            game.text(GameScreen.Font.SMALL, x, y, texts.get("mod") + mod.id() + "   " + texts.get("file") + shorten(mod.java().jar().toString(), 110),
                    0.65f, 0.65f, 0.65f, 1f);
        } else {
            game.text(GameScreen.Font.SMALL, x, y, texts.get("luaMod"), 0.55f, 0.8f, 1f, 1f);
            y += small;
            game.text(GameScreen.Font.SMALL, x, y, texts.get("mod") + mod.id()
                    + (mod.workshopId() > 0 ? "   Workshop " + mod.workshopId() : ""), 0.65f, 0.65f, 0.65f, 1f);
        }
        y += small;
        game.text(GameScreen.Font.SMALL, x, y, texts.get("luaNote"), 0.55f, 0.55f, 0.55f, 1f);
    }

    private void drawLibrary(Library lib, int x, int top, int width, int bottom) {
        int medium = game.fontHeight(GameScreen.Font.MEDIUM);
        int small = game.fontHeight(GameScreen.Font.SMALL) + 3;
        game.rect(x - 6, top - 4, width + 12, medium + 10, 0.03f, 0.03f, 0.04f, 1f);
        game.text(GameScreen.Font.MEDIUM, x, top, texts.get("search") + lib.query + "_", 1f, 1f, 1f, 1f);
        int y = top + medium + 14;
        game.text(GameScreen.Font.SMALL, x, y, lib.status.isEmpty() ? texts.get("searchHint") : lib.status, 0.7f, 0.7f, 0.7f, 1f);
        y += small + 6;

        int line = medium + small + 10;
        int listBottom = bottom - small * 2 - 8;
        int visible = Math.max(1, (listBottom - y) / line);
        int first = Math.max(0, Math.min(lib.selected - visible / 2, lib.items.size() - visible));
        for (int i = first; i < Math.min(lib.items.size(), first + visible); i++) {
            LibraryClient.Item item = lib.items.get(i);
            int ry = y + (i - first) * line;
            if (i == lib.selected) {
                game.rect(x - 6, ry - 3, width + 12, line - 2, 0.25f, 0.45f, 0.3f, 0.6f);
            }
            game.text(GameScreen.Font.MEDIUM, x, ry, item.title(), 1f, 1f, 1f, 1f);
            String right = workshopLabel(item) + "   " + String.format(Locale.ROOT, texts.get("subscribers"), item.subscriptions());
            int w = game.textWidth(GameScreen.Font.SMALL, right);
            game.text(GameScreen.Font.SMALL, x + width - w, ry + 2, right, 0.7f, 0.85f, 0.7f, 1f);
            game.text(GameScreen.Font.SMALL, x + 12, ry + medium + 2, cut(item.description(), 140), 0.6f, 0.6f, 0.6f, 1f);
        }
        if (!lib.items.isEmpty()) {
            LibraryClient.Item item = lib.items.get(lib.selected);
            int dy = listBottom + 6;
            game.rect(x - 6, dy - 4, width + 12, 1, 0.4f, 0.4f, 0.4f, 1f);
            game.text(GameScreen.Font.SMALL, x, dy, "Workshop " + item.id() + "   " + String.join(", ", item.tags()), 0.65f, 0.65f, 0.65f, 1f);
            game.text(GameScreen.Font.SMALL, x, dy + small, texts.get(item.java() ? "javaNote" : "luaLibraryNote"), 0.55f, 0.55f, 0.55f, 1f);
        }
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
        if (download != null) {
            sb.append(" downloading=").append(download.item().id());
        }
        sb.append(" message=").append(message);
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
