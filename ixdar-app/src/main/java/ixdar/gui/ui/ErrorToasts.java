package ixdar.gui.ui;

import java.util.ArrayList;
import java.util.List;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorLerp;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.Platforms;
import ixdar.scenes.Scene;

/**
 * Why a click or key was refused, shown as red text at the top left that fades out over
 * {@link #FADE_SECONDS}, newest on top; the same refusal again restarts its fade instead of
 * stacking. Every refusal is also printed to the scene terminal and the process log.
 */
public final class ErrorToasts {

    public static final float FADE_SECONDS = 30f;

    public static final String LOG_PREFIX = "[refused] ";

    private static final byte[] ALPHA_ONLY_LERP_MASK = { 0, 0, 0, 1 };

    /** Scene whose terminal each refusal is printed to. */
    public final Scene scene;

    /** Shown refusals, newest first. */
    public final List<String> messages = new ArrayList<>();

    /** Name of the tool that raised each shown refusal, parallel to {@link #messages}. */
    public final List<String> sources = new ArrayList<>();

    /**
     * Each shown refusal's colour, the theme's error colour fading to transparent once from when
     * it was last raised, parallel to {@link #messages}.
     */
    public final List<ColorLerp> colors = new ArrayList<>();

    /**
     * Bind the toasts to the scene that prints them.
     *
     * @param owner scene whose terminal, once it has one, each refusal is printed to
     */
    public ErrorToasts(Scene owner) {
        this.scene = owner;
    }

    /**
     * Show a refusal on top and print it with its tool to the terminal and the log; a refusal
     * already shown moves to the top with a fresh fade. An empty message shows nothing.
     *
     * @param source  name of the tool that refused
     * @param message why, and what to do instead
     */
    public void show(String source, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        for (int toast = messages.size() - 1; toast >= 0; toast--) {
            if (messages.get(toast).equals(message) && sources.get(toast).equals(source)) {
                messages.remove(toast);
                sources.remove(toast);
                colors.remove(toast);
            }
        }
        messages.add(0, message);
        sources.add(0, source);
        Color refusal = Scene.activePalette().error;
        colors.add(0, ColorLerp.lerpOnce(refusal, Color.TRANSPARENT,
                ALPHA_ONLY_LERP_MASK, FADE_SECONDS));
        String line = source + ": " + message;
        Platforms.get().log(LOG_PREFIX + line);
        if (scene.sceneTerminal != null) {
            scene.sceneTerminal.history.addLine(line, refusal);
        }
    }

    /** Drop every refusal whose colour has faded out; runs once a frame. */
    public void perFrame() {
        for (int toast = messages.size() - 1; toast >= 0; toast--) {
            if (colors.get(toast).finished()) {
                messages.remove(toast);
                sources.remove(toast);
                colors.remove(toast);
            }
        }
    }

    /**
     * Draw the shown refusals one per row from the top left, each in its fading colour.
     *
     * @param screen the 2D camera the overlay text is drawn through
     */
    public void draw(Camera2D screen) {
        draw(screen, 0f);
    }

    /**
     * Draw the shown refusals one per row from the top left, starting on the first row wholly below a
     * strip the scene keeps at the top, such as a bar.
     *
     * @param screen   the 2D camera the overlay text is drawn through
     * @param topInset height in pixels of the strip at the top to stay below
     */
    public void draw(Camera2D screen, float topInset) {
        if (messages.isEmpty() || screen == null) {
            return;
        }
        HyperString rows = new HyperString();
        for (int toast = 0; toast < messages.size(); toast++) {
            rows.addLine(messages.get(toast), colors.get(toast));
        }
        int firstRow = (int) Math.ceil(topInset / Drawing.FONT_HEIGHT_PIXELS);
        Drawing.getDrawing().font.drawHyperStringRows(rows, firstRow, 0f, Drawing.FONT_HEIGHT_PIXELS,
                screen);
    }
}
