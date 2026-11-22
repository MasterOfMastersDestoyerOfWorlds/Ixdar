package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ops.MeshFolds;
import ixdar.geometry.mesh.data.ops.MeshUnfold;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * CRAW-114: an isolated sliver turned over against its neighbours, as a surface extractor leaves
 * them, is unfolded in place: no face folds afterwards, the topology is unchanged and only the
 * pleat's corners moved.
 */
class MeshUnfoldTest {

    private static final int GRID = 7;

    private static final int PLEATED_ROW = 3;

    private static final int PLEATED_COLUMN = 3;

    private static final float PLEAT_OVERSHOOT = 1.6f;

    private static final String SAME_TOPOLOGY = "the unfold changes no topology";

    /**
     * A flat triangulated grid with one interior vertex pushed past its right-hand neighbour, which
     * turns the faces between them over, is unfolded.
     */
    @Test
    void aTurnedOverSliverIsUnfoldedWithoutTouchingTheRestOfTheSurface() {
        PackedPoints positions = new PackedPoints(GRID * GRID);
        for (int row = 0; row < GRID; row++) {
            for (int column = 0; column < GRID; column++) {
                float x = column;
                if (row == PLEATED_ROW && column == PLEATED_COLUMN) {
                    x += PLEAT_OVERSHOOT;
                }
                positions.set(row * GRID + column, x, row, 0f);
            }
        }
        int[] triangles = new int[(GRID - 1) * (GRID - 1) * 2 * 3];
        int slot = 0;
        for (int row = 0; row < GRID - 1; row++) {
            for (int column = 0; column < GRID - 1; column++) {
                int first = row * GRID + column;
                int[] quad = {first, first + 1, first + GRID + 1, first + GRID};
                triangles[slot++] = quad[0];
                triangles[slot++] = quad[1];
                triangles[slot++] = quad[2];
                triangles[slot++] = quad[0];
                triangles[slot++] = quad[2];
                triangles[slot++] = quad[3];
            }
        }
        HalfEdgeMesh sheet = HalfEdgeMeshOps.buildFromIndexedMesh(positions, triangles);
        sheet.computeNormals();
        int faces = sheet.faceCount();
        int vertices = sheet.vertexCount();
        assertTrue(foldedCount(sheet) > 0, "the pleat folds a face before the unfold");
        Vector3f corner = sheet.vertexPosition(sheet.vertexIdAt(0), new Vector3f());

        MeshUnfold unfolded = MeshUnfold.run(sheet);

        assertTrue(unfolded.foldedFaceCount > 0, "the unfold saw the pleat");
        assertEquals(0, unfolded.stillFoldedFaceCount, "no face is left folded");
        assertEquals(0, foldedCount(sheet), "a fresh fold test agrees");
        assertEquals(faces, sheet.faceCount(), SAME_TOPOLOGY);
        assertEquals(vertices, sheet.vertexCount(), SAME_TOPOLOGY);
        assertTrue(unfolded.movedVertexCount < vertices / 2, "only the pleat's corners moved");
        assertEquals(corner, sheet.vertexPosition(sheet.vertexIdAt(0), new Vector3f()),
                "a vertex far from the pleat stays where it was");
    }

    /**
     * How many faces quad_layout's fold test marks folded.
     *
     * @param mesh the surface
     * @return the folded face count
     */
    private static int foldedCount(HalfEdgeMesh mesh) {
        int[] activeOf = new int[mesh.vertexIdBound()];
        boolean[] folded = new MeshFolds(mesh, activeOf, mesh.packedPositions(activeOf)).foldedAtStart;
        int count = 0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            if (folded[mesh.faceIdAt(activeFace)]) {
                count++;
            }
        }
        return count;
    }
}
