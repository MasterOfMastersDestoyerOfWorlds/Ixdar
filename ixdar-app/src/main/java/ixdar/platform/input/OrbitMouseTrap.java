package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;

import org.joml.Vector2f;
import org.joml.Vector3f;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera3D;
import ixdar.platform.Platforms;

public class OrbitMouseTrap extends MouseTrap {
    private static final float DRAG_RADIANS_PER_PIXEL = 0.01f;
    private static final float MIN_ELEVATION = (float) Math.toRadians(-85.0);
    private static final float MAX_ELEVATION = (float) Math.toRadians(85.0);
    private static final float MIN_DISTANCE = 0.75f;
    private static final float MAX_DISTANCE = 40.0f;
    private static final float ZOOM_BASE = 0.97f;

    private final Camera3D orbitCamera;
    private final Vector3f orbitTarget = new Vector3f();

    private Vector2f leftMouseDownPos;
    private float azimuth = (float) Math.toRadians(90.0);
    private float elevation = (float) Math.toRadians(20.0);
    private float distance = 3.5f;

    public OrbitMouseTrap(Camera3D camera, Canvas3D canvas) {
        super(null, camera, canvas);
        this.orbitCamera = camera;
        applyOrbit();
    }

    public void setTarget(Vector3f target) {
        orbitTarget.set(target);
        applyOrbit();
    }

    public void setOrbit(float azimuthRadians, float elevationRadians, float orbitDistance) {
        azimuth = azimuthRadians;
        elevation = clamp(elevationRadians, MIN_ELEVATION, MAX_ELEVATION);
        distance = clamp(orbitDistance, MIN_DISTANCE, MAX_DISTANCE);
        applyOrbit();
    }

    public float getAzimuth() { return azimuth; }
    public float getElevation() { return elevation; }
    public float getDistance() { return distance; }

    @Override
    public void mouseButton(int button, int action, int mods) {
        Platforms.init(Platforms.get().getPlatformID());
        if (!active) {
            return;
        }
        float x = lastX;
        float y = lastY;
        if (action == ACTION_PRESS && button == MOUSE_BUTTON_LEFT) {
            leftMouseDownPos = new Vector2f(x, y);
            mousePressed(x, y);
        } else if (action == ACTION_RELEASE && button == MOUSE_BUTTON_LEFT) {
            leftMouseDownPos = null;
        }
    }

    @Override
    public void moveOrDrag(long window, float x, float y) {
        Platforms.init(Platforms.get().getPlatformID());
        if (!active) {
            return;
        }
        boolean leftDown = Platforms.gl().getMouseButton(window, MouseButtons.MOUSE_BUTTON_LEFT);
        Vector2f currentPos = new Vector2f(x, y);
        if (leftDown && leftMouseDownPos != null && currentPos.distance(leftMouseDownPos) > 3f) {
            mouseDragged(x, y);
        } else {
            mousePos(x, y);
        }
    }

    @Override
    public void mousePos(float x, float y) {
        if (!active) {
            return;
        }
        normalizedPosX = camera.getNormalizePosX(x);
        normalizedPosY = camera.getNormalizePosY(y);
        lastX = (int) x;
        lastY = (int) y;
    }

    @Override
    public void mouseDragged(float x, float y) {
        if (!active) {
            return;
        }
        if (lastX == Integer.MIN_VALUE || lastY == Integer.MIN_VALUE) {
            mousePos(x, y);
            return;
        }
        float dx = x - lastX;
        float dy = y - lastY;
        azimuth += dx * DRAG_RADIANS_PER_PIXEL;
        elevation = clamp(elevation + dy * DRAG_RADIANS_PER_PIXEL, MIN_ELEVATION, MAX_ELEVATION);
        mousePos(x, y);
        applyOrbit();
    }

    @Override
    public void scrollCallback(double y) {
        if (!active) {
            return;
        }
        super.scrollCallback(y);
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
            distance = clamp(distance * (float) Math.pow(ZOOM_BASE, queuedMouseWheelTicks), MIN_DISTANCE, MAX_DISTANCE);
            queuedMouseWheelTicks = 0;
            applyOrbit();
        }
    }

    private void applyOrbit() {
        float cosElevation = (float) Math.cos(elevation);
        orbitCamera.position.set(
                orbitTarget.x + distance * cosElevation * (float) Math.cos(azimuth),
                orbitTarget.y + distance * (float) Math.sin(elevation),
                orbitTarget.z + distance * cosElevation * (float) Math.sin(azimuth));
        orbitCamera.target.set(orbitTarget);
        orbitCamera.up.set(orbitCamera.worldUp);
        orbitCamera.updateViewFirstPerson();
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
