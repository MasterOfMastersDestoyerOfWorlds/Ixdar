package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Where chord endpoints land on a source edge. Positions come from the count alone, so an
 * edge asked for n of them cuts into n+1 equal fragments and the smallest is 1/(n+1)
 * however close the arcs wanting them actually run.
 */
class SnappingLaneOrderTest {

    /** Corners of a triangle. */
    private static final int CORNERS = 3;

    /** Endpoints asked of the edge under test. */
    private static final int LANES = 3;

    /** How far a lane may sit from its uniform position. */
    private static final double TOLERANCE = 1.0e-9;

    /**
     * Three endpoints on one edge land at 1/4, 2/4 and 3/4 of its length, in order from
     * the edge's canonical start, so the smallest fragment is a quarter of the edge.
     */
    @Test
    void lanesAreUniformAndOrdered() {
        EmbeddedMeshTopology topology = twoTriangles();
        int sourceEdgeId = diagonal(topology.sourceMesh);
        int halfEdge = topology.sourceMesh.edgeHalfEdge(sourceEdgeId);
        Vector3f start = topology.sourceMesh.vertexPosition(
            topology.sourceMesh.halfEdgeVertex(halfEdge));
        Vector3f end = topology.sourceMesh.vertexPosition(
            topology.sourceMesh.halfEdgeEndVertex(halfEdge));

        List<Integer> lanes = splitIntoLanes(topology, sourceEdgeId);

        assertEquals(LANES, lanes.size(), "lanes minted");
        double previous = 0.0;
        for (int lane = 0; lane < lanes.size(); lane++) {
            double along = alongEdge(topology, lanes.get(lane), start, end);
            assertEquals((lane + 1.0) / (LANES + 1.0), along, TOLERANCE,
                "lane " + lane + " is not at its uniform position");
            assertTrue(along > previous, "lane " + lane + " is out of order along the edge");
            previous = along;
        }
        assertTrue(1.0 - previous >= 1.0 / (LANES + 1.0) - TOLERANCE,
            "the tail fragment is shorter than 1/(lanes+1)");
    }

    /**
     * Splitting an edge for n arcs leaves every fragment at least 1/(n+1) long, which is
     * the property that keeps a crowded edge from producing an unmeasurable triangle.
     */
    @Test
    void smallestFragmentIsBoundedByTheCount() {
        EmbeddedMeshTopology topology = twoTriangles();
        int sourceEdgeId = diagonal(topology.sourceMesh);
        int halfEdge = topology.sourceMesh.edgeHalfEdge(sourceEdgeId);
        Vector3f start = topology.sourceMesh.vertexPosition(
            topology.sourceMesh.halfEdgeVertex(halfEdge));
        Vector3f end = topology.sourceMesh.vertexPosition(
            topology.sourceMesh.halfEdgeEndVertex(halfEdge));

        List<Integer> lanes = splitIntoLanes(topology, sourceEdgeId);

        double previous = 0.0;
        double smallest = 1.0;
        for (int lane : lanes) {
            double along = alongEdge(topology, lane, start, end);
            smallest = Math.min(smallest, along - previous);
            previous = along;
        }
        smallest = Math.min(smallest, 1.0 - previous);
        assertTrue(smallest >= 1.0 / (LANES + 1.0) - TOLERANCE,
            "smallest fragment " + smallest + " is under 1/(lanes+1)");
    }

    /**
     * Cuts the edge into {@link #LANES}{@code + 1} uniform fragments the way
     * {@code SnappingCarve.splitEdgeIntoLanes} does, without needing a traced graph.
     *
     * @param topology     working copy to split
     * @param sourceEdgeId source edge to cut
     * @return the minted copy vertices, in order from the edge's canonical start
     */
    private List<Integer> splitIntoLanes(EmbeddedMeshTopology topology, int sourceEdgeId) {
        int halfEdge = topology.sourceMesh.edgeHalfEdge(sourceEdgeId);
        int head = topology.copyVertexForSourceVertexId(
            topology.sourceMesh.halfEdgeVertex(halfEdge));
        int tail = topology.copyVertexForSourceVertexId(
            topology.sourceMesh.halfEdgeEndVertex(halfEdge));
        List<Integer> lanes = new ArrayList<>();
        double placed = 0.0;
        for (int lane = 1; lane <= LANES; lane++) {
            int fragment = topology.edgeBetween(head, tail);
            double target = lane / (LANES + 1.0);
            double local = (target - placed) / (1.0 - placed);
            int canonicalStart = topology.copy.halfEdgeVertex(
                topology.copy.edgeHalfEdge(fragment));
            lanes.add(topology.splitEdgeAtParameter(fragment,
                canonicalStart == head ? local : 1.0 - local));
            head = lanes.get(lanes.size() - 1);
            placed = target;
        }
        return lanes;
    }

    /**
     * How far along an edge a copy vertex sits, as a fraction of its length.
     *
     * @param topology working copy holding the vertex
     * @param vertexId copy vertex to locate
     * @param start    the edge's canonical start position
     * @param end      the edge's canonical end position
     * @return the fraction from start to end
     */
    private double alongEdge(EmbeddedMeshTopology topology, int vertexId, Vector3f start,
            Vector3f end) {
        Vector3f at = topology.copy.vertexPosition(vertexId);
        Vector3f span = new Vector3f(end).sub(start);
        return new Vector3f(at).sub(start).dot(span) / span.dot(span);
    }

    /**
     * The shared edge of the two triangles, which is the one both faces can demand
     * endpoints on.
     *
     * @param mesh the fixture mesh
     * @return the diagonal's edge id
     */
    private int diagonal(HalfEdgeMesh mesh) {
        for (int index = 0; index < mesh.edgeCount(); index++) {
            int edgeId = mesh.edgeIdAt(index);
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            if (mesh.halfEdgeFace(halfEdge) >= 0
                    && mesh.halfEdgeFace(mesh.halfEdgeTwin(halfEdge)) >= 0) {
                return edgeId;
            }
        }
        throw new IllegalStateException("the fixture has no interior edge");
    }

    /**
     * Two triangles sharing one diagonal, the smallest mesh with an interior edge.
     *
     * @return a working copy of them
     */
    private EmbeddedMeshTopology twoTriangles() {
        float[] positions = { 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 0.0f };
        int[] faceIndices = { 0, 1, 2, 2, 1, CORNERS };
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);
        return new EmbeddedMeshTopology(mesh);
    }
}
