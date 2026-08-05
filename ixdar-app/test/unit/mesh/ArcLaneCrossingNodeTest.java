package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.ExactBarycentricOrient;
import ixdar.geometry.mesh.quadlayout.embedding.SourceFaceTriangulation;

/**
 * One face carrying two traces that meet inside it. The motorcycle graph records the
 * crossing as a node on the hitting trace only, so the hit trace runs past it with no
 * chain node and the hitting trace's chord dangles on the hit trace's interior.
 */
class ArcLaneCrossingNodeTest {

    /** Corners of a triangle. */
    private static final int CORNERS = 3;

    /** Local vertex of the crossing node, interior to the face. */
    private static final int CROSSING = 5;

    /** Trace that runs clean across the face and is hit. */
    private static final int HIT_TRACE = 0;

    /** Trace that terminates on the other, at the crossing node. */
    private static final int HITTING_TRACE = 1;

    /**
     * Least share of the face a produced triangle may cover: the floor
     * {@code GridMapOptimizer} divides the parametrization reference by.
     */
    private static final double MINIMUM_AREA_FRACTION = 1.0e-9;

    /**
     * With the hit trace subdivided at the crossing node, both traces share that vertex
     * and the face triangulates. This is what the corrected chain copy must produce.
     */
    @Test
    void sharedCrossingNodeTriangulates() {
        double[][] barycentric = layout();
        int[] boundaryCycle = { 0, 3, 1, 4, 2, 6 };
        int[] chordFrom = { 3, CROSSING, 6 };
        int[] chordTo = { CROSSING, 4, CROSSING };
        int[] chordOwner = { HIT_TRACE, HIT_TRACE, HITTING_TRACE };

        SourceFaceTriangulation triangulation = new SourceFaceTriangulation(
                barycentric, boundaryCycle, chordFrom, chordTo, chordOwner).build();

        assertEquals(barycentric.length, countReferenced(triangulation.triangles),
                "every local vertex is used, so the crossing node is a real corner");
        double faceArea = ExactBarycentricOrient.area(barycentric[0], barycentric[1],
                barycentric[2]);
        double covered = 0.0;
        for (int[] triangle : triangulation.triangles) {
            double fraction = ExactBarycentricOrient.area(barycentric[triangle[0]],
                    barycentric[triangle[1]], barycentric[triangle[2]]) / faceArea;
            assertTrue(fraction >= MINIMUM_AREA_FRACTION, "triangle " + triangle[0] + ","
                    + triangle[1] + "," + triangle[2] + " covers " + fraction
                    + " of the face, below the optimizer's floor");
            covered += fraction;
        }
        assertEquals(1.0, covered, MINIMUM_AREA_FRACTION, "the triangles tile the face");
    }

    /**
     * Without that subdivision the hitting trace's chord dangles on the hit trace's
     * interior, which is not a non-crossing family and must be rejected rather than
     * triangulated into something inverted.
     */
    @Test
    void danglingCrossingChordIsRejected() {
        double[][] barycentric = layout();
        int[] boundaryCycle = { 0, 3, 1, 4, 2, 6 };
        int[] chordFrom = { 3, 6 };
        int[] chordTo = { 4, CROSSING };
        int[] chordOwner = { HIT_TRACE, HITTING_TRACE };

        SourceFaceTriangulation triangulation = new SourceFaceTriangulation(
                barycentric, boundaryCycle, chordFrom, chordTo, chordOwner);
        try {
            triangulation.build();
        } catch (IllegalStateException rejected) {
            assertTrue(rejected.getMessage().contains("non-crossing family"),
                    "rejected for the wrong reason: " + rejected.getMessage());
            return;
        }
        throw new AssertionError("a chord dangling on another chord's interior was"
                + " triangulated instead of rejected");
    }

    /**
     * The shared fixture: a triangle with one crossing point on each of two edges, one
     * on the third, and the crossing node where the two traces meet inside.
     *
     * @return barycentric of each local vertex
     */
    private double[][] layout() {
        double[][] barycentric = new double[7][];
        barycentric[0] = new double[] { 1.0, 0.0, 0.0 };
        barycentric[1] = new double[] { 0.0, 1.0, 0.0 };
        barycentric[2] = new double[] { 0.0, 0.0, 1.0 };
        barycentric[3] = new double[] { 0.5, 0.5, 0.0 };
        barycentric[4] = new double[] { 0.0, 0.5, 0.5 };
        barycentric[CROSSING] = new double[] { 0.25, 0.5, 0.25 };
        barycentric[6] = new double[] { 0.5, 0.0, 0.5 };
        return barycentric;
    }

    /**
     * How many distinct local vertices the triangles reference.
     *
     * @param triangles the triangulation's triangles
     * @return the count of distinct corners used
     */
    private int countReferenced(List<int[]> triangles) {
        boolean[] seen = new boolean[7];
        int count = 0;
        for (int[] triangle : triangles) {
            for (int corner = 0; corner < CORNERS; corner++) {
                count += seen[triangle[corner]] ? 0 : 1;
                seen[triangle[corner]] = true;
            }
        }
        return count;
    }
}
