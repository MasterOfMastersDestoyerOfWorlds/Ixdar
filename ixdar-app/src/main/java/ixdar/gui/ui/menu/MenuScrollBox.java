package ixdar.gui.ui.menu;

import java.util.function.BooleanSupplier;

import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.text.HyperString;
import ixdar.gui.ui.actions.Action;
import ixdar.platform.input.PointerRegion;

/**
 * One independently scrolling box of rows, the one scroll every menu and text pane uses: one
 * wrapping {@link HyperString}, clipped to a framebuffer rectangle that, while its owner is shown,
 * takes the wheel and the presses over it.
 */
public final class MenuScrollBox implements PointerRegion {

    public static final float PIXELS_PER_SCROLL_UNIT = 90f;

    /** The box's rectangle in framebuffer pixels, y up; the owner lays it out. */
    public final Bounds bounds;

    /**
     * Run on every click in the box before the clicked word's action, such as focusing the pane
     * the box is; {@code null} for none.
     */
    public final Runnable clicked;

    /** This frame's rows, one line each; {@code null} before the first {@link #clearRows()}. */
    public HyperString text;

    /** Box rows the text took at the last {@link #layout}, wrapped continuation rows included. */
    public int rowsUsed;

    /**
     * The bar at the box's right edge, which holds the scroll: any pixel, not only whole rows. It
     * is drawn and clickable only while the rows overflow.
     */
    public final MenuScrollBar scrollBar;

    /**
     * Whether the next {@link #layout} scrolls so the last row sits at the bottom of the box, or
     * leaves the scroll at the top when every row fits; layout clears it.
     */
    public boolean scrollToEndPending;

    private final BooleanSupplier shown;

    private int rowToCentre = -1;

    /**
     * An empty box that scrolls only while {@code shown} holds and runs nothing extra on a click.
     *
     * @param id view id of the box's bounds
     * @param shown whether the owning menu is on screen
     */
    public MenuScrollBox(String id, BooleanSupplier shown) {
        this(id, shown, null);
    }

    /**
     * An empty box that scrolls only while {@code shown} holds.
     *
     * @param id view id of the box's bounds
     * @param shown whether the owner is on screen
     * @param clicked run on every click in the box, see {@link #clicked}
     */
    public MenuScrollBox(String id, BooleanSupplier shown, Runnable clicked) {
        this.bounds = new Bounds(0, 0, 0, 0, id);
        this.shown = shown;
        this.clicked = clicked;
        this.scrollBar = new MenuScrollBar(bounds, false, Drawing.FONT_HEIGHT_PIXELS);
    }

