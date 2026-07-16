package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedContraction;
import ixdar.geometry.mesh.quadlayout.embedding.PatchRectangleMap;
import ixdar.geometry.mesh.quadlayout.embedding.PatchRegionMapper;
import ixdar.geometry.mesh.quadlayout.embedding.PatchRegions;
import ixdar.geometry.mesh.quadlayout.embedding.TorusLayoutFixture;

/**
 * The Tutte engine ({@link PatchRectangleMap}) and its patch adapter ({@link PatchRegionMapper}).
 * The engine is checked against a closed-form case a hand can verify and a grid whose interior
 * vertex the uniform average pins to the centre; the adapter is checked by the property the whole
 * construction exists for — every patch of the contracted torus maps to its rectangle with no
 * folded triangles, which is Tutte's 1963 guarantee and the precondition for straight-line arc
 * tracing.
 */
class PatchRectangleMapTest {

    private static final double TOLERANCE = 1e-9;

    /**
     * A unit square with a single centre vertex fanned to its four corners: uniform Tutte places
     * the centre at the average of the four corners, which is the rectangle's centre. This is the
     * smallest case where the answer is known in closed form, so it catches a wrong weight sign or
     * a transposed coordinate that the fold-free check alone could miss.
     */
    @Test
    void squareWithCentreVertexMapsCentreToRectangleCentre() {
        Vector3f[] positions = {
            new Vector3f(0f, 0f, 0f),
            new Vector3f(1f, 0f, 0f),
            new Vector3f(1f, 1f, 0f),
            new Vector3f(0f, 1f, 0f),
            new Vector3f(0.5f, 0.5f, 0f),
        };
        int[][] triangles = {{0, 1, 4}, {1, 2, 4}, {2, 3, 4}, {3, 0, 4}};
        int[] boundaryLoop = {0, 1, 2, 3};
        int[] cornerAt = {0, 1, 2, 3};

        PatchRectangleMap map = new PatchRectangleMap(positions, triangles, boundaryLoop, cornerAt,
                1.0, 1.0).build();

        map.assertFoldFree();
        assertEquals(0.5, map.rectangleU[4], TOLERANCE, "centre vertex x");
        assertEquals(0.5, map.rectangleV[4], TOLERANCE, "centre vertex y");
    }

    /**
     * A 3×3 grid, boundary pinned to a 2×2 rectangle: the one interior vertex must land at the
     * rectangle centre and every triangle must keep its winding. This exercises the interior solve
     * with a real sparse system rather than a single unknown.
     */
    @Test
    void gridRegionMapsFoldFreeWithCentredInterior() {
        Vector3f[] positions = new Vector3f[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                positions[row * 3 + column] = new Vector3f(column, row, 0f);
            }
        }
        int[][] triangles = {
            {0, 1, 4}, {0, 4, 3},
            {1, 2, 5}, {1, 5, 4},
            {3, 4, 7}, {3, 7, 6},
            {4, 5, 8}, {4, 8, 7},
        };
        int[] boundaryLoop = {0, 1, 2, 5, 8, 7, 6, 3};
        int[] cornerAt = {0, 2, 4, 6};

        PatchRectangleMap map = new PatchRectangleMap(positions, triangles, boundaryLoop, cornerAt,
                2.0, 2.0).build();

        map.assertFoldFree();
        assertEquals(1.0, map.rectangleU[4], TOLERANCE, "interior vertex x");
        assertEquals(1.0, map.rectangleV[4], TOLERANCE, "interior vertex y");
    }

    /**
     * The gate the plan names: after contracting the Figure-9 torus to a zero-element-free layout,
     * every live patch maps bijectively onto its rectangle — no folded triangles — so a straight
     * line drawn in any patch's rectangle is guaranteed crossing-free when pulled back.
     */
    @Test
    void everyContractedPatchMapsFoldFree() {
        TorusLayoutFixture fixture = new TorusLayoutFixture();
        new EmbeddedContraction(fixture.tmesh, TorusLayoutFixture.TORUS_EULER_CHARACTERISTIC)
                .contract();
        PatchRegions regions = new PatchRegions(fixture.tmesh).build();
        PatchRegionMapper mapper = new PatchRegionMapper(fixture.tmesh, regions);

        int mapped = 0;
        for (int patchId = 0; patchId < fixture.tmesh.patches.size(); patchId++) {
            if (!fixture.tmesh.patches.get(patchId).alive) {
                continue;
            }
            mapper.mapPatch(patchId).assertFoldFree();
            mapped++;
        }
        assertTrue(mapped > 0, "the contracted torus should have live patches to map");
    }
}
