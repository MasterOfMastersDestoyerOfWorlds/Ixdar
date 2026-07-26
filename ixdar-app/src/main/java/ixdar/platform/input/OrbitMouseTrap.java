package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;

import org.joml.Vector2f;
import org.joml.Vector3f;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.text.HyperString;
import ixdar.platform.Platforms;

/**
 * Orbit camera controller: left-drag rotates around a fixed target (azimuth + elevation),
 * scroll wheel adjusts orbit distance. Elevation is clamped to roughly +/-85 degrees so the
 * camera never inverts; distance is clamped between {@link #MIN_DISTANCE} and
 * {@link #MAX_DISTANCE}. Used for mesh-node inspection and 3D debug views.
 */
public class OrbitMouseTrap extends MouseTrap {
    public static final float NUM_3 = 3f;
    public static final int NUM_60 = 60;
    public static final float DEFAULT_MIN_DISTANCE = 0.75f;
    public static final float DEFAULT_MAX_DISTANCE = 40.0f;
    private static final float DRAG_RADIANS_PER_PIXEL = 0.01f;
    /** GLFW shift modifier bit; shift + left-drag pans the orbit centre. */
    private static final int MOD_SHIFT = 0x0001;
    /** Orbit-centre pan distance per drag pixel, as a fraction of orbit distance. */
    private static final float PAN_DISTANCE_FRACTION_PER_PIXEL = 0.0015f;
    private static final float MIN_ELEVATION = (float) Math.toRadians(-85.0);
    private static final float MAX_ELEVATION = (float) Math.toRadians(85.0);
    private static final float ZOOM_BASE = 0.97f;

    private final Camera3D orbitCamera;
    private final Vector3f orbitTarget = new Vector3f();
    private final Vector3f homeTarget = new Vector3f();

    private Vector2f leftMouseDownPos;
    private boolean panningDrag;
    private float azimuth = (float) Math.toRadians(90.0);
    private float elevation = (float) Math.toRadians(20.0);
    private float distance = 3.5f;
    private float minDistance = DEFAULT_MIN_DISTANCE;
    private float maxDistance = DEFAULT_MAX_DISTANCE;

    /**
     * Build a trap that orbits {@code camera} around the origin at the default angles and
     * distance, then push that pose to the camera via {@link #applyOrbit()}.
     *
     * @param camera 3D camera to control
     * @param canvas owning canvas
     */
    public OrbitMouseTrap(Camera3D camera, Canvas3D canvas) {
        super(null, camera, canvas);
        this.orbitCamera = camera;
        applyOrbit();
    }

    /**
     * Re-center the orbit on a new world-space point and reapply the camera pose.
     *
     * @param target new orbit center (copied)
     */
    public void setTarget(Vector3f target) {
        orbitTarget.set(target);
        homeTarget.set(target);
        applyOrbit();
    }

    /**
     * Restore the orbit centre to the home point recorded by the last
     * {@link #setTarget(Vector3f)} (the scene's mesh centre), undoing any
     * shift-drag panning. Bound to {@code Ctrl+R} by {@link OrbitCameraKeyGuy}.
     */
    public void resetTarget() {
        orbitTarget.set(homeTarget);
        applyOrbit();
    }

    /**
     * Set the orbit angles and distance directly. Elevation is clamped to
     * [{@link #MIN_ELEVATION}, {@link #MAX_ELEVATION}] and distance to
     * [{@link #MIN_DISTANCE}, {@link #MAX_DISTANCE}].
     *
     * @param azimuthRadians horizontal angle around the target
     * @param elevationRadians vertical angle above the equator
     * @param orbitDistance camera distance from the target
     */
    public void setOrbit(float azimuthRadians, float elevationRadians, float orbitDistance) {
        azimuth = azimuthRadians;
        elevation = clamp(elevationRadians, MIN_ELEVATION, MAX_ELEVATION);
        distance = clamp(orbitDistance, minDistance, maxDistance);
        applyOrbit();
    }

    /**
     * Configure the orbit zoom bounds. {@code distance} clamps to
     * {@code [minDistance, maxDistance]} on every {@link #setOrbit} and
     * scroll-wheel update.
     *
     * @param minDist closest the camera may sit to the orbit target
     * @param maxDist farthest the camera may sit from the orbit target
     */
    public void setDistanceBounds(float minDist, float maxDist) {
        this.minDistance = Math.max(0f, minDist);
        this.maxDistance = Math.max(this.minDistance, maxDist);
        distance = clamp(distance, this.minDistance, this.maxDistance);
        applyOrbit();
    }

