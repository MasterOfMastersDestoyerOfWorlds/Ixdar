package ixdar.gui.ui.menu;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.gui.ui.Drawing;

/**
 * A scroll bar along the right edge (vertical) or bottom edge (horizontal) of a rectangle: track,
 * thumb and end arrows stroked with the UI's {@link SDFLine}. It owns the scroll: content and
 * visible lengths in pixels and the offset of the view into the content.
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

    /** The rectangle whose right edge, or bottom edge when {@link #horizontal}, holds the bar. */
    public final Bounds bounds;

    /** Whether the bar runs left to right along the bottom edge rather than down the right edge. */
    public final boolean horizontal;

    /** Pixels an end arrow scrolls by, and the unit a page is a whole number of. */
    public final float stepPixels;

    /** Length of everything that scrolls, in pixels along the bar. */
    public float contentLength;

    /** Length of the part of the content on screen, in pixels along the bar. */
    public float visibleLength;

    /**
     * Pixels the view is scrolled on from the content's start (its top, or its left edge when
     * {@link #horizontal}); press and drag keep it in [0, {@link #maximumScrollOffset()}].
     */
    public float scrollOffset;

    /** Distance along the bar the thumb was grabbed at, or NaN while no drag holds it. */
    public float grabAlong = Float.NaN;

    /** The scroll when the thumb was grabbed. */
    public float grabScrollOffset;

    /**
     * A bar on {@code bounds} with nothing to scroll yet.
     *
     * @param bounds rectangle whose edge holds the bar
     * @param horizontal true for a bar along the bottom edge, false for one down the right edge
     * @param stepPixels pixels an end arrow scrolls by
     */
    public MenuScrollBar(Bounds bounds, boolean horizontal, float stepPixels) {
        this.bounds = bounds;
        this.horizontal = horizontal;
        this.stepPixels = stepPixels;
    }

    /**
     * Whether the content overflows the view, so the bar has something to scroll.
     *
     * @return true when the content is longer than the visible part
     */
    public boolean isNeeded() {
        return maximumScrollOffset() > 0;
    }

    /**
     * Largest scroll that still leaves the content's end at the view's end.
     *
     * @return the limit in pixels, zero when everything fits
     */
    public float maximumScrollOffset() {
        return Math.max(0, contentLength - visibleLength);
    }

    /**
     * Length of the bar's edge of {@link #bounds}.
     *
     * @return its width when horizontal, else its height
     */
    public float length() {
        return horizontal ? bounds.viewWidth : bounds.viewHeight;
    }

    /**
     * Length of the track between the two arrows.
     *
     * @return pixels between the arrows, never negative
     */
    public float trackLength() {
        return Math.max(0, length() - 2 * WIDTH_PIXELS);
    }

    /**
     * Length of the thumb: the track times the share of the content the view shows, kept at least
     * {@link #MINIMUM_THUMB_PIXELS} so it stays grabbable, and never longer than the track.
     *
     * @return the thumb's length in pixels
     */
    public float thumbLength() {
        float track = trackLength();
        if (contentLength <= visibleLength) {
            return track;
        }
        float visibleShare = visibleLength / contentLength;
        return Math.min(track, Math.max(Math.min(MINIMUM_THUMB_PIXELS, track), track * visibleShare));
    }

    /**
     * Distance along the bar from its start (top, or left end) to the thumb's near edge: at the
     * start arrow with no scroll, at the end arrow at the limit, and in proportion between.
     *
     * @return pixels from the bar's start end
     */
    public float thumbStart() {
        float limit = maximumScrollOffset();
        float scrolledShare = limit <= 0 ? 0 : Math.max(0, Math.min(1, scrollOffset / limit));
        return WIDTH_PIXELS + scrolledShare * (trackLength() - thumbLength());
    }

    /**
     * Pixels a click on the track moves by: the whole steps the view shows, less one kept for
     * context.
     *
     * @return at least one step
     */
    public float pageLength() {
        return Math.max(1, (int) (visibleLength / stepPixels) - 1) * stepPixels;
    }

    /**
     * Scroll by {@code pixels} from the scroll now, clamped to the content.
     *
     * @param pixels distance to move on by; negative moves back toward the start
     */
    public void scrollBy(float pixels) {
        scrollOffset = Math.max(0, Math.min(maximumScrollOffset(), scrollOffset + pixels));
    }

    /**
     * Distance of a framebuffer point along the bar from its start end.
     *
     * @param x framebuffer x
     * @param y framebuffer y, y up
     * @return pixels from the left edge when horizontal, else from the top edge
     */
    public float along(float x, float y) {
        return horizontal ? x - bounds.offsetX : bounds.offsetY + bounds.viewHeight - y;
    }

    /**
     * Whether a framebuffer point lies on the bar's strip.
     *
     * @param x framebuffer x
     * @param y framebuffer y, y up
     * @return true inside the {@link #WIDTH_PIXELS}-wide strip along the bar's edge
     */
    public boolean contains(float x, float y) {
        float across = horizontal ? y - bounds.offsetY : bounds.offsetX + bounds.viewWidth - x;
        float along = along(x, y);
        return across >= 0 && across <= WIDTH_PIXELS && along >= 0 && along <= length();
    }

    /**
     * Act on a press on a bar with something to scroll: the start arrow scrolls back a step, the
     * end arrow on a step, the track either side of the thumb a page, and the thumb is grabbed.
     *
     * @param x framebuffer x of the press
     * @param y framebuffer y of the press, y up
     * @return true when the press landed on the bar
     */
    public boolean press(float x, float y) {
        if (!isNeeded() || !contains(x, y)) {
            return false;
        }
        float along = along(x, y);
        float thumbStart = thumbStart();
        if (along <= WIDTH_PIXELS) {
            scrollBy(-stepPixels);
        } else if (along >= length() - WIDTH_PIXELS) {
            scrollBy(stepPixels);
        } else if (along < thumbStart) {
            scrollBy(-pageLength());
        } else if (along > thumbStart + thumbLength()) {
            scrollBy(pageLength());
        } else {
            grabAlong = along;
            grabScrollOffset = scrollOffset;
        }
        return true;
    }

    /**
     * Move a grabbed thumb with the cursor: the scroll follows in proportion, so the thumb's full
     * travel along the track spans the whole content.
     *
     * @param x framebuffer x of the cursor
     * @param y framebuffer y of the cursor, y up
     */
    public void dragTo(float x, float y) {
        float travel = trackLength() - thumbLength();
        if (Float.isNaN(grabAlong) || travel <= 0) {
            return;
        }
        float limit = maximumScrollOffset();
        float target = grabScrollOffset + (along(x, y) - grabAlong) * limit / travel;
        scrollOffset = Math.max(0, Math.min(limit, target));
    }

    /** Let go of the thumb. */
    public void release() {
        grabAlong = Float.NaN;
    }

    /**
     * Draw the track, thumb and arrows into the bar's strip, leaving the camera's view on that
     * strip. The caller decides whether a bar with nothing to scroll is drawn.
     *
     * @param camera 2D camera whose view is moved to the bar's strip
     */
    public void draw(Camera2D camera) {
        float length = length();
        if (horizontal) {
            camera.updateView((int) bounds.offsetX, (int) bounds.offsetY, (int) length, (int) WIDTH_PIXELS);
        } else {
            camera.updateView((int) (bounds.offsetX + bounds.viewWidth - WIDTH_PIXELS), (int) bounds.offsetY,
                    (int) WIDTH_PIXELS, (int) length);
        }
        SDFLine line = Drawing.getDrawing().sdfLine;
        float trackCap = TRACK_STROKE_PIXELS * LINE_CAP_REACH_PAST_ENDPOINT_PER_STROKE_PIXEL;
        line.setStroke(TRACK_STROKE_PIXELS, false, 1f, 0f, false, false, false);
        line.draw(stripPoint(WIDTH_PIXELS + trackCap, 0), stripPoint(length - WIDTH_PIXELS - trackCap, 0),
                Color.GREY_DARK, camera);
        float thumbMiddle = thumbStart() + thumbLength() / 2;
        float thumbHalf = Math.max(1, thumbLength() / 2
                - THUMB_STROKE_PIXELS * LINE_CAP_REACH_PAST_ENDPOINT_PER_STROKE_PIXEL);
        line.setStroke(THUMB_STROKE_PIXELS, false, 1f, 0f, false, false, false);
        line.draw(stripPoint(thumbMiddle - thumbHalf, 0), stripPoint(thumbMiddle + thumbHalf, 0), Color.BLUE_GREY,
                camera);
        line.setStroke(ARROW_STROKE_PIXELS, false, 1f, 0f, false, false, false);
        for (int end = 0; end < 2; end++) {
            float centre = end == 0 ? WIDTH_PIXELS / 2 : length - WIDTH_PIXELS / 2;
            float tipDirection = end == 0 ? -1 : 1;
            Vector2f tip = stripPoint(centre + tipDirection * ARROW_HALF_HEIGHT_PIXELS, 0);
            float base = centre - tipDirection * ARROW_HALF_HEIGHT_PIXELS;
            line.draw(stripPoint(base, -ARROW_HALF_WIDTH_PIXELS), tip, Color.BLUE_GREY, camera);
            line.draw(tip, stripPoint(base, ARROW_HALF_WIDTH_PIXELS), Color.BLUE_GREY, camera);
        }
    }

    /**
     * A point of the bar's strip in the strip's own view pixels, y up.
     *
     * @param along distance from the bar's start end
     * @param across offset from the strip's centre line
     * @return the point
     */
    private Vector2f stripPoint(float along, float across) {
        float middle = WIDTH_PIXELS / 2;
        return horizontal ? new Vector2f(along, middle + across) : new Vector2f(middle + across, length() - along);
    }
}
