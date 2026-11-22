package io.github.jumpstartpz.mod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.bitecheckpz.core.Verifier;
import io.github.jumpstartpz.core.LiveInstaller;
import io.github.jumpstartpz.core.gate.BiteCheckGate;

/**
 * Jumpstart's secure screen: the only place where Java mods are loaded, updated or switched, and
 * where the player answers BiteCheck's questions.
 *
 * <p>Lua mods are not checked by anyone and draw everything else the player sees, so none of this
 * may depend on Lua. The screen is drawn by Java at the very end of the game's UI pass (after
 * every Lua window and OnPostUIDraw), opens only on the physical keys Ctrl+Shift+J, and reads
 * only the physical keyboard through GLFW, never the game's key queue that Lua can fill. While it
 * is open, the game and Lua get no keyboard and no UI input. A question can only come up after the
 * player opened the screen and pressed Enter on a mod there, and the answer key works only after a
 * few seconds, so a reflex cannot confirm it.
 *
 * <p>The game calls {@link #onRender()} and {@link #blocksInput()} through {@link GameHooks};
 * everything runs on the game's main thread.
 */
public final class SecureScreen {
    // GLFW key codes
    static final int KEY_ESCAPE = 256;
    static final int KEY_ENTER = 257;
    static final int KEY_KP_ENTER = 335;
    static final int KEY_UP = 265;
    static final int KEY_DOWN = 264;
    static final int KEY_PAGE_UP = 266;
    static final int KEY_PAGE_DOWN = 267;
    static final int KEY_A = 65;
    static final int KEY_J = 74;
    static final int KEY_N = 78;
    static final int KEY_R = 82;
    static final int KEY_Y = 89;
    static final int KEY_LEFT_SHIFT = 340;
    static final int KEY_LEFT_CONTROL = 341;
    static final int KEY_RIGHT_SHIFT = 344;
    static final int KEY_RIGHT_CONTROL = 345;
    private static final int[] WATCHED = {KEY_ESCAPE, KEY_ENTER, KEY_KP_ENTER, KEY_UP, KEY_DOWN, KEY_PAGE_UP,
        KEY_PAGE_DOWN, KEY_A, KEY_J, KEY_N, KEY_R, KEY_Y, KEY_LEFT_SHIFT, KEY_LEFT_CONTROL, KEY_RIGHT_SHIFT,
        KEY_RIGHT_CONTROL};
    /** A pretend key for tests: the Ctrl+Shift+J combination. */
    static final int OPEN = -1;

    /** How long the answer key does nothing after a question comes up. */
    static final long FIRST_DELAY_MS = 3000;
    static final long SECOND_DELAY_MS = 5000;

    enum Mode { CLOSED, LIST, CONFIRM_ONCE, CONFIRM_TWICE }

    private static volatile SecureScreen current;
    private static volatile boolean reportedError;

    private final GameScreen game;
    private final Actions actions;
    private final boolean testInput;
    private final Set<Integer> held = new HashSet<>();
    private final Deque<Integer> injected = new ArrayDeque<>();
    private final Map<Path, BiteCheckGate.Check> checks = new HashMap<>();
    private Texts texts;

    private Mode mode = Mode.CLOSED;
    private boolean waitForRelease;
    private List<ModEntry> mods = List.of();
    private final Map<String, String> states = new HashMap<>();
    private int selected;
    private String message = "";
    private boolean messageGood;
    private Pending pending;
    private long questionSince;

    private record Pending(ModEntry mod, BiteCheckGate.Check check, boolean update) {
    }

