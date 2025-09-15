package shell.cameras;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import shell.PointSet;
import shell.platform.Platforms;
import shell.platform.gl.GL;
import shell.render.Clock;
import shell.render.shaders.ShaderProgram;
import shell.ui.Canvas3D;

public class Camera3D implements Camera {

    private static GL gl = Platforms.gl();
    private static final float MovementSpeed = 2.5f;
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
    double SHIFT_MOD;
    public double fov;
    private float zIndex;
    private float farZIndex;

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

        fov = 45f;
    }

    public void orbit(float radius, float radsPerSecond) {

        float camX = ((float) Math.sin(Clock.time() * radsPerSecond)) * radius;
        float camZ = ((float) Math.cos(Clock.time() * radsPerSecond)) * radius;
        view.set(new Matrix4f()).lookAt(position.set(camX, target.y, camZ), target, up);
    }

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

    @Override
    public void reset() {
        yaw = startYaw;
        pitch = startPitch;
    }

    @Override
    public void move(Direction direction) {
        float velocity = MovementSpeed * (float) Clock.deltaTime();
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

    @Override
    public void setShiftMod(float SHIFT_MOD) {
        this.SHIFT_MOD = SHIFT_MOD;
    }

    @Override
    public void onScroll(boolean b, double delta) {

        float deltaRee= (float)delta/100f;
        if (b) {
            fov += (float) ZOOM_SPEED * SHIFT_MOD * deltaRee * fov;
            if (fov < 1.0f)
                fov = 1.0f;
            if (fov > 45.0f)
                fov = 45.0f;
        } else {
            fov -= (float) ZOOM_SPEED * SHIFT_MOD * deltaRee * fov;
            if (fov < 1.0f)
                fov = 1.0f;
            if (fov > 45.0f)
                fov = 45.0f;
        }
    }

    @Override
    public void drag(float d, float e) {
        yaw += d;
        pitch += e;
        updateCameraVectors();
    }

    @Override
    public float getScaleFactor() {
        return 1;
    }

    @Override
    public void mouseMove(float lastX, float lastY, float x, float y) {

        float xoffset = x - lastX;
        float yoffset = lastY - y;
        lastX = x;
        lastY = y;

        float sensitivity = 0.1f;
        xoffset *= sensitivity;
        yoffset *= sensitivity;

        yaw += xoffset;
        pitch += yoffset;

        if (pitch > 89.0f)
            pitch = 89.0f;
        if (pitch < -89.0f)
            pitch = -89.0f;

        updateCameraVectors();
    }

    @Override
    public float getNormalizePosX(float xPos) {
        return (((((float) xPos) / ((float) Platforms.get().getWindowWidth()) * Canvas3D.frameBufferWidth)));
    }

    @Override
    public float getNormalizePosY(float yPos) {
        return ((1 - (yPos) / ((float) Platforms.get().getWindowHeight())) * Canvas3D.frameBufferHeight);
    }

    @Override
    public void incZIndex() {
        zIndex += ShaderProgram.ORTHO_Z_INCREMENT;
    }

    @Override
    public void addZIndex(float diff) {
        zIndex += diff;
    }

    @Override
    public void setZIndex(Camera camera) {
        zIndex += camera.getZIndex() + 1;
    }

    @Override
    public float getZIndex() {
        return zIndex;
    }

    @Override
    public void resetZIndex() {
        zIndex = 0;
        farZIndex = ShaderProgram.ORTHO_NEAR + 1f;
    }

    @Override
    public void decFarZIndex() {
        farZIndex += ShaderProgram.ORTHO_Z_INCREMENT;
    }

    @Override
    public float getFarZIndex() {
        return farZIndex;
    }

    @Override
    public void calculateCameraTransform(PointSet ps) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'calculateCameraTransform'");
    }

    @Override
    public float screenTransformX(float normalizedPosX) {
        throw new UnsupportedOperationException("Unimplemented method 'screenTransformX'");
    }

    @Override
    public float screenTransformY(float normalizedPosY) {
        throw new UnsupportedOperationException("Unimplemented method 'screenTransformY'");
    }

    @Override
    public float pointTransformX(float normalizedPosX) {
        throw new UnsupportedOperationException("Unimplemented method 'screenTransformX'");
    }

    @Override
    public float pointTransformY(float normalizedPosY) {
        throw new UnsupportedOperationException("Unimplemented method 'screenTransformY'");
    }

    @Override
    public float getWidth() {
        return Canvas3D.frameBufferWidth;
    }

    @Override
    public float getHeight() {
        return Canvas3D.frameBufferHeight;
    }

    @Override
    public float getScreenOffsetX() {
        return 0;
    }

    @Override
    public float getScreenOffsetY() {
        return 0;
    }

    @Override
    public float getScreenWidthRatio() {
        return 1;
    }

    @Override
    public float getScreenHeightRatio() {
        return 1;
    }

    @Override
    public Bounds getBounds() {
        return null;
    }

    @Override
    public boolean contains(Vector2f pB) {
        return false;
    }

    @Override
    public void updateView(int x, int y, int width, int height) {
        gl.viewport(x, y, width, height);
        for (ShaderProgram s : Canvas3D.shaders) {
            s.updateProjectionMatrix(width, height, 1f);
        }
    }

    @Override
    public void resetView() {
        this.updateView(0, 0, Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight);
    }

}
