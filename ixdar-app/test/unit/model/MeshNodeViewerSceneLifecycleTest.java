package unit.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import ixdar.platform.Platforms;
import ixdar.platform.gl.headless.HeadlessGL;
import ixdar.platform.gl.headless.HeadlessPlatform;
import ixdar.scenes.mesh.MeshNodeViewerScene;

public class MeshNodeViewerSceneLifecycleTest {

    @Test
    public void sceneCanInitDrawDeactivateAndShutdownWithRuntimeBackedMesh() {
        Platforms.init(new HeadlessPlatform(), new HeadlessGL());
        MeshNodeViewerScene scene = new MeshNodeViewerScene();

        assertDoesNotThrow(scene::initGL);
        assertEquals(8, scene.getMeshVertexCount());
        assertEquals(12, scene.getMeshFaceCount());
        assertDoesNotThrow(scene::drawScene);
        assertDoesNotThrow(() -> scene.activate(false));
        assertDoesNotThrow(scene::shutdown);
    }
}