    /**
     * @param testInput whether {@link #inject} works (JVM option -Djumpstart.testInput=true, only
     *                  for automated tests; read once at start, so Lua cannot turn it on)
     */
    SecureScreen(GameScreen game, Actions actions, boolean testInput) {
        this.game = game;
        this.actions = actions;
        this.testInput = testInput;
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
        Set<Integer> pressed = poll();
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
            case LIST -> onList(pressed, now);
            case CONFIRM_ONCE, CONFIRM_TWICE -> onQuestion(pressed, now);
        }
        if (mode != Mode.CLOSED) {
            draw(now);
        }
    }

    /** Keys pressed since the last frame (physical, plus injected ones in tests). */
    private Set<Integer> poll() {
        Set<Integer> pressed = new HashSet<>();
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
        message = "";
        checks.clear();
        reload();
        selected = 0;
        game.pause(true);
    }

    private void close() {
        mode = Mode.CLOSED;
        pending = null;
        waitForRelease = true; // keep the game's input off until Esc is released
        game.pause(false);
    }

    private void reload() {
        mods = actions.javaMods();
        refreshStates();
        if (selected >= mods.size()) {
            selected = Math.max(0, mods.size() - 1);
        }
    }

    private void refreshStates() {
        states.clear();
        for (ModEntry mod : mods) {
            states.put(mod.id(), actions.stateOf(mod.id()));
        }
    }

    private ModEntry selectedMod() {
        return mods.isEmpty() ? null : mods.get(selected);
    }

    private String stateOf(ModEntry mod) {
        return states.getOrDefault(mod.id(), "not loaded");
    }

    private void onList(Set<Integer> pressed, long now) {
        if (pressed.contains(KEY_ESCAPE)) {
            close();
            return;
        }
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
        ModEntry mod = selectedMod();
        if (mod == null) {
            return;
        }
        if (pressed.contains(KEY_A)) {
            String state = stateOf(mod);
            if (state.equals("on")) {
                String r = actions.off(mod.id());
                say(r, r.startsWith("OK"));
            } else if (state.equals("off")) {
                String r = actions.on(mod.id());
                say(r, r.startsWith("OK"));
            }
            refreshStates();
        } else if (pressed.contains(KEY_ENTER) || pressed.contains(KEY_KP_ENTER)) {
            start(mod, now);
        }
    }

    /** Enter on a mod: load it, or reload its jar when it is loaded already. */
    private void start(ModEntry mod, long now) {
        BiteCheckGate.Check check;
        try {
            check = actions.check(mod.jar());
        } catch (IOException e) {
            say(texts.get("cannotCheck") + e.getMessage(), false);
            return;
        }
        checks.put(mod.jar(), check);
        pending = new Pending(mod, check, !stateOf(mod).equals("not loaded"));
        switch (check.verdict()) {
            case REFUSE -> {
                pending = null;
                say(texts.get("refused"), false);
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
    }

    private void say(String text, boolean good) {
        message = text == null ? "" : text;
        messageGood = good;
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
        y += 12;

        int bottom = py + ph - 18;
        int small = game.fontHeight(GameScreen.Font.SMALL);
        if (mode == Mode.LIST) {
            game.text(GameScreen.Font.SMALL, x, bottom - small, texts.get("help"), 0.7f, 0.7f, 0.7f, 1f);
            drawList(x, y, width, bottom - small - 12);
        } else {
            drawQuestion(x, y, width, bottom, now);
        }
    }

    private void drawList(int x, int top, int width, int bottom) {
        int line = game.fontHeight(GameScreen.Font.MEDIUM) + 8;
        int small = game.fontHeight(GameScreen.Font.SMALL) + 3;
        int detailsHeight = small * 5 + 10;
        int listBottom = bottom - detailsHeight;
        if (mods.isEmpty()) {
            game.text(GameScreen.Font.MEDIUM, x, top, texts.get("noMods"), 0.8f, 0.8f, 0.8f, 1f);
        }
        int visible = Math.max(1, (listBottom - top) / line);
        int first = Math.max(0, Math.min(selected - visible / 2, mods.size() - visible));
        for (int i = first; i < Math.min(mods.size(), first + visible); i++) {
            ModEntry mod = mods.get(i);
            int y = top + (i - first) * line;
            if (i == selected) {
                game.rect(x - 6, y - 3, width + 12, line - 2, 0.25f, 0.45f, 0.3f, 0.6f);
            }
            game.text(GameScreen.Font.MEDIUM, x, y, mod.name(), 1f, 1f, 1f, 1f);
            String state = stateOf(mod);
            String label = texts.get("state." + state);
            float[] c = switch (state) {
                case "on" -> new float[] {0.45f, 0.95f, 0.45f};
                case "off" -> new float[] {0.95f, 0.7f, 0.3f};
                case "on, until restart" -> new float[] {0.6f, 0.8f, 0.6f};
                default -> new float[] {0.65f, 0.65f, 0.65f};
            };
            int w = game.textWidth(GameScreen.Font.MEDIUM, label);
            game.text(GameScreen.Font.MEDIUM, x + width - w, y, label, c[0], c[1], c[2], 1f);
        }

        int y = listBottom + 6;
        game.rect(x - 6, y - 4, width + 12, 1, 0.4f, 0.4f, 0.4f, 1f);
        ModEntry mod = selectedMod();
        if (mod != null) {
            BiteCheckGate.Check check = checks.computeIfAbsent(mod.jar(), this::checkQuietly);
            if (check != null) {
                String verdict = check.verdict().name();
                float[] c = verdict.equals("ALLOW") ? new float[] {0.5f, 1f, 0.5f}
                        : verdict.equals("REFUSE") ? new float[] {1f, 0.4f, 0.4f} : new float[] {1f, 0.8f, 0.3f};
                game.text(GameScreen.Font.SMALL, x, y, texts.get(verdict) + "  (" + check.status() + ": " + check.info() + ")",
                        c[0], c[1], c[2], 1f);
            }
            y += small;
            game.text(GameScreen.Font.SMALL, x, y, texts.get("mod") + mod.id() + "   " + texts.get("file") + shorten(mod.jar().toString(), 110),
                    0.65f, 0.65f, 0.65f, 1f);
            y += small;
            game.text(GameScreen.Font.SMALL, x, y, texts.get("luaNote"), 0.55f, 0.55f, 0.55f, 1f);
            y += small;
        }
        if (!message.isEmpty()) {
            wrapped(GameScreen.Font.SMALL, x, y + 4, width, message,
                    messageGood ? 0.5f : 1f, messageGood ? 1f : 0.65f, messageGood ? 0.5f : 0.4f);
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

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : "..." + text.substring(text.length() - max + 3);
    }

    // --- for tests -------------------------------------------------------------------------------

    /** One line about the current state, for automated tests. */
    String describe() {
        ModEntry mod = selectedMod();
        StringBuilder sb = new StringBuilder(mode.name());
        sb.append(" selected=").append(mod == null ? "-" : mod.id());
        if (mod != null) {
            sb.append(" state=").append(stateOf(mod));
        }
        if (pending != null) {
            sb.append(" asking=").append(pending.check().verdict());
        }
        sb.append(" message=").append(message);
        return sb.toString();
    }

    Mode mode() {
        return mode;
    }

    List<String> modIds() {
        List<String> ids = new ArrayList<>();
        for (ModEntry mod : mods) {
            ids.add(mod.id());
        }
        return ids;
    }
}
