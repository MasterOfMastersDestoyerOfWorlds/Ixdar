package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Predicate;

import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.render.Clock;
import ixdar.platform.Platforms;

/**
 * Stands in front of a platform's {@link MouseTrap}: each pointer event is offered to the
 * subscribed {@link PointerRegion}s first, and only what none takes reaches the trap.
 */
public class PointerDispatcher {

    private static final HashMap<Integer, PointerDispatcher> DISPATCHERS_BY_PLATFORM = new HashMap<>();

    /** Subscribed regions' screen rectangles, parallel to {@link #regions}. */
    public final ArrayList<Bounds> regionBounds = new ArrayList<>();

    /** Subscribed regions in subscription order; earlier ones win where they overlap. */
    public final ArrayList<PointerRegion> regions = new ArrayList<>();

    /** The region holding the current left press, or {@code null} while none does. */
    public PointerRegion pressHolder;

    /** Window x of the press {@link #pressHolder} took. */
    public float pressWindowX;

    /** Window y of the press {@link #pressHolder} took. */
    public float pressWindowY;

    /** Window x of the cursor since the held press, its latest drag. */
    public float heldCursorWindowX;

    /** Window y of the cursor since the held press. */
    public float heldCursorWindowY;

    /**
     * The dispatcher for one platform, created on first use.
     *
     * @param platformId platform ID as {@link Platforms} registers it
     * @return that platform's dispatcher
     */
    public static PointerDispatcher forPlatform(int platformId) {
        return DISPATCHERS_BY_PLATFORM.computeIfAbsent(platformId, id -> new PointerDispatcher());
    }

    /**
     * The dispatcher for the platform current in {@link Platforms}.
     *
     * @return that platform's dispatcher
     */
    public static PointerDispatcher current() {
        return forPlatform(Platforms.gl().getPlatformID());
    }

    /**
     * Ask {@code region} for pointer input while the cursor is inside {@code bounds}.
     *
     * @param bounds screen rectangle, framebuffer pixels, y up, recalculated at every test
     * @param region the region's input handler
     */
    public void subscribe(Bounds bounds, PointerRegion region) {
        regionBounds.add(bounds);
        regions.add(region);
    }

    /**
     * Drop every subscription of {@code region}, releasing its press if it holds one.
     *
     * @param region region to unregister
     */
    public void unsubscribe(PointerRegion region) {
        for (int index = regions.size() - 1; index >= 0; index--) {
            if (regions.get(index) == region) {
                regions.remove(index);
                regionBounds.remove(index);
            }
        }
        if (pressHolder == region) {
            pressHolder = null;
        }
    }

    /**
     * The first eligible subscribed region under the trap's cursor, each region's bounds
     * recalculated first so a resized pane is tested at its current size. The cursor is tested in
     * framebuffer pixels, y up, the way clicks are.
     *
     * @param trap trap whose cursor position is tested
     * @param eligible which regions may take the input, such as those claiming the wheel
     * @return that region, or {@code null} when the cursor is in none or has not moved yet
     */
    public PointerRegion regionUnderCursor(MouseTrap trap, Predicate<PointerRegion> eligible) {
        if (trap.lastX == Integer.MIN_VALUE) {
            return null;
        }
        for (int index = 0; index < regions.size(); index++) {
            Bounds bounds = regionBounds.get(index);
            PointerRegion region = regions.get(index);
            if (bounds == null || !eligible.test(region)) {
                continue;
            }
            bounds.recalc();
            boolean inside = trap.normalizedPosX >= bounds.offsetX
                    && trap.normalizedPosX <= bounds.offsetX + bounds.viewWidth
                    && trap.normalizedPosY >= bounds.offsetY
                    && trap.normalizedPosY <= bounds.offsetY + bounds.viewHeight;
            if (inside) {
                return region;
            }
        }
        return null;
    }

