package ixdar.graphics.cameras;

import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import ixdar.geometry.tsp.points.PointSet;
import ixdar.platform.Clock;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.Platforms;
import ixdar.platform.input.KeyNames;

/**
 * Perspective 3D camera: a first-person position plus yaw and pitch, or an orbit round a target
 * whose angles, distance and zoom bounds it holds. As a {@link CameraController} it orbits: drag
 * turns, shift-drag pans the target, the wheel zooms.
 */
public class Camera3D implements Camera, CameraController {
    public static final String UNIMPLEMENTED_METHOD_SCREENTRANSFORMX = "Unimplemented method 'screenTransformX'";
    public static final String UNIMPLEMENTED_METHOD_SCREENTRANSFORMY = "Unimplemented method 'screenTransformY'";
    public static final float DEFAULT_FOV = 45f;
    public static final float WHEEL_NOTCH_SCALE = 100f;
    public static final float MAX_PITCH = 89f;
    public static final float MOUSE_SENSITIVITY = 0.1f;
    public static final float DEFAULT_MIN_DISTANCE = 0.75f;
    public static final float DEFAULT_MAX_DISTANCE = 40.0f;
    public static final float DEFAULT_FRAME_PADDING = 0.15f;
    public static final float MINIMUM_FRAME_RADIUS = 1e-4f;
    public static final float FRAME_DISTANCE_FLOOR_FRACTION = 0.01f;
    public static final float FRAME_DISTANCE_CEILING_MULTIPLE = 8f;

    private static final float DEFAULT_MOVEMENT_SPEED = 2.5f;
    private static final float ZOOM_SPEED = 1f;
    private static final float DRAG_RADIANS_PER_PIXEL = 0.01f;
    private static final float PAN_DISTANCE_FRACTION_PER_PIXEL = 0.0015f;
    private static final float ORBIT_ZOOM_BASE = 0.97f;

    /** The point the orbit turns round; {@link #target} follows it while orbiting. */
    public final Vector3f orbitTarget = new Vector3f();

    /** The orbit centre {@link #resetTarget} returns to, set by {@link #setTarget}. */
    public final Vector3f homeTarget = new Vector3f();

    /** Orbit angle round the target's vertical, radians. */
    public float azimuth = (float) Math.toRadians(90.0);

    /** Orbit angle above the target's equator, radians in (-pi, pi]; past +-pi/2 the camera is over a pole. */
    public float elevation = (float) Math.toRadians(20.0);

    /** Distance from the orbit target, or the third-person camera's from its pivot. */
    public float distance = 3.5f;

    /** Turn of the orbit camera about its view direction in radians; positive turns the picture counter-clockwise. */
    public float roll;

    /** Closest the orbit may come to its target. */
    public float minDistance = DEFAULT_MIN_DISTANCE;

    /** Farthest the orbit may go from its target. */
    public float maxDistance = DEFAULT_MAX_DISTANCE;

    /** Whether the held drag pans the orbit target rather than turning, chosen by shift at the press. */
    public boolean panningDrag;

    /**
     * The projection every 3D pass draws with, rebuilt by {@link #updateProjection}; a runtime that
     * draws, picks or projects a point calls that first with its own clip planes.
     */
    public final Matrix4f projection = new Matrix4f();

    /**
     * Answers whether text anchored at a world point is seen: the surface a scene draws through
     * this camera, else bare projection, which hides nothing.
     */
    public PointVisibility pointVisibility = (camera, x, y, z, depthLift, pixelDest) -> camera
            .projectToPixels(new Matrix4f(), x, y, z, pixelDest);

    public Vector3f position;
    public Vector3f target;
    public Vector3f right;
    public Vector3f up;
    public Matrix4f view;
    public Vector3f front;
    public Vector3f worldUp;
    public float yaw;
    public float pitch;
    public float startYaw;
    public float startPitch;
    public double fov;
    double SHIFT_MOD;
    private float movementSpeed = DEFAULT_MOVEMENT_SPEED;
    private float zIndex;
    private float farZIndex;
    /**
     * Construct a camera at {@code position} looking in the direction given
     * by yaw/pitch (degrees). Builds the initial front/right/up basis and
     * view matrix and stores yaw/pitch as the reset target.
     *
     * @param position world-space camera position
     * @param yaw initial yaw in degrees (rotation around world up)
     * @param pitch initial pitch in degrees (rotation around right axis)
     */
    public Camera3D(Vector3f position, float yaw, float pitch) {
        this.position = position;
        worldUp = new Vector3f(0.0f, 1.0f, 0.0f);
        this.yaw = yaw;
        startYaw = yaw;

        this.pitch = pitch;
        startPitch = pitch;
        front = new Vector3f();
        up = new Vector3f();
        right = new Vector3f();
        target = new Vector3f();
        updateCameraVectors();
        view = new Matrix4f().lookAt(position, target, up);

        fov = DEFAULT_FOV;
    }

