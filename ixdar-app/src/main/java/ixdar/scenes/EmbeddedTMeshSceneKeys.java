package ixdar.scenes;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.platform.input.Keys;
import ixdar.platform.input.OrbitCameraKeyGuy;
import ixdar.platform.input.OrbitMouseTrap;

/**
 * Keyboard control for {@link EmbeddedTMeshScene}: orbit as usual, plus SPACE to apply one
 * more LCBK19 operator-(1) zero-arc collapse, PERIOD one operator-(2) split, C to drive all
 * three operators to a fixed point, and R to reset to the pristine layout, so the re-embedding
 * can be watched step by step in the window.
 */
public final class EmbeddedTMeshSceneKeys extends OrbitCameraKeyGuy {

    private final EmbeddedTMeshScene scene;

    /**
     * Binds the keys to a scene.
     *
     * @param scene      scene the keys drive
     * @param orbitMouse orbit controller shared with the mouse
     * @param camera     scene camera
     * @param canvas     owning canvas
     */
    public EmbeddedTMeshSceneKeys(EmbeddedTMeshScene scene, OrbitMouseTrap orbitMouse,
            Camera camera, Canvas3D canvas) {
        super(orbitMouse, camera, canvas);
        this.scene = scene;
    }

    @Override
    protected void handleSceneKeys(int key, int mods) {
        if (key == Keys.SPACE) {
            scene.requestCollapseStep();
        } else if (key == Keys.PERIOD) {
            scene.requestSplitStep();
        } else if (key == Keys.C) {
            scene.requestFullContraction();
        } else if (key == Keys.R) {
            scene.requestReset();
        }
    }
}
