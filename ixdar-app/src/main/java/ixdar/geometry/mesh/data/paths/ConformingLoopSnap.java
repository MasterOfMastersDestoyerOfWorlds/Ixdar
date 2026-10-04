package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Snaps a traced geodesic to the nearest cycle of original mesh edges, for consumers that need
 * edge marks rather than a polyline.
 *
 * <p>
 * Traced points collapse to a nearby vertex and are joined by a bounded Dijkstra walk, so no vertex
 * is inserted.
 */
public final class ConformingLoopSnap {

    public static final int DEFAULT_SEARCH_BUDGET = 4096;

    /** Vertex ids the snapped path runs through, in travel order. */
    public int[] vertexCycle = new int[0];

    /** Number of valid entries in {@link #vertexCycle}. */
    public int vertexCycleLength;

    /** Gaps the bounded walk could not close, left unmarked in the output. */
    public int unresolvedGaps;

    /** Vertices one gap-closing walk may settle. */
    public int searchBudget = DEFAULT_SEARCH_BUDGET;

    private int[] visitStamp = new int[0];
    private double[] visitDistance = new double[0];
    private int[] visitParent = new int[0];
    private int currentStamp;
    private final Vector3f scratchPosition = new Vector3f();
    private final Vector3f otherPosition = new Vector3f();

    /**
     * Marks the edges of the conforming path nearest a traced geodesic, only ever the mesh's own
     * edges: a point inside a polygon snaps to that polygon's nearest corner.
     *
     * @param mesh source mesh the trace was taken on
     * @param path traced polyline with its per-point vertex, edge or face correspondence
     * @return per-edge-id flags, true on every edge of the snapped path
     */
    public boolean[] snap(MeshTopology mesh, TracedSurfacePath path) {
        int maxEdgeId = 0;
        for (int index = 0; index < mesh.edgeCount(); index++) {
            maxEdgeId = Math.max(maxEdgeId, mesh.edgeIdAt(index));
        }
        boolean[] marks = new boolean[maxEdgeId + 1];
        vertexCycleLength = 0;
        unresolvedGaps = 0;
        if (path.pointCount == 0) {
            vertexCycle = new int[0];
            return marks;
        }
        prepareVisitBuffers(mesh);

        for (int index = 0; index < path.pointCount; index++) {
            int vertexId = path.vertexId[index];
            int faceId = path.faceId[index];
            if (vertexId < 0 && faceId < 0) {
                vertexId = nearerEdgeEnd(mesh, path.edgeId[index], path.fraction[index]);
            }
            double nearest = Double.POSITIVE_INFINITY;
            int corners = vertexId < 0 ? mesh.faceVertexCount(faceId) : 0;
            for (int corner = 0; corner < corners; corner++) {
                int cornerVertexId = mesh.faceVertexAt(faceId, corner);
                mesh.vertexPosition(cornerVertexId, scratchPosition);
                double distance = scratchPosition.distanceSquared(
                        (float) path.surfacePositions[3 * index],
                        (float) path.surfacePositions[3 * index + 1],
                        (float) path.surfacePositions[3 * index + 2]);
                if (distance < nearest) {
                    nearest = distance;
                    vertexId = cornerVertexId;
                }
            }
            appendVertex(vertexId);
        }
        if (path.closed && vertexCycleLength > 1
                && vertexCycle[0] == vertexCycle[vertexCycleLength - 1]) {
            vertexCycleLength--;
        }

        int spans = path.closed ? vertexCycleLength : vertexCycleLength - 1;
        for (int index = 0; index < spans; index++) {
            int fromVertex = vertexCycle[index];
            int toVertex = vertexCycle[(index + 1) % vertexCycleLength];
            if (!markShortestEdgeWalk(mesh, fromVertex, toVertex, marks)) {
                unresolvedGaps++;
            }
        }
        vertexCycle = Arrays.copyOf(vertexCycle, vertexCycleLength);
        if (!path.closed) {
            return marks;
        }
        // A walk whose next walk turns back along its last edge leaves a dead-end spur: across a
        // polygon, or where consecutive traced points snap to vertices A, B, A on a fine triangle
        // mesh. A closed ring never needs one, so unmark them down to the loop.
        int[] markedDegree = new int[visitStamp.length];
        for (int edgeId = 0; edgeId < marks.length; edgeId++) {
            if (marks[edgeId]) {
                int halfEdge = mesh.edgeHalfEdge(edgeId);
                markedDegree[mesh.halfEdgeVertex(halfEdge)]++;
                markedDegree[mesh.halfEdgeEndVertex(halfEdge)]++;
            }
        }
        int[] deadEnds = new int[markedDegree.length];
        int deadEndCount = 0;
        for (int vertexId = 0; vertexId < markedDegree.length; vertexId++) {
            if (markedDegree[vertexId] == 1) {
                deadEnds[deadEndCount++] = vertexId;
            }
        }
        while (deadEndCount > 0) {
            int vertexId = deadEnds[--deadEndCount];
            int spokes = mesh.vertexEdgeCount(vertexId);
            for (int spoke = 0; spoke < spokes && markedDegree[vertexId] == 1; spoke++) {
                int edgeId = mesh.vertexEdgeAt(vertexId, spoke);
                if (edgeId >= marks.length || !marks[edgeId]) {
                    continue;
                }
                marks[edgeId] = false;
                markedDegree[vertexId]--;
                int other = mesh.edgeOtherVertex(edgeId, vertexId);
                if (--markedDegree[other] == 1) {
                    deadEnds[deadEndCount++] = other;
                }
            }
        }
        return marks;
    }

