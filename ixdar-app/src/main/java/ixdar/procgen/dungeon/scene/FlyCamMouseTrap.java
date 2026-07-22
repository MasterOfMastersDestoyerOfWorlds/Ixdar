package ixdar.procgen.dungeon.scene;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.platform.Platforms;
import ixdar.platform.input.MouseTrap;

/**
 * Mouse-look trap that rotates only while the left button is held, or on every cursor delta
 * when {@code captureSupplier} reports true. Capture mode expects the platform cursor in
 * {@link ixdar.platform.gl.Platform.CursorMode#CAPTURED}.
 *
 * <p>Deltas and scroll ticks go to the caller's callbacks; no camera is touched here.
 */
public class FlyCamMouseTrap extends MouseTrap {

    private final DeltaHandler onDelta;
    private final IntConsumer onScroll;
    private final BooleanSupplier captureSupplier;
    private boolean leftDown = false;

    /**
     * Builds a mouse trap whose look-mode and delta routing are decided by the supplied callbacks.
     *
     * @param camera          camera passed to the base {@link MouseTrap}
     * @param canvas          canvas passed to the base {@link MouseTrap}
     * @param captureSupplier returns {@code true} when cursor deltas should rotate without LMB
     *     (capture / player mode) and {@code false} when rotation requires LMB-drag (fly-cam)
     * @param onDelta         consumer for cursor deltas — caller decides whether to feed them to
     *     first-person camera look or third-person orbit
     * @param onScroll        consumer for scroll-wheel ticks (e.g. third-person zoom); may be
     *     {@code null} to ignore scroll
     */
    public FlyCamMouseTrap(Camera camera, Canvas3D canvas,
                           BooleanSupplier captureSupplier,
                           DeltaHandler onDelta,
                           IntConsumer onScroll) {
        super(null, camera, canvas);
        this.captureSupplier = captureSupplier;
        this.onDelta = onDelta;
        this.onScroll = onScroll;
    }

    /**
     * Tracks the left mouse-button state used to gate drag-to-rotate.
     *
     * @param button platform mouse-button code
     * @param action {@link ixdar.platform.input.Keys#ACTION_PRESS} or
     *     {@link ixdar.platform.input.Keys#ACTION_RELEASE}
     * @param mods   platform modifier-key bitmask (currently unused)
     */
    @Override
    public void mouseButton(int button, int action, int mods) {
        Platforms.init(Platforms.get().getPlatformID());
        if (!active) return;
        if (button == MOUSE_BUTTON_LEFT) {
            if (action == ACTION_PRESS) {
                leftDown = true;
            } else if (action == ACTION_RELEASE) {
                leftDown = false;
            }
        }
    }

    /**
     * Cursor-move callback. In capture mode every delta rotates; otherwise only updates the
     * cached last-position so a future drag has a baseline.
     *
     * @param x cursor X in window pixels
     * @param y cursor Y in window pixels
     */
    @Override
    public void mousePos(float x, float y) {
        if (!active) return;
        if (captureSupplier.getAsBoolean()) {
            if (lastX != Integer.MIN_VALUE && lastY != Integer.MIN_VALUE) {
                onDelta.apply(lastX, lastY, x, y);
            }
        }
        lastX = (int) x;
        lastY = (int) y;
    }

    /**
     * Drag callback for fly-cam mode — emits a delta only while the left mouse button is held.
     *
     * @param x cursor X in window pixels
     * @param y cursor Y in window pixels
     */
    @Override
    public void mouseDragged(float x, float y) {
        if (!active || !leftDown) return;
        if (lastX == Integer.MIN_VALUE || lastY == Integer.MIN_VALUE) {
            mousePos(x, y);
            return;
        }
        onDelta.apply(lastX, lastY, x, y);
        lastX = (int) x;
        lastY = (int) y;
    }

    /**
     * Unified cursor entry point — dispatches to {@link #mousePos} in capture mode or to
     * {@link #mouseDragged} when LMB is held in fly-cam mode.
     *
     * @param window platform window handle (unused; required by the base API)
     * @param x cursor X in window pixels
     * @param y cursor Y in window pixels
     */
    @Override
    public void moveOrDrag(long window, float x, float y) {
        if (captureSupplier.getAsBoolean() || leftDown) {
            if (captureSupplier.getAsBoolean()) {
                mousePos(x, y);
            } else {
                mouseDragged(x, y);
            }
        } else {
            mousePos(x, y);
        }
    }

    /**
     * Per-frame hook that drains queued scroll-wheel ticks into {@code onScroll}.
     *
     * @param shiftMod movement-speed multiplier (unused here; kept for base-class symmetry)
     */
    @Override
    public void paintUpdate(float shiftMod) {
        if (queuedMouseWheelTicks != 0 && onScroll != null) {
            int ticks = queuedMouseWheelTicks;
            queuedMouseWheelTicks = 0;
            onScroll.accept(ticks);
        } else {
            queuedMouseWheelTicks = 0;
        }
    }

    @FunctionalInterface
    public interface DeltaHandler {
        /**
         * Receives a cursor-position delta and routes it to the appropriate look target.
         *
         * @param lastX previous cursor X in window pixels
         * @param lastY previous cursor Y in window pixels
         * @param x     current cursor X in window pixels
         * @param y     current cursor Y in window pixels
         */
        void apply(float lastX, float lastY, float x, float y);
    }
}
