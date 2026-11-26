package io.github.jumpstartpz.mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@link GameScreen} on top of Project Zomboid (Build 42) and LWJGL, reached by reflection so the
 * mod builds without the game's jar. Must only be used on the game's main thread, inside the UI
 * render pass.
 */
final class ReflectiveGameScreen implements GameScreen {
    private final Object textManager;
    private final Method drawString;
    private final Method measureStringX;
    private final Method getFontHeight;
    private final Map<Font, Object> fonts = new EnumMap<>(Font.class);
    private final Object spriteRenderer;
    private final Method renderi;
    private final Method getCore;
    private final Method getScreenWidth;
    private final Method getScreenHeight;
    private final Method glfwGetKey;
    private final Method glfwGetKeyName;
    private final Method getWindow;
    private final Method isIngameState;
    private final Field speedControlsInstance;
    private final Method getLanguage;
    private Integer speedBeforePause;

    ReflectiveGameScreen() throws ReflectiveOperationException {
        Class<?> uiFont = Class.forName("zombie.ui.UIFont");
        fonts.put(Font.SMALL, uiFont.getField("Small").get(null));
        fonts.put(Font.MEDIUM, uiFont.getField("Medium").get(null));
        fonts.put(Font.LARGE, uiFont.getField("Large").get(null));

        Class<?> tm = Class.forName("zombie.ui.TextManager");
        textManager = tm.getField("instance").get(null);
        drawString = tm.getMethod("DrawString", uiFont, double.class, double.class, String.class,
                double.class, double.class, double.class, double.class);
        measureStringX = tm.getMethod("MeasureStringX", uiFont, String.class);
        getFontHeight = tm.getMethod("getFontHeight", uiFont);

        Class<?> sr = Class.forName("zombie.core.SpriteRenderer");
        spriteRenderer = sr.getField("instance").get(null);
        renderi = sr.getMethod("renderi", Class.forName("zombie.core.textures.Texture"), int.class, int.class,
                int.class, int.class, float.class, float.class, float.class, float.class, Consumer.class);

        Class<?> core = Class.forName("zombie.core.Core");
        getCore = core.getMethod("getInstance");
        getScreenWidth = core.getMethod("getScreenWidth");
        getScreenHeight = core.getMethod("getScreenHeight");

        glfwGetKey = Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwGetKey", long.class, int.class);
        glfwGetKeyName = Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwGetKeyName", int.class, int.class);
        getWindow = Class.forName("org.lwjglx.opengl.Display").getMethod("getWindow");

        isIngameState = Class.forName("zombie.GameWindow").getMethod("isIngameState");
        speedControlsInstance = Class.forName("zombie.ui.SpeedControls").getField("instance");
        getLanguage = Class.forName("zombie.core.Translator").getMethod("getLanguage");
    }

    @Override
    public int width() {
        return (Integer) call(getScreenWidth, call(getCore, null));
    }

    @Override
    public int height() {
        return (Integer) call(getScreenHeight, call(getCore, null));
    }

    @Override
    public int fontHeight(Font font) {
        return (Integer) call(getFontHeight, textManager, fonts.get(font));
    }

    @Override
    public int textWidth(Font font, String text) {
        return (Integer) call(measureStringX, textManager, fonts.get(font), text);
    }

    @Override
    public void text(Font font, int x, int y, String text, float r, float g, float b, float a) {
        call(drawString, textManager, fonts.get(font), (double) x, (double) y, text,
                (double) r, (double) g, (double) b, (double) a);
    }

    @Override
    public void rect(int x, int y, int w, int h, float r, float g, float b, float a) {
        call(renderi, spriteRenderer, null, x, y, w, h, r, g, b, a, null);
    }

    private Method glfwGetCursorPos;
    private Method glfwGetWindowSize;
    private Method glfwGetMouseButton;
    private Method mouseWheel;

    private void mouseMethods() throws ReflectiveOperationException {
        if (glfwGetCursorPos == null) {
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
            glfwGetCursorPos = glfw.getMethod("glfwGetCursorPos", long.class, double[].class, double[].class);
            glfwGetWindowSize = glfw.getMethod("glfwGetWindowSize", long.class, int[].class, int[].class);
            glfwGetMouseButton = glfw.getMethod("glfwGetMouseButton", long.class, int.class);
            Class<?> mouse = Class.forName("zombie.input.Mouse");
            mouseWheel = mouse.getMethod("getWheelState");
        }
    }

