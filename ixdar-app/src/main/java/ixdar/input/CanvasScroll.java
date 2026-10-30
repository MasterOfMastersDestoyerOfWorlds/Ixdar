package ixdar.input;

import java.util.Set;
import java.util.function.BooleanSupplier;

import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.cameras.CameraController;
import ixdar.gui.ui.menu.MenuScrollBar;
import ixdar.platform.Clock;
import ixdar.platform.input.KeyNames;
import ixdar.platform.input.PointerRegion;

/**
 * Scrolling of every 2D canvas: subscribed over its camera's main view it takes the wheel and the
 * presses on its scroll bars; as the trap's controller, the drag-pan and the held keys.
 */
public class CanvasScroll implements CameraController, PointerRegion {
    public static final float SCROLL_PAN_PX_PER_UNIT = 30f;
    public static final float BAR_STEP_PX = 40f;

    /** The camera scrolled; its main view is the canvas, in framebuffer pixels, y up. */
    public final Camera2D camera;

    /**
     * Whether the canvas has scroll bars: a scroll range, which the wheel scrolls within, drawn
     * and driven by {@link #horizontalBar} and {@link #verticalBar}; without, it scrolls without limit.
     */
    public final boolean scrollBars;

    /** Whether the canvas is on screen, so its region takes the wheel and its bars' presses. */
    public final BooleanSupplier shown;

    /**
     * The content's bounds in point space, as its owner hands them over; the home range is this
     * rectangle grown about its centre to the main view's aspect ratio, what a frame of it shows.
     */
    public float contentLeft;
    public float contentBottom;
    public float contentRight;
    public float contentTop;

    /**
     * The scroll range in point space: the home range grown to hold every view since its last
     * reset, so a drag past the content widens it and the wheel can scroll back.
     */
    public float rangeLeft;
    public float rangeBottom;
    public float rangeRight;
    public float rangeTop;

    /**
     * Bar along the main view's bottom edge, its content the range's width and its offset the
     * view's distance from the range's left, in framebuffer pixels.
     */
    public final MenuScrollBar horizontalBar;

    /** Bar down the main view's right edge, above the horizontal one: the range's height, from its top. */
    public final MenuScrollBar verticalBar;

    /** The bar the held press landed on, which its drag moves, or {@code null}. */
    public MenuScrollBar pressedBar;

    /** Whether the held press the trap handed over started in the main view, so its drag pans. */
    public boolean dragPans;

    /** Framebuffer position the held pan drag last moved the view from. */
    public float dragStartX;
    public float dragStartY;

    /**
     * Scroll {@code camera}, whose main view must be bound before input arrives.
     *
     * @param camera     the canvas's camera
     * @param scrollBars true for a scroll range with bars, false to scroll without limit
     * @param shown      whether the canvas is on screen
     */
    public CanvasScroll(Camera2D camera, boolean scrollBars, BooleanSupplier shown) {
        this.camera = camera;
        this.scrollBars = scrollBars;
        this.shown = shown;
        horizontalBar = new MenuScrollBar(new Bounds(0, 0, 0, 0, "CANVAS_HORIZONTAL_BAR"), true, BAR_STEP_PX);
        verticalBar = new MenuScrollBar(new Bounds(0, 0, 0, 0, "CANVAS_VERTICAL_BAR"), false, BAR_STEP_PX);
    }

    /**
     * Take the content's bounds and reset the scroll range to the home range and the view.
     *
     * @param left   point-space x of the content's left edge
     * @param bottom its bottom edge
     * @param right  its right edge
     * @param top    its top edge
     */
    public void setContent(float left, float bottom, float right, float top) {
        contentLeft = left;
        contentBottom = bottom;
        contentRight = right;
        contentTop = top;
        resetRange();
    }

    /**
     * Zoom and pan so a point-space rectangle fills the main view along its tighter axis, centred,
     * then reset the scroll range.
     *
     * @param left   point-space x of the rectangle's left edge
     * @param bottom its bottom edge
     * @param right  its right edge
     * @param top    its top edge
     */
    public void frame(float left, float bottom, float right, float top) {
        Bounds view = camera.mainViewBounds;
        camera.calculateCameraTransform(camera.ps);
        float pixelsPerUnitAtUnitScale = camera.Width / camera.rangeX;
        camera.ScaleFactor = Math.min(view.viewWidth / (right - left), view.viewHeight / (top - bottom))
                / pixelsPerUnitAtUnitScale;
        float pixelsPerUnit = pixelsPerUnitAtUnitScale * camera.ScaleFactor;
        camera.PanX = view.viewWidth / 2 - ((left + right) / 2 - camera.minX) * pixelsPerUnit;
        camera.PanY = view.viewHeight / 2 - ((bottom + top) / 2 - camera.minY) * pixelsPerUnit;
        camera.calculateCameraTransform(camera.ps);
        resetRange();
    }

