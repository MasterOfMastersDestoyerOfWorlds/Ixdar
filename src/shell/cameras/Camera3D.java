package shell.cameras;

import java.awt.event.MouseEvent;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import shell.render.Clock;

public class Camera3D implements Camera {

    private static final float MovementSpeed = 2.5f;
    private static final float ZOOM_SPEED = 1f;
    Vector3f position;
    Vector3f target;
    Vector3f right;
    Vector3f up;
    public Matrix4f view;
    Vector3f front;
    Vector3f worldUp;
    float yaw;
    float pitch;
    double SHIFT_MOD;
    public double fov;

    public Camera3D(Vector3f position, float yaw, float pitch) {
        this.position = position;
        worldUp = new Vector3f(0.0f, 1.0f, 0.0f);
        this.yaw = yaw;
        this.pitch = pitch;
        updateCameraVectors();
        view = new Matrix4f().lookAt(position, target, up);
        target = new Vector3f(position).add(front);
        fov = 45f;
    }

    public void orbit(float radius, float radsPerSecond) {

        float camX = ((float) Math.sin(Clock.time() * radsPerSecond)) * radius;
        float camZ = ((float) Math.cos(Clock.time() * radsPerSecond)) * radius;
        view = new Matrix4f().lookAt(position.set(camX, target.y, camZ), target, up);
    }

    public void updateViewFirstPerson() {
        view = new Matrix4f().lookAt(position, target, up);
    }

    void updateCameraVectors() {
        // calculate the new Front vector
        Vector3f front = new Vector3f();
        front.x = (float) (Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
        front.y = (float) Math.sin(Math.toRadians(pitch));
        front.z = (float) (Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
        this.front = front.normalize();
        // also re-calculate the Right and Up vector
        right = new Vector3f(front).cross(worldUp).normalize();
        up = new Vector3f(right).cross(front).normalize();
        target = new Vector3f(position).add(front);
    }

    @Override
    public void reset() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'reset'");
    }

    @Override
    public void move(CameraMoveDirection direction) {
        float velocity = MovementSpeed * Clock.deltaTime();
        if (direction == CameraMoveDirection.FORWARD)
            position.add(front.mul(velocity));
        else if (direction == CameraMoveDirection.BACKWARD)
            position.sub(front.mul(velocity));
        else if (direction == CameraMoveDirection.LEFT)
            position.sub(right.mul(velocity));
        else if (direction == CameraMoveDirection.RIGHT)
            position.add(right.mul(velocity));
        updateCameraVectors();
    }

    @Override
    public void setShiftMod(double SHIFT_MOD) {
        this.SHIFT_MOD = SHIFT_MOD;
    }

    @Override
    public void zoom(boolean b) {

        if (b) {
            fov += (float) ZOOM_SPEED * SHIFT_MOD;
            if (fov < 1.0f)
                fov = 1.0f;
            if (fov > 45.0f)
                fov = 45.0f;
        } else { 
            fov -= (float) ZOOM_SPEED * SHIFT_MOD;
            if (fov < 1.0f)
                fov = 1.0f;
            if (fov > 45.0f)
                fov = 45.0f;
        }
    }

    public void drag(double d, double e) {
    }

    @Override
    public void mouseMove(float lastX, float lastY, MouseEvent e) {
        float xpos = e.getX();
        float ypos = e.getY();

        float xoffset = xpos - lastX;
        float yoffset = lastY - ypos;
        lastX = xpos;
        lastY = ypos;

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
}
