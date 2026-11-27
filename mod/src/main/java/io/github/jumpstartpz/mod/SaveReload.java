package io.github.jumpstartpz.mod;

import java.util.Locale;

/**
 * Loads the running save again without the player leaving the game: what a mod brings that the
 * game reads only while a save loads (sounds, models, tiles, maps, sandbox options, clothing; the
 * items of a mod switched off) comes in, and the clicks through the main menu are Jumpstart's.
 * Single player only. The game saves and goes back to the main menu as with "Quit"; once the main
 * menu has settled and shows that save as the latest, Jumpstart continues it the way the menu's
 * own Continue does ({@code MainScreen.continueLatestSave}), which loads the save's mod list.
 *
 * <p>If the main menu shows another save as the latest, or nothing happens for five minutes,
 * nothing is continued: the player is in the main menu as after a normal quit.
 */
final class SaveReload {

    /** The running game as the reload needs it. {@link GameActions} is PZ; tests use a fake. */
    interface Game {
        /** The game state's class name (MainScreenState, IngameState, ...), or "". */
        String state();

        /** The main menu's Lua screen exists (MainScreen.instance). */
        boolean menuReady();

        /** {world, game mode} of the save the main menu would continue, or null. */
        String[] latestSave();

        /** The main menu's Continue for that save. */
        void continueSave(String world, String gameMode) throws Exception;
    }

    static final long TIMEOUT_MS = 5 * 60_000L;
    /** Frames the main menu shows before Jumpstart continues (it may reset Lua when it opens). */
    static final int SETTLE_FRAMES = 60;

    private record Pending(String world, Game game, long since) {
    }

    private static volatile Pending pending;
    private static int menuFrames;

    private SaveReload() {
    }

    /** The game is about to save and quit to the main menu: continue {@code world} from there. */
    static void request(String world, Game game, long now) {
        menuFrames = 0;
        pending = new Pending(world, game, now);
    }

    static boolean waiting() {
        return pending != null;
    }

    /** Every UI frame (main menu and game). Never throws. */
    static void onFrame(long now) {
        Pending p = pending;
        if (p == null) {
            return;
        }
        try {
            if (now - p.since() > TIMEOUT_MS) {
                pending = null;
                System.out.println("[Jumpstart] load the save again: the main menu did not come; nothing continued");
                return;
            }
            if (!p.game().state().equals("MainScreenState") || !p.game().menuReady()) {
                menuFrames = 0;
                return;
            }
            if (++menuFrames < SETTLE_FRAMES) {
                return;
            }
            pending = null;
            String[] latest = p.game().latestSave();
            if (latest == null || latest.length < 2 || !sameWorld(latest[0], p.world())) {
                System.out.println("[Jumpstart] load the save again: the main menu's latest save is not " + p.world() + "; nothing continued");
                return;
            }
            System.out.println("[Jumpstart] load the save again: continuing " + latest[0]);
            p.game().continueSave(latest[0], latest[1]);
        } catch (Throwable t) {
            pending = null;
            System.out.println("[Jumpstart] load the save again failed: " + t);
        }
    }

    /** The menu names a save by its folder, sometimes with the game mode's folder in front. */
    static boolean sameWorld(String latest, String world) {
        if (latest == null || world == null || world.isBlank()) {
            return false;
        }
        String a = latest.replace('\\', '/').toLowerCase(Locale.ROOT);
        String b = world.replace('\\', '/').toLowerCase(Locale.ROOT);
        return a.equals(b) || a.endsWith("/" + b);
    }

    /** Tests. */
    static void reset() {
        pending = null;
        menuFrames = 0;
    }
}
