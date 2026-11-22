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

    @Override
    public boolean physicalKeyDown(int glfwKey) {
        long window = (Long) call(getWindow, null);
        return window != 0 && (Integer) call(glfwGetKey, null, window, glfwKey) == 1; // GLFW_PRESS
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
