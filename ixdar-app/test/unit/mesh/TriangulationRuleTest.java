package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.csg.QuadTriangulation;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangulationRule;

/**
 * The one face triangulation: a fan from corner 0, or for a quad under the shorter-diagonal rule a
 * cut along its shorter diagonal, the 0-2 one on a tie; the boolean's quad split goes through it.
 */
class TriangulationRuleTest {

    private static final int PENTAGON = 5;

    private static final int QUAD = 4;

    @Test
    void aFanStartsAtTheFirstCorner() {
        int[] corners = new int[3 * PENTAGON];
        assertEquals(PENTAGON - 2, MeshTopology.triangleCorners(TriangulationRule.FAN, PENTAGON,
                false, corners));
        assertArrayEquals(new int[] { 0, 1, 2, 0, 2, 3, 0, 3, 4 },
                Arrays.copyOf(corners, 3 * (PENTAGON - 2)));
    }

    @Test
    void aQuadIsCutAlongItsShorterDiagonal() {
        int[] corners = new int[3 * QUAD];
        MeshTopology.triangleCorners(TriangulationRule.SHORTER_DIAGONAL, QUAD, false, corners);
        assertArrayEquals(new int[] { 1, 2, 3, 1, 3, 0 }, Arrays.copyOf(corners, 6));
        MeshTopology.triangleCorners(TriangulationRule.SHORTER_DIAGONAL, QUAD, true, corners);
        assertArrayEquals(new int[] { 0, 1, 2, 0, 2, 3 }, Arrays.copyOf(corners, 6));
    }

    @Test
    void theBooleanSplitsQuadsThroughTheMeshTriangulation() {
        // A kite whose 1-3 diagonal is the shorter, and apart from it a square, a tie.
        float[] positions = { 0f, 0f, 0f, 2f, -1f, 0f, 4f, 0f, 0f, 2f, 1f, 0f,
            10f, 0f, 0f, 12f, 0f, 0f, 12f, 2f, 0f, 10f, 2f, 0f };
        int[] quads = { 0, 1, 2, 3, 4, 5, 6, 7 };
        MeshTopology mesh = HalfEdgeMeshOps.bulkAllocate(positions, quads, QUAD);
        int[] faceOfTriangle = new int[mesh.triangleCount()];
        int[] expected = mesh.triangulate(TriangulationRule.SHORTER_DIAGONAL, faceOfTriangle);
        assertEquals(mesh.faceVertexAt(mesh.faceIdAt(0), 1), expected[0],
                "the kite fans from corner 1");
        assertEquals(mesh.faceVertexAt(mesh.faceIdAt(1), 0), expected[2 * 3],
                "the square, a tie, fans from corner 0");

        QuadTriangulation split = new QuadTriangulation(mesh).build();
        assertArrayEquals(faceOfTriangle, split.triangleSourceFace);
        for (int corner = 0; corner < expected.length; corner++) {
            assertEquals(expected[corner], mesh.vertexIdAt(split.triangles[corner]));
        }
    }
}
