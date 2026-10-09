package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;

import java.util.function.BooleanSupplier;

import org.joml.Vector2f;
import org.joml.Vector3f;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.text.HyperString;
import ixdar.platform.Platforms;

/**
 * Orbit camera: left-drag turns azimuth and elevation round a target, the wheel zooms. Elevation
 * runs through the poles, the up vector following the meridian, so top views never flip.
 */
public class OrbitMouseTrap extends MouseTrap {
    public static final float CLICK_DRAG_THRESHOLD_PX = 3f;
    public static final float DEFAULT_MIN_DISTANCE = 0.75f;
    public static final float DEFAULT_MAX_DISTANCE = 40.0f;
    public static final float DEFAULT_FRAME_PADDING = 0.15f;
    public static final float MINIMUM_FRAME_RADIUS = 1e-4f;
    public static final float FRAME_DISTANCE_FLOOR_FRACTION = 0.01f;
    public static final float FRAME_DISTANCE_CEILING_MULTIPLE = 8f;
    private static final float DRAG_RADIANS_PER_PIXEL = 0.01f;
    private static final int MOD_SHIFT = 0x0001;
    private static final float PAN_DISTANCE_FRACTION_PER_PIXEL = 0.0015f;
    private static final float ZOOM_BASE = 0.97f;
    public ClickHandler toolClick;

    /**
     * Asked on every left press whether a tool takes the drag that may follow, so the drag moves
     * the tool's handle instead of orbiting; {@code null} leaves every drag to the camera.
     */
    public BooleanSupplier toolGrab;

    /** Told when a drag {@link #toolGrab} took ends, before any click the release makes. */
    public Runnable toolRelease;

    private boolean toolDragging;

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
     * Aim the orbit at a point without moving the home centre, so {@code Ctrl+R} still returns to
     * the mesh centre afterwards.
     *
     * @param target new orbit centre (copied)
     */
    public void moveTarget(Vector3f target) {
        orbitTarget.set(target);
        applyOrbit();
    }

    /**
     * Set the orbit angles and distance directly. Elevation is wrapped into (-pi, pi], so +-pi/2
     * is a true top or bottom view and a larger angle carries on over the pole; distance is
     * clamped to the bounds {@link #setDistanceBounds} set.
     *
     * @param azimuthRadians horizontal angle around the target
     * @param elevationRadians vertical angle above the equator
     * @param orbitDistance camera distance from the target
     */
    public void setOrbit(float azimuthRadians, float elevationRadians, float orbitDistance) {
        azimuth = azimuthRadians;
        elevation = wrapAngle(elevationRadians);
        distance = clamp(orbitDistance, minDistance, maxDistance);
        applyOrbit();
    }