    /**
     * Shrink the scroll range to the home range, the content grown about its centre to the main
     * view's aspect ratio, and the view, as every move of the view but a scroll does.
     */
    public void resetRange() {
        if (!scrollBars) {
            return;
        }
        Bounds view = camera.mainViewBounds;
        view.recalc();
        float width = contentRight - contentLeft;
        float height = contentTop - contentBottom;
        float viewAspect = view.viewWidth / view.viewHeight;
        float halfGrowX = Math.max(0, height * viewAspect - width) / 2;
        float halfGrowY = Math.max(0, width / viewAspect - height) / 2;
        rangeLeft = contentLeft - halfGrowX;
        rangeRight = contentRight + halfGrowX;
        rangeBottom = contentBottom - halfGrowY;
        rangeTop = contentTop + halfGrowY;
        updateBars();
    }

    /**
     * Lay the bars along the main view's edges, grow the scroll range to hold the view and point
     * the bars at it: content the range's extent in whole pixels, offset the view's distance in.
     */
    public void updateBars() {
        Bounds view = camera.mainViewBounds;
        view.recalc();
        float barWidth = MenuScrollBar.WIDTH_PIXELS;
        horizontalBar.bounds.update(view.offsetX, view.offsetY, view.viewWidth - barWidth, view.viewHeight);
        verticalBar.bounds.update(view.offsetX, view.offsetY + barWidth, view.viewWidth, view.viewHeight - barWidth);
        camera.calculateCameraTransform(camera.ps);
        float viewLeft = camera.screenTransformX(0);
        float viewTop = camera.screenTransformY(view.viewHeight);
        rangeLeft = Math.min(rangeLeft, viewLeft);
        rangeBottom = Math.min(rangeBottom, camera.screenTransformY(0));
        rangeRight = Math.max(rangeRight, camera.screenTransformX(view.viewWidth));
        rangeTop = Math.max(rangeTop, viewTop);
        // Not pointTransformX(1) - pointTransformX(0): far into a zoom both are ~1e5 px and their
        // float difference is off by tenths of a percent, which a scroll then turns into a jump.
        float pixelsPerUnit = camera.width / camera.rangeX;
        horizontalBar.contentLength = Math.round((rangeRight - rangeLeft) * pixelsPerUnit);
        horizontalBar.visibleLength = view.viewWidth;
        horizontalBar.scrollOffset = (viewLeft - rangeLeft) * pixelsPerUnit;
        verticalBar.contentLength = Math.round((rangeTop - rangeBottom) * pixelsPerUnit);
        verticalBar.visibleLength = view.viewHeight;
        verticalBar.scrollOffset = (rangeTop - viewTop) * pixelsPerUnit;
    }

    /**
     * Pan the camera so the view sits in the scroll range where the bars' offsets say, the
     * inverse of {@link #updateBars}.
     */
    public void panToBars() {
        camera.calculateCameraTransform(camera.ps);
        camera.drag(-horizontalBar.scrollOffset - camera.pointTransformX(rangeLeft),
                camera.mainViewBounds.viewHeight + verticalBar.scrollOffset - camera.pointTransformY(rangeTop));
        camera.calculateCameraTransform(camera.ps);
    }

    /**
     * Draw the bars over the main view, with scroll bars on; the camera's viewport is left on the
     * last bar drawn.
     */
    public void drawBars() {
        if (!scrollBars) {
            return;
        }
        updateBars();
        horizontalBar.draw(camera);
        verticalBar.draw(camera);
    }

    /**
     * Unused: the canvas claims the wheel and scrolls through {@link #onScrollDelta}.
     *
     * @param scrollUp     unused
     * @param deltaSeconds unused
     */
    @Override
    public void onScroll(boolean scrollUp, double deltaSeconds) {
    }

    /**
     * A shown canvas takes the wheel over its main view.
     *
     * @return {@link #shown}
     */
    @Override
    public boolean claimsWheel() {
        return shown.getAsBoolean();
    }

