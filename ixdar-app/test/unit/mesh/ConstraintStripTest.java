package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.FaceStripPath;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Refining a traced passage onto the constraint mesh, where a node vertex splits the face it
 * was minted in. Which fan edges a passage crosses is decided by the traced geometry alone,
 * so the arc goes the same side of the node its trace did.
 */
class ConstraintStripTest {

    /** Corners of a triangle. */
    private static final int CORNERS = 3;

    /** The only source face of every fixture here. */
    private static final int SOURCE_FACE = 0;

    /** Arc the passages are refined for. */
    private static final int ARC = 2;

    /** Edge length of the fixture triangle. */
    private static final float EDGE_LENGTH = 6.0f;

    /** Weight pulling a node toward one corner, enough to put it past a chord. */
    private static final double TOWARD_CORNER = 4.0 / 6.0;

    /** The two remaining weights of that node. */
    private static final double AWAY_FROM_CORNER = 1.0 / 6.0;

    /**
     * A passage ending at the node stops there, crossing nothing, and lands in the child
     * face its entry edge bounds.
     */
    @Test
    void aPassageEndingAtTheNodeCrossesNothing() {
        EmbeddedMeshTopology topology = triangle();
        int node = topology.splitFaceAtBarycentric(SOURCE_FACE, centroid());
        FaceStripPath route = new FaceStripPath(topology, ARC);

        route.addPassage(SOURCE_FACE, onFirstEdge(), topology.barycentricOf(SOURCE_FACE, node));

        assertEquals(1, route.passageFaces.size(), "the passage was cut where it need not be");
        assertEquals(0, route.crossedEdges.size(), "the passage crossed an edge to reach a node");
        assertTrue(holdsCorner(topology, route.passageFaces.get(0), node),
            "the passage does not land in a face the node is a corner of");
    }

    /**
     * With the node on the far side of the chord, the passage cuts off the shared corner and
     * crosses the single fan edge running to it.
     */
    @Test
    void aNodeBeyondTheChordCostsOneFanCrossing() {
        EmbeddedMeshTopology topology = triangle();
        topology.splitFaceAtBarycentric(SOURCE_FACE, centroid());
        FaceStripPath route = new FaceStripPath(topology, ARC);

        route.addPassage(SOURCE_FACE, onFirstEdge(), onSecondEdge());

        assertEquals(2, route.passageFaces.size(), "passages across a face split by one node");
        assertEquals(1, route.crossedEdges.size(), "fan edges crossed");
    }

    /**
     * With the node pulled to the near side of the same chord, the passage runs the other
     * way round it and crosses two fan edges instead. Only the traced positions differ, so
     * this is the decision the exact predicate has to get right.
     */
    @Test
    void aNodeInsideTheCutOffCornerCostsTwoFanCrossings() {
        EmbeddedMeshTopology topology = triangle();
        topology.splitFaceAtBarycentric(SOURCE_FACE, nearSecondCorner());
        FaceStripPath route = new FaceStripPath(topology, ARC);

        route.addPassage(SOURCE_FACE, onFirstEdge(), onSecondEdge());

        assertEquals(CORNERS, route.passageFaces.size(), "passages round the near-side node");
        assertEquals(2, route.crossedEdges.size(), "fan edges crossed");
    }

    /**
     * Passages always run one longer than crossings, so a crossing's index is also its
     * passage's. Every chord laid downstream reads the two together.
     */
    @Test
    void passagesRunOneLongerThanCrossings() {
        EmbeddedMeshTopology topology = triangle();
        topology.splitFaceAtBarycentric(SOURCE_FACE, nearSecondCorner());
        FaceStripPath route = new FaceStripPath(topology, ARC);

        route.addPassage(SOURCE_FACE, onFirstEdge(), onSecondEdge());

        assertEquals(route.crossedEdges.size() + 1, route.passageFaces.size(),
            "passages and crossings are out of step");
        assertEquals(route.crossedEdges.size(), route.crossingParameters.size(),
            "a crossing was recorded without where the trace met it");
    }

    /**
     * No face of the constraint mesh holds a vertex strictly inside it. This is the property
     * the whole snapping rests on: with nothing loose in a face, keeping the crossings in
     * traced order along each edge is enough to keep two arcs' chords apart.
     */
    @Test
    void noConstraintFaceHoldsAVertexInside() {
        EmbeddedMeshTopology topology = triangle();
        topology.splitFaceAtBarycentric(SOURCE_FACE, centroid());
        topology.splitEdgeAtParameter(topology.edgeBetween(0, 1), 0.5);

        for (int face : topology.copyFacesBySourceFace.get(SOURCE_FACE)) {
            for (int vertexId = 0; vertexId < topology.copy.vertexCount(); vertexId++) {
                assertFalse(topology.strictlyInside(face, SOURCE_FACE,
                    topology.barycentricOf(SOURCE_FACE, vertexId)),
                    "copy vertex " + vertexId + " lies inside constraint face " + face);
            }
        }
    }

    /**
     * Whether a constraint face has a copy vertex as one of its corners.
     *
     * @param topology working copy to look in
     * @param face     constraint face to search
     * @param vertexId copy vertex to find
     * @return true when it is a corner of that face
     */
    private boolean holdsCorner(EmbeddedMeshTopology topology, int face, int vertexId) {
        for (int corner = 0; corner < CORNERS; corner++) {
            if (topology.copy.faceVertexAt(face, corner) == vertexId) {
                return true;
            }
        }
        return false;
    }

    /**
     * The midpoint of the source face's first edge, where every passage here enters.
     *
     * @return its barycentric
     */
    private double[] onFirstEdge() {
        return new double[] { 0.5, 0.5, 0.0 };
    }

    /**
     * The midpoint of the source face's second edge, where every passage here leaves.
     *
     * @return its barycentric
     */
    private double[] onSecondEdge() {
        return new double[] { 0.0, 0.5, 0.5 };
    }

    /**
     * The face's centroid, which lies beyond a chord joining the two edge midpoints.
     *
     * @return its barycentric
     */
    private double[] centroid() {
        return new double[] { 1.0 / CORNERS, 1.0 / CORNERS, 1.0 / CORNERS };
    }

    /**
     * A point pulled toward the second corner, inside the corner that same chord cuts off.
     *
     * @return its barycentric
     */
    private double[] nearSecondCorner() {
        return new double[] { AWAY_FROM_CORNER, TOWARD_CORNER, AWAY_FROM_CORNER };
    }

    /**
     * A single triangle, the only mesh on which every passage stays in one source face.
     *
     * @return a working copy of it
     */
    private EmbeddedMeshTopology triangle() {
        float[] positions = { 0.0f, 0.0f, 0.0f, EDGE_LENGTH, 0.0f, 0.0f,
            0.0f, EDGE_LENGTH, 0.0f };
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(positions,
            new int[] { 0, 1, 2 });
        return new EmbeddedMeshTopology(mesh);
    }
}
