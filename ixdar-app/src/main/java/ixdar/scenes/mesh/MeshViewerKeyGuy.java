package ixdar.scenes.mesh;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.platform.input.OrbitCameraKeyGuy;
import ixdar.platform.input.OrbitMouseTrap;

public class MeshViewerKeyGuy extends OrbitCameraKeyGuy {

    // GLFW modifier bit for the shift key.
    private static final int MOD_SHIFT = 0x0001;

    private final MeshNodeViewerScene meshScene;

    /**
     * Wrap the shared {@link OrbitCameraKeyGuy} with mesh-viewer-specific shortcuts
     * (Z, P, Shift+P, [, ], D) that operate on {@code meshScene}.
     *
     * @param meshScene  the scene whose toggles/cycles this handler drives
     * @param orbitMouse orbit controller whose centre {@code Ctrl+R} resets
     * @param camera     camera passed through to the base handler
     * @param canvas     backing canvas passed through to the base handler
     */
    public MeshViewerKeyGuy(MeshNodeViewerScene meshScene, OrbitMouseTrap orbitMouse,
            Camera camera, Canvas3D canvas) {
        super(orbitMouse, camera, canvas);
        this.meshScene = meshScene;
    }

    /**
     * Handle mesh-viewer key presses on key-down: Z toggles wireframe,
     * P toggles the patch overlay (Shift+P cycles shader mode instead),
     * [ / ] step backward/forward through the model catalog, and D
     * cycles the active patch decomposer.
     *
     * @param key  GLFW key code
     * @param mods GLFW modifier bitmask (Shift is read here)
     */
    @Override
    protected void handleSceneKeys(int key, int mods) {
        Platforms.init(canvas.platform.getPlatformID());
        switch (key) {
            case Keys.Z -> meshScene.toggleMeshWireframe();
            case Keys.P -> {
                if ((mods & MOD_SHIFT) != 0) {
                    meshScene.toggleShaderMode();
                } else {
                    meshScene.togglePatchOverlay();
                }
            }
            case Keys.LEFT_BRACKET -> meshScene.prevModel();
            case Keys.RIGHT_BRACKET -> meshScene.nextModel();
            case Keys.D -> meshScene.toggleDecomposer();
            default -> { }
        }
    }
}