    /**
     * Pan both axes, Shift turning a vertical-only scroll sideways, within the scroll range with
     * scroll bars on; with Control (pinch), zoom about the cursor, whose point stays put.
     *
     * @param deltaX summed horizontal delta, positive toward the left
     * @param deltaY summed vertical delta, 1.0 per wheel notch, positive away from the user
     * @param x      framebuffer x of the cursor
     * @param y      framebuffer y of the cursor, y up
     * @param mods   modifier-key bitmask held, as {@link KeyNames} spells the bits
     */
    @Override
    public void onScrollDelta(double deltaX, double deltaY, float x, float y, int mods) {
        if ((mods & KeyNames.MOD_CONTROL) != 0) {
            Bounds view = camera.mainViewBounds;
            camera.zoomAbout(Math.pow(Camera2D.ZOOM_PER_SCROLL_UNIT, deltaY), x - view.offsetX, y - view.offsetY);
            resetRange();
            return;
        }
        boolean sideways = (mods & KeyNames.MOD_SHIFT) != 0 && deltaX == 0;
        float panX = (float) ((sideways ? deltaY : deltaX) * SCROLL_PAN_PX_PER_UNIT);
        float panY = (float) (-(sideways ? 0 : deltaY) * SCROLL_PAN_PX_PER_UNIT);
        if (!scrollBars) {
            camera.drag(panX, panY);
            return;
        }
        updateBars();
        horizontalBar.scrollBy(-panX);
        verticalBar.scrollBy(panY);
        panToBars();
    }

    /**
     * A shown canvas with scroll bars takes the presses on them.
     *
     * @param x framebuffer x of the press
     * @param y framebuffer y of the press, y up
     * @return true on a bar
     */
    @Override
    public boolean claimsPointer(float x, float y) {
        return scrollBars && shown.getAsBoolean() && (horizontalBar.contains(x, y) || verticalBar.contains(x, y));
    }

    /**
     * Hand the claimed press to the bar under it, the view following.
     *
     * @param x framebuffer x of the press
     * @param y framebuffer y of the press, y up
     */
    @Override
    public void onPress(float x, float y) {
        updateBars();
        pressedBar = horizontalBar.contains(x, y) ? horizontalBar : verticalBar;
        pressedBar.press(x, y);
        panToBars();
    }

    /**
     * Move the grabbed bar's thumb with the cursor, the view following.
     *
     * @param x framebuffer x of the cursor
     * @param y framebuffer y of the cursor, y up
     */
    @Override
    public void onDrag(float x, float y) {
        pressedBar.dragTo(x, y);
        panToBars();
    }

    /**
     * Let go of the grabbed bar.
     *
     * @param x     framebuffer x of the release, unused
     * @param y     framebuffer y of the release, unused
     * @param click unused: a bar acts on its press
     */
    @Override
    public void onRelease(float x, float y, boolean click) {
        pressedBar.release();
        pressedBar = null;
    }

    /**
     * A press no region or tool took starts a pan drag when it lands in the main view.
     *
     * @param x    window x of the press
     * @param y    window y of the press
     * @param mods modifier-key bitmask, unused
     */
    @Override
    public void pointerPressed(float x, float y, int mods) {
        dragStartX = camera.getNormalizePosX(x);
        dragStartY = camera.getNormalizePosY(y);
        Bounds view = camera.mainViewBounds;
        dragPans = view == null || (dragStartX >= view.offsetX && dragStartX <= view.offsetX + view.viewWidth
                && dragStartY >= view.offsetY && dragStartY <= view.offsetY + view.viewHeight);
    }

    /**
     * Pan by the travel since the last sample, without limit, each step resetting the scroll
     * range so it grows to hold the view.
     *
     * @param lastX window x of the previous cursor sample, unused: the pan runs from the press
     * @param lastY window y of the previous cursor sample, unused
     * @param x     window x of the cursor
     * @param y     window y of the cursor
     */
    @Override
    public void pointerDragged(float lastX, float lastY, float x, float y) {
        if (!dragPans) {
            return;
        }
        float cursorX = camera.getNormalizePosX(x);
        float cursorY = camera.getNormalizePosY(y);
        camera.drag(cursorX - dragStartX, cursorY - dragStartY);
        dragStartX = cursorX;
        dragStartY = cursorY;
        resetRange();
    }

    /**
     * Move by the held movement keys and zoom about the view's centre by the held zoom keys; a
     * key that moved the view resets the scroll range, as a drag does.
     *
     * @param pressedKeys key codes held down
     * @param shiftMod    speed multiplier
     */
    @Override
    public void keysHeld(Set<Integer> pressedKeys, float shiftMod) {
        float panX = camera.PanX;
        float panY = camera.PanY;
        float scale = camera.ScaleFactor;
        CameraController.moveWithKeys(camera, pressedKeys, shiftMod, Clock.deltaTime());
        if (camera.PanX != panX || camera.PanY != panY || camera.ScaleFactor != scale) {
            resetRange();
        }
    }
}
