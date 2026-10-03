package ixdar.gui.ui.menu;

import java.util.function.BooleanSupplier;

import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.actions.Action;
import ixdar.platform.input.MouseTrap;

/**
 * One independently scrolling box of a menu: a framebuffer rectangle that clips its rows and, while
 * its menu is shown, takes the wheel from the orbit camera to scroll them. The rows are one wrapping
 * {@link HyperString}, a line per row, which wraps, lays out, hit-tests and counts them.
 */
public final class MenuScrollBox implements MouseTrap.ScrollHandler {

    public static final float PIXELS_PER_SCROLL_UNIT = 90f;

    /** The box's rectangle in framebuffer pixels, y up; the owning menu lays it out. */
    public final Bounds bounds;

    /**
     * Pixels the rows are scrolled on by, between zero and {@link #maximumScrollOffsetY()}; any
     * value, not only whole rows.
     */
    public float scrollOffsetY;

    /** This frame's rows, one line each; {@code null} before the first {@link #clearRows()}. */
    public HyperString text;

    /** Box rows the text took at the last {@link #layout}, wrapped continuation rows included. */
    public int rowsUsed;

    private final BooleanSupplier shown;

    private int rowToCentre = -1;

    /**
     * An empty box that scrolls only while {@code shown} holds.
     *
     * @param id view id of the box's bounds
     * @param shown whether the owning menu is on screen
     */
    public MenuScrollBox(String id, BooleanSupplier shown) {
        this.bounds = new Bounds(0, 0, 0, 0, id);
        this.shown = shown;
    }

    /**
     * Start this frame's rows on a fresh wrapping text.
     */
    public void clearRows() {
        text = new HyperString();
        text.wrap();
    }

    /**
     * Append one row as a line of {@link #text}, every word of it clicking {@code click}.
     *
     * @param row text of the row
     * @param color colour of the row
     * @param click action run by a click on any word of the row, or {@code null}
     * @return index of the row, which is its line number in {@link #text}
     */
    public int addRow(String row, Color color, Action click) {
        if (!text.words.isEmpty()) {
            text.newLine();
        }
        text.addWordClick(row, color, click);
        return text.lines - 1;
    }

    /**
     * Set the camera's view to the box, let the text wrap to its width and count the rows used,
     * then apply a pending {@link #centreRow} and clamp the scroll to those rows.
     *
     * @param camera 2D camera whose view is set to the box and left there
     */
    public void layout(Camera2D camera) {
        camera.updateView((int) bounds.offsetX, (int) bounds.offsetY, (int) bounds.viewWidth,
                (int) bounds.viewHeight);
        float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
        rowsUsed = text.words.isEmpty() ? 0 : text.setLineOffsetFromTopRow(camera, 0, 0, rowHeight);
        if (rowToCentre >= 0 && rowToCentre < text.lines) {
            int firstRow = 0;
            for (int line = 0; line < rowToCentre; line++) {
                firstRow += text.setLineOffsetFromTopRow(camera, firstRow, 0, rowHeight, line);
            }
            int ownRows = text.setLineOffsetFromTopRow(camera, firstRow, 0, rowHeight, rowToCentre);
            scrollOffsetY = (firstRow + ownRows / 2f) * rowHeight - bounds.viewHeight / 2;
        }
        rowToCentre = -1;
        scrollOffsetY = Math.max(0, Math.min(scrollOffsetY, maximumScrollOffsetY()));
    }

    /**
     * Draw the rows top-down at {@link #drawnScrollOffsetY()} with the GL viewport on the box, so a
     * part-scrolled row is cut at the box edge, as its hit area is.
     *
     * @param camera 2D camera whose view is set to the box and left there
     */
    public void draw(Camera2D camera) {
        layout(camera);
        Drawing.getDrawing().font.drawHyperStringRows(text, 0, drawnScrollOffsetY(),
                Drawing.FONT_HEIGHT_PIXELS, camera);
    }

    /**
     * The scroll the rows are drawn, hit-tested and reported at: {@link #scrollOffsetY} itself, with
     * no easing, so a scroll shows on the next frame.
     *
     * @return the offset in pixels, between zero and {@link #maximumScrollOffsetY()}
     */
    public float drawnScrollOffsetY() {
        return scrollOffsetY;
    }

    /**
     * Largest scroll that still leaves the last row at the bottom of the box.
     *
     * @return the limit in pixels, zero when every row fits
     */
    public float maximumScrollOffsetY() {
        return Math.max(0, rowsUsed * Drawing.FONT_HEIGHT_PIXELS - bounds.viewHeight);
    }

    /**
     * At the next {@link #layout}, scroll so line {@code row}'s wrapped rows sit in the middle of
     * the box, as far as the scroll limits allow.
     *
     * @param row line of {@link #text} to centre
     */
    public void centreRow(int row) {
        rowToCentre = row;
    }

    /**
     * Scroll by {@link #PIXELS_PER_SCROLL_UNIT} times the delta at once, clamped to the rows, so the
     * list follows a trackpad finger and can stop at any pixel: a delta below zero (wheel toward
     * the user) moves on to later rows.
     *
     * @param delta raw scroll delta, 1.0 per wheel notch
     */
    @Override
    public void onScrollDelta(double delta) {
        if (!shown.getAsBoolean()) {
            return;
        }
        scrollOffsetY = (float) Math.max(0, Math.min(maximumScrollOffsetY(),
                scrollOffsetY - delta * PIXELS_PER_SCROLL_UNIT));
    }

    /**
     * Unused: a box claims the wheel and scrolls through {@link #onScrollDelta}.
     *
     * @param scrollUp unused
     * @param deltaSeconds unused
     */
    @Override
    public void onScroll(boolean scrollUp, double deltaSeconds) {
    }

    /**
     * A shown box takes the wheel, so scrolling over it never zooms the camera.
     *
     * @return whether the owning menu is shown
     */
    @Override
    public boolean claimsWheel() {
        return shown.getAsBoolean();
    }
}
