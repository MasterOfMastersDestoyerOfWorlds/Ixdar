package ixdar.scenes.trade.hud;

import java.util.ArrayList;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.sdf.SDFRoundedRect;
import ixdar.graphics.render.text.Font;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.menu.MenuButton;
import ixdar.gui.ui.text.HyperString;
import ixdar.scenes.Scene;

/**
 * The trade HUD's drawing kit in screen pixels, y up: panels, buttons, text and tags. It remembers this
 * frame's buttons and panels so a click lands on a button, is swallowed by a panel, or reaches the map.
 */
public class Hud {
    public static final float TEXT_HEIGHT = 20f;
    public static final float SMALL_TEXT_HEIGHT = 16f;
    public static final float TITLE_TEXT_HEIGHT = 26f;
    public static final float ROW_HEIGHT = 24f;
    public static final float BUTTON_HEIGHT = 30f;
    public static final float PADDING = 8f;
    public static final float CORNER_RADIUS = 6f;
    public static final float BORDER_WIDTH = 1.5f;
    public static final float CHARACTER_WIDTH_PER_HEIGHT = 0.52f;

    public Camera2D camera;
    /** The scene's palette this frame, which buttons and tags are drawn in. */
    public Palette palette = Scene.activePalette();
    /** The cursor this frame, in the HUD's pixels, y up; a button under it flashes its rim. */
    public float hoverX;
    public float hoverY;
    private final SDFRoundedRect box = new SDFRoundedRect();
    private final ArrayList<MenuButton> buttons = new ArrayList<>();
    private final ArrayList<float[]> panelRectangles = new ArrayList<>();

    /**
     * Start a frame: forget last frame's buttons and panels, take the scene's palette as it is now and
     * note where the cursor is.
     *
     * @param hudCamera    the camera whose pixel space the HUD draws in
     * @param scenePalette the scene's palette, read each frame so a swap recolours the HUD
     * @param cursorX      cursor x in pixels
     * @param cursorY      cursor y in pixels, y up
     */
    public void begin(Camera2D hudCamera, Palette scenePalette, float cursorX, float cursorY) {
        camera = hudCamera;
        palette = scenePalette;
        hoverX = cursorX;
        hoverY = cursorY;
        buttons.clear();
        panelRectangles.clear();
    }

    /**
     * Draw a panel's card and let it swallow clicks.
     *
     * @param x      left edge
     * @param y      bottom edge
     * @param w      width
     * @param h      height
     * @param fill   card colour
     * @param border border colour
     */
    public void panel(float x, float y, float w, float h, Color fill, Color border) {
        box.draw(x, y, w, h, CORNER_RADIUS, BORDER_WIDTH, fill, border, camera);
        panelRectangles.add(new float[] { x, y, w, h });
    }

    /**
     * Draw a flat rectangle, such as a bar's fill.
     *
     * @param x    left edge
     * @param y    bottom edge
     * @param w    width
     * @param h    height
     * @param fill its colour
     */
    public void rectangle(float x, float y, float w, float h, Color fill) {
        box.draw(x, y, w, h, 0, 0, fill, fill, camera);
    }

    /**
     * Draw a {@link MenuButton} labelled in whichever of text and background stands further from the fill,
     * muted when disabled, and keep it for this frame's clicks.
     *
     * @param x       left edge
     * @param y       bottom edge
     * @param w       width
     * @param h       height
     * @param label   its text
     * @param fill    the plate's fill, a colour of {@link #palette}
     * @param enabled whether a click runs the action
     * @param action  what a click does
     */
    public void button(float x, float y, float w, float h, String label, Color fill, boolean enabled,
            Runnable action) {
        Color ink = palette.text.distanceTo(fill) >= palette.background.distanceTo(fill) ? palette.text
                : palette.background;
        HyperString words = new HyperString();
        words.addWordClick(label, enabled ? ink : palette.mutedText, () -> {
        });
        MenuButton button = new MenuButton(words, enabled, action);
        button.draw(x, y, w, h, SMALL_TEXT_HEIGHT, palette, fill, hoverX, hoverY, camera);
        buttons.add(button);
    }

    /**
     * Draw a line of text centred on a pixel.
     *
     * @param text   the text
     * @param x      centre x
     * @param y      centre y
     * @param height row height
     * @param color  text colour
     */
    public void text(String text, float x, float y, float height, Color color) {
        HyperString words = new HyperString();
        words.addWord(text, color);
        Drawing.getDrawing().font.drawHyperString(words, x, y, height, camera);
    }

    /**
     * Draw a label on a small rounded card, as legs and grow targets carry.
     *
     * @param label the text
     * @param x     centre x
     * @param y     centre y
     * @param color text and border colour
     */
    public void tag(String label, float x, float y, Color color) {
        float w = textWidth(label, SMALL_TEXT_HEIGHT) + PADDING;
        box.draw(x - w * 0.5f, y - SMALL_TEXT_HEIGHT * 0.5f, w, SMALL_TEXT_HEIGHT, SMALL_TEXT_HEIGHT * 0.5f,
                BORDER_WIDTH, palette.panel, color, camera);
        text(label, x, y, SMALL_TEXT_HEIGHT, color);
    }

    /**
     * How wide a line of text draws, measured on the font, or estimated before its atlas has loaded.
     *
     * @param text   the text
     * @param height its row height
     * @return its width in pixels
     */
    public float textWidth(String text, float height) {
        Font font = Drawing.getDrawing().font;
        if (font == null || font.glyphs == null || font.fontHeight <= 0) {
            return text.length() * height * CHARACTER_WIDTH_PER_HEIGHT;
        }
        return font.getWidth(text) * height / font.fontHeight;
    }

    /**
     * Route a click: the topmost button under it runs, a panel under it swallows it.
     *
     * @param x click x in pixels
     * @param y click y in pixels, y up
     * @return true when the HUD took the click
     */
    public boolean click(float x, float y) {
        for (int k = buttons.size() - 1; k >= 0; k--) {
            if (buttons.get(k).click(x, y)) {
                return true;
            }
        }
        for (float[] r : panelRectangles) {
            if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) {
                return true;
            }
        }
        return false;
    }
}
