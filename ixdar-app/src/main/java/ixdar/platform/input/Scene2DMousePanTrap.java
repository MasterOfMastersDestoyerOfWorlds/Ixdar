package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;

import org.joml.Vector2f;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Clock;
import ixdar.platform.Platforms;

/**
 * Generic 2D pan / zoom mouse handler — left-drag pans the camera, scroll zooms. Used by
 * scenes that need {@link MouseTrap}'s plumbing without {@code MainScene}-specific click logic.
 */
public class Scene2DMousePanTrap extends MouseTrap {
    public static final float NUM_3 = 3f;
    public static final int NUM_4 = 4;
    public static final int NUM_60 = 60;
    public static final float NUM_100 = 100f;
    private Vector2f leftMouseDownPos;

    /**
     * @param camera camera to pan/zoom
     * @param canvas owning canvas (for platform-id resolution)
     */
    public Scene2DMousePanTrap(Camera camera, Canvas3D canvas) {
        super(null, camera, canvas);
    }

    /**
     * Track press-down position for drag detection; release clears it.
     *
     * @param button button index
     * @param action {@code ACTION_PRESS} or {@code ACTION_RELEASE}
     * @param mods modifier-key bitmask (unused)
     */
    @Override
    public void mouseButton(int button, int action, int mods) {
        Platforms.init(Platforms.get().getPlatformID());
        if (!active) {
            return;
        }
        float x = lastX;
        float y = lastY;
        if (action == ACTION_PRESS) {
            leftMouseDownPos = new Vector2f(x, y);
            mousePressed(x, y);
        } else if (action == ACTION_RELEASE) {
            leftMouseDownPos = null;
        }
    }

    /**
     * Dispatch to {@link #mouseDragged} when the left button is held and the cursor has moved
     * more than {@link #NUM_3} pixels from the press point, otherwise treat as a plain move.
     *
     * @param window platform window handle
     * @param x cursor x in window coordinates
     * @param y cursor y in window coordinates
     */
    @Override
    public void moveOrDrag(long window, float x, float y) {
        Platforms.init(Platforms.get().getPlatformID());
        if (!active) {
            return;
        }
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        lastX = (int) x;
        lastY = (int) y;
        boolean leftDown = Platforms.gl().getMouseButton(window, MouseButtons.MOUSE_BUTTON_LEFT);
        Vector2f currentPos = new Vector2f(x, y);
        if (leftDown && leftMouseDownPos != null && currentPos.distance(leftMouseDownPos) > NUM_3) {
            mouseDragged(x, y);
        } else {
            mousePos(x, y);
        }
    }

    /**
     * Pan the camera by the normalized-coordinate delta since the previous drag sample.
     *
     * @param x cursor x in window coordinates
     * @param y cursor y in window coordinates
     */
    @Override
    public void mouseDragged(float x, float y) {
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        camera.drag((float) (normalizedPosX - startX), (float) (normalizedPosY - startY));
        startX = normalizedPosX;
        startY = normalizedPosY;
    }

    /**
     * Queue scroll ticks (applied during {@link #paintUpdate}); ticks decay if no further scroll
     * arrives within {@link #NUM_60} ms.
     *
     * @param y vertical scroll delta
     */
    @Override
    public void scrollCallback(double y) {
        Platforms.init(Platforms.get().getPlatformID());
        if (!active) {
            return;
        }
        queuedMouseWheelTicks += (int) (NUM_4 * y);
        timeLastScroll = System.currentTimeMillis();
    }

    /**
     * Per-frame: drain queued scroll ticks into a camera zoom call (sign of accumulated ticks
     * picks zoom-in vs zoom-out).
     *
     * @param shiftMod speed multiplier (currently unused; preserved for API parity)
     */
    @Override
    public void paintUpdate(float shiftMod) {
        if (!active) {
            return;
        }
        if (System.currentTimeMillis() - timeLastScroll > NUM_60) {
            queuedMouseWheelTicks = 0;
        }
        if (queuedMouseWheelTicks != 0) {
            boolean zoomIn = queuedMouseWheelTicks < 0;
            camera.onScroll(zoomIn, Clock.deltaTime() * NUM_100);
            queuedMouseWheelTicks = 0;
        }
    }
}
