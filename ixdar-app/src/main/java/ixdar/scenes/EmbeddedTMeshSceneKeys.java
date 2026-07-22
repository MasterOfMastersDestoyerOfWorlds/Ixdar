package ixdar.scenes;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.platform.input.Keys;
import ixdar.platform.input.OrbitCameraKeyGuy;
import ixdar.platform.input.OrbitMouseTrap;

/**
 * Keyboard control for {@link EmbeddedTMeshScene}: orbit as usual, plus SPACE for one zero-arc
 * collapse, PERIOD for one split, C for a checked full contraction, F for one that stops at the
 * first reroute failure, H to highlight it, and R to reset.
 *
 * <p>See also: LCBK19 Section 6.1
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
        } else if (key == Keys.F) {
            scene.requestContractToFailure();
        } else if (key == Keys.H) {
            scene.toggleFailureHighlight();
        } else if (key == Keys.R) {
            scene.requestReset();
        }
    }
}
