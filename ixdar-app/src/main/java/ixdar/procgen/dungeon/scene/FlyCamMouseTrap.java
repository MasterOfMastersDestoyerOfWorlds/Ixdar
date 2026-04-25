package ixdar.procgen.dungeon.scene;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;

import java.util.function.BooleanSupplier;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera3D;
import ixdar.platform.Platforms;
import ixdar.platform.input.MouseTrap;

/**
 * First-person mouse look. Two operating modes via {@code captureSupplier}:
 *
 * <ul>
 *   <li><b>Drag-to-rotate</b> (supplier returns {@code false}): hold left mouse button and move
 *       the cursor to yaw/pitch the camera. Used in fly-cam mode.</li>
 *   <li><b>Capture</b> (supplier returns {@code true}): every cursor delta rotates the camera,
 *       no button needed. Used in player mode together with
 *       {@link Platform.CursorMode#CAPTURED}.</li>
 * </ul>
 *
 * <p>Reuses {@link Camera3D#mouseMove(float, float, float, float)} for the yaw/pitch math.
 */
public class FlyCamMouseTrap extends MouseTrap {

    private final Camera3D fpCamera;
    private final BooleanSupplier captureSupplier;
    private boolean leftDown = false;

    public FlyCamMouseTrap(Camera3D camera, Canvas3D canvas) {
        this(camera, canvas, () -> false);
    }

    public FlyCamMouseTrap(Camera3D camera, Canvas3D canvas, BooleanSupplier captureSupplier) {
        super(null, camera, canvas);
        this.fpCamera = camera;
        this.captureSupplier = captureSupplier;
    }

    @Override
    public void mouseButton(int button, int action, int mods) {
        Platforms.init(Platforms.get().getPlatformID());
        if (!active) return;
        if (button == MOUSE_BUTTON_LEFT) {
            if (action == ACTION_PRESS) {
                leftDown = true;
            } else if (action == ACTION_RELEASE) {
                leftDown = false;
            }
        }
    }

    @Override
    public void mousePos(float x, float y) {
        if (!active) return;
        if (captureSupplier.getAsBoolean()) {
            // FPS-capture: rotate on every cursor event without requiring a button press.
            if (lastX != Integer.MIN_VALUE && lastY != Integer.MIN_VALUE) {
                fpCamera.mouseMove(lastX, lastY, x, y);
            }
        }
        lastX = (int) x;
        lastY = (int) y;
    }

    @Override
    public void mouseDragged(float x, float y) {
        if (!active || !leftDown) return;
        if (lastX == Integer.MIN_VALUE || lastY == Integer.MIN_VALUE) {
            mousePos(x, y);
            return;
        }
        fpCamera.mouseMove(lastX, lastY, x, y);
        lastX = (int) x;
        lastY = (int) y;
    }

    @Override
    public void moveOrDrag(long window, float x, float y) {
        // In capture mode, treat every move as drag-style rotation; otherwise honor LMB-gate.
        if (captureSupplier.getAsBoolean() || leftDown) {
            // Capture path goes through mousePos to use the no-LMB rotation branch above.
            if (captureSupplier.getAsBoolean()) {
                mousePos(x, y);
            } else {
                mouseDragged(x, y);
            }
        } else {
            mousePos(x, y);
        }
    }

    @Override
    public void paintUpdate(float shiftMod) {
        // Fly-cam has no per-frame state to tick — yaw/pitch are updated on drag, and WASD
        // movement is handled by KeyGuy -> Camera2DInputController -> camera.move().
    }
}
