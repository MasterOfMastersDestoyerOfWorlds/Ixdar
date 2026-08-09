package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ExactBarycentricOrient;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Joining two vertices of one source face by an edge. The strip of children the chord
 * crosses is retired and rebuilt, so the chord costs no vertex and no face however many
 * children lie in its way.
 */
class ChordInsertionTest {

    /** Corners of a triangle. */
    private static final int CORNERS = 3;

    /** The only source face of every fixture here. */
    private static final int SOURCE_FACE = 0;

    /** Arc the chords under test belong to. */
    private static final int ARC = 4;

    /** A different arc, used to hold a chord against a crossing one. */
    private static final int OTHER_ARC = 5;

    /** Edge of the fixture triangle running from its first corner to its second. */
    private static final int FIRST_EDGE_LENGTH = 6;

    /** Fraction of a source face's chart area below which a triangle is a needle. */
    private static final double MINIMUM_CHART_FRACTION = 1.0e-9;

    /** Slack allowed when child areas are summed back to the source face's. */
    private static final double AREA_TOLERANCE = 1.0e-12;

    /**
     * Two vertices an edge already joins need no work: the chord is that edge, and the
     * mesh is left exactly as it was.
     */
    @Test
    void alreadyAdjacentVerticesAreLeftAlone() {
        EmbeddedMeshTopology topology = triangle();
        int lane = topology.splitEdgeAtParameter(edgeBetweenCorners(topology, 0, 1), 0.5);
        int opposite = topology.copyVertexForSourceVertexId(
            topology.sourceMesh.faceVertexAt(topology.sourceMesh.faceIdAt(SOURCE_FACE), 2));
        int vertices = topology.copy.vertexCount();
        int faces = topology.copy.faceCount();

        List<Integer> chain = topology.insertChord(SOURCE_FACE, lane, opposite, ARC);

        assertEquals(List.of(lane, opposite), chain, "an existing edge was not used as the chord");
        assertEquals(vertices, topology.copy.vertexCount(), "an adjacent pair minted a vertex");
        assertEquals(faces, topology.copy.faceCount(), "an adjacent pair changed the face count");
    }

    /**
     * A chord crossing one interior edge produces an edge between its endpoints while
     * leaving both the vertex count and the face count untouched, which is the property
     * that separates snapping from splitting.
     */
    @Test
    void chordAcrossOneEdgeCostsNoVertexAndNoFace() {
        EmbeddedMeshTopology topology = laneFixture();
        int from = laneVertex(topology);
        int to = crossLaneVertex(topology);
        int vertices = topology.copy.vertexCount();
        int faces = topology.copy.faceCount();
        assertEquals(EmbeddedMeshTopology.UNCLAIMED, topology.edgeBetween(from, to),
            "the fixture already joins the endpoints, so no strip is crossed");

        topology.insertChord(SOURCE_FACE, from, to, ARC);

        assertNotEquals(EmbeddedMeshTopology.UNCLAIMED, topology.edgeBetween(from, to),
            "the chord left no edge between its endpoints");
        assertEquals(vertices, topology.copy.vertexCount(), "the chord minted a vertex");
        assertEquals(faces, topology.copy.faceCount(), "the chord changed the face count");
    }

    /**
     * The rebuilt strip covers the same ground it replaced: the children of the source
     * face still tile it exactly, with no overlap, no hole and nothing inverted.
     */
    @Test
    void rebuiltStripTilesTheSourceFaceExactly() {
        EmbeddedMeshTopology topology = laneFixture();

        topology.insertChord(SOURCE_FACE, laneVertex(topology), crossLaneVertex(topology), ARC);

        assertEquals(1.0, coveredArea(topology), AREA_TOLERANCE,
            "the children no longer tile the source face");
    }

