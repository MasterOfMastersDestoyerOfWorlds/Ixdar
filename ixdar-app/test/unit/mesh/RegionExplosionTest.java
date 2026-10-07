package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RegionExplosion;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

/**
 * Exploded view on three squares in a row, each its own region, the last twice as wide: centroids
 * are area-weighted, every region moves by the amount times its centroid's distance from the model
 * centroid, and stepping advances, holds and eases the amount it writes under each region's tag.
 */
class RegionExplosionTest {

    private static final float TOLERANCE = 1e-5f;

    private static final float HALF = 0.5f;

    private static final float QUARTER = 0.25f;

    private static final float[] SQUARE_LEFT = { 0f, 2f, 4f };

    private static final float[] SQUARE_SIDE = { 1f, 1f, 2f };

    @Test
    void centroidsAreAreaWeighted() {
        RegionExplosion explosion = measuredSquares();

        assertEquals(1.0, explosion.regionArea[0], TOLERANCE);
        assertEquals(4.0, explosion.regionArea[2], TOLERANCE);
        assertEquals(5f, explosion.regionCentroidXyz[3 * 2], TOLERANCE);
        assertEquals(1f, explosion.regionCentroidXyz[3 * 2 + 1], TOLERANCE);
        // (1 * 0.5 + 1 * 2.5 + 4 * 5) / 6 and (1 * 0.5 + 1 * 0.5 + 4 * 1) / 6.
        assertEquals(23f / 6f, explosion.modelCentroid.x, TOLERANCE);
        assertEquals(5f / 6f, explosion.modelCentroid.y, TOLERANCE);
        assertEquals(0f, explosion.modelCentroid.z, TOLERANCE);
    }

    @Test
    void offsetsScaleCentroidsAboutTheModelCentroid() {
        RegionExplosion explosion = measuredSquares();
        Map<String, Vector3f> offsets = new HashMap<>();

        explosion.step(RegionExplosion.SWEEP_SECONDS, 0, offsets);
        assertTrue(offsets.isEmpty());

        explosion.toggle();
        explosion.step(RegionExplosion.SWEEP_SECONDS, 0, offsets);
        float spread = RegionExplosion.FULL_SPREAD;
        assertEquals(spread * (0.5f - 23f / 6f), offsets.get(tag(0)).x, TOLERANCE);
        assertEquals(spread * (0.5f - 5f / 6f), offsets.get(tag(0)).y, TOLERANCE);
        assertEquals(spread * (5f - 23f / 6f), offsets.get(tag(2)).x, TOLERANCE);
        assertEquals(spread * (1f - 5f / 6f), offsets.get(tag(2)).y, TOLERANCE);
        assertEquals(0f, offsets.get(tag(2)).z, TOLERANCE);
    }

    @Test
    void everyPairSeparatesAlongItsCentroidLine() {
        RegionExplosion explosion = measuredSquares();
        Map<String, Vector3f> offsets = new HashMap<>();

        explosion.toggle();
        explosion.step(RegionExplosion.SWEEP_SECONDS, 0, offsets);
        for (int region = 0; region < 3; region++) {
            for (int other = region + 1; other < 3; other++) {
                for (int axis = 0; axis < 3; axis++) {
                    float centroidGap = explosion.regionCentroidXyz[3 * region + axis]
                            - explosion.regionCentroidXyz[3 * other + axis];
                    assertEquals(RegionExplosion.FULL_SPREAD * centroidGap,
                            offsets.get(tag(region)).get(axis) - offsets.get(tag(other)).get(axis),
                            TOLERANCE);
                }
            }
        }
    }

