package ixdar.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.cameras.CameraController;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.AutomationBackend;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.platform.input.MouseButtons;
import ixdar.platform.input.PointerRegion;
import ixdar.scenes.Scene;

/**
 * Every scene's mouse handler, behind the {@link PointerDispatcher}: what the panes and the active
 * tool leave goes to the scene's {@link #controller camera controller}, and the cursor and clicks
 * to the scene's view and the overlay text's links.
 */
public class MouseTrap {
    public static final float MIN_WINDOW_DIMENSION = 1f;
    public static final int SCROLL_DECAY_MS = 60;
    public static final int CLICK_DRAG_THRESHOLD_PX = 3;
    public static final int ZOOM_SETTLE_FRAMES = 10;
    public static final float HOVER_LOCK_TOLERANCE_PX = 2f;
    public static ArrayList<HyperString> hyperStrings = new ArrayList<>();

    /**
     * Raw platform scroll deltas summed since the last frame drained them, never rounded: a
     * trackpad's many small deltas add up exactly as a wheel's whole notches do.
     */
    public double queuedScrollDelta = 0;

    /**
     * Raw horizontal scroll deltas summed the same way, positive toward the left as GLFW reports
     * them; only a region that {@link PointerRegion#claimsWheel claims the wheel} uses them.
     */
    public double queuedScrollDeltaX = 0;

    /** Window position of the left press the dispatcher let through, or {@code null} while none is held. */
    public Vector2f heldPressPosition;

    /** Whether the held left press has moved past {@link #CLICK_DRAG_THRESHOLD_PX}; kept until release. */
    public boolean heldPressDragged;

    /** Frames until the wheel zoom counts as over; see {@link #isZooming()}. */
    public int zoomSettleFramesLeft;

    /** Window position of the press of any button this trap holds, the click test's origin; null for none. */
    public Vector2f pressPosition;

    /** How the scene's camera answers drags, the wheel and held keys; {@code null} for a still camera. */
    public CameraController controller;

    /** Whether automation pinned the hover at {@link #automationHoverX}, re-hovered every frame. */
    public boolean automationHoverLocked;
    public float automationHoverX;
    public float automationHoverY;

    /** Whether automation is delivering input, which a pinned hover survives. */
    public boolean automationInputInProgress;

    public int lastX = Integer.MIN_VALUE;
    public int lastY = Integer.MIN_VALUE;
    public Camera camera;
    public Scene canvas;
    public boolean active = true;
    public float normalizedPosX;
    public float normalizedPosY;
    public long timeLastScroll;

    /**
     * Build a mouse handler over a camera and scene, its camera still until a controller is set.
     *
     * @param camera camera whose framebuffer mapping positions the cursor
     * @param canvas owning scene, which hears hovers and clicks on its view
     */
    public MouseTrap(Camera camera, Scene canvas) {
        this.camera = camera;
        this.canvas = canvas;
    }

    /**
     * Record a high-level mouse action with the platform's automation backend, if it has one.
     *
     * @param action    action type tag
     * @param keyValues alternating payload keys and values
     */
    public static void recordAbstractAction(String action, Object... keyValues) {
        AutomationBackend automation = Platforms.get().automation();
        if (automation == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            payload.put((String) keyValues[i], keyValues[i + 1]);
        }
        automation.recordAbstractActionMap(action, payload);
    }

    /**
     * End the held press without a click: the next drag reaches the camera only after a new press.
     * The dispatcher calls it directly when the scene's tool took the click.
     */
    public void mouseReleased() {
        pressPosition = null;
    }

    /**
     * Add the platform's scroll delta, untruncated, to {@link #queuedScrollDelta} and stamp the
     * time the queue decays from when the camera leaves the wheel to the panes.
     *
     * @param y vertical scroll delta, 1.0 per wheel notch, fractions from a trackpad
     */
    public void scrollCallback(double y) {
        if (canvas != null) {
            Platforms.init(canvas.platform.getPlatformID());
        }
        if (!active) {
            return;
        }
        queuedScrollDelta += y;
        timeLastScroll = System.currentTimeMillis();
        recordAbstractAction("mouse_scroll", "delta", y);
    }

    /**
     * The platform's two-axis scroll: queue the horizontal delta in {@link #queuedScrollDeltaX},
     * then the vertical one through {@link #scrollCallback(double)}.
     *
     * @param x horizontal scroll delta, positive toward the left, fractions from a trackpad
     * @param y vertical scroll delta, positive away from the user
     */
    public void scrollCallback(double x, double y) {
        queuedScrollDeltaX += x;
        scrollCallback(y);
    }

    /**
     * Whether the camera controller puts the wheel to a use of its own, such as a zoom; otherwise
     * the dispatcher hands it to the pane under the cursor.
     *
     * @return true while the controller uses the wheel
     */
    public boolean usesWheel() {
        return controller != null && controller.usesWheel();
    }

