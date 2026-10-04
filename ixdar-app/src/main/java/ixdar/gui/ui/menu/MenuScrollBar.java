package ixdar.gui.ui.menu;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.gui.ui.Drawing;

/**
 * The scroll bar at the right edge of an overflowing {@link MenuScrollBox}: track, thumb and end
 * arrows stroked with the UI's {@link SDFLine}, acting on the presses its box hands it.
 */
public final class MenuScrollBar {

    public static final float WIDTH_PIXELS = 20f;

    public static final float MINIMUM_THUMB_PIXELS = 16f;

    public static final float TRACK_STROKE_PIXELS = 2f;

    public static final float THUMB_STROKE_PIXELS = 4f;

    public static final float ARROW_STROKE_PIXELS = 2f;

    public static final float ARROW_HALF_WIDTH_PIXELS = 5f;

    public static final float ARROW_HALF_HEIGHT_PIXELS = 3f;

    public static final float LINE_CAP_REACH_PAST_ENDPOINT_PER_STROKE_PIXEL = 1.5f;

    /** The box this bar scrolls. */
    public final MenuScrollBox box;

    /** Framebuffer y the thumb was grabbed at, or NaN while no drag holds it. */
    public float grabY = Float.NaN;

    /** The box's scroll when the thumb was grabbed. */
    public float grabScrollOffsetY;

    /**
     * A bar for {@code box}.
     *
     * @param box the box the bar scrolls
     */
    public MenuScrollBar(MenuScrollBox box) {
        this.box = box;
    }

    /**
     * Whether the box's rows overflow it at its last layout, so the bar is drawn and the rows wrap
     * to its left.
     *
     * @return true when the rows are taller than the box
     */
    public boolean isNeeded() {
        return box.maximumScrollOffsetY() > 0;
    }

    /**
     * Length of the track between the two arrows.
     *
     * @return pixels from the bottom arrow's top to the top arrow's bottom, never negative
     */
    public float trackLength() {
        return Math.max(0, box.bounds.viewHeight - 2 * WIDTH_PIXELS);
    }

    /**
     * Length of the thumb: the track times the share of the rows the box shows, kept at least
     * {@link #MINIMUM_THUMB_PIXELS} so it stays grabbable, and never longer than the track.
     *
     * @return the thumb's length in pixels
     */
    public float thumbLength() {
        float track = trackLength();
        float rowsHeight = box.rowsUsed * Drawing.FONT_HEIGHT_PIXELS;
        if (rowsHeight <= box.bounds.viewHeight) {
            return track;
        }
        float visibleShare = box.bounds.viewHeight / rowsHeight;
        return Math.min(track, Math.max(Math.min(MINIMUM_THUMB_PIXELS, track), track * visibleShare));
    }

    /**
     * Box-local y of the thumb's top edge: at the top of the track with the first row at the top,
     * at the bottom of the track with the last row at the bottom, and in proportion between.
     *
     * @return pixels above the box's bottom edge, y up
     */
    public float thumbTop() {
        float limit = box.maximumScrollOffsetY();
        float scrolledShare = limit <= 0 ? 0
                : Math.max(0, Math.min(1, box.drawnScrollOffsetY() / limit));
        return box.bounds.viewHeight - WIDTH_PIXELS - scrolledShare * (trackLength() - thumbLength());
    }

    /**
     * Width the box's rows wrap to: the whole box, less the bar when the rows overflow.
     *
     * @return pixels from the box's left edge to the bar, or to its right edge with no bar
     */
    public float textWidth() {
        return box.bounds.viewWidth - (isNeeded() ? WIDTH_PIXELS : 0);
    }

    /**
     * Rows a click on the track moves by: the rows the box shows, less one kept for context.
     *
     * @return at least one
     */
    public int pageRows() {
        return Math.max(1, (int) (box.bounds.viewHeight / Drawing.FONT_HEIGHT_PIXELS) - 1);
    }

    /**
     * Scroll the box by whole rows from the rows it shows now, clamped to its rows.
     *
     * @param rows rows to move on by; negative moves back toward the first row
     */
    public void scrollByRows(int rows) {
        float target = box.drawnScrollOffsetY() + rows * Drawing.FONT_HEIGHT_PIXELS;
        box.scrollOffsetY = Math.max(0, Math.min(box.maximumScrollOffsetY(), target));
    }

