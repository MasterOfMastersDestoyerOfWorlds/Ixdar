package ixdar.scenes;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.platform.input.Keys;
import ixdar.platform.input.OrbitCameraKeyGuy;
import ixdar.platform.input.OrbitMouseTrap;

/**
 * Keyboard control for {@link EmbeddedTMeshScene}
 * 
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
        if (key == Keys.C) {
            scene.pendingContract = true;
        } else if (key == Keys.M) {
            scene.pendingFoldFlip = true;
        } 
    }
}
