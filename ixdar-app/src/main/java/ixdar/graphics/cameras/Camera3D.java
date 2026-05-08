package ixdar.graphics.cameras;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import ixdar.canvas.Canvas3D;
import ixdar.geometry.point.PointSet;
import ixdar.graphics.render.Clock;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.Platforms;

/**
 * Perspective 3D first-person camera. Holds position plus yaw/pitch and
 * derives front/right/up basis vectors and a JOML look-at view matrix.
 * Supports orbit, drag-rotate, mouse-look, WASD movement, and fov-based
 * scroll zoom; many of the {@link Camera} 2D-only hooks throw
 * {@link UnsupportedOperationException}.
 */
public class Camera3D implements Camera {
    public static final String UNIMPLEMENTED_METHOD_SCREENTRANSFORMX = "Unimplemented method 'screenTransformX'";
    public static final String UNIMPLEMENTED_METHOD_SCREENTRANSFORMY = "Unimplemented method 'screenTransformY'";
    public static final float NUM_45 = 45f;
    public static final float NUM_0 = 0f;
    public static final float NUM_100 = 100f;
    public static final float NUM_45_0 = 45.0f;
    public static final float NUM_89 = 89f;
    public static final float NUM_0_1 = 0.1f;
    public static final float NUM_89_0 = 89.0f;

    private static final float DEFAULT_MOVEMENT_SPEED = 2.5f;
    private static final float ZOOM_SPEED = 1f;
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
    public Canvas3D canvas;
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
     * @param canvas owning 3D canvas, used by host wiring
     */
    public Camera3D(Vector3f position, float yaw, float pitch, Canvas3D canvas) {
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

        fov = NUM_45;
        this.canvas = canvas;
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
     * Continuously orbit the camera around {@code target} at the given radius
     * and angular rate, keeping target.y as the orbit-plane height.
     *
     * @param radius orbital radius in world units
     * @param radsPerSecond angular speed in radians per second
     */
    public void orbit(float radius, float radsPerSecond) {

        float camX = ((float) Math.sin(Clock.time() * radsPerSecond)) * radius;
        float camZ = ((float) Math.cos(Clock.time() * radsPerSecond)) * radius;
        view.set(new Matrix4f()).lookAt(position.set(camX, target.y, camZ), target, up);
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
        if (len > NUM_0) {
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
        if (rlen > NUM_0) {
            right.div(rlen);
        }
        // up = normalize(cross(right, front))
        up.x = right.y * front.z - right.z * front.y;
        up.y = right.z * front.x - right.x * front.z;
        up.z = right.x * front.y - right.y * front.x;
        float ulen = (float) Math.sqrt(up.x * up.x + up.y * up.y + up.z * up.z);
        if (ulen > NUM_0) {
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
     * Scroll-zoom by widening or narrowing the field of view, clamped to
     * [1°, 45°].
     *
     * @param b true to widen fov, false to narrow
     * @param delta wheel notch magnitude (units of 100)
     */
    @Override
    public void onScroll(boolean b, double delta) {

        float deltaRee= (float)delta/NUM_100;
        if (b) {
            fov += (float) ZOOM_SPEED * SHIFT_MOD * deltaRee * fov;
            if (fov < 1.0f)
                fov = 1.0f;
            if (fov > NUM_45_0)
                fov = NUM_45_0;
        } else {
            fov -= (float) ZOOM_SPEED * SHIFT_MOD * deltaRee * fov;
            if (fov < 1.0f)
                fov = 1.0f;
            if (fov > NUM_45_0)
                fov = NUM_45_0;
        }
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
        this.pitch = Math.max(-NUM_89, Math.min(NUM_89, pitchDegrees));
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

        float sensitivity = NUM_0_1;
        xoffset *= sensitivity;
        yoffset *= sensitivity;

        yaw += xoffset;
        pitch += yoffset;

        if (pitch > NUM_89_0)
            pitch = NUM_89_0;
        if (pitch < -NUM_89_0)
            pitch = -NUM_89_0;

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
     * Resize the GL viewport and rebuild perspective projection matrices on
     * every shader.
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
            s.updateProjectionMatrix(width, height, ZOOM_SPEED);
        }
    }

    /**
     * Reset the GL viewport to the full framebuffer.
     */
    @Override
    public void resetView() {
        this.updateView(0, 0, Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight());
    }

}
