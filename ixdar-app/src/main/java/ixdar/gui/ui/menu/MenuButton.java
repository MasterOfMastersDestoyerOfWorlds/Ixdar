package ixdar.gui.ui.menu;

import ixdar.audio.AudioAssets;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.sdf.SDFUnion;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.scenes.Scene;

/**
 * The one button, the main menu's and every other: a label, an enabled flag and an action. It draws,
 * hovers and answers clicks at the rectangle it was last drawn at.
 */
public class MenuButton {
    public static final float ALPHA = 0.95f;
    public static final float INNER_OFFSET_Y = 0.02f;
    public static final int PIN_STRIPE_COUNT = 5;
    public static final float BUTTON_WIDTH_PER_PLATE_WIDTH = 0.91f;
    public static final float PLATE_HEIGHT_PER_BUTTON_HEIGHT = 2f;
    public static final float LABEL_Y_OFFSET_FACTOR = 0.075f;

    private static SDFUnion plate;
    private static GL plateGl;

    /** The label, styled by the caller: its words' colours, clicks and hover are its own. */
    public final HyperString label;
    public final boolean enabled;
    private final Runnable action;
    private float left;
    private float bottom;
    private float width;
    private float height;

    /**
     * A button with its label, whether a click does anything, and what it does.
     *
     * @param label   the text drawn on it
     * @param enabled whether a click runs {@code action}
     * @param action  what a click does
     */
    public MenuButton(HyperString label, boolean enabled, Runnable action) {
        this.label = label;
        this.enabled = enabled;
        this.action = action;
    }

    /**
     * The plate every button draws on, loaded on first use and again when the GL platform changes, its
     * construction colours from the active palette; draws pass their own.
     *
     * @return the shared plate
     */
    private static SDFUnion plate() {
        if (plate == null || plateGl != Platforms.gl()) {
            Palette palette = Scene.activePalette();
            plate = new SDFUnion("menu_inner.png", palette.panel, ALPHA, 0, -INNER_OFFSET_Y, "menu_outer.png",
                    palette.panelBorder, ALPHA, PIN_STRIPE_COUNT, 2);
            plateGl = Platforms.gl();
        }
        return plate;
    }

    /**
     * Whether the plate's textures have arrived, so buttons can be drawn and measured.
     *
     * @return true once loaded
     */
    public static boolean loaded() {
        return plate().outerTexture != null && plate().innerTexture != null;
    }

    /**
     * A button's width when its plate is drawn at its texture's size times a scale.
     *
     * @param scale screen pixels per texture pixel
     * @return the clickable width in pixels
     */
    public static float naturalWidth(float scale) {
        return plate().outerTexture.width * scale * BUTTON_WIDTH_PER_PLATE_WIDTH;
    }

    /**
     * A button's height when its plate is drawn at its texture's size times a scale.
     *
     * @param scale screen pixels per texture pixel
     * @return the clickable height in pixels
     */
    public static float naturalHeight(float scale) {
        return plate().outerTexture.height * scale / PLATE_HEIGHT_PER_BUTTON_HEIGHT;
    }

    /**
     * Draw the plate over a rectangle, rim lit while hovered and {@code disabled} when disabled, then the
     * label centred on it; remember the rectangle for clicks.
     *
     * @param x           left edge
     * @param y           bottom edge
     * @param w           width
     * @param h           height
     * @param labelHeight the label's row height in pixels
     * @param palette     the palette the plate is drawn in
     * @param fill        the plate's fill, usually {@code palette.panel}
     * @param cursorX     cursor x in the same pixels
     * @param cursorY     cursor y, y up
     * @param camera      the camera it draws through
     */
    public void draw(float x, float y, float w, float h, float labelHeight, Palette palette, Color fill,
            float cursorX, float cursorY, Camera2D camera) {
        left = x;
        bottom = y;
        width = w;
        height = h;
        if (!loaded()) {
            return;
        }
        Color rim = !enabled ? palette.disabled.withAlpha(ALPHA)
                : contains(cursorX, cursorY) ? palette.hovered(palette.panelBorder)
                        : palette.panelBorder.withAlpha(ALPHA);
        float plateWidth = w / BUTTON_WIDTH_PER_PLATE_WIDTH;
        float plateHeight = h * PLATE_HEIGHT_PER_BUTTON_HEIGHT;
        plate().draw(x + (w - plateWidth) * 0.5f, y + (h - plateHeight) * 0.5f, plateWidth, plateHeight,
                fill.withAlpha(ALPHA), rim, camera);
        Drawing.getDrawing().font.drawHyperString(label, x + w * 0.5f, y + h * (0.5f + LABEL_Y_OFFSET_FACTOR),
                labelHeight, camera);
    }

    /**
     * Whether a point lies on the button as last drawn.
     *
     * @param x point x
     * @param y point y, y up
     * @return true inside the rectangle, edges included
     */
    public boolean contains(float x, float y) {
        return x >= left && x <= left + width && y >= bottom && y <= bottom + height;
    }

    /**
     * Answer a click: on an enabled button, play the menu click and run its action. A click on a disabled
     * button still lands on it and does nothing.
     *
     * @param x click x
     * @param y click y, y up
     * @return true when the click is on the button, enabled or not
     */
    public boolean click(float x, float y) {
        if (!contains(x, y)) {
            return false;
        }
        if (enabled) {
            Scene.audioPlaySfx(AudioAssets.MENU_CLICK_SFX, 1f);
            action.run();
        }
        return true;
    }
}