    /**
     * Act on a press on the bar of an overflowing box: the top arrow scrolls back a row, the
     * bottom arrow on a row, the track above or below the thumb a page, and the thumb is grabbed.
     *
     * @param x framebuffer x of the press
     * @param y framebuffer y of the press, y up
     * @return true when the press landed on the bar
     */
    public boolean press(float x, float y) {
        if (!isNeeded()) {
            return false;
        }
        float localX = x - box.bounds.offsetX;
        float localY = y - box.bounds.offsetY;
        float height = box.bounds.viewHeight;
        if (localX < box.bounds.viewWidth - WIDTH_PIXELS || localX > box.bounds.viewWidth
                || localY < 0 || localY > height) {
            return false;
        }
        float thumbTop = thumbTop();
        if (localY >= height - WIDTH_PIXELS) {
            scrollByRows(-1);
        } else if (localY <= WIDTH_PIXELS) {
            scrollByRows(1);
        } else if (localY > thumbTop) {
            scrollByRows(-pageRows());
        } else if (localY < thumbTop - thumbLength()) {
            scrollByRows(pageRows());
        } else {
            grabY = y;
            grabScrollOffsetY = box.drawnScrollOffsetY();
        }
        return true;
    }

    /**
     * Move a grabbed thumb with the cursor: the scroll follows in proportion, so the thumb's full
     * travel along the track spans every row.
     *
     * @param x framebuffer x of the cursor, unused
     * @param y framebuffer y of the cursor, y up
     */
    public void dragTo(float x, float y) {
        float travel = trackLength() - thumbLength();
        if (Float.isNaN(grabY) || travel <= 0) {
            return;
        }
        float limit = box.maximumScrollOffsetY();
        float target = grabScrollOffsetY + (grabY - y) * limit / travel;
        box.scrollOffsetY = Math.max(0, Math.min(limit, target));
    }

    /** Let go of the thumb. */
    public void release() {
        grabY = Float.NaN;
    }

    /**
     * Draw the track, thumb and arrows into the strip at the box's right edge when the rows
     * overflow, leaving the camera's view on that strip.
     *
     * @param camera 2D camera whose view is moved to the bar's strip
     */
    public void draw(Camera2D camera) {
        if (!isNeeded()) {
            return;
        }
        float height = box.bounds.viewHeight;
        camera.updateView((int) (box.bounds.offsetX + box.bounds.viewWidth - WIDTH_PIXELS),
                (int) box.bounds.offsetY, (int) WIDTH_PIXELS, (int) height);
        SDFLine line = Drawing.getDrawing().sdfLine;
        float middleX = WIDTH_PIXELS / 2;
        float trackCap = TRACK_STROKE_PIXELS * LINE_CAP_REACH_PAST_ENDPOINT_PER_STROKE_PIXEL;
        line.setStroke(TRACK_STROKE_PIXELS, false, 1f, 0f, false, false, false);
        line.draw(new Vector2f(middleX, WIDTH_PIXELS + trackCap),
                new Vector2f(middleX, height - WIDTH_PIXELS - trackCap), Color.LIGHT_GRAY, camera);
        float thumbTop = thumbTop();
        float thumbMiddle = thumbTop - thumbLength() / 2;
        float thumbHalf = Math.max(1, thumbLength() / 2
                - THUMB_STROKE_PIXELS * LINE_CAP_REACH_PAST_ENDPOINT_PER_STROKE_PIXEL);
        line.setStroke(THUMB_STROKE_PIXELS, false, 1f, 0f, false, false, false);
        line.draw(new Vector2f(middleX, thumbMiddle - thumbHalf),
                new Vector2f(middleX, thumbMiddle + thumbHalf), Color.BLUE_WHITE, camera);
        line.setStroke(ARROW_STROKE_PIXELS, false, 1f, 0f, false, false, false);
        for (int end = 0; end < 2; end++) {
            float centreY = end == 0 ? height - WIDTH_PIXELS / 2 : WIDTH_PIXELS / 2;
            float tipDirection = end == 0 ? 1 : -1;
            Vector2f tip = new Vector2f(middleX, centreY + tipDirection * ARROW_HALF_HEIGHT_PIXELS);
            float baseY = centreY - tipDirection * ARROW_HALF_HEIGHT_PIXELS;
            line.draw(new Vector2f(middleX - ARROW_HALF_WIDTH_PIXELS, baseY), tip, Color.BLUE_WHITE,
                    camera);
            line.draw(tip, new Vector2f(middleX + ARROW_HALF_WIDTH_PIXELS, baseY), Color.BLUE_WHITE,
                    camera);
        }
    }
}