    @Test
    void steppingAdvancesByTheSweepAndWritesEasedOffsets() {
        RegionExplosion explosion = measuredSquares();
        Map<String, Vector3f> offsets = new HashMap<>();

        explosion.toggle();
        explosion.step(QUARTER * RegionExplosion.SWEEP_SECONDS, 0, offsets);

        assertEquals(QUARTER, explosion.progress, TOLERANCE);
        // Smoothstep at a quarter: 0.25^2 * (3 - 0.5).
        float eased = QUARTER * QUARTER * (3 - 2 * QUARTER);
        assertEquals(eased, explosion.appliedAmount, TOLERANCE);
        assertTrue(explosion.exploded());
        assertEquals(3, offsets.size());
        float spread = eased * RegionExplosion.FULL_SPREAD;
        assertEquals(spread * (5f - 23f / 6f), offsets.get(tag(2)).x, TOLERANCE);
        assertEquals(spread * (1f - 5f / 6f), offsets.get(tag(2)).y, TOLERANCE);

        explosion.step(RegionExplosion.SWEEP_SECONDS, 0, offsets);
        assertEquals(1f, explosion.progress, TOLERANCE);
        assertEquals(1f, explosion.appliedAmount, TOLERANCE);
    }

    @Test
    void aHeldDirectionMovesTheAmountAndHoldsIt() {
        RegionExplosion explosion = measuredSquares();
        Map<String, Vector3f> offsets = new HashMap<>();

        explosion.step(HALF * RegionExplosion.SWEEP_SECONDS, 1, offsets);
        assertEquals(HALF, explosion.progress, TOLERANCE);
        assertEquals(HALF, explosion.target, TOLERANCE);

        explosion.step(RegionExplosion.SWEEP_SECONDS, 0, offsets);
        assertEquals(HALF, explosion.progress, TOLERANCE);
        assertEquals(HALF, explosion.appliedAmount, TOLERANCE);

        explosion.step(RegionExplosion.SWEEP_SECONDS, -1, offsets);
        assertEquals(0f, explosion.progress, TOLERANCE);
        assertFalse(explosion.exploded());
        assertTrue(offsets.isEmpty());
    }

    @Test
    void collapseAndAnimateToClampAndClear() {
        RegionExplosion explosion = measuredSquares();
        Map<String, Vector3f> offsets = new HashMap<>();

        explosion.animateTo(2f);
        assertEquals(1f, explosion.target, TOLERANCE);
        explosion.step(RegionExplosion.SWEEP_SECONDS, 0, offsets);
        assertTrue(explosion.exploded());

        explosion.collapse(offsets);
        assertFalse(explosion.exploded());
        assertEquals(0f, explosion.target, TOLERANCE);
        assertTrue(offsets.isEmpty());
    }

    @Test
    void aMismatchedRegionAssignmentIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new RegionExplosion(RegionExplosionTest::tag).measure(threeSquares(),
                        new int[] { 0, 1, 2 }, 3));
    }

    /** The three squares measured as regions 0, 1 and 2, assembled. */
    private static RegionExplosion measuredSquares() {
        RegionExplosion explosion = new RegionExplosion(RegionExplosionTest::tag);
        explosion.measure(threeSquares(), new int[] { 0, 0, 1, 1, 2, 2 }, 3);
        return explosion;
    }

    /** The draw tag a region's offset is written under in these tests. */
    private static String tag(int region) {
        return "region_" + region;
    }

    /** Three disjoint squares in the z = 0 plane, two triangles each, in region order. */
    private static MeshTopology threeSquares() {
        float[] positions = new float[3 * 4 * SQUARE_LEFT.length];
        int[] triangles = new int[3 * 2 * SQUARE_LEFT.length];
        for (int square = 0; square < SQUARE_LEFT.length; square++) {
            float left = SQUARE_LEFT[square];
            float side = SQUARE_SIDE[square];
            float[] corners = { left, 0f, left + side, 0f, left + side, side, left, side };
            for (int corner = 0; corner < 4; corner++) {
                positions[3 * (4 * square + corner)] = corners[2 * corner];
                positions[3 * (4 * square + corner) + 1] = corners[2 * corner + 1];
            }
            int base = 4 * square;
            int[] squareTriangles = { base, base + 1, base + 2, base, base + 2, base + 3 };
            System.arraycopy(squareTriangles, 0, triangles, 3 * 2 * square, squareTriangles.length);
        }
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, triangles);
    }
}