    /**
     * Units-per-second the camera travels when {@link #move(Direction)} is called.
     *
     * @param speed translation speed in world units per second
     */
    public void setMovementSpeed(float speed) {
        this.movementSpeed = speed;
    }

    /**
     * Rebuild the look-at view matrix from the current position, target,
     * and up vector for first-person rendering.
     */
    public void updateViewFirstPerson() {
        view.set(new Matrix4f()).lookAt(position, target, up);
    }

    void updateCameraVectors() {
        // calculate the new Front vector
        front.set((float) (Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch))),
                (float) Math.sin(Math.toRadians(pitch)),
                (float) (Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch))));
        float len = (float) Math.sqrt(front.x * front.x + front.y * front.y + front.z * front.z);
        if (len > 0f) {
            front.x /= len;
            front.y /= len;
            front.z /= len;
        }
        // front already refers to this.front
        // also re-calculate the Right and Up vector
        // right = normalize(cross(front, worldUp)) without JOML cross to avoid Math.fma
        right.x = front.y * worldUp.z - front.z * worldUp.y;
        right.y = front.z * worldUp.x - front.x * worldUp.z;
        right.z = front.x * worldUp.y - front.y * worldUp.x;
        float rlen = (float) Math.sqrt(right.x * right.x + right.y * right.y + right.z * right.z);
        if (rlen > 0f) {
            right.div(rlen);
        }
        // up = normalize(cross(right, front))
        up.x = right.y * front.z - right.z * front.y;
        up.y = right.z * front.x - right.x * front.z;
        up.z = right.x * front.y - right.y * front.x;
        float ulen = (float) Math.sqrt(up.x * up.x + up.y * up.y + up.z * up.z);
        if (ulen > 0f) {
            up.div(ulen);
        }
        target.set(position).add(front);
    }

    /**
     * Restore yaw and pitch to the values supplied at construction. Position
     * is left untouched.
     */
    @Override
    public void reset() {
        yaw = startYaw;
        pitch = startPitch;
    }

    /**
     * Translate the camera one frame's worth along the front or right axis
     * scaled by movement speed, then rebuild the basis.
     *
     * @param direction cardinal direction to move
     */
    @Override
    public void move(Direction direction) {
        float velocity = movementSpeed * (float) Clock.deltaTime();
        if (direction == Direction.FORWARD)
            position.add(front.mul(velocity));
        else if (direction == Direction.BACKWARD)
            position.sub(front.mul(velocity));
        else if (direction == Direction.LEFT)
            position.sub(right.mul(velocity));
        else if (direction == Direction.RIGHT)
            position.add(right.mul(velocity));
        updateCameraVectors();
    }

    /**
     * Set the multiplier applied to zoom rate while shift is held.
     *
     * @param SHIFT_MOD multiplier to install
     */
    @Override
    public void setShiftMod(float SHIFT_MOD) {
        this.SHIFT_MOD = SHIFT_MOD;
    }

    /**
     * Scroll-zoom by widening or narrowing the field of view, clamped to 1° below and to
     * 45° or the wider field already set (by {@code orbit-set}, say) above.
     *
     * @param b true to widen fov, false to narrow
     * @param delta wheel notch magnitude (units of 100)
     */
    @Override
    public void onScroll(boolean b, double delta) {

        float deltaRee= (float)delta/WHEEL_NOTCH_SCALE;
        double widest = Math.max(DEFAULT_FOV, fov);
        if (b) {
            fov += (float) ZOOM_SPEED * SHIFT_MOD * deltaRee * fov;
        } else {
            fov -= (float) ZOOM_SPEED * SHIFT_MOD * deltaRee * fov;
        }
        fov = Math.max(1.0, Math.min(widest, fov));
    }

    /**
     * Apply a mouse drag as direct yaw/pitch deltas in degrees and rebuild
     * the basis vectors.
     *
     * @param d delta yaw in degrees
     * @param e delta pitch in degrees
     */
    @Override
    public void drag(float d, float e) {
        yaw += d;
        pitch += e;
        updateCameraVectors();
    }

    /**
     * Sets yaw and pitch directly and rebuilds derived front/right/up/target vectors.
     *
     * @param yawDegrees absolute yaw in degrees
     * @param pitchDegrees absolute pitch in degrees, clamped to [-89, 89]
     */
    public void setOrientation(float yawDegrees, float pitchDegrees) {
        this.yaw = yawDegrees;
        this.pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitchDegrees));
        updateCameraVectors();
    }

    /**
     * {@inheritDoc}.
     *
     * @return the constant 1; 3D cameras do not scale via point-space zoom
     */
    @Override
    public float getScaleFactor() {
        return 1;
    }

    /**
     * Mouse-look update: convert the cursor delta into yaw/pitch (with a
     * sensitivity of 0.1°/pixel), clamp pitch to [-89, 89], and rebuild
     * basis vectors.
     *
     * @param lastX previous cursor x
     * @param lastY previous cursor y
     * @param x current cursor x
     * @param y current cursor y
     */
    @Override
    public void mouseMove(float lastX, float lastY, float x, float y) {

        float xoffset = x - lastX;
        float yoffset = lastY - y;
        lastX = x;
        lastY = y;

        float sensitivity = MOUSE_SENSITIVITY;
        xoffset *= sensitivity;
        yoffset *= sensitivity;

        yaw += xoffset;
        pitch += yoffset;

        if (pitch > MAX_PITCH)
            pitch = MAX_PITCH;
        if (pitch < -MAX_PITCH)
            pitch = -MAX_PITCH;

        updateCameraVectors();
    }

    /**
     * Normalize a window-space cursor x to framebuffer space for HiDPI scaling.
     *
     * @param xPos window-space cursor x
     * @return framebuffer-space x
     */
    @Override
    public float getNormalizePosX(float xPos) {
        return (((((float) xPos) / ((float) Platforms.get().getWindowWidth()) * Platforms.get().getFrameBufferWidth())));
    }

    /**
     * Flip a window-space cursor y to framebuffer space (bottom-origin)
     * with HiDPI scaling.
     *
     * @param yPos window-space cursor y
     * @return framebuffer-space y
     */
    @Override
    public float getNormalizePosY(float yPos) {
        return ((1 - (yPos) / ((float) Platforms.get().getWindowHeight())) * Platforms.get().getFrameBufferHeight());
    }

    /**
     * Advance the ortho z-index by one increment. Used by the shared 2D
     * overlay path on top of the 3D scene.
     */
    @Override
    public void incZIndex() {
        zIndex += ShaderProgram.ORTHO_Z_INCREMENT;
    }

    /**
     * Add an arbitrary delta to the ortho z-index.
     *
     * @param diff signed z-index increment
     */
    @Override
    public void addZIndex(float diff) {
        zIndex += diff;
    }

    /**
     * Step this camera's ortho z-index past {@code camera}'s.
     *
     * @param camera reference camera whose z-index defines the baseline
     */
    @Override
    public void setZIndex(Camera camera) {
        zIndex += camera.getZIndex() + 1;
    }

    /**
     * {@inheritDoc}.
     *
     * @return the current ortho z-index
     */
    @Override
    public float getZIndex() {
        return zIndex;
    }

    /**
     * Reset z-index to zero and far-z cursor near the near plane for the
     * start of a frame.
     */
    @Override
    public void resetZIndex() {
        zIndex = 0;
        farZIndex = ShaderProgram.ORTHO_NEAR + ZOOM_SPEED;
    }

    /**
     * Step the far-z cursor away from the near plane by one ortho-z increment.
     */
    @Override
    public void decFarZIndex() {
        farZIndex += ShaderProgram.ORTHO_Z_INCREMENT;
    }

    /**
     * {@inheritDoc}.
     *
     * @return current depth used by the descending far-z cursor
     */
    @Override
    public float getFarZIndex() {
        return farZIndex;
    }

    /**
     * Unsupported in 3D: framing is driven by direct {@link #position} /
     * {@link #target} writes, not by point-set bounds.
     *
     * @param ps point set (ignored)
     * @throws UnsupportedOperationException always
     */
    @Override
    public void calculateCameraTransform(PointSet ps) {
        throw new UnsupportedOperationException("Unimplemented method 'calculateCameraTransform'");
    }

    /**
     * Unsupported in 3D: there is no single inverse-projection transform
     * available without a depth value.
     *
     * @param normalizedPosX framebuffer-space x (ignored)
     * @throws UnsupportedOperationException always
     * @return never returns
     */
    @Override
    public float screenTransformX(float normalizedPosX) {
        throw new UnsupportedOperationException(UNIMPLEMENTED_METHOD_SCREENTRANSFORMX);
    }

    /**
     * Unsupported in 3D: there is no single inverse-projection transform
     * available without a depth value.
     *
     * @param normalizedPosY framebuffer-space y (ignored)
     * @throws UnsupportedOperationException always
     * @return never returns
     */
    @Override
    public float screenTransformY(float normalizedPosY) {
        throw new UnsupportedOperationException(UNIMPLEMENTED_METHOD_SCREENTRANSFORMY);
    }

    /**
     * Unsupported in 3D: 2D point-space transforms do not apply.
     *
     * @param normalizedPosX point-space x (ignored)
     * @throws UnsupportedOperationException always
     * @return never returns
     */
    @Override
    public float pointTransformX(float normalizedPosX) {
        throw new UnsupportedOperationException(UNIMPLEMENTED_METHOD_SCREENTRANSFORMX);
    }

    /**
     * Unsupported in 3D: 2D point-space transforms do not apply.
     *
     * @param normalizedPosY point-space y (ignored)
     * @throws UnsupportedOperationException always
     * @return never returns
     */
    @Override
    public float pointTransformY(float normalizedPosY) {
        throw new UnsupportedOperationException(UNIMPLEMENTED_METHOD_SCREENTRANSFORMY);
    }

    /**
     * {@inheritDoc}.
     *
     * @return current framebuffer width (the 3D viewport always covers it)
     */
    @Override
    public float getWidth() {
        return Platforms.get().getFrameBufferWidth();
    }

    /**
     * {@inheritDoc}.
     *
     * @return current framebuffer height
     */
    @Override
    public float getHeight() {
        return Platforms.get().getFrameBufferHeight();
    }

    /**
     * Width over height of the framebuffer the 3D viewport covers, the aspect its projection uses.
     *
     * @return the aspect ratio, or 1 before the framebuffer has a size
     */
    public float aspectRatio() {
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        return width <= 0 || height <= 0 ? 1f : (float) width / (float) height;
    }

    /**
     * Rebuild {@link #projection} over the framebuffer's aspect: perspective at {@link #fov}, or
     * orthographic as tall as the perspective view is at the target's distance.
     *
     * @param orthographic whether the projection is orthographic
     * @param near         near clip plane distance
     * @param far          far clip plane distance
     * @return {@link #projection}
     */
    public Matrix4f updateProjection(boolean orthographic, float near, float far) {
        return updateProjection(orthographic, aspectRatio(), near, far);
    }

    /**
     * Rebuild {@link #projection} over a given aspect, for a view drawn at a size other than the
     * framebuffer's, or with no framebuffer at all.
     *
     * @param orthographic whether the projection is orthographic
     * @param aspect       view width over height
     * @param near         near clip plane distance
     * @param far          far clip plane distance
     * @return {@link #projection}
     */
    public Matrix4f updateProjection(boolean orthographic, float aspect, float near, float far) {
        if (orthographic) {
            float halfHeight = position.distance(target) * (float) Math.tan(Math.toRadians(fov / 2.0));
            float halfWidth = halfHeight * aspect;
            return projection.identity().ortho(-halfWidth, halfWidth, -halfHeight, halfHeight, near, far);
        }
        return projection.identity().perspective((float) Math.toRadians((float) fov), aspect, near, far);
    }

    /**
     * World units one framebuffer pixel spans at a point, the scale a pixel reach is in.
     *
     * @param x world x
     * @param y world y
     * @param z world z
     * @return the span at the point's eye distance, or 0 before the framebuffer has a size
     */
    public double worldPerPixel(float x, float y, float z) {
        return worldPerPixel(x, y, z, Platforms.get().getFrameBufferHeight());
    }

    /**
     * World units one pixel of a view {@code height} pixels tall spans at a point.
     *
     * @param x      world x
     * @param y      world y
     * @param z      world z
     * @param height view height in pixels
     * @return the span at the point's eye distance, or 0 for an empty view
     */
    public double worldPerPixel(float x, float y, float z, int height) {
        return height <= 0 ? 0.0
                : 2.0 * position.distance(x, y, z) * Math.tan(Math.toRadians(fov) / 2.0) / height;
    }

    /**
     * Project a model-space point through {@code model}, {@link #view} and {@link #projection} onto
     * the framebuffer, where a 2D pass can mark it and test it against the depth buffer.
     *
     * @param model     model matrix of the surface the point belongs to
     * @param x         model-space x
     * @param y         model-space y
     * @param z         model-space z
     * @param pixelDest receives framebuffer x from the left, y from the top, and the window-space
     *                  depth in {@code [0, 1]} the point would write
     * @return true when the framebuffer has a size and the point lies in front of the camera
     */
    public boolean projectToPixels(Matrix4f model, float x, float y, float z, float[] pixelDest) {
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        Vector4f point = new Vector4f(x, y, z, 1f);
        model.transform(point);
        view.transform(point);
        projection.transform(point);
        if (point.w <= 0f) {
            return false;
        }
        pixelDest[0] = (point.x / point.w * 0.5f + 0.5f) * width;
        pixelDest[1] = (0.5f - point.y / point.w * 0.5f) * height;
        pixelDest[2] = point.z / point.w * 0.5f + 0.5f;
        return true;
    }

    /**
     * The model-space ray a framebuffer pixel looks along, from the near plane to the far plane of
     * {@link #projection}.
     *
     * @param model        model matrix of the surface the ray is cast at
     * @param framebufferX pixel x, measured from the left, a whole number the pixel's centre
     * @param framebufferY pixel y, measured from the top, a whole number the pixel's centre
     * @param origin       receives the ray origin, packed xyz
     * @param direction    receives the ray direction, packed xyz, not normalised
     * @return true when the framebuffer has a size
     */
    public boolean rayThroughPixel(Matrix4f model, float framebufferX, float framebufferY,
            float[] origin, float[] direction) {
        return rayThroughPixel(model, framebufferX, framebufferY, Platforms.get().getFrameBufferWidth(),
                Platforms.get().getFrameBufferHeight(), origin, direction);
    }

    /**
     * The model-space ray a pixel of a {@code width} by {@code height} view looks along, from the
     * near plane to the far plane of {@link #projection}; no framebuffer is read.
     *
     * @param model     model matrix of the surface the ray is cast at
     * @param pixelX    pixel x from the left, whole numbers at pixel centres
     * @param pixelY    pixel y from the top, whole numbers at pixel centres
     * @param width     view width in pixels
     * @param height    view height in pixels
     * @param origin    receives the ray origin, packed xyz
     * @param direction receives the ray direction, packed xyz, not normalised
     * @return true when the view has a size
     */
    public boolean rayThroughPixel(Matrix4f model, float pixelX, float pixelY, int width, int height,
            float[] origin, float[] direction) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        Matrix4f inverse = new Matrix4f(projection).mul(view).mul(model).invert();
        float normalisedX = 2f * (pixelX + 0.5f) / width - 1f;
        float normalisedY = 1f - 2f * (pixelY + 0.5f) / height;
        Vector4f near = inverse.transform(new Vector4f(normalisedX, normalisedY, -1f, 1f));
        Vector4f far = inverse.transform(new Vector4f(normalisedX, normalisedY, 1f, 1f));
        if (near.w == 0f || far.w == 0f) {
            return false;
        }
        origin[0] = near.x / near.w;
        origin[1] = near.y / near.w;
        origin[2] = near.z / near.w;
        direction[0] = far.x / far.w - origin[0];
        direction[1] = far.y / far.w - origin[1];
        direction[2] = far.z / far.w - origin[2];
        return true;
    }

    /**
     * {@inheritDoc}.
     *
     * @return zero (3D viewport originates at the framebuffer corner)
     */
    @Override
    public float getScreenOffsetX() {
        return 0;
    }

    /**
     * {@inheritDoc}.
     *
     * @return zero (3D viewport originates at the framebuffer corner)
     */
    @Override
    public float getScreenOffsetY() {
        return 0;
    }

    /**
     * {@inheritDoc}.
     *
     * @return 1 (no DPI rescaling applied to 3D viewport sizing)
     */
    @Override
    public float getScreenWidthRatio() {
        return 1;
    }

    /**
     * {@inheritDoc}.
     *
     * @return 1 (no DPI rescaling applied to 3D viewport sizing)
     */
    @Override
    public float getScreenHeightRatio() {
        return 1;
    }

    /**
     * {@inheritDoc}.
     *
     * @return {@code null}; 3D camera does not maintain a {@link Bounds} rectangle
     */
    @Override
    public Bounds getBounds() {
        return null;
    }

    /**
     * 3D camera has no 2D viewport rectangle, so no screen-space point lies
     * inside it.
     *
     * @param pB screen-space point (ignored)
     * @return always {@code false}
     */
    @Override
    public boolean contains(Vector2f pB) {
        return false;
    }

    /**
     * Resize the GL viewport and give every shader its screen projection for the new size; the 3D
     * projection is {@link #updateProjection}'s.
     *
     * @param x viewport lower-left x
     * @param y viewport lower-left y
     * @param width viewport width
     * @param height viewport height
     */
    @Override
    public void updateView(int x, int y, int width, int height) {
        Platforms.gl().viewport(x, y, width, height);
        for (ShaderProgram s : Platforms.gl().getShaders()) {
            if (s.ID < 0) {
                continue;
            }
            s.updateProjectionMatrix(width, height);
        }
    }

    /**
     * Reset the GL viewport to the full framebuffer.
     */
    @Override
    public void resetView() {
        this.updateView(0, 0, Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight());
    }

    /**
     * Re-centre the orbit on a new world-space point, which also becomes the home centre
     * {@link #resetTarget} returns to, and reapply the pose.
     *
     * @param center new orbit centre (copied)
     */
    public void setTarget(Vector3f center) {
        orbitTarget.set(center);
        homeTarget.set(center);
        applyOrbit();
    }

    /**
     * Put the orbit centre back on the home point the last {@link #setTarget} recorded, undoing
     * any shift-drag panning.
     */
    public void resetTarget() {
        orbitTarget.set(homeTarget);
        applyOrbit();
    }

    /**
     * Aim the orbit at a point without moving the home centre.
     *
     * @param center new orbit centre (copied)
     */
    public void moveTarget(Vector3f center) {
        orbitTarget.set(center);
        applyOrbit();
    }

    /**
     * Set the orbit angles and distance directly. Elevation wraps into (-pi, pi], so a larger angle
     * carries on over the pole; distance clamps to the zoom bounds.
     *
     * @param azimuthRadians   horizontal angle round the target
     * @param elevationRadians vertical angle above the equator
     * @param orbitDistance    camera distance from the target
     */
    public void setOrbit(float azimuthRadians, float elevationRadians, float orbitDistance) {
        azimuth = azimuthRadians;
        elevation = wrapAngle(elevationRadians);
        distance = clamp(orbitDistance, minDistance, maxDistance);
        applyOrbit();
    }

    /**
     * Aim at a sphere and pull back until it, grown by the padding, fills the narrower view angle,
     * keeping the orbit angles. The zoom bounds widen to reach it, never narrow.
     *
     * @param center      centre of the sphere, the new orbit centre (home unchanged)
     * @param radius      radius of the sphere, floored at {@link #MINIMUM_FRAME_RADIUS}
     * @param padding     margin as a fraction of the radius
     * @param aspectRatio viewport width over height
     * @return the distance the fit asked for, before the distance clamp
     */
    public float frame(Vector3f center, float radius, float padding, float aspectRatio) {
        float paddedRadius = Math.max(MINIMUM_FRAME_RADIUS, radius) * (1f + Math.max(0f, padding));
        float halfFieldOfViewY = (float) Math.toRadians(fov) * 0.5f;
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
     * Set the orbit zoom bounds, which every {@link #setOrbit} and wheel zoom clamps to.
     *
     * @param minDist closest the camera may sit to the orbit target
     * @param maxDist farthest the camera may sit from the orbit target
     */
    public void setDistanceBounds(float minDist, float maxDist) {
        minDistance = Math.max(0f, minDist);
        maxDistance = Math.max(minDistance, maxDist);
        distance = clamp(distance, minDistance, maxDistance);
        applyOrbit();
    }

    /**
     * A camera drag starts: shift held at the press pans the orbit centre instead of turning.
     *
     * @param x    window x of the press
     * @param y    window y of the press
     * @param mods modifier-key bitmask of the press
     */
    @Override
    public void pointerPressed(float x, float y, int mods) {
        panningDrag = (mods & KeyNames.MOD_SHIFT) != 0;
    }

    /**
     * Turn the orbit by the cursor travel, or pan its centre on a shift-drag, and reapply the pose.
     * Over a pole the view is upside down, so the azimuth turns the other way to follow the cursor.
     *
     * @param lastX window x of the previous cursor sample
     * @param lastY window y of the previous cursor sample
     * @param x     window x of the cursor
     * @param y     window y of the cursor
     */
    @Override
    public void pointerDragged(float lastX, float lastY, float x, float y) {
        float dx = x - lastX;
        float dy = y - lastY;
        if (panningDrag) {
            Vector3f forward = new Vector3f(orbitTarget).sub(position).normalize();
            Vector3f screenRight = forward.cross(up, new Vector3f()).normalize();
            Vector3f screenUp = new Vector3f(up);
            float step = distance * PAN_DISTANCE_FRACTION_PER_PIXEL;
            orbitTarget.add(screenRight.mul(-dx * step)).add(screenUp.mul(dy * step));
        } else {
            float upright = Math.cos(elevation) < 0.0 ? -1f : 1f;
            azimuth += upright * dx * DRAG_RADIANS_PER_PIXEL;
            elevation = wrapAngle(elevation + dy * DRAG_RADIANS_PER_PIXEL);
        }
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
     * Zoom by {@code 0.97} to the power of {@link #SCROLL_TICKS_PER_UNIT} times the summed delta,
     * clamped to the zoom bounds.
     *
     * @param delta summed vertical wheel delta, positive away from the user
     */
    @Override
    public void wheelScrolled(double delta) {
        distance = clamp(distance * (float) Math.pow(ORBIT_ZOOM_BASE, SCROLL_TICKS_PER_UNIT * delta),
                minDistance, maxDistance);
        applyOrbit();
    }

    /**
     * Zoom the field of view by the held zoom keys; the orbit alone places the camera, so the
     * movement keys do nothing.
     *
     * @param pressedKeys key codes held down
     * @param shiftMod    speed multiplier
     */
    @Override
    public void keysHeld(Set<Integer> pressedKeys, float shiftMod) {
        setShiftMod(shiftMod);
        CameraController.zoomWithKeys(this, pressedKeys, Clock.deltaTime());
    }

    /**
     * Place the camera on the orbit sphere looking at the centre. Up is the meridian's tangent
     * toward rising elevation, world up at the equator, never parallel to the view.
     */
    private void applyOrbit() {
        float cosElevation = (float) Math.cos(elevation);
        float sinElevation = (float) Math.sin(elevation);
        float cosAzimuth = (float) Math.cos(azimuth);
        float sinAzimuth = (float) Math.sin(azimuth);
        position.set(
                orbitTarget.x + distance * cosElevation * cosAzimuth,
                orbitTarget.y + distance * sinElevation,
                orbitTarget.z + distance * cosElevation * sinAzimuth);
        target.set(orbitTarget);
        up.set(-sinElevation * cosAzimuth, cosElevation, -sinElevation * sinAzimuth);
        if (roll != 0f) {
            // Rodrigues about the unit view axis, to which up is perpendicular.
            float axisX = -cosElevation * cosAzimuth;
            float axisY = -sinElevation;
            float axisZ = -cosElevation * sinAzimuth;
            float cosRoll = (float) Math.cos(roll);
            float sinRoll = (float) Math.sin(roll);
            float crossX = axisY * up.z - axisZ * up.y;
            float crossY = axisZ * up.x - axisX * up.z;
            float crossZ = axisX * up.y - axisY * up.x;
            up.set(up.x * cosRoll + crossX * sinRoll, up.y * cosRoll + crossY * sinRoll,
                    up.z * cosRoll + crossZ * sinRoll);
        }
        updateViewFirstPerson();
    }

    private static float clamp(float value, float min, float max) {
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