    /**
     * A button event: a left press over a region that {@link PointerRegion#claimsPointer claims
     * the pointer} is held by it, and so is its release, a click when the cursor stayed within
     * {@link MouseTrap#CLICK_DRAG_THRESHOLD_PX}; every other event goes to the trap.
     *
     * @param trap the platform's current trap
     * @param button button index
     * @param action {@code ACTION_PRESS} or {@code ACTION_RELEASE}
     * @param mods modifier-key bitmask
     */
    public void mouseButton(MouseTrap trap, int button, int action, int mods) {
        if (button == MOUSE_BUTTON_LEFT && action == ACTION_PRESS && trap.active) {
            PointerRegion region = regionUnderCursor(trap, PointerRegion::claimsPointer);
            if (region != null) {
                pressHolder = region;
                pressWindowX = trap.lastX;
                pressWindowY = trap.lastY;
                heldCursorWindowX = pressWindowX;
                heldCursorWindowY = pressWindowY;
                region.onPress(trap.normalizedPosX, trap.normalizedPosY);
                return;
            }
        }
        if (button == MOUSE_BUTTON_LEFT && action == ACTION_RELEASE && pressHolder != null) {
            PointerRegion region = pressHolder;
            pressHolder = null;
            boolean click = Math.hypot(heldCursorWindowX - pressWindowX,
                    heldCursorWindowY - pressWindowY) < MouseTrap.CLICK_DRAG_THRESHOLD_PX;
            region.onRelease(trap.camera.getNormalizePosX(heldCursorWindowX),
                    trap.camera.getNormalizePosY(heldCursorWindowY), click);
            return;
        }
        trap.mouseButton(button, action, mods);
    }

    /**
     * Cursor motion: while a region holds the press it is that region's drag, otherwise the
     * trap's.
     *
     * @param trap the platform's current trap
     * @param window platform window handle (for GL mouse-state polling)
     * @param x cursor x in window pixels
     * @param y cursor y in window pixels
     */
    public void moveOrDrag(MouseTrap trap, long window, float x, float y) {
        if (pressHolder == null) {
            trap.moveOrDrag(window, x, y);
        } else {
            mouseDragged(trap, x, y);
        }
    }

    /**
     * A move with the left button held: the held region's drag wherever the cursor now is, or
     * the trap's drag when no region holds the press.
     *
     * @param trap the platform's current trap
     * @param x cursor x in window pixels
     * @param y cursor y in window pixels
     */
    public void mouseDragged(MouseTrap trap, float x, float y) {
        if (pressHolder == null) {
            trap.mouseDragged(x, y);
            return;
        }
        heldCursorWindowX = x;
        heldCursorWindowY = y;
        pressHolder.onDrag(trap.camera.getNormalizePosX(x), trap.camera.getNormalizePosY(y));
    }

    /**
     * Per-frame: the trap's queued wheel delta goes whole to a region under the cursor that
     * {@link PointerRegion#claimsWheel claims it}; when the trap does not
     * {@link MouseTrap#usesWheel use the wheel}, any region there steps instead.
     *
     * @param trap trap whose frame this is
     * @param shiftMod speed multiplier, passed to the trap
     */
    public void paintUpdate(MouseTrap trap, float shiftMod) {
        if (trap.queuedScrollDelta != 0) {
            PointerRegion claimer = trap.active ? regionUnderCursor(trap, PointerRegion::claimsWheel) : null;
            if (claimer != null) {
                double delta = trap.queuedScrollDelta;
                trap.queuedScrollDelta = 0;
                claimer.onScrollDelta(delta);
            } else if (!trap.usesWheel()
                    && System.currentTimeMillis() - trap.timeLastScroll <= MouseTrap.SCROLL_DECAY_MS) {
                PointerRegion region = regionUnderCursor(trap, any -> true);
                if (region != null) {
                    region.onScroll(trap.queuedScrollDelta < 0,
                            Clock.deltaTime() * MouseTrap.SCROLL_SPEED_SCALE);
                    return;
                }
            }
        }
        trap.paintUpdate(shiftMod);
    }
}