    /**
     * A chord bending around an interior node vertex triangulates without inverting
     * anything, which is the case a fan from the node would get wrong.
     */
    @Test
    void chordPastAnInteriorNodeInvertsNothing() {
        EmbeddedMeshTopology topology = interiorNodeFixture();
        int corner = topology.copyVertexForSourceVertexId(
            topology.sourceMesh.faceVertexAt(topology.sourceMesh.faceIdAt(SOURCE_FACE), 2));
        int faces = topology.copy.faceCount();

        topology.insertChord(SOURCE_FACE, corner, laneVertex(topology), ARC);

        assertEquals(faces, topology.copy.faceCount(), "the chord changed the face count");
        assertEquals(1.0, coveredArea(topology), AREA_TOLERANCE,
            "a triangle past the interior node is inverted or missing");
    }

    /**
     * No triangle the rebuild produces may be a needle, since Stage 5 divides by a
     * triangle's share of its source face's chart area.
     */
    @Test
    void rebuiltTrianglesAreNotNeedles() {
        EmbeddedMeshTopology topology = laneFixture();

        topology.insertChord(SOURCE_FACE, laneVertex(topology), crossLaneVertex(topology), ARC);

        for (int child : topology.copyFacesBySourceFace.get(SOURCE_FACE)) {
            assertTrue(childArea(topology, child) > MINIMUM_CHART_FRACTION,
                "copy face " + child + " is a needle at " + childArea(topology, child));
        }
    }

    /**
     * A chord that would cross one another arc already holds is refused by name rather
     * than silently cut through, because crossing chords would corrupt the arrangement.
     */
    @Test
    void crossingAHeldChordIsRefused() {
        EmbeddedMeshTopology topology = laneFixture();
        int held = laneVertex(topology);
        int across = crossLaneVertex(topology);
        topology.insertChord(SOURCE_FACE, held, across, OTHER_ARC);
        int corner = topology.copyVertexForSourceVertexId(
            topology.sourceMesh.faceVertexAt(topology.sourceMesh.faceIdAt(SOURCE_FACE), 2));
        int blocked = farLaneVertex(topology);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> topology.insertChord(SOURCE_FACE, blocked, corner, ARC));

