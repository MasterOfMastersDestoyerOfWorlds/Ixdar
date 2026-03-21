package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.graphics.render.model.HalfEdgeCompiledMeshData;

public class ArrayMeshTest {

    @Test
    public void arrayMeshMatchesHalfEdgeBulkCube() {
        float c = 0.5f;
        float[] positions = {
                -c, -c, -c, c, -c, -c, c, c, -c, -c, c, -c,
                -c, -c, c, c, -c, c, c, c, c, -c, c, c,
        };
        int[] quads = {
                0, 1, 2, 3,
                4, 7, 6, 5,
                0, 4, 5, 1,
                3, 2, 6, 7,
                1, 5, 6, 2,
                0, 3, 7, 4,
        };
        ArrayMesh am = ArrayMesh.fromQuads(positions, quads);
        am.computeNormals();

        HalfEdgeMesh hem = HalfEdgeMesh.bulkAllocate(positions.clone(), quads.clone(), 4);
        hem.computeNormals();

        HalfEdgeCompiledMeshData a = am.compileSurfaceData();
        HalfEdgeCompiledMeshData h = hem.compileSurfaceData();
        assertEquals(a.vertexCount, h.vertexCount);
        assertEquals(a.faceCount, h.faceCount);
        assertEquals(a.indices.length, h.indices.length);
        assertTrue(a.radius > 0f);
        assertEquals(a.indices.length, h.indices.length);
        int[] ei = am.getEdgeIndices();
        assertEquals(12 * 2, ei.length);
    }
}
