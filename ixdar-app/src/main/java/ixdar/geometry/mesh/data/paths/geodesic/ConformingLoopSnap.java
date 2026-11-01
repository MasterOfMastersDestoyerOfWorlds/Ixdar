package ixdar.geometry.mesh.data.paths.geodesic;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.numeric.graph.Dijkstra;

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

    /** Traced point each vertex of the last {@link #orderedWalk} came from or led toward. */
    public int[] walkPoint = new int[0];

    /** Vertices one gap-closing walk may settle. */
    public int searchBudget = DEFAULT_SEARCH_BUDGET;

    private final Dijkstra walk = new Dijkstra();
    private int vertexIdBound;
    private final Vector3f scratchPosition = new Vector3f();
    private final Vector3f otherPosition = new Vector3f();

    /**
     * Marks the edges of the conforming path nearest a traced geodesic, only ever the mesh's own
     * edges: a point inside a polygon snaps to that polygon's nearest corner. A closed path's marks
     * lose their dead-end spurs and the small loops hanging off them at one vertex.
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
        vertexIdBound = mesh.vertexIdBound();

        for (int index = 0; index < path.pointCount; index++) {
            appendVertex(snappedVertex(mesh, path, index));
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
        int[] markedDegree = new int[vertexIdBound];
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
        // A walk that crosses a polygon round two of its sides and back can close a small loop that
        // hangs off the ring at one vertex. Each loop leaving a junction and returning to it through
        // plain ring vertices is a side loop; the shortest go until the junction is plain again.
        boolean[] walked = new boolean[marks.length];
        int[] sideLoop = new int[0];
        for (int junction = 0; junction < markedDegree.length; junction++) {
            while (markedDegree[junction] > 2) {
                int shortest = -1;
                int[] shortestLoop = new int[0];
                for (int start = 0; start < mesh.vertexEdgeCount(junction); start++) {
                    int edgeId = mesh.vertexEdgeAt(junction, start);
                    if (edgeId >= marks.length || !marks[edgeId] || walked[edgeId]) {
                        continue;
                    }
                    int length = 0;
                    int vertexId = mesh.edgeOtherVertex(edgeId, junction);
                    int cameFrom = edgeId;
                    sideLoop = length < sideLoop.length ? sideLoop : new int[2 * sideLoop.length + 2];
                    sideLoop[length++] = edgeId;
                    walked[edgeId] = true;
                    while (vertexId != junction && markedDegree[vertexId] == 2) {
                        int next = EdgeMarks.nextMarkedEdge(mesh, vertexId, cameFrom, marks);
                        sideLoop = length < sideLoop.length ? sideLoop
                                : Arrays.copyOf(sideLoop, 2 * sideLoop.length);
                        sideLoop[length++] = next;
                        walked[next] = true;
                        vertexId = mesh.edgeOtherVertex(next, vertexId);
                        cameFrom = next;
                    }
                    if (vertexId == junction && (shortest < 0 || length < shortest)) {
                        shortest = length;
                        shortestLoop = Arrays.copyOf(sideLoop, length);
                    }
                }
                Arrays.fill(walked, false);
                if (shortest < 0) {
                    break;
                }
                for (int edgeId : shortestLoop) {
                    marks[edgeId] = false;
                    int halfEdge = mesh.edgeHalfEdge(edgeId);
                    markedDegree[mesh.halfEdgeVertex(halfEdge)]--;
                    markedDegree[mesh.halfEdgeEndVertex(halfEdge)]--;
                }
            }
        }
        return marks;
    }

    /**
     * The edge loop nearest a traced path, its mask from {@link #snap} with the gaps that snap
     * left open.
     *
     * @param mesh source mesh the trace was taken on
     * @param path traced polyline with its per-point vertex, edge or face correspondence
     * @return the snapped edges
     */
    public EdgeMarks edgeLoop(MeshTopology mesh, TracedSurfacePath path) {
        boolean[] marks = snap(mesh, path);
        return new EdgeMarks(marks, unresolvedGaps);
    }

    /**
     * The vertex walk an open traced path snaps to, in travel order: each point's vertex as
     * {@link #snap} picks it, joined to the next by the same shortest edge walk, so over any run of
     * points it marks what {@link #snap} marks. {@link #walkPoint} gives each walk vertex's point.
     *
     * @param mesh source mesh the trace was taken on
     * @param path traced polyline with its per-point vertex, edge or face correspondence
     * @return the walk's vertex ids, consecutive ones joined by a mesh edge where no gap was left
     */
    public int[] orderedWalk(MeshTopology mesh, TracedSurfacePath path) {
        vertexIdBound = mesh.vertexIdBound();
        unresolvedGaps = 0;
        int[] walked = new int[Math.max(1, path.pointCount)];
        int[] pointOf = new int[walked.length];
        int count = 0;
        int[] between = new int[0];
        for (int index = 0; index < path.pointCount; index++) {
            int vertexId = snappedVertex(mesh, path, index);
            if (count > 0 && walked[count - 1] == vertexId) {
                continue;
            }
            int steps = 0;
            if (count > 0 && reachShortest(mesh, walked[count - 1], vertexId)) {
                for (int step = walk.via(vertexId); step != walked[count - 1]; step = walk.via(step)) {
                    between = steps < between.length ? between
                            : Arrays.copyOf(between, 2 * between.length + 2);
                    between[steps++] = step;
                }
            } else if (count > 0) {
                unresolvedGaps++;
            }
            if (count + steps + 1 > walked.length) {
                walked = Arrays.copyOf(walked, 2 * (count + steps + 1));
                pointOf = Arrays.copyOf(pointOf, walked.length);
            }
            for (int step = steps - 1; step >= 0; step--) {
                pointOf[count] = index;
                walked[count++] = between[step];
            }
            pointOf[count] = index;
            walked[count++] = vertexId;
        }
        walkPoint = Arrays.copyOf(pointOf, count);
        return Arrays.copyOf(walked, count);
    }

    private int nearerEdgeEnd(MeshTopology mesh, int edgeId, double fraction) {
        int halfEdge = mesh.edgeHalfEdge(edgeId);
        return fraction <= 0.5 ? mesh.halfEdgeVertex(halfEdge) : mesh.halfEdgeEndVertex(halfEdge);
    }

    /**
     * The vertex one traced point snaps to: its own vertex, the nearer end of the edge it crosses,
     * or the nearest corner of the polygon it lies inside.
     */
    private int snappedVertex(MeshTopology mesh, TracedSurfacePath path, int index) {
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
        return vertexId;
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
        if (!reachShortest(mesh, fromVertex, toVertex)) {
            return false;
        }
        for (int vertexId = toVertex; vertexId != fromVertex; vertexId = walk.via(vertexId)) {
            marks[mesh.edgeBetween(vertexId, walk.via(vertexId))] = true;
        }
        return true;
    }

    /**
     * Runs the bounded shortest edge walk from one vertex to another, leaving its back pointers
     * in the walk.
     *
     * @return true when the walk reached the target inside the budget
     */
    private boolean reachShortest(MeshTopology mesh, int fromVertex, int toVertex) {
        walk.start(vertexIdBound);
        walk.relax(fromVertex, 0.0, -1);
        int settled = 0;
        boolean reached = false;
        while (settled < searchBudget) {
            int vertexId = walk.settle();
            if (vertexId < 0) {
                break;
            }
            settled++;
            if (vertexId == toVertex) {
                reached = true;
                break;
            }
            mesh.vertexPosition(vertexId, scratchPosition);
            double here = walk.distance(vertexId);
            int spokes = mesh.vertexEdgeCount(vertexId);
            for (int spoke = 0; spoke < spokes; spoke++) {
                int edgeId = mesh.vertexEdgeAt(vertexId, spoke);
                int other = mesh.edgeOtherVertex(edgeId, vertexId);
                if (other < 0) {
                    continue;
                }
                mesh.vertexPosition(other, otherPosition);
                walk.relax(other, here + scratchPosition.distance(otherPosition), vertexId);
            }
        }
        return reached;
    }
}