    @Override
    public int[] mousePosition() {
        try {
            mouseMethods();
            long window = (Long) call(getWindow, null);
            if (window == 0) {
                return null;
            }
            double[] cx = new double[1];
            double[] cy = new double[1];
            glfwGetCursorPos.invoke(null, window, cx, cy);
            int[] ww = new int[1];
            int[] wh = new int[1];
            glfwGetWindowSize.invoke(null, window, ww, wh);
            // GLFW gives window coordinates; the game draws in pixels (they differ on high-DPI screens)
            double sx = ww[0] > 0 ? (double) width() / ww[0] : 1;
            double sy = wh[0] > 0 ? (double) height() / wh[0] : 1;
            return new int[] {(int) Math.round(cx[0] * sx), (int) Math.round(cy[0] * sy)};
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean physicalMouseDown(int button) {
        try {
            mouseMethods();
            long window = (Long) call(getWindow, null);
            return window != 0 && (Integer) glfwGetMouseButton.invoke(null, window, button) == 1; // GLFW_PRESS
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public int wheel() {
        try {
            mouseMethods();
            return Integer.signum((Integer) mouseWheel.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }


    /** Loaded pictures by file; a file that failed to load is kept as null, so it is tried once. */
    private final Map<String, Object> textures = new java.util.HashMap<>();
    private Method getSharedTexture;
    private Method textureWidth;
    private Method textureHeight;

    @Override
    public boolean image(String file, int x, int y, int w, int h) {
        try {
            if (getSharedTexture == null) {
                Class<?> texture = Class.forName("zombie.core.textures.Texture");
                getSharedTexture = texture.getMethod("getSharedTexture", String.class);
                textureWidth = texture.getMethod("getWidth");
                textureHeight = texture.getMethod("getHeight");
            }
            if (!textures.containsKey(file)) {
                // the same call the game's mod list uses for mod icons and posters (absolute paths)
                textures.put(file, getSharedTexture.invoke(null, file));
            }
            Object tex = textures.get(file);
            if (tex == null) {
                return false;
            }
            int tw = (Integer) textureWidth.invoke(tex);
            int th = (Integer) textureHeight.invoke(tex);
            if (tw <= 0 || th <= 0) {
                return false; // still loading
            }
            double f = Math.min((double) w / tw, (double) h / th);
            int dw = (int) Math.round(tw * f);
            int dh = (int) Math.round(th * f);
            call(renderi, spriteRenderer, tex, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, 1f, 1f, 1f, 1f, null);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            textures.put(file, null);
            return false;
        }
    }

    @Override
    public boolean physicalKeyDown(int glfwKey) {
        long window = (Long) call(getWindow, null);
        return window != 0 && (Integer) call(glfwGetKey, null, window, glfwKey) == 1; // GLFW_PRESS
    }

    @Override
    public String keyName(int glfwKey) {
        Object name = call(glfwGetKeyName, null, glfwKey, 0);
        if (name instanceof String s && s.length() == 1) {
            return s;
        }
        // keys GLFW has no name for: the US layout's character
        if ((glfwKey >= 'A' && glfwKey <= 'Z') || (glfwKey >= '0' && glfwKey <= '9')) {
            return String.valueOf((char) Character.toLowerCase(glfwKey));
        }
        return null;
    }

    @Override
    public boolean inGame() {
        return Boolean.TRUE.equals(call(isIngameState, null));
    }

    @Override
    public void pause(boolean paused) {
        try {
            Object speed = speedControlsInstance.get(null);
            if (speed == null || !inGame()) {
                return;
            }
            Method get = speed.getClass().getMethod("getCurrentGameSpeed");
            Method set = speed.getClass().getMethod("SetCurrentGameSpeed", int.class);
            if (paused && speedBeforePause == null) {
                speedBeforePause = (Integer) get.invoke(speed);
                set.invoke(speed, 0);
            } else if (!paused && speedBeforePause != null) {
                set.invoke(speed, speedBeforePause);
                speedBeforePause = null;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            speedBeforePause = null; // not fatal: the screen still blocks the keyboard
        }
    }

    @Override
    public String language() {
        Object language = call(getLanguage, null);
        return language == null ? "EN" : language.toString();
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(method.getName() + ": " + e.getCause(), e);
        }
    }
}
