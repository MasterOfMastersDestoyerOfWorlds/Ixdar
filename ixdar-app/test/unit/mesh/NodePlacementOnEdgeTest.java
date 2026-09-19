package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ExactBarycentricOrient;
import ixdar.geometry.mesh.quadlayout.embedding.FaceChordWalk;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Where the re-carve puts a node landing a rounding step off an edge of the working copy. The
 * contraction hands it the interpolated midpoint of an edge, which is never exactly on the
 * line through the two ends once they carry chart coordinates.
 */
class NodePlacementOnEdgeTest {

    /** Corners of a triangle. */
    private static final int CORNERS = 3;

    /** The only source face of the fixture. */
    private static final int SOURCE_FACE = 0;

    /** First weight of the point the fixture splits the source face at. */
    private static final double FIRST_SPLIT_FIRST = 0.2119876543210987;

    /** Second weight of that point, chosen so no component is a round fraction. */
    private static final double FIRST_SPLIT_SECOND = 0.3876543210987654;

    /** Weight the second split puts on the first split's vertex. */
    private static final double SECOND_SPLIT_ON_FIRST = 0.31;

    /** Weight the second split puts on the source face's first corner. */
    private static final double SECOND_SPLIT_ON_CORNER = 0.41;

    /** Weight the second split puts on the source face's second corner. */
    private static final double SECOND_SPLIT_ON_NEXT = 0.28;

    /**
     * A node at the interpolated midpoint of an interior edge splits that edge rather than
     * cutting a sliver beside it, so the edge is gone and the node sits between its two ends.
     */
    @Test
    void aNodeAMidpointOffAnEdgeSplitsThatEdge() {
        EmbeddedMeshTopology topology = triangle();
        int firstSplit = topology.splitFaceAtBarycentric(SOURCE_FACE, firstSplitPoint());
        int secondSplit = topology.splitFaceAtBarycentric(
            childHolding(topology, secondSplitPoint(topology, firstSplit)),
            secondSplitPoint(topology, firstSplit));
        double[] midpoint = interpolatedMidpoint(topology, firstSplit, secondSplit);
        assertNotEquals(0, ExactBarycentricOrient.sign(
            topology.barycentricOf(SOURCE_FACE, firstSplit),
            topology.barycentricOf(SOURCE_FACE, secondSplit), midpoint),
            "the fixture no longer puts the midpoint off the line through the edge");

        int placed = new FaceChordWalk(topology).placeVertex(SOURCE_FACE, midpoint);

        assertEquals(EmbeddedMeshTopology.UNCLAIMED,
            topology.edgeBetween(firstSplit, secondSplit),
            "the edge survived the placement, so a later split of it mints the node again");
        assertNotEquals(EmbeddedMeshTopology.UNCLAIMED,
            topology.edgeBetween(firstSplit, placed),
            "the node is not joined to the edge's first end");
        assertNotEquals(EmbeddedMeshTopology.UNCLAIMED,
            topology.edgeBetween(placed, secondSplit),
            "the node is not joined to the edge's second end");
    }

    /**
     * Minting a lane on the edge the node landed on leaves no two copy vertices at one chart
     * position, which is what the layout resolution needs: two vertices at one point make an
     * arc step of zero length.
     */
    @Test
    void aLaneOnThatEdgeDoesNotLandOnTheNode() {
        EmbeddedMeshTopology topology = triangle();
        int firstSplit = topology.splitFaceAtBarycentric(SOURCE_FACE, firstSplitPoint());
        int secondSplit = topology.splitFaceAtBarycentric(
            childHolding(topology, secondSplitPoint(topology, firstSplit)),
            secondSplitPoint(topology, firstSplit));
        int placed = new FaceChordWalk(topology).placeVertex(SOURCE_FACE,
            interpolatedMidpoint(topology, firstSplit, secondSplit));

        int whole = topology.edgeBetween(firstSplit, secondSplit);
        topology.splitEdgeAtParameter(whole == EmbeddedMeshTopology.UNCLAIMED
            ? topology.edgeBetween(firstSplit, placed) : whole, 0.5);

        for (int first = 0; first < topology.copy.vertexCount(); first++) {
            for (int second = first + 1; second < topology.copy.vertexCount(); second++) {
                assertTrue(separation(topology, first, second)
                        >= FaceChordWalk.COINCIDENT_SEPARATION,
                    "copy vertices " + first + " and " + second + " hold one chart position");
            }
        }
    }

