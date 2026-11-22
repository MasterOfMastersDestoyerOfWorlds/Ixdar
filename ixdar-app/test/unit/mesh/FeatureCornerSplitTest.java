package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.quadlayout.FeatureCornerSplit;

/**
 * CRAW-117: a triangle with two alignment edges has its third edge split (BCE13 §3.2), so no
 * triangle carries two; the input is never touched and a mesh needing no split passes through.
 */
class FeatureCornerSplitTest {

    private static final int SQUARE_CORNERS = 4;

    private static final int TRIANGLE_CORNERS = 3;

    private static final int TETRAHEDRON_CORNERS = 4;

    /**
     * Both triangles of a two-triangle square have two boundary edges; their shared diagonal is
     * split once, leaving four triangles with one boundary edge each.
     */
    @Test
    void theDiagonalOfAFeatureCornerPairIsSplitOnce() {
        HalfEdgeMesh square = square();

        FeatureCornerSplit split = FeatureCornerSplit.of(square, CrossField.DEFAULT_FEATURE_DIHEDRAL_COS);

        assertEquals(1, split.edgeSplitCount, "the shared diagonal is split once, for both triangles");
        assertEquals(SQUARE_CORNERS, split.mesh.faceCount(), "two triangles become four");
        assertEquals(SQUARE_CORNERS + 1, split.mesh.vertexCount(), "one new vertex on the diagonal");
        assertEquals(2, square.faceCount(), "the input mesh is left as it was");

        FeatureCornerSplit again = FeatureCornerSplit.of(split.mesh, CrossField.DEFAULT_FEATURE_DIHEDRAL_COS);
        assertEquals(0, again.edgeSplitCount, "after the split no triangle has two alignment edges");
        assertSame(split.mesh, again.mesh, "a mesh needing no split passes through as itself");
    }

    /**
     * Every edge of a regular tetrahedron is a crease, so every face starts with three alignment
     * edges. Pruning leaves at most two per face and the splits leave at most one, and a pruned
     * crease stays unaligned after its split, though its halves are still creases.
     */
    @Test
    void creaseTrianglesArePrunedThenSplitAndThePruningSurvivesTheSplits() {
        PackedPoints positions = new PackedPoints(TETRAHEDRON_CORNERS);
        positions.set(0, 1f, 1f, 1f);
        positions.set(1, 1f, -1f, -1f);
        positions.set(2, -1f, 1f, -1f);
        positions.set(3, -1f, -1f, 1f);
        HalfEdgeMesh tetrahedron = HalfEdgeMeshOps.buildFromIndexedMesh(positions,
                new int[] {0, 1, 2, 0, 3, 1, 0, 2, 3, 1, 3, 2});
        tetrahedron.computeNormals();

        FeatureCornerSplit split = FeatureCornerSplit.of(tetrahedron, CrossField.DEFAULT_FEATURE_DIHEDRAL_COS);

        assertTrue(split.prunedCount > 0, "some crease closing a triangle of three was left unaligned");
        assertTrue(split.edgeSplitCount > 0, "faces left with two alignment edges had their third split");
        for (int activeFace = 0; activeFace < split.mesh.faceCount(); activeFace++) {
            int faceId = split.mesh.faceIdAt(activeFace);
            int aligned = 0;
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                aligned += split.alignmentByEdgeId[split.mesh.faceEdgeAt(faceId, corner)] ? 1 : 0;
            }
            assertTrue(aligned <= 1, "face " + faceId + " still has " + aligned + " alignment edges");
        }
    }

    /**
     * The unit square as the triangles 0-1-2 and 0-2-3.
     *
     * @return the square, its normals computed
     */
    private static HalfEdgeMesh square() {
        PackedPoints positions = new PackedPoints(SQUARE_CORNERS);
        positions.set(0, 0f, 0f, 0f);
        positions.set(1, 1f, 0f, 0f);
        positions.set(2, 1f, 1f, 0f);
        positions.set(3, 0f, 1f, 0f);
        HalfEdgeMesh mesh = HalfEdgeMeshOps.buildFromIndexedMesh(positions, new int[] {0, 1, 2, 0, 2, 3});
        mesh.computeNormals();
        return mesh;
    }
}