        assertTrue(refused.getMessage().contains("arc " + OTHER_ARC),
            "the refusal does not name the arc holding the crossed chord: "
                + refused.getMessage());
    }

    /**
     * A chord aimed exactly through an existing vertex stops there and carries on, so the
     * exact predicate's zero is an answer rather than a failure.
     */
    @Test
    void chordThroughAVertexIsLaidInTwoPieces() {
        EmbeddedMeshTopology topology = triangle();
        int apex = topology.copyVertexForSourceVertexId(
            topology.sourceMesh.faceVertexAt(topology.sourceMesh.faceIdAt(SOURCE_FACE), 0));
        int midpoint = topology.splitEdgeAtParameter(edgeBetweenCorners(topology, 1, 2), 0.5);
        int between = topology.splitEdgeAtParameter(topology.edgeBetween(apex, midpoint), 0.5);
        int faces = topology.copy.faceCount();

        List<Integer> chain = topology.insertChord(SOURCE_FACE, apex, midpoint, ARC);

        assertEquals(List.of(apex, between, midpoint), chain,
            "the chord did not stop at the vertex it runs exactly through");
        assertEquals(faces, topology.copy.faceCount(), "the chord changed the face count");
    }

    /**
     * Total chart area of a source face's children, which is {@code 1} exactly when they
     * tile it: any overlap counts twice, any hole is missing, and an inverted triangle
     * subtracts.
     *
     * @param topology working copy to measure
     * @return the summed barycentric area of the children
     */
    private double coveredArea(EmbeddedMeshTopology topology) {
        double covered = 0.0;
        for (int child : topology.copyFacesBySourceFace.get(SOURCE_FACE)) {
            covered += childArea(topology, child);
        }
        return covered;
    }

    /**
     * One child face's share of its source face's chart area, signed so an inverted
     * triangle reads negative.
     *
     * @param topology working copy holding the face
     * @param childFace copy face to measure
     * @return its barycentric area
     */
    private double childArea(EmbeddedMeshTopology topology, int childFace) {
        double[][] corner = new double[CORNERS][];
        for (int index = 0; index < CORNERS; index++) {
            corner[index] = topology.barycentricOf(SOURCE_FACE,
                topology.copy.faceVertexAt(childFace, index));
        }
        return ExactBarycentricOrient.area(corner[0], corner[1], corner[2]);
    }

    /**
     * The first lane minted on the fixture's first edge, which every chord here leaves
     * from.
     *
     * @param topology working copy holding it
     * @return that lane's copy vertex
     */
    private int laneVertex(EmbeddedMeshTopology topology) {
        return topology.originalVertexBound;
    }

    /**
     * The second lane minted on the fixture's first edge, which sits beyond the first
     * chord's path.
     *
     * @param topology working copy holding it
     * @return that lane's copy vertex
     */
    private int farLaneVertex(EmbeddedMeshTopology topology) {
        return topology.originalVertexBound + 1;
    }

    /**
     * The lane minted on the fixture's second edge, which chords run across to.
     *
     * @param topology working copy holding it
     * @return that lane's copy vertex
     */
    private int crossLaneVertex(EmbeddedMeshTopology topology) {
        return topology.originalVertexBound + 2;
    }

    /**
     * One source triangle carrying two lanes on its first edge and one on its second, the
     * smallest fixture where a chord has a child face to cross rather than an edge already
     * joining its ends.
     *
     * @return a working copy of it
     */
    private EmbeddedMeshTopology laneFixture() {
        EmbeddedMeshTopology topology = triangle();
        int near = topology.splitEdgeAtParameter(edgeBetweenCorners(topology, 0, 1), 1.0 / CORNERS);
        int far = topology.splitEdgeAtParameter(topology.edgeBetween(near,
            topology.copyVertexForSourceVertexId(topology.sourceMesh.faceVertexAt(
                topology.sourceMesh.faceIdAt(SOURCE_FACE), 1))), 0.5);
        assertNotEquals(EmbeddedMeshTopology.UNCLAIMED, far, "the second lane was not minted");
        topology.splitEdgeAtParameter(edgeBetweenCorners(topology, 1, 2), 0.5);
        return topology;
    }

    /**
     * One source triangle split at its centroid and then given a lane on its first edge,
     * so a chord across it must bend around the interior vertex.
     *
     * @return a working copy of it
     */
    private EmbeddedMeshTopology interiorNodeFixture() {
        EmbeddedMeshTopology topology = triangle();
        topology.splitFaceAtBarycentric(SOURCE_FACE,
            new double[] { 1.0 / CORNERS, 1.0 / CORNERS, 1.0 / CORNERS });
        topology.splitEdgeAtParameter(edgeBetweenCorners(topology, 0, 1), 1.0 / CORNERS);
        return topology;
    }

    /**
     * The copy edge joining two corners of the fixture's source face.
     *
     * @param topology working copy to look in
     * @param first    first corner index of the source face
     * @param second   second corner index of the source face
     * @return that edge's copy edge id
     */
    private int edgeBetweenCorners(EmbeddedMeshTopology topology, int first, int second) {
        int sourceFaceId = topology.sourceMesh.faceIdAt(SOURCE_FACE);
        return topology.edgeBetween(
            topology.copyVertexForSourceVertexId(
                topology.sourceMesh.faceVertexAt(sourceFaceId, first)),
            topology.copyVertexForSourceVertexId(
                topology.sourceMesh.faceVertexAt(sourceFaceId, second)));
    }

    /**
     * A single triangle, the only mesh where every chord stays inside one source face.
     *
     * @return a working copy of it
     */
    private EmbeddedMeshTopology triangle() {
        float[] positions = { 0.0f, 0.0f, 0.0f, FIRST_EDGE_LENGTH, 0.0f, 0.0f,
            0.0f, FIRST_EDGE_LENGTH, 0.0f };
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(positions,
            new int[] { 0, 1, 2 });
        return new EmbeddedMeshTopology(mesh);
    }
}