    /**
     * How far apart two copy vertices are in the source face's frame, as the widest gap
     * between their barycentric components.
     *
     * @param topology working copy holding both
     * @param first    one copy vertex
     * @param second   the other copy vertex
     * @return the widest component gap
     */
    private double separation(EmbeddedMeshTopology topology, int first, int second) {
        double[] here = topology.barycentricOf(SOURCE_FACE, first);
        double[] there = topology.barycentricOf(SOURCE_FACE, second);
        double widest = 0.0;
        for (int index = 0; index < CORNERS; index++) {
            widest = Math.max(widest, Math.abs(here[index] - there[index]));
        }
        return widest;
    }

    /**
     * The midpoint of two copy vertices as the interpolation actually computes it, component
     * by component in double, which is how a contracted path carries a bend point.
     *
     * @param topology working copy holding both
     * @param from     copy vertex the edge runs from
     * @param to       copy vertex it runs to
     * @return the midpoint's barycentric in the source face
     */
    private double[] interpolatedMidpoint(EmbeddedMeshTopology topology, int from, int to) {
        double[] start = topology.barycentricOf(SOURCE_FACE, from);
        double[] end = topology.barycentricOf(SOURCE_FACE, to);
        double[] midpoint = new double[CORNERS];
        for (int index = 0; index < CORNERS; index++) {
            midpoint[index] = start[index] + 0.5 * (end[index] - start[index]);
        }
        return midpoint;
    }

    /**
     * The child face of the source face a point lies strictly inside.
     *
     * @param topology    working copy to search
     * @param barycentric the point
     * @throws IllegalStateException when no child holds it
     * @return that child face
     */
    private int childHolding(EmbeddedMeshTopology topology, double[] barycentric) {
        for (int face : topology.copyFacesBySourceFace.get(SOURCE_FACE)) {
            if (topology.strictlyInside(face, SOURCE_FACE, barycentric)) {
                return face;
            }
        }
        throw new IllegalStateException("no child of the fixture holds the second split point");
    }

    /**
     * A point well inside the source face whose weights are round in no base.
     *
     * @return its barycentric
     */
    private double[] firstSplitPoint() {
        return new double[] { FIRST_SPLIT_FIRST, FIRST_SPLIT_SECOND,
            1.0 - FIRST_SPLIT_FIRST - FIRST_SPLIT_SECOND };
    }

    /**
     * A second point inside the corner fan of the first, so the two are joined by an edge
     * whose every component is messy.
     *
     * @param topology   working copy holding the first split
     * @param firstSplit the first split's copy vertex
     * @return its barycentric
     */
    private double[] secondSplitPoint(EmbeddedMeshTopology topology, int firstSplit) {
        double[] anchor = topology.barycentricOf(SOURCE_FACE, firstSplit);
        return new double[] {
            SECOND_SPLIT_ON_FIRST * anchor[0] + SECOND_SPLIT_ON_CORNER,
            SECOND_SPLIT_ON_FIRST * anchor[1] + SECOND_SPLIT_ON_NEXT,
            SECOND_SPLIT_ON_FIRST * anchor[2] };
    }

    /**
     * A single triangle, the smallest working copy a node can be placed in.
     *
     * @return a working copy of it
     */
    private EmbeddedMeshTopology triangle() {
        float[] positions = { 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f };
        int[] faceIndices = { 0, 1, 2 };
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);
        return new EmbeddedMeshTopology(mesh);
    }
}
