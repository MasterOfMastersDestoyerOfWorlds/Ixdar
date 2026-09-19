package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.FaceStripPath;
import ixdar.geometry.mesh.quadlayout.embedding.SnappingCarve;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * A route whose last crossing sits on an edge its own end node sits on runs into
 * that node along the edge, so a lane minted there leaves its final hop on an
 * edge a second arc between the same ends also wants.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
class CarveEndCrossingTrimTest {

    /** The only source face of the fixture. */
    private static final int SOURCE_FACE = 0;

    /** Node the route runs from, an interior vertex on no crossed edge. */
    private static final int START_NODE = 0;

    /** Node it runs to, which sits on an endpoint of its last crossed edge. */
    private static final int END_NODE = 1;

    /** The arc the fixture's route belongs to. */
    private static final int ARC = 0;

    /** Arcs the fixture declares, so the carve's per-arc arrays have a length. */
    private static final int ARC_COUNT = 1;

    /** Nodes the fixture declares. */
    private static final int NODE_COUNT = 2;

    /**
     * Traced position of the last crossing, hard against the end node's corner —
     * bolt's offending crossing sat at 0.99999999999901.
     */
    private static final double AT_THE_CORNER = 0.99999999999901;

    /** Traced position of a crossing well away from either end of its edge. */
    private static final double MID_EDGE = 0.4;

    /** First weight of the point the fixture mints its interior vertex at. */
    private static final double INSIDE_FIRST = 0.3;

    /** Second weight of that point. */
    private static final double INSIDE_SECOND = 0.4;

    /**
     * The last crossing is dropped when its edge has the route's own end vertex as
     * an endpoint, leaving the route to reach the node from the passage before it.
     */
    @Test
    void aLastCrossingOnTheEndNodesOwnEdgeIsDropped() {
        EmbeddedMeshTopology topology = triangle();
        int[] corners = cornersOf(topology);
        SnappingCarve carve = carveOver(topology, corners);
        FaceStripPath strip = route(topology);
        addCrossing(strip, corners[0], corners[1], MID_EDGE);
        addCrossing(strip, corners[1], corners[2], AT_THE_CORNER);
        closeRoute(strip);

        carve.trimEndCrossings(strip, ARC);

        assertEquals(1, strip.crossedEdges.size(), "only the offending crossing is dropped");
        assertEquals(1, carve.trimmedEndCrossingCount, "the trim is counted");
        assertFalse(strip.crossingTouches(0, carve.vertexIdByNode[END_NODE]),
            "the surviving crossing is clear of the end node's vertex");
        assertEquals(strip.crossedEdges.size() + 1, strip.passageFaces.size(),
            "a route still has one more passage than it has crossings");
    }

    /**
     * A route whose crossings all sit away from its nodes' vertices is left alone,
     * so the guard costs nothing on the arcs that were already sound.
     */
    @Test
    void aRouteClearOfItsNodesEdgesIsUntouched() {
        EmbeddedMeshTopology topology = triangle();
        int[] corners = cornersOf(topology);
        SnappingCarve carve = carveOver(topology, corners);
        FaceStripPath strip = route(topology);
        addCrossing(strip, corners[0], corners[1], MID_EDGE);
        closeRoute(strip);

        carve.trimEndCrossings(strip, ARC);

        assertEquals(1, strip.crossedEdges.size(), "the crossing survives");
        assertEquals(0, carve.trimmedEndCrossingCount, "nothing was trimmed");
    }

    /**
     * A carve over the fixture with its node and arc bookkeeping filled in by hand,
     * which is all {@code trimEndCrossings} reads. The start node takes a minted
     * interior vertex so no crossed edge touches it.
     *
     * @param topology working copy the routes are refined onto
     * @param corners  the source triangle's three copy vertices
     * @return the carve, ready to trim
     */
    private SnappingCarve carveOver(EmbeddedMeshTopology topology, int[] corners) {
        SnappingCarve carve = new SnappingCarve(topology);
        carve.vertexIdByNode = new int[NODE_COUNT];
        carve.vertexIdByNode[START_NODE] = topology.splitFaceAtBarycentric(SOURCE_FACE,
            new double[] { INSIDE_FIRST, INSIDE_SECOND, 1.0 - INSIDE_FIRST - INSIDE_SECOND });
        carve.vertexIdByNode[END_NODE] = corners[2];
        carve.startNodeByArc = new int[ARC_COUNT];
        carve.endNodeByArc = new int[ARC_COUNT];
        carve.startNodeByArc[ARC] = START_NODE;
        carve.endNodeByArc[ARC] = END_NODE;
        return carve;
    }

    /**
     * An empty route over the fixture, to which crossings are appended.
     *
     * @param topology working copy the route is refined onto
     * @return the route
     */
    private FaceStripPath route(EmbeddedMeshTopology topology) {
        return new FaceStripPath(topology, ARC);
    }

    /**
     * Appends one crossed edge to a route along with the passage entered before it.
     *
     * @param strip    route to append to
     * @param first    one endpoint of the crossed edge
     * @param second   the other endpoint
     * @param position traced position along it
     */
    private void addCrossing(FaceStripPath strip, int first, int second, double position) {
        strip.passageFaces.add(SOURCE_FACE);
        strip.passageSourceFaces.add(SOURCE_FACE);
        strip.crossedEdges.add(new int[] { Math.min(first, second), Math.max(first, second) });
        strip.crossedVertices.add(EmbeddedMeshTopology.UNCLAIMED);
        strip.crossingParameters.add(position);
    }

    /**
     * Adds the passage after the route's last crossing, so it holds one more
     * passage than it has crossings.
     *
     * @param strip route to close
     */
    private void closeRoute(FaceStripPath strip) {
        strip.passageFaces.add(SOURCE_FACE);
        strip.passageSourceFaces.add(SOURCE_FACE);
    }

    /**
     * The three copy vertices of the fixture's only face, before anything splits it.
     *
     * @param topology working copy holding them
     * @return the corners in face order
     */
    private int[] cornersOf(EmbeddedMeshTopology topology) {
        int face = topology.copyFacesBySourceFace.get(SOURCE_FACE).iterator().next();
        return new int[] { topology.copy.faceVertexAt(face, 0),
            topology.copy.faceVertexAt(face, 1), topology.copy.faceVertexAt(face, 2) };
    }

    /**
     * A single triangle, the smallest working copy a route can be laid in.
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
