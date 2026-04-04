package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.ArrayMeshEngine;

public class SolidifyMeshTest {

    @Test
    public void solidifySingleQuadIsClosedWithSixFaces() {
        float[] pos = {
                0f, 0f, 0f,
                0f, 0f, 1f,
                1f, 0f, 1f,
                1f, 0f, 0f,
        };
        int[] quads = { 0, 1, 2, 3 };
        ArrayMesh sheet = ArrayMesh.fromQuads(pos, quads);
        sheet.computeNormals();
        assertTrue(sheet.faceCount() == 1);
        int boundary = 0;
        for (int ei = 0; ei < sheet.edgeCount(); ei++) {
            if (sheet.isBoundaryEdge(ei)) {
                boundary++;
            }
        }
        assertEquals(4, boundary);

        ArrayMesh solid = ArrayMeshEngine.solidifyUniformQuads(sheet, 0.1f);
        assertEquals(8, solid.vertexCount());
        assertEquals(6, solid.faceCount());
        int boundaryAfter = 0;
        for (int ei = 0; ei < solid.edgeCount(); ei++) {
            if (solid.isBoundaryEdge(ei)) {
                boundaryAfter++;
            }
        }
        assertEquals(0, boundaryAfter, "solidified quad should have no boundary edges");

        Vector3f min = solid.boundsMin(new Vector3f());
        Vector3f max = solid.boundsMax(new Vector3f());
        assertTrue(min.y < -0.05f && max.y > -0.05f);
    }
}