    /**
     * Current azimuth angle of the orbit camera.
     *
     * @return current azimuth in radians
     */
    public float getAzimuth() { return azimuth; }
    /**
     * Current elevation angle of the orbit camera.
     *
     * @return current elevation in radians (clamped to MIN/MAX_ELEVATION)
     */
    public float getElevation() { return elevation; }
    /**
     * Current orbit distance from the target.
     *
     * @return current distance from the orbit target
     */
    public float getDistance() { return distance; }

    /**
     * Track left-button press for drag detection. The left button drives orbiting,
     * or panning of the orbit centre when shift is held at press time.
     *
     * @param button button index
     * @param action {@code ACTION_PRESS} or {@code ACTION_RELEASE}
     * @param mods modifier-key bitmask; {@link #MOD_SHIFT} selects pan over orbit
     */
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
            panningDrag = (mods & MOD_SHIFT) != 0;
            mousePressed(x, y);
        } else if (action == ACTION_RELEASE && button == MOUSE_BUTTON_LEFT) {
            boolean wasClick = leftMouseDownPos != null && leftMouseDownPos.distance(x, y) <= NUM_3;
            leftMouseDownPos = null;
            panningDrag = false;
            if (wasClick && canvas != null && canvas.camera2D != null) {
                Camera2D overlayCamera = canvas.camera2D;
                float overlayX = overlayCamera.getNormalizePosX(x);
                float overlayY = overlayCamera.getNormalizePosY(y);
                for (HyperString hyperString : hyperStrings) {
                    hyperString.click(overlayX, overlayY);
                }
            }
        }
    }

    /**
     * Route mouse motion to {@link #mouseDragged} while the left button is held past the
     * {@link #NUM_3}-pixel deadzone, otherwise to {@link #mousePos}.
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
        boolean leftDown = Platforms.gl().getMouseButton(window, MouseButtons.MOUSE_BUTTON_LEFT);
        Vector2f currentPos = new Vector2f(x, y);
        if (leftDown && leftMouseDownPos != null && currentPos.distance(leftMouseDownPos) > NUM_3) {
            mouseDragged(x, y);
        } else {
            mousePos(x, y);
        }
    }

    /**
     * Update normalized cursor position and last pixel coordinates without changing orbit angles.
     *
     * @param x cursor x in window coordinates
     * @param y cursor y in window coordinates
     */
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

    /**
     * Left-drag with shift held pans the orbit centre in the screen plane (see
     * {@link #panTarget(float, float)}); otherwise apply the per-pixel azimuth /
     * elevation deltas (scaled by {@link #DRAG_RADIANS_PER_PIXEL}) and reapply the
     * camera pose.
     *
     * @param x cursor x in window coordinates
     * @param y cursor y in window coordinates
     */
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
        if (panningDrag) {
            panTarget(dx, dy);
        } else {
            azimuth += dx * DRAG_RADIANS_PER_PIXEL;
            elevation = clamp(elevation + dy * DRAG_RADIANS_PER_PIXEL, MIN_ELEVATION, MAX_ELEVATION);
        }
        mousePos(x, y);
        applyOrbit();
    }

    /**
     * Translate the orbit centre in the screen plane opposite the mouse travel, so
     * the grabbed scene follows the cursor. The screen right/up basis is derived from
     * the current orbit angles; the step scales with orbit distance so panning feels
     * consistent at any zoom.
     *
     * @param dx cursor x delta in pixels since the last drag sample
     * @param dy cursor y delta in pixels since the last drag sample
     */
    private void panTarget(float dx, float dy) {
        float cosElevation = (float) Math.cos(elevation);
        Vector3f forward = new Vector3f(
                -cosElevation * (float) Math.cos(azimuth),
                -(float) Math.sin(elevation),
                -cosElevation * (float) Math.sin(azimuth));
        Vector3f right = new Vector3f();
        forward.cross(orbitCamera.worldUp, right).normalize();
        Vector3f up = new Vector3f();
        right.cross(forward, up).normalize();
        float step = distance * PAN_DISTANCE_FRACTION_PER_PIXEL;
        orbitTarget.add(right.mul(-dx * step)).add(up.mul(dy * step));
    }

    /**
     * Forward to {@link MouseTrap#scrollCallback(double)} which queues ticks for
     * {@link #paintUpdate} to consume as zoom changes.
     *
     * @param y vertical scroll delta
     */
    @Override
    public void scrollCallback(double y) {
        if (!active) {
            return;
        }
        super.scrollCallback(y);
    }

    /**
     * Per-frame: drain queued scroll ticks into a multiplicative distance change
     * ({@link #ZOOM_BASE}^ticks, then clamped) and reapply the camera pose.
     *
     * @param shiftMod speed multiplier (currently unused)
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
            distance = clamp(distance * (float) Math.pow(ZOOM_BASE, queuedMouseWheelTicks), minDistance, maxDistance);
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