    /**
     * Aim at a sphere and pull back until it, grown by the padding, fills the narrower of the two
     * view angles, keeping the orbit angles. The zoom bounds widen to reach it, never narrow.
     *
     * @param center      centre of the sphere to frame; becomes the orbit centre via
     *                    {@link #moveTarget}
     * @param radius      radius of the sphere, floored at {@link #MINIMUM_FRAME_RADIUS}
     * @param padding     margin as a fraction of the radius
     * @param aspectRatio viewport width over height
     * @return the distance the fit asked for, before the distance clamp
     */
    public float frame(Vector3f center, float radius, float padding, float aspectRatio) {
        float paddedRadius = Math.max(MINIMUM_FRAME_RADIUS, radius) * (1f + Math.max(0f, padding));
        float halfFieldOfViewY = (float) Math.toRadians(orbitCamera.fov) * 0.5f;
        float halfFieldOfViewX = (float) Math.atan(Math.tan(halfFieldOfViewY) * aspectRatio);
        float fitDistance = paddedRadius
                / (float) Math.sin(Math.min(halfFieldOfViewY, halfFieldOfViewX));
        minDistance = Math.min(minDistance, fitDistance * FRAME_DISTANCE_FLOOR_FRACTION);
        maxDistance = Math.max(maxDistance, fitDistance * FRAME_DISTANCE_CEILING_MULTIPLE);
        moveTarget(center);
        setOrbit(azimuth, elevation, fitDistance);
        return fitDistance;
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
     * @return current elevation in radians, in (-pi, pi]; beyond +-pi/2 the camera is over a pole
     */
    public float getElevation() { return elevation; }
    /**
     * Current orbit distance from the target.
     *
     * @return current distance from the orbit target
     */
    public float getDistance() { return distance; }

    /**
     * Current orbit centre, for saving a pose across a reload.
     *
     * @param out receives the orbit centre
     * @return {@code out}, set to the current orbit centre
     */
    public Vector3f getTarget(Vector3f out) { return out.set(orbitTarget); }

    /**
     * Closest the camera may sit to the orbit target.
     *
     * @return current minimum orbit distance
     */
    public float getMinDistance() { return minDistance; }

    /**
     * Farthest the camera may sit from the orbit target.
     *
     * @return current maximum orbit distance
     */
    public float getMaxDistance() { return maxDistance; }

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
            toolDragging = !panningDrag && toolGrab != null && toolGrab.getAsBoolean();
            mousePressed(x, y);
        } else if (action == ACTION_RELEASE && button == MOUSE_BUTTON_LEFT) {
            boolean wasClick = leftMouseDownPos != null && leftMouseDownPos.distance(x, y) <= CLICK_DRAG_THRESHOLD_PX;
            leftMouseDownPos = null;
            panningDrag = false;
            if (toolDragging) {
                toolDragging = false;
                if (toolRelease != null) {
                    toolRelease.run();
                }
            }
            if (wasClick && toolClick != null) {
                toolClick.onClick(button);
                return;
            }
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
     * {@link #CLICK_DRAG_THRESHOLD_PX}-pixel deadzone, otherwise to {@link #mousePos}.
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
        if (leftDown && leftMouseDownPos != null && currentPos.distance(leftMouseDownPos) > CLICK_DRAG_THRESHOLD_PX) {
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
        if (toolDragging) {
            mousePos(x, y);
            return;
        }
        if (panningDrag) {
            panTarget(dx, dy);
        } else {
            // Over a pole the view is upside down, so the azimuth turns the other way to keep
            // following the cursor.
            float upright = Math.cos(elevation) < 0.0 ? -1f : 1f;
            azimuth += upright * dx * DRAG_RADIANS_PER_PIXEL;
            elevation = wrapAngle(elevation + dy * DRAG_RADIANS_PER_PIXEL);
        }
        mousePos(x, y);
        applyOrbit();
    }

    /**
     * A held drag is the camera's unless {@link #toolGrab} took its press, which moves the tool's
     * handle instead of orbiting.
     *
     * @return true while a drag the tool did not grab is in progress
     */
    @Override
    public boolean isCameraDragging() {
        return !toolDragging && super.isCameraDragging();
    }

    /**
     * Translate the orbit centre in the screen plane opposite the mouse travel, so
     * the grabbed scene follows the cursor. The screen basis is the camera's own up and the right
     * it makes with the view direction, sound at the poles too; the step scales with distance.
     *
     * @param dx cursor x delta in pixels since the last drag sample
     * @param dy cursor y delta in pixels since the last drag sample
     */
    private void panTarget(float dx, float dy) {
        Vector3f forward = new Vector3f(orbitTarget).sub(orbitCamera.position).normalize();
        Vector3f right = forward.cross(orbitCamera.up, new Vector3f()).normalize();
        Vector3f up = new Vector3f(orbitCamera.up);
        float step = distance * PAN_DISTANCE_FRACTION_PER_PIXEL;
        orbitTarget.add(right.mul(-dx * step)).add(up.mul(dy * step));
    }

    /**
     * Forward to {@link MouseTrap#scrollCallback(double)}, which sums the raw delta for
     * {@link #paintUpdate}.
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
     * Per-frame: zoom by {@link #ZOOM_BASE} to the power of {@link #SCROLL_TICKS_PER_UNIT} times
     * the whole queued delta, however old, and empty the queue, nothing rounded.
     *
     * @param shiftMod speed multiplier (currently unused)
     */
    @Override
    public void paintUpdate(float shiftMod) {
        if (!active || queuedScrollDelta == 0) {
            return;
        }
        double delta = queuedScrollDelta;
        queuedScrollDelta = 0;
        distance = clamp(distance * (float) Math.pow(ZOOM_BASE, SCROLL_TICKS_PER_UNIT * delta),
                minDistance, maxDistance);
        applyOrbit();
    }

    /**
     * The wheel zooms the orbit.
     *
     * @return true
     */
    @Override
    public boolean usesWheel() {
        return true;
    }

    /**
     * Place the camera on the orbit sphere and point it at the centre. Up is the meridian's
     * tangent toward rising elevation, world up at the equator, never parallel to the view.
     */
    private void applyOrbit() {
        float cosElevation = (float) Math.cos(elevation);
        float sinElevation = (float) Math.sin(elevation);
        float cosAzimuth = (float) Math.cos(azimuth);
        float sinAzimuth = (float) Math.sin(azimuth);
        orbitCamera.position.set(
                orbitTarget.x + distance * cosElevation * cosAzimuth,
                orbitTarget.y + distance * sinElevation,
                orbitTarget.z + distance * cosElevation * sinAzimuth);
        orbitCamera.target.set(orbitTarget);
        orbitCamera.up.set(-sinElevation * cosAzimuth, cosElevation, -sinElevation * sinAzimuth);
        orbitCamera.updateViewFirstPerson();
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * An angle wrapped into (-pi, pi].
     *
     * @param radians any angle
     * @return the same direction, in (-pi, pi]
     */
    private static float wrapAngle(float radians) {
        double wrapped = Math.IEEEremainder(radians, 2.0 * Math.PI);
        return (float) (wrapped <= -Math.PI ? wrapped + 2.0 * Math.PI : wrapped);
    }
}
