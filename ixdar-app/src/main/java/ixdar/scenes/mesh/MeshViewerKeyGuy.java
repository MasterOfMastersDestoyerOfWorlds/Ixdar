package ixdar.scenes.mesh;

import static ixdar.platform.input.Keys.ACTION_PRESS;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.platform.Platforms;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.Keys;

public class MeshViewerKeyGuy extends KeyGuy {

    private final MeshNodeViewerScene meshScene;

    public MeshViewerKeyGuy(MeshNodeViewerScene meshScene, Camera camera, Canvas3D canvas) {
        super(camera, canvas);
        this.meshScene = meshScene;
    }

    @Override
    public void keyCallback(long window, int key, int scancode, int action, int mods) {
        Platforms.init(canvas.platform.getPlatformID());
        if (active && action == ACTION_PRESS && key == Keys.Z) {
            meshScene.toggleMeshWireframe();
        }
        super.keyCallback(window, key, scancode, action, mods);
    }
}
