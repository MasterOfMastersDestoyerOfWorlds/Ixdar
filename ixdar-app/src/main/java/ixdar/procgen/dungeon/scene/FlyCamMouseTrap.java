package ixdar.procgen.dungeon.scene;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera3D;
import ixdar.platform.Platforms;
import ixdar.platform.input.MouseTrap;

/**
 * First-person mouse look, drag-to-rotate. Hold left mouse button and move the cursor to yaw/pitch
 * the camera; release to stop. Passive hover does NOT rotate the view (matches typical DCC tools
 * and avoids the aggressive always-on look that base {@link MouseTrap} produces).
 *
 * <p>Reuses {@link Camera3D#mouseMove(float, float, float, float)} for the actual yaw/pitch math
 * so the sensitivity/clamp behavior matches the rest of the engine.
 */
public class FlyCamMouseTrap extends MouseTrap {

    private final Camera3D fpCamera;
    private boolean leftDown = false;

    public FlyCamMouseTrap(Camera3D camera, Canvas3D canvas) {
        super(null, camera, canvas);
        this.fpCamera = camera;
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
        // Passive hover: just track position, no rotation.
        if (!active) return;
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
        if (leftDown) {
            mouseDragged(x, y);
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
