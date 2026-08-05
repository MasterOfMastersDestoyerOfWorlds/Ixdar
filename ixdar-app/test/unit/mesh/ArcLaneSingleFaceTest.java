package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.ExactBarycentricOrient;
import ixdar.geometry.mesh.quadlayout.embedding.SourceFaceTriangulation;

/**
 * Five arcs crossing one triangle, each between its own pair of nodes. Every carve
 * point has to be minted inside or on that one face, with no neighbour to borrow a
 * free vertex from, so this is the case the incremental split collapses on.
 */
class ArcLaneSingleFaceTest {

    /** Corners of a triangle. */
    private static final int CORNERS = 3;

    /** Arcs crossing the face; each contributes one chord and two nodes. */
    private static final int ARCS = 5;

    /**
     * How tightly the five arcs are packed against the face's first corner. The
     * incremental carve mints one arc's crossing on the retriangulation edge the
     * previous arc's split left behind, so the gaps shrink geometrically from here.
     */
    private static final double PACKING = 1.0e-3;

    /**
     * Least share of the face's area a produced triangle may cover. This is the floor
     * {@code GridMapOptimizer} divides the parametrization reference by, so anything
     * below it cannot reach the Newton solver at all.
     */
    private static final double MINIMUM_AREA_FRACTION = 1.0e-9;

    /**
     * Five nested chords across one triangle are cut into regions and ear-clipped
     * without minting a single vertex inside the face, so every triangle stays far
     * above the optimizer's area floor.
     */
    @Test
    void packedChordsTriangulateWithoutCollapsing() {
        List<double[]> barycentric = new ArrayList<>();
        barycentric.add(new double[] { 1.0, 0.0, 0.0 });
        barycentric.add(new double[] { 0.0, 1.0, 0.0 });
        barycentric.add(new double[] { 0.0, 0.0, 1.0 });
        List<Integer> alongFirstEdge = new ArrayList<>();
        List<Integer> alongSecondEdge = new ArrayList<>();
        for (int arc = 0; arc < ARCS; arc++) {
            double offset = PACKING * (arc + 1);
            alongFirstEdge.add(barycentric.size());
            barycentric.add(new double[] { 1.0 - offset, offset, 0.0 });
            alongSecondEdge.add(barycentric.size());
            barycentric.add(new double[] { 1.0 - offset, 0.0, offset });
        }

        int[] boundaryCycle = new int[CORNERS + 2 * ARCS];
        int at = 0;
        boundaryCycle[at++] = 0;
        for (int arc = 0; arc < ARCS; arc++) {
            boundaryCycle[at++] = alongFirstEdge.get(arc);
        }
        boundaryCycle[at++] = 1;
        boundaryCycle[at++] = 2;
        for (int arc = ARCS - 1; arc >= 0; arc--) {
            boundaryCycle[at++] = alongSecondEdge.get(arc);
        }
        assertEquals(boundaryCycle.length, at, "boundary cycle filled");

        int[] chordFrom = new int[ARCS];
        int[] chordTo = new int[ARCS];
        int[] chordOwner = new int[ARCS];
        for (int arc = 0; arc < ARCS; arc++) {
            chordFrom[arc] = alongSecondEdge.get(arc);
            chordTo[arc] = alongFirstEdge.get(arc);
            chordOwner[arc] = arc;
        }

        double[][] positions = barycentric.toArray(new double[0][]);
        SourceFaceTriangulation triangulation = new SourceFaceTriangulation(
                positions, boundaryCycle, chordFrom, chordTo, chordOwner).build();

        assertEquals(ARCS, triangulation.cutCount, "every arc cut the face once");
        assertEquals(positions.length, barycentric.size(),
                "the face interior gained no vertex");

        double faceArea = ExactBarycentricOrient.area(positions[0], positions[1], positions[2]);
        double covered = 0.0;
        for (int[] triangle : triangulation.triangles) {
            double fraction = ExactBarycentricOrient.area(positions[triangle[0]],
                    positions[triangle[1]], positions[triangle[2]]) / faceArea;
            assertTrue(fraction > 0.0, "triangle " + triangle[0] + "," + triangle[1] + ","
                    + triangle[2] + " is wound against the face, covering " + fraction);
            assertTrue(fraction >= MINIMUM_AREA_FRACTION, "triangle " + triangle[0] + ","
                    + triangle[1] + "," + triangle[2] + " covers " + fraction
                    + " of the face, below the optimizer's floor " + MINIMUM_AREA_FRACTION);
            covered += fraction;
        }
        assertEquals(1.0, covered, MINIMUM_AREA_FRACTION,
                "the triangles tile the face exactly");

        for (int arc = 0; arc < ARCS; arc++) {
            assertTrue(hasEdge(triangulation.triangles, chordFrom[arc], chordTo[arc]),
                    "arc " + arc + " has no edge between its two carve points");
        }
    }

    /**
     * Whether some produced triangle carries an edge between two local vertices.
     *
     * @param triangles the triangulation's triangles
     * @param from      one local vertex index
     * @param to        the other local vertex index
     * @return true when an edge joins them
     */
    private boolean hasEdge(List<int[]> triangles, int from, int to) {
        for (int[] triangle : triangles) {
            for (int corner = 0; corner < CORNERS; corner++) {
                int at = triangle[corner];
                int next = triangle[(corner + 1) % CORNERS];
                if (at == from && next == to || at == to && next == from) {
                    return true;
                }
            }
        }
        return false;
    }
}