    /**
     * Whether a left press this trap took is dragging its view, held still or moving: true from
     * its first move past {@link #CLICK_DRAG_THRESHOLD_PX} until its release.
     *
     * @return true while such a drag is in progress
     */
    public boolean isCameraDragging() {
        return heldPressDragged;
    }

    /**
     * Whether a wheel zoom is in progress. GLFW reports no scroll start or end, so the zoom lasts
     * from the frame a wheel delta reaches this trap until {@link #ZOOM_SETTLE_FRAMES} frames pass
     * with none; a trackpad gesture, a stream of small deltas, ends that many frames after its last.
     *
     * @return true from a frame that zoomed until the settle frames have passed
     */
    public boolean isZooming() {
        return zoomSettleFramesLeft > 0;
    }

    /**
     * Track the held left press from a button event the dispatcher hands this trap: a press is
     * held undragged, a release ends it.
     *
     * @param button button index
     * @param action {@code ACTION_PRESS} or {@code ACTION_RELEASE}
     */
    public void trackPress(int button, int action) {
        if (button != Keys.MOUSE_BUTTON_LEFT) {
            return;
        }
        heldPressDragged = false;
        heldPressPosition = action == ACTION_PRESS && active ? new Vector2f(lastX, lastY) : null;
    }

    /**
     * Track a cursor move with the left press held: past {@link #CLICK_DRAG_THRESHOLD_PX} from
     * the press it becomes a drag, which stays one until the release.
     *
     * @param x cursor x in window pixels
     * @param y cursor y in window pixels
     */
    public void trackDrag(float x, float y) {
        if (heldPressPosition != null && heldPressPosition.distance(x, y) > CLICK_DRAG_THRESHOLD_PX) {
            heldPressDragged = true;
        }
    }

    /**
     * Count one frame of the zoom state, before the trap drains its wheel delta: a delta it zooms
     * with restarts the settle count, any other frame counts it down.
     */
    public void trackZoomFrame() {
        if (queuedScrollDelta != 0 && usesWheel() && active) {
            zoomSettleFramesLeft = ZOOM_SETTLE_FRAMES;
        } else if (zoomSettleFramesLeft > 0) {
            zoomSettleFramesLeft--;
        }
    }

    /**
     * Re-bind this trap to a different canvas (used during scene switches).
     *
     * @param canvas3d new owning canvas
     */
    public void setCanvas(Scene canvas3d) {
        this.canvas = canvas3d;
    }

    /**
     * A move with no press dragging: the cursor position updates, the controller hears the travel,
     * then the scene's view and the overlay text's links hover what is under the cursor. Real
     * motion past {@link #HOVER_LOCK_TOLERANCE_PX} drops an automation hover pin.
     *
     * @param x cursor x in window pixels
     * @param y cursor y in window pixels
     */
    public void mousePos(float x, float y) {
        if (!active) {
            return;
        }
        dropHoverLockOnMotion(x, y);
        int previousX = lastX;
        int previousY = lastY;
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        lastX = (int) x;
        lastY = (int) y;
        if (controller != null && previousX != Integer.MIN_VALUE) {
            controller.pointerMoved(previousX, previousY, x, y);
        }
        if (canvas != null) {
            canvas.viewHovered(x, y);
        }
        hoverLinks();
    }

    /**
     * A move with the press held past the click threshold: the controller drags the camera from
     * the previous cursor sample.
     *
     * @param x cursor x in window pixels
     * @param y cursor y in window pixels
     */
    public void mouseDragged(float x, float y) {
        if (!active) {
            return;
        }
        if (lastX == Integer.MIN_VALUE || lastY == Integer.MIN_VALUE) {
            mousePos(x, y);
            return;
        }
        dropHoverLockOnMotion(x, y);
        if (controller != null) {
            controller.pointerDragged(lastX, lastY, x, y);
        }
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        lastX = (int) x;
        lastY = (int) y;
    }

    /**
     * Pin the hover at {@code (x, y)} for automation: re-hovered every frame until
     * {@link #clearAutomationHoverLock()} or real motion past {@link #HOVER_LOCK_TOLERANCE_PX}.
     *
     * @param x window x to pin the hover at
     * @param y window y to pin the hover at
     */
    public void setAutomationHoverLock(float x, float y) {
        automationHoverLocked = true;
        automationHoverX = x;
        automationHoverY = y;
        mousePos(x, y);
    }

    /**
     * Release the automation hover pin so live mouse movement controls hover again.
     */
    public void clearAutomationHoverLock() {
        automationHoverLocked = false;
    }

