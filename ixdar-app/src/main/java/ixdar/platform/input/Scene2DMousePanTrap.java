package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;

import org.joml.Vector2f;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Clock;
import ixdar.platform.Platforms;

public class Scene2DMousePanTrap extends MouseTrap {
    private Vector2f leftMouseDownPos;

    public Scene2DMousePanTrap(Camera camera, Canvas3D canvas) {
        super(null, camera, canvas);
    }

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
        if (leftDown && leftMouseDownPos != null && currentPos.distance(leftMouseDownPos) > 3f) {
            mouseDragged(x, y);
        } else {
            mousePos(x, y);
        }
    }

    @Override
    public void mouseDragged(float x, float y) {
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        camera.drag((float) (normalizedPosX - startX), (float) (normalizedPosY - startY));
        startX = normalizedPosX;
        startY = normalizedPosY;
    }

    @Override
    public void scrollCallback(double y) {
        Platforms.init(Platforms.get().getPlatformID());
        if (!active) {
            return;
        }
        queuedMouseWheelTicks += (int) (4 * y);
        timeLastScroll = System.currentTimeMillis();
    }

    @Override
    public void paintUpdate(float shiftMod) {
        if (!active) {
            return;
        }
        if (System.currentTimeMillis() - timeLastScroll > 60) {
            queuedMouseWheelTicks = 0;
        }
        if (queuedMouseWheelTicks != 0) {
            boolean zoomIn = queuedMouseWheelTicks < 0;
            camera.onScroll(zoomIn, Clock.deltaTime() * 100f);
            queuedMouseWheelTicks = 0;
        }
    }
}
