package ixdar.graphics.cameras;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import ixdar.canvas.Canvas3D;
import ixdar.geometry.point.PointSet;
import ixdar.graphics.render.Clock;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.Platforms;

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
     * TODO: document {@code Camera3D}.
     *
     * @param position TODO: describe
     * @param yaw TODO: describe
     * @param pitch TODO: describe
     * @param canvas TODO: describe
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
     * @param speed TODO: describe
     */
    public void setMovementSpeed(float speed) {
        this.movementSpeed = speed;
    }

    /**
     * TODO: document {@code orbit}.
     *
     * @param radius TODO: describe
     * @param radsPerSecond TODO: describe
     */
    public void orbit(float radius, float radsPerSecond) {

        float camX = ((float) Math.sin(Clock.time() * radsPerSecond)) * radius;
        float camZ = ((float) Math.cos(Clock.time() * radsPerSecond)) * radius;
        view.set(new Matrix4f()).lookAt(position.set(camX, target.y, camZ), target, up);
    }

    /**
     * TODO: document {@code updateViewFirstPerson}.
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
     * TODO: document {@code reset}.
     */
    @Override
    public void reset() {
        yaw = startYaw;
        pitch = startPitch;
    }

    /**
     * TODO: document {@code move}.
     *
     * @param direction TODO: describe
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
     * TODO: document {@code setShiftMod}.
     *
     * @param SHIFT_MOD TODO: describe
     */
    @Override
    public void setShiftMod(float SHIFT_MOD) {
        this.SHIFT_MOD = SHIFT_MOD;
    }

    /**
     * TODO: document {@code onScroll}.
     *
     * @param b TODO: describe
     * @param delta TODO: describe
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
     * TODO: document {@code drag}.
     *
     * @param d TODO: describe
     * @param e TODO: describe
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
     * @param yawDegrees TODO: describe
     * @param pitchDegrees TODO: describe
     */
    public void setOrientation(float yawDegrees, float pitchDegrees) {
        this.yaw = yawDegrees;
        this.pitch = Math.max(-NUM_89, Math.min(NUM_89, pitchDegrees));
        updateCameraVectors();
    }

    /**
     * TODO: document {@code getScaleFactor}.
     *
     * @return TODO: describe
     */
    @Override
    public float getScaleFactor() {
        return 1;
    }

    /**
     * TODO: document {@code mouseMove}.
     *
     * @param lastX TODO: describe
     * @param lastY TODO: describe
     * @param x TODO: describe
     * @param y TODO: describe
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
     * TODO: document {@code getNormalizePosX}.
     *
     * @param xPos TODO: describe
     * @return TODO: describe
     */
    @Override
    public float getNormalizePosX(float xPos) {
        return (((((float) xPos) / ((float) Platforms.get().getWindowWidth()) * Platforms.get().getFrameBufferWidth())));
    }

    /**
     * TODO: document {@code getNormalizePosY}.
     *
     * @param yPos TODO: describe
     * @return TODO: describe
     */
    @Override
    public float getNormalizePosY(float yPos) {
        return ((1 - (yPos) / ((float) Platforms.get().getWindowHeight())) * Platforms.get().getFrameBufferHeight());
    }

    /**
     * TODO: document {@code incZIndex}.
     */
    @Override
    public void incZIndex() {
        zIndex += ShaderProgram.ORTHO_Z_INCREMENT;
    }

    /**
     * TODO: document {@code addZIndex}.
     *
     * @param diff TODO: describe
     */
    @Override
    public void addZIndex(float diff) {
        zIndex += diff;
    }

    /**
     * TODO: document {@code setZIndex}.
     *
     * @param camera TODO: describe
     */
    @Override
    public void setZIndex(Camera camera) {
        zIndex += camera.getZIndex() + 1;
    }

    /**
     * TODO: document {@code getZIndex}.
     *
     * @return TODO: describe
     */
    @Override
    public float getZIndex() {
        return zIndex;
    }

    /**
     * TODO: document {@code resetZIndex}.
     */
    @Override
    public void resetZIndex() {
        zIndex = 0;
        farZIndex = ShaderProgram.ORTHO_NEAR + ZOOM_SPEED;
    }

    /**
     * TODO: document {@code decFarZIndex}.
     */
    @Override
    public void decFarZIndex() {
        farZIndex += ShaderProgram.ORTHO_Z_INCREMENT;
    }

    /**
     * TODO: document {@code getFarZIndex}.
     *
     * @return TODO: describe
     */
    @Override
    public float getFarZIndex() {
        return farZIndex;
    }

    /**
     * TODO: document {@code calculateCameraTransform}.
     *
     * @param ps TODO: describe
     * @throws UnsupportedOperationException TODO: describe
     */
    @Override
    public void calculateCameraTransform(PointSet ps) {
        throw new UnsupportedOperationException("Unimplemented method 'calculateCameraTransform'");
    }

    /**
     * TODO: document {@code screenTransformX}.
     *
     * @param normalizedPosX TODO: describe
     * @throws UnsupportedOperationException TODO: describe
     * @return TODO: describe
     */
    @Override
    public float screenTransformX(float normalizedPosX) {
        throw new UnsupportedOperationException(UNIMPLEMENTED_METHOD_SCREENTRANSFORMX);
    }

    /**
     * TODO: document {@code screenTransformY}.
     *
     * @param normalizedPosY TODO: describe
     * @throws UnsupportedOperationException TODO: describe
     * @return TODO: describe
     */
    @Override
    public float screenTransformY(float normalizedPosY) {
        throw new UnsupportedOperationException(UNIMPLEMENTED_METHOD_SCREENTRANSFORMY);
    }

    /**
     * TODO: document {@code pointTransformX}.
     *
     * @param normalizedPosX TODO: describe
     * @throws UnsupportedOperationException TODO: describe
     * @return TODO: describe
     */
    @Override
    public float pointTransformX(float normalizedPosX) {
        throw new UnsupportedOperationException(UNIMPLEMENTED_METHOD_SCREENTRANSFORMX);
    }

    /**
     * TODO: document {@code pointTransformY}.
     *
     * @param normalizedPosY TODO: describe
     * @throws UnsupportedOperationException TODO: describe
     * @return TODO: describe
     */
    @Override
    public float pointTransformY(float normalizedPosY) {
        throw new UnsupportedOperationException(UNIMPLEMENTED_METHOD_SCREENTRANSFORMY);
    }

    /**
     * TODO: document {@code getWidth}.
     *
     * @return TODO: describe
     */
    @Override
    public float getWidth() {
        return Platforms.get().getFrameBufferWidth();
    }

    /**
     * TODO: document {@code getHeight}.
     *
     * @return TODO: describe
     */
    @Override
    public float getHeight() {
        return Platforms.get().getFrameBufferHeight();
    }

    /**
     * TODO: document {@code getScreenOffsetX}.
     *
     * @return TODO: describe
     */
    @Override
    public float getScreenOffsetX() {
        return 0;
    }

    /**
     * TODO: document {@code getScreenOffsetY}.
     *
     * @return TODO: describe
     */
    @Override
    public float getScreenOffsetY() {
        return 0;
    }

    /**
     * TODO: document {@code getScreenWidthRatio}.
     *
     * @return TODO: describe
     */
    @Override
    public float getScreenWidthRatio() {
        return 1;
    }

    /**
     * TODO: document {@code getScreenHeightRatio}.
     *
     * @return TODO: describe
     */
    @Override
    public float getScreenHeightRatio() {
        return 1;
    }

    /**
     * TODO: document {@code getBounds}.
     *
     * @return TODO: describe
     */
    @Override
    public Bounds getBounds() {
        return null;
    }

    /**
     * TODO: document {@code contains}.
     *
     * @param pB TODO: describe
     * @return TODO: describe
     */
    @Override
    public boolean contains(Vector2f pB) {
        return false;
    }

    /**
     * TODO: document {@code updateView}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
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
     * TODO: document {@code resetView}.
     */
    @Override
    public void resetView() {
        this.updateView(0, 0, Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight());
    }

}