    /**
     * A box over rows its owner lays out and draws itself, scrolled by a bar the owner already
     * holds, such as the main menu's buttons.
     *
     * @param bounds    the rectangle the rows sit in
     * @param scrollBar the bar that holds the rows' scroll
     * @param shown     whether the owner is on screen
     */
    public MenuScrollBox(Bounds bounds, MenuScrollBar scrollBar, BooleanSupplier shown) {
        this.bounds = bounds;
        this.shown = shown;
        this.clicked = null;
        this.scrollBar = scrollBar;
        clearRows();
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
     * Fit the box to the current window, wrap the text to its width (less the
     * {@link #scrollBar} when the rows overflow), then apply a pending scroll and clamp it.
     *
     * @param camera 2D camera whose view is set to the box's text and left there
     */
    public void layout(Camera2D camera) {
        bounds.recalc();
        float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
        layoutRows(camera, bounds.viewWidth);
        if (scrollBar.isNeeded()) {
            layoutRows(camera, bounds.viewWidth - MenuScrollBar.WIDTH_PIXELS);
        }
        if (rowToCentre >= 0 && rowToCentre < text.lines) {
            int firstRow = 0;
            for (int line = 0; line < rowToCentre; line++) {
                firstRow += text.setLineOffsetFromTopRow(camera, firstRow, 0, rowHeight, line);
            }
            int ownRows = text.setLineOffsetFromTopRow(camera, firstRow, 0, rowHeight, rowToCentre);
            scrollBar.scrollOffset = (firstRow + ownRows / 2f) * rowHeight - bounds.viewHeight / 2;
        }
        if (scrollToEndPending) {
            scrollBar.scrollOffset = scrollBar.maximumScrollOffset();
        }
        rowToCentre = -1;
        scrollToEndPending = false;
        scrollBar.scrollBy(0);
    }

    /**
     * Set the camera's view to the box's left {@code width} pixels, wrap the rows to it and give
     * the {@link #scrollBar} their height.
     *
     * @param camera 2D camera whose view is set there
     * @param width pixels the rows may take
     */
    private void layoutRows(Camera2D camera, float width) {
        camera.updateView(bounds.id, (int) bounds.offsetX, (int) bounds.offsetY, (int) width,
                (int) bounds.viewHeight);
        rowsUsed = text.words.isEmpty() ? 0
                : text.setLineOffsetFromTopRow(camera, 0, 0, Drawing.FONT_HEIGHT_PIXELS);
        scrollBar.contentLength = rowsUsed * Drawing.FONT_HEIGHT_PIXELS;
        scrollBar.visibleLength = bounds.viewHeight;
    }

    /**
     * Draw the rows top-down at {@link #drawnScrollOffsetY()} with the GL viewport on the rows' part
     * of the box, so a part-scrolled row is cut at the box edge as its hit area is, then the scroll
     * bar when they overflow.
     *
     * @param camera 2D camera whose view is moved to the box and left on a part of it
     */
    public void draw(Camera2D camera) {
        layout(camera);
        Drawing.getDrawing().font.drawHyperStringRows(text, 0, drawnScrollOffsetY(),
                Drawing.FONT_HEIGHT_PIXELS, camera);
        if (scrollBar.isNeeded()) {
            scrollBar.draw(camera);
        }
    }

    /**
     * The scroll the rows are drawn, hit-tested and reported at: the bar's scroll itself, with no
     * easing, so a scroll shows on the next frame.
     *
     * @return the offset in pixels, between zero and the bar's maximum
     */
    public float drawnScrollOffsetY() {
        return scrollBar.scrollOffset;
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
     * the user) moves on to later rows. The box scrolls only vertically.
     *
     * @param deltaX raw horizontal delta, ignored
     * @param deltaY raw vertical delta, 1.0 per wheel notch
     */
    @Override
    public void onScrollDelta(double deltaX, double deltaY) {
        if (!shown.getAsBoolean()) {
            return;
        }
        scrollBar.scrollBy((float) (-deltaY * PIXELS_PER_SCROLL_UNIT));
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

    /**
     * A shown box takes presses over it, so they reach its rows and bar and nothing under it.
     *
     * @param x framebuffer x of the press
     * @param y framebuffer y of the press, y up
     * @return whether the owner is shown
     */
    @Override
    public boolean claimsPointer(float x, float y) {
        return shown.getAsBoolean();
    }

    /**
     * Hand the press to the {@link #scrollBar}, which acts on it when it lands on the bar; a press
     * on a row waits for its release.
     *
     * @param x framebuffer x of the press
     * @param y framebuffer y of the press, y up
     */
    @Override
    public void onPress(float x, float y) {
        scrollBar.press(x, y);
    }

    /**
     * Move a thumb the press grabbed.
     *
     * @param x framebuffer x of the cursor
     * @param y framebuffer y of the cursor, y up
     */
    @Override
    public void onDrag(float x, float y) {
        scrollBar.dragTo(x, y);
    }

    /**
     * Let go of the thumb and, for a click, run {@link #clicked} and then the action of the word
     * under the cursor.
     *
     * @param x framebuffer x of the release
     * @param y framebuffer y of the release, y up
     * @param click whether the press and release make a click
     */
    @Override
    public void onRelease(float x, float y, boolean click) {
        scrollBar.release();
        if (click && clicked != null) {
            clicked.run();
        }
        if (click && text != null) {
            text.click(x, y);
        }
    }
}
