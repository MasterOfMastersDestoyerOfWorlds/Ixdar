package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;

import org.joml.Vector2f;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Clock;
import ixdar.platform.Platforms;

public class Scene2DMousePanTrap extends MouseTrap {
    public static final float NUM_3 = 3f;
    public static final int NUM_4 = 4;
    public static final int NUM_60 = 60;
    public static final float NUM_100 = 100f;
    private Vector2f leftMouseDownPos;

    /**
     * TODO: document {@code Scene2DMousePanTrap}.
     *
     * @param camera TODO: describe
     * @param canvas TODO: describe
     */
    public Scene2DMousePanTrap(Camera camera, Canvas3D canvas) {
        super(null, camera, canvas);
    }

    /**
     * TODO: document {@code mouseButton}.
     *
     * @param button TODO: describe
     * @param action TODO: describe
     * @param mods TODO: describe
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
     * TODO: document {@code moveOrDrag}.
     *
     * @param window TODO: describe
     * @param x TODO: describe
     * @param y TODO: describe
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
     * TODO: document {@code mouseDragged}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
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
     * TODO: document {@code scrollCallback}.
     *
     * @param y TODO: describe
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
     * TODO: document {@code paintUpdate}.
     *
     * @param shiftMod TODO: describe
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
