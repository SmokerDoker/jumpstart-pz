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
     * Draws a picture file (absolute path) fitted into the box, keeping its proportions. False if it
     * cannot be loaded (then nothing is drawn).
     */
    boolean image(String file, int x, int y, int w, int h);

    /**
     * Whether a key is physically held down, read from GLFW (key codes as in GLFW). Not from the
     * game's keyboard queue, which Lua can fill with fake key events.
     */
    boolean physicalKeyDown(int glfwKey);

    /**
     * The character a letter, digit or punctuation key types on the player's keyboard layout (for
     * the search field), or null. Key codes as in GLFW, which names keys after the US layout.
     */
    String keyName(int glfwKey);

    /**
     * Where the physical mouse is, in screen pixels ({x, y}), read from GLFW; null if unknown. Lua
     * cannot move it while the screen is open (Mouse.setXY is blocked then).
     */
    int[] mousePosition();

    /** Whether a mouse button (0 left, 1 right) is physically held down, from GLFW. */
    boolean physicalMouseDown(int button);

    /** The mouse wheel this frame: positive up, negative down, 0 still. */
    int wheel();

    boolean inGame();

    /** Stops game time while the screen is open (single player), and restores it afterwards. */
    void pause(boolean paused);

    /** "DE", "EN", ... */
    String language();
}