    private void prepareVisitBuffers(MeshTopology mesh) {
        int maxVertexId = 0;
        for (int index = 0; index < mesh.vertexCount(); index++) {
            maxVertexId = Math.max(maxVertexId, mesh.vertexIdAt(index));
        }
        if (visitStamp.length <= maxVertexId) {
            visitStamp = new int[maxVertexId + 1];
            visitDistance = new double[maxVertexId + 1];
            visitParent = new int[maxVertexId + 1];
            currentStamp = 0;
        }
    }

    private int nearerEdgeEnd(MeshTopology mesh, int edgeId, double fraction) {
        int halfEdge = mesh.edgeHalfEdge(edgeId);
        return fraction <= 0.5 ? mesh.halfEdgeVertex(halfEdge) : mesh.halfEdgeEndVertex(halfEdge);
    }

    private void appendVertex(int vertexId) {
        if (vertexCycleLength > 0 && vertexCycle[vertexCycleLength - 1] == vertexId) {
            return;
        }
        if (vertexCycleLength == vertexCycle.length) {
            vertexCycle = Arrays.copyOf(vertexCycle, Math.max(64, vertexCycle.length * 2));
        }
        vertexCycle[vertexCycleLength++] = vertexId;
    }

    /**
     * Marks the shortest edge walk between two vertices, searching only far enough to cover the
     * gap two consecutive snapped points can leave.
     */
    private boolean markShortestEdgeWalk(MeshTopology mesh, int fromVertex, int toVertex,
            boolean[] marks) {
        if (fromVertex == toVertex) {
            return true;
        }
        currentStamp++;
        visitStamp[fromVertex] = currentStamp;
        visitDistance[fromVertex] = 0.0;
        visitParent[fromVertex] = -1;
        PriorityQueue<double[]> frontier = new PriorityQueue<>(
                Comparator.comparingDouble(entry -> entry[0]));
        frontier.add(new double[] { 0.0, fromVertex });
        int settled = 0;
        boolean reached = false;
        while (!frontier.isEmpty() && settled < searchBudget) {
            double[] entry = frontier.poll();
            int vertexId = (int) entry[1];
            if (entry[0] > visitDistance[vertexId]) {
                continue;
            }
            settled++;
            if (vertexId == toVertex) {
                reached = true;
                break;
            }
            mesh.vertexPosition(vertexId, scratchPosition);
            int spokes = mesh.vertexEdgeCount(vertexId);
            for (int spoke = 0; spoke < spokes; spoke++) {
                int edgeId = mesh.vertexEdgeAt(vertexId, spoke);
                int other = mesh.edgeOtherVertex(edgeId, vertexId);
                if (other < 0) {
                    continue;
                }
                mesh.vertexPosition(other, otherPosition);
                double relaxed = visitDistance[vertexId] + scratchPosition.distance(otherPosition);
                if (visitStamp[other] != currentStamp || relaxed < visitDistance[other]) {
                    visitStamp[other] = currentStamp;
                    visitDistance[other] = relaxed;
                    visitParent[other] = vertexId;
                    frontier.add(new double[] { relaxed, other });
                }
            }
        }
        if (!reached) {
            return false;
        }
        int walk = toVertex;
        while (walk != fromVertex) {
            int parent = visitParent[walk];
            marks[mesh.edgeBetween(walk, parent)] = true;
            walk = parent;
        }
        return true;
    }
}
