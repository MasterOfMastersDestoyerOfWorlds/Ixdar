package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.data.fields.crossfield.NDirectionField;
import ixdar.geometry.mesh.data.fields.crossfield.TriangleCurvature;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.quadlayout.NSmoothRefinement;
import ixdar.nodegraph.nodes.primitives.GridMeshNode;

/**
 * KCP13 Appendix B's n-smooth condition {@code |n κ_t| < π}: a triangulated cube breaks it at
 * every triangle (each corner carries a third of a quarter turn too much), so the cross field
 * refuses it, and {@link NSmoothRefinement} splits it until the field builds and its cones obey
 * Poincaré-Hopf. A flat grid already complies and passes through as the same mesh.
 */
class NSmoothRefinementTest {

    private static final int CUBE_VERTICES = 8;

    private static final int FIRST_TOP_VERTEX = 4;

    private static final int GRID = 4;

    private static final int CUBE_EULER_CHARACTERISTIC = 2;

    private static final int[] CUBE_FACES = {
        0, 2, 1, 0, 3, 2,
        4, 5, 6, 4, 6, 7,
        0, 1, 5, 0, 5, 4,
        3, 7, 6, 3, 6, 2,
        0, 4, 7, 0, 7, 3,
        1, 2, 6, 1, 6, 5,
    };

    @Test
    void theCrossFieldRefusesACube() {
        HalfEdgeMesh cube = cube();
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> new NDirectionField().build(cube, SurfaceMetric.of(cube)));
        assertTrue(refusal.getMessage().contains("n-smooth"), refusal.getMessage());
    }

    @Test
    void refinementMakesTheCubeNSmoothAndItsConesSumToFourChi() {
        HalfEdgeMesh cube = cube();
        NSmoothRefinement refinement = NSmoothRefinement.of(cube, null);
        assertTrue(refinement.violatingAtStart > 0, "the cube starts out breaking the condition");
        assertTrue(refinement.mesh.faceCount() > cube.faceCount(), "refinement split edges");
        assertDoesNotThrow(() -> TriangleCurvature.requireNSmooth(refinement.mesh,
                NSmoothRefinement.CROSS_FIELD_SYMMETRY));
        CrossField field = new NDirectionField().build(refinement.mesh,
                SurfaceMetric.of(refinement.mesh));
        int indexSum = 0;
        for (int active = 0; active < field.singularityIndex4.length(); active++) {
            indexSum += field.singularityIndex4.get(active);
        }
        assertEquals(NSmoothRefinement.CROSS_FIELD_SYMMETRY * CUBE_EULER_CHARACTERISTIC, indexSum);
    }

    @Test
    void aMeshThatAlreadyCompliesComesBackAsItself() {
        HalfEdgeMesh grid = GridMeshNode.triangulated(GRID, GRID);
        grid.computeNormals();
        NSmoothRefinement refinement = NSmoothRefinement.of(grid, null);
        assertSame(grid, refinement.mesh);
        assertEquals(0, refinement.edgeSplitCount);
    }

    /**
     * The unit cube, two outward-facing triangles per side.
     *
     * @return the cube, its normals computed
     */
    private static HalfEdgeMesh cube() {
        PackedPoints positions = new PackedPoints(CUBE_VERTICES);
        for (int vertex = 0; vertex < CUBE_VERTICES; vertex++) {
            int x = vertex == 1 || vertex == 2 || vertex == 5 || vertex == 6 ? 1 : 0;
            int y = vertex == 2 || vertex == 3 || vertex == 6 || vertex == 7 ? 1 : 0;
            int z = vertex >= FIRST_TOP_VERTEX ? 1 : 0;
            positions.set(vertex, x, y, z);
        }
        HalfEdgeMesh cube = HalfEdgeMeshOps.buildFromIndexedMesh(positions, CUBE_FACES);
        cube.computeNormals();
        return cube;
    }
}
