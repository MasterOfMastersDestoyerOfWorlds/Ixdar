package unit.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
        assertTrue(scene.getMeshVertexCount() > 0, "DSL graph should produce a non-empty mesh");
        assertTrue(scene.getMeshFaceCount() > 0);
        assertTrue(scene.getMeshEdgeCount() > 0);
        assertTrue(scene.getMeshRadius() > 0f);
        assertDoesNotThrow(scene::drawScene);
        assertDoesNotThrow(() -> scene.activate(false));
        assertDoesNotThrow(scene::shutdown);
    }
}
