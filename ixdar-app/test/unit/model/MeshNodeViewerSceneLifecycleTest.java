package unit.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(18, scene.getMeshEdgeCount());
        assertEquals(12, scene.getMeshFaceCount());
        assertEquals(0, scene.getMeshBoundaryEdgeCount());
        assertEquals(2, scene.getMeshEulerCharacteristic());
        assertEquals(0, scene.getMeshDegenerateFaceCount());
        assertTrue(scene.isMeshClosed());
        assertTrue(scene.getMeshRadius() > 0f);
        assertEquals(0f, scene.getMeshCenter().x, 0.0001f);
        assertEquals(0f, scene.getMeshCenter().y, 0.0001f);
        assertEquals(0f, scene.getMeshCenter().z, 0.0001f);
        assertDoesNotThrow(scene::drawScene);
        assertDoesNotThrow(() -> scene.activate(false));
        assertDoesNotThrow(scene::shutdown);
    }
}
