package io.github.jumpstartpz.mod;

/**
 * What the secure screen needs from the game: drawing during the UI pass and the physical keyboard.
 * {@link ReflectiveGameScreen} is the game; tests use a fake.
 */
interface GameScreen {
    enum Font { SMALL, MEDIUM, LARGE }

    int width();

    int height();

    int fontHeight(Font font);

    int textWidth(Font font, String text);

    void text(Font font, int x, int y, String text, float r, float g, float b, float a);

    void rect(int x, int y, int w, int h, float r, float g, float b, float a);

    /**
     * Whether a key is physically held down, read from GLFW (key codes as in GLFW). Not from the
     * game's keyboard queue, which Lua can fill with fake key events.
     */
    boolean physicalKeyDown(int glfwKey);

    boolean inGame();

    /** Stops game time while the screen is open (single player), and restores it afterwards. */
    void pause(boolean paused);

    /** "DE", "EN", ... */
    String language();
}
