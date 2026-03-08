package unit.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.HalfEdgeMesh;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.gl.headless.HeadlessGL;
import ixdar.platform.gl.headless.HeadlessPlatform;
import ixdar.scenes.mesh.MeshNodeViewerScene;

public class HalfEdgeMeshRuntimeTest {

    @Test
    public void runtimeUploadsRendersAndReuploadsWithoutRecreatingSceneBuffers() throws Exception {
        Platforms.init(new HeadlessPlatform(), new HeadlessGL());
        HalfEdgeMeshRuntime runtime = new HalfEdgeMeshRuntime();
        Camera3D camera = new Camera3D(new Vector3f(0f, 0f, 4f), -90f, 0f, new MeshNodeViewerScene());

        HalfEdgeMesh cube = HalfEdgeMesh.buildFromIndexedMesh(cubePositions(), cubeTriangles());
        HalfEdgeMesh tri = HalfEdgeMesh.buildFromIndexedMesh(
                new float[] {
                        0f, 0f, 0f,
                        1f, 0f, 0f,
                        0f, 1f, 0f,
                },
                new int[] { 0, 1, 2 });

        assertDoesNotThrow(() -> runtime.upload(cube));
        assertEquals(8, runtime.getVertexCount());
        assertEquals(12, runtime.getFaceCount());

        assertDoesNotThrow(() -> runtime.frameCamera(camera));
        assertTrue(camera.position.distance(camera.target) > 0f);
        assertDoesNotThrow(() -> runtime.render(camera));

        assertDoesNotThrow(() -> runtime.reupload(tri));
        assertEquals(3, runtime.getVertexCount());
        assertEquals(1, runtime.getFaceCount());
        assertDoesNotThrow(() -> runtime.render(camera));
        assertDoesNotThrow(runtime::dispose);
    }

    private static float[] cubePositions() {
        return new float[] {
                -0.5f, -0.5f, -0.5f,
                0.5f, -0.5f, -0.5f,
                0.5f, 0.5f, -0.5f,
                -0.5f, 0.5f, -0.5f,
                -0.5f, -0.5f, 0.5f,
                0.5f, -0.5f, 0.5f,
                0.5f, 0.5f, 0.5f,
                -0.5f, 0.5f, 0.5f,
        };
    }

    private static int[] cubeTriangles() {
        return new int[] {
                0, 1, 2, 2, 3, 0,
                4, 7, 6, 6, 5, 4,
                0, 4, 5, 5, 1, 0,
                3, 2, 6, 6, 7, 3,
                1, 5, 6, 6, 2, 1,
                0, 3, 7, 7, 4, 0,
        };
    }
}