    /**
     * Per-frame: a controller that uses the wheel takes the whole queued delta; otherwise a delta
     * older than {@link #SCROLL_DECAY_MS} is dropped and a fresh one refreshes the links' hover.
     * Then a pinned automation hover is re-applied.
     *
     * @param shiftMod speed multiplier (unused; the controller's keys take it)
     */
    public void paintUpdate(float shiftMod) {
        if (!active) {
            return;
        }
        if (usesWheel()) {
            if (queuedScrollDelta != 0) {
                double delta = queuedScrollDelta;
                queuedScrollDelta = 0;
                controller.wheelScrolled(delta);
            }
        } else {
            if (System.currentTimeMillis() - timeLastScroll > SCROLL_DECAY_MS) {
                queuedScrollDelta = 0;
            }
            if (queuedScrollDelta != 0) {
                hoverLinks();
                queuedScrollDelta = 0;
            }
        }
        if (automationHoverLocked) {
            mousePos(automationHoverX, automationHoverY);
        }
    }

    /**
     * Drop the automation hover pin when the cursor, moved by anything but automation, leaves it
     * by more than {@link #HOVER_LOCK_TOLERANCE_PX}, so the pin never re-hovers under a real move
     * or a drag.
     *
     * @param x cursor x in window pixels
     * @param y cursor y in window pixels
     */
    private void dropHoverLockOnMotion(float x, float y) {
        if (automationHoverLocked && !automationInputInProgress
                && (Math.abs(x - automationHoverX) > HOVER_LOCK_TOLERANCE_PX
                        || Math.abs(y - automationHoverY) > HOVER_LOCK_TOLERANCE_PX)) {
            automationHoverLocked = false;
        }
    }

    /**
     * Re-run the overlay text's hover at the cursor: every link drops its hover, then the one under
     * the cursor takes it.
     */
    private void hoverLinks() {
        for (HyperString h : hyperStrings) {
            h.calculateClearHover(normalizedPosX, normalizedPosY);
        }
        for (HyperString h : hyperStrings) {
            h.calculateHover(normalizedPosX, normalizedPosY);
        }
    }

    /**
     * Platform mouse-button entry point: a left press starts the controller's drag, and a release
     * near its press is a click for the scene's view, the overlay links, then the first
     * {@link PointerDispatcher#clickRegions click region} holding it.
     *
     * @param button button index
     * @param action {@code ACTION_PRESS} or {@code ACTION_RELEASE}
     * @param mods modifier-key bitmask
     */
    public void mouseButton(int button, int action, int mods) {
        if (canvas != null) {
            Platforms.init(canvas.platform.getPlatformID());
        }
        if (!active) {
            return;
        }
        float x = lastX;
        float y = lastY;
        if (action == ACTION_PRESS) {
            pressPosition = new Vector2f(x, y);
            if (button == Keys.MOUSE_BUTTON_LEFT && controller != null) {
                controller.pointerPressed(x, y, mods);
            }
        } else if (action == ACTION_RELEASE && pressPosition != null) {
            boolean click = pressPosition.distance(x, y) <= CLICK_DRAG_THRESHOLD_PX;
            pressPosition = null;
            if (!click) {
                return;
            }
            normalizedPosX = camera.getNormalizePosX(x);
            normalizedPosY = camera.getNormalizePosY(y);
            recordAbstractAction("mouse_click",
                    "button", button,
                    "xPx", x,
                    "yPx", y,
                    "xCoord", normalizedPosX,
                    "yCoord", normalizedPosY,
                    "xNorm", x / Math.max(MIN_WINDOW_DIMENSION, Platforms.get().getWindowWidth()),
                    "yNorm", y / Math.max(MIN_WINDOW_DIMENSION, Platforms.get().getWindowHeight()));
            if (canvas != null) {
                canvas.viewClicked(x, y, button);
            }
            for (HyperString h : hyperStrings) {
                h.click(normalizedPosX, normalizedPosY);
            }
            PointerDispatcher pointer = PointerDispatcher.current();
            for (int index = 0; index < pointer.clickRegions.size(); index++) {
                Bounds bounds = pointer.clickBounds.get(index);
                bounds.recalc();
                if (normalizedPosX >= bounds.offsetX && normalizedPosX <= bounds.offsetX + bounds.viewWidth
                        && normalizedPosY >= bounds.offsetY
                        && normalizedPosY <= bounds.offsetY + bounds.viewHeight) {
                    pointer.clickRegions.get(index).onClick(button);
                    return;
                }
            }
        }
    }

    /**
     * Mouse-motion entry point: a drag past the {@link #CLICK_DRAG_THRESHOLD_PX}-pixel deadzone
     * with the left button held goes to {@link #mouseDragged}, any other motion to
     * {@link #mousePos}.
     *
     * @param window platform window handle (for GL mouse-state polling)
     * @param x cursor x in window pixels
     * @param y cursor y in window pixels
     */
    public void moveOrDrag(long window, float x, float y) {
        if (canvas != null) {
            Platforms.init(canvas.platform.getPlatformID());
        }
        if (!active) {
            return;
        }
        boolean leftDown = Platforms.gl().getMouseButton(window, MouseButtons.MOUSE_BUTTON_LEFT);
        if (leftDown && pressPosition != null && pressPosition.distance(x, y) > CLICK_DRAG_THRESHOLD_PX) {
            mouseDragged(x, y);
        } else {
            mousePos(x, y);
        }
    }
}
