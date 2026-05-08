package ixdar.scenes.mesh;

import static ixdar.platform.input.Keys.ACTION_PRESS;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.platform.Platforms;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.Keys;

public class MeshViewerKeyGuy extends KeyGuy {

    // GLFW modifier bit for the shift key.
    private static final int MOD_SHIFT = 0x0001;

    private final MeshNodeViewerScene meshScene;

    /**
     * Wrap the standard {@link KeyGuy} with mesh-viewer-specific shortcuts
     * (Z, P, Shift+P, [, ], D) that operate on {@code meshScene}.
     *
     * @param meshScene the scene whose toggles/cycles this handler drives
     * @param camera camera passed through to the base {@link KeyGuy}
     * @param canvas backing canvas passed through to the base {@link KeyGuy}
     */
    public MeshViewerKeyGuy(MeshNodeViewerScene meshScene, Camera camera, Canvas3D canvas) {
        super(camera, canvas);
        this.meshScene = meshScene;
    }

    /**
     * Handle mesh-viewer key presses on key-down: Z toggles wireframe,
     * P toggles the patch overlay (Shift+P cycles shader mode instead),
     * [ / ] step backward/forward through the model catalog, and D
     * cycles the active patch decomposer. All other keys defer to the
     * base {@link KeyGuy}.
     *
     * @param window GLFW window handle (unused beyond Platforms init)
     * @param key GLFW key code
     * @param scancode platform-specific scancode (unused)
     * @param action GLFW action (only {@code ACTION_PRESS} is handled)
     * @param mods GLFW modifier bitmask (Shift is read here)
     */
    @Override
    public void keyCallback(long window, int key, int scancode, int action, int mods) {
        Platforms.init(canvas.platform.getPlatformID());
        if (active && action == ACTION_PRESS) {
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
                default -> {}
            }
        }
        super.keyCallback(window, key, scancode, action, mods);
    }
}
