package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact chord walk inside one source face: connects a path head to a target point,
 * materializing every child edge the chord crosses.
 *
 * <p>Works in barycentric coordinates, deciding every case by an exact
 * {@link ExactBarycentricOrient} sign test, with no tolerance.
 *
 * <p>See also: LCBK19 Section 6.1
 */
public final class FaceChordWalk {

    /** Corners (and edges) of a triangle. */
    private static final int CORNERS = 3;

    /** Split position for corridor checkpoints. */
    private static final double HALFWAY = 0.5;

    public final EmbeddedMeshTopology topology;

    /** Carve points that reused an existing free vertex (LCBK19's snap). */
    public int snappedCrossingCount;

    /** Carve points with no free vertex available, so the edge was split. */
    public int splitCrossingCount;

    /** Retriangulation edges crossed on the way between two carve points. */
    public int interiorSplitCount;

    /** Retriangulation corners the chord passed exactly through. */
    public int vertexCrossingCount;

    /** Nodes placed on an existing free vertex they coincide with. */
    public int placedBySnapCount;

    /** Nodes placed by splitting the edge they lie on. */
    public int placedByEdgeSplitCount;

    /** Nodes placed by splitting the face they lie inside. */
    public int placedByFaceSplitCount;

    /** Walks finished by the terminal free-edge search after rounding collapse. */
    public int terminalFanSearchCount;

    /** Crossed diagonals inserted by an edge flip instead of a split. */
    public int flipInsertCount;

    /**
     * Stores the working copy the walk carves into.
     *
     * @param topology working copy with provenance and claims
     */
    public FaceChordWalk(EmbeddedMeshTopology topology) {
        this.topology = topology;
    }

    /**
     * Place a claim-free copy vertex at an exact point of a source face, for a T-mesh
     * node, by reusing a free corner, splitting an edge, or splitting the face. Must run
     * before any arc is carved, while no edge is yet claimed.
     *
     * @param sourceFace  source active face the point lies in
     * @param barycentric the point's barycentric coordinate in that face
     * @throws IllegalStateException when the node's point coincides with a copy vertex
     *                               another T-mesh element already claims, which no
     *                               split can resolve because the point is the vertex
     * @return a copy vertex at the point, owned by nobody
     */
    public int placeVertex(int sourceFace, double[] barycentric) {
        int childFace = locateChildFace(sourceFace, barycentric);
        for (int corner = 0; corner < CORNERS; corner++) {
            int vertex = topology.copy.faceVertexAt(childFace, corner);
            if (!isAtCorner(sourceFace, childFace, corner, barycentric)) {
                continue;
            }
            if (!isFree(vertex)) {
                throw new IllegalStateException("T-mesh node sits exactly on " + "copy vertex "
                        + vertex + ", which another T-mesh element already claims" + "; two nodes cannot share one mesh vertex");
            }
            placedBySnapCount++;
            return vertex;
        }
        for (int corner = 0; corner < CORNERS; corner++) {
            int edgeId = topology.copy.faceEdgeAt(childFace, corner);
            double parameter = edgeParameterOf(sourceFace, edgeId, barycentric);
            if (Double.isNaN(parameter)
                    || topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            placedByEdgeSplitCount++;
            return topology.splitEdgeAtParameter(edgeId, parameter);
        }
        placedByFaceSplitCount++;
        return topology.splitFaceAtBarycentric(childFace, barycentric.clone());
    }

    /**
     * The child face of a source face containing a point.
     *
     * @param sourceFace  source active face
     * @param barycentric the point's barycentric coordinate in that face
     * @throws IllegalStateException when the point lies in no child face, which means
     *                               it was never inside the source face
     * @return the containing child face
     */
    public int locateChildFace(int sourceFace, double[] barycentric) {
        for (int childFace : topology.copyFacesBySourceFace.get(sourceFace)) {
            if (targetContainedBy(sourceFace, childFace, barycentric)) {
                return childFace;
            }
        }
        throw new IllegalStateException("point lies in no child face of source face " + sourceFace);
    }

    /**
     * Walk the chord from a path head to a target inside one source face, appending
     * every vertex it reaches (the head excluded) to the path.
     *
     * @param arcId             arc the walk carves for; its claims are respected
     * @param sourceFace        source active face the chord lies in
     * @param startVertex       path head; a corner of some child face of the source face
     * @param targetBarycentric target's barycentric coordinate in the source face
     * @param targetVertex      existing vertex realizing the target (a pre-placed
     *                          T-mesh node), or {@link EmbeddedMeshTopology#UNCLAIMED}
     *                          when the target must still be materialized
     * @param pathVertices      path vertices, extended in place with every vertex the
     *                          walk reaches
     * @throws IllegalStateException when the chord leaves the face, fails to converge,
     *                               or crosses a foreign arc's lane — all upstream
     *                               invariant violations, since consecutive carve
     *                               points bound a chord that meets no other trace
     * @return the copy vertex the walk ended on
     */
    public int walk(int arcId, int sourceFace, int startVertex, double[] targetBarycentric,
            int targetVertex, List<Integer> pathVertices) {
        int head = startVertex;
        int stepCap = 2 * topology.copyFacesBySourceFace.get(sourceFace).size() + CORNERS;
        for (int step = 0; step <= stepCap; step++) {
            if (head == targetVertex) {
                return head;
            }
            double[] headBarycentric = requireBarycentric(sourceFace, head);
            int childFace = childFaceTowards(sourceFace, head, headBarycentric, targetBarycentric);
            if (childFace == EmbeddedMeshTopology.UNCLAIMED) {
                if (targetVertex != EmbeddedMeshTopology.UNCLAIMED) {
                    return finishByFanSearch(arcId, sourceFace, head, targetVertex, pathVertices);
                }
                throw new IllegalStateException("arc " + arcId + " chord leaves source face "
                        + sourceFace + " at " + "copy vertex " + head);
            }
            if (isAtCorner(sourceFace, childFace, cornerOf(childFace, head), targetBarycentric)) {
                if (targetVertex == EmbeddedMeshTopology.UNCLAIMED || head == targetVertex) {
                    return head;
                }
                return finishByFanSearch(arcId, sourceFace, head, targetVertex, pathVertices);
            }
            if (targetVertex != EmbeddedMeshTopology.UNCLAIMED
                    && isCornerOf(childFace, targetVertex)) {
                hop(arcId, pathVertices, head, targetVertex);
                return targetVertex;
            }
            if (targetContainedBy(sourceFace, childFace, targetBarycentric)) {
                if (targetVertex != EmbeddedMeshTopology.UNCLAIMED) {
                    return finishByFanSearch(arcId, sourceFace, head, targetVertex, pathVertices);
                }
                int reached = materialize(arcId, sourceFace, childFace, head, targetBarycentric);
                hop(arcId, pathVertices, head, reached);
                return reached;
            }
            int stepped = advance(arcId, sourceFace, childFace, head, headBarycentric,
                    targetBarycentric, pathVertices, targetVertex);
            if (stepped == EmbeddedMeshTopology.UNCLAIMED) {
                return finishByFanSearch(arcId, sourceFace, head, targetVertex, pathVertices);
            }
            head = stepped;
        }
        throw new IllegalStateException("arc " + arcId + " chord walk did not converge in source face "
                + sourceFace + " from " + "copy vertex " + startVertex);
    }

    /**
     * Finish a walk whose exact march collapsed near its pre-placed target: a
     * face corridor through free edges only, so the completion crosses no lane.
     *
     * @param arcId        arc being carved
     * @param sourceFace   source active face confining the search
     * @param head         path head the march stopped on
     * @param targetVertex pre-placed vertex the walk must reach
     * @param pathVertices path vertices, extended in place
     * @throws IllegalStateException when no free corridor to the target exists
     * @return the target vertex
     */
    private int finishByFanSearch(int arcId, int sourceFace, int head, int targetVertex,
            List<Integer> pathVertices) {
        terminalFanSearchCount++;
        Map<Integer, int[]> parentByFace = new HashMap<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int index = 0; index < topology.copy.vertexFaceCount(targetVertex); index++) {
            int faceId = topology.copy.vertexFaceAt(targetVertex, index);
            if (topology.sourceFaceByCopyFace[faceId] == sourceFace
                    && isCornerOf(faceId, head)) {
                hop(arcId, pathVertices, head, targetVertex);
                return targetVertex;
            }
        }
        for (int index = 0; index < topology.copy.vertexFaceCount(head); index++) {
            int faceId = topology.copy.vertexFaceAt(head, index);
            if (topology.sourceFaceByCopyFace[faceId] == sourceFace) {
                parentByFace.put(faceId, null);
                queue.add(faceId);
            }
        }
        while (!queue.isEmpty()) {
            int faceId = queue.poll();
            for (int corner = 0; corner < CORNERS; corner++) {
                int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                int neighborFace = topology.copy.halfEdgeFace(halfEdge) == faceId
                        ? topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(halfEdge))
                        : topology.copy.halfEdgeFace(halfEdge);
                if (neighborFace < 0 || parentByFace.containsKey(neighborFace)
                        || topology.sourceFaceByCopyFace[neighborFace] != sourceFace) {
                    continue;
                }
                parentByFace.put(neighborFace, new int[] {faceId, edgeId});
                if (isCornerOf(neighborFace, targetVertex)) {
                    return realizeCorridor(arcId, head, targetVertex, neighborFace,
                            parentByFace, pathVertices);
                }
                queue.add(neighborFace);
            }
        }
        throw new IllegalStateException("arc " + arcId + " has no free corridor to its carve point "
                + targetVertex + " from copy vertex " + head + " in source face " + sourceFace
                + fanReport(head) + fanReport(targetVertex));
    }

    /**
     * Realize a face corridor: split every crossed free edge at its midpoint and
     * hop checkpoint to checkpoint from the head to the target.
     *
     * @param arcId        arc being carved
     * @param head         path head the corridor starts at
     * @param targetVertex pre-placed vertex the corridor ends at
     * @param lastFace     corridor face containing the target
     * @param parentByFace BFS parents: face to {parent face, crossed edge}
     * @param pathVertices path vertices, extended in place
     * @return the target vertex
     */
    private int realizeCorridor(int arcId, int head, int targetVertex, int lastFace,
            Map<Integer, int[]> parentByFace, List<Integer> pathVertices) {
        List<Integer> crossedEdges = new ArrayList<>();
        for (int[] link = parentByFace.get(lastFace); link != null;
                link = parentByFace.get(link[0])) {
            crossedEdges.add(link[1]);
        }
        int current = head;
        for (int position = crossedEdges.size() - 1; position >= 0; position--) {
            int minted = topology.splitEdgeAtParameter(crossedEdges.get(position), HALFWAY);
            hop(arcId, pathVertices, current, minted);
            current = minted;
        }
        hop(arcId, pathVertices, current, targetVertex);
        return targetVertex;
    }

    /**
     * Neighborhood report of a blocked carve point: its incident faces with
     * corner owners, for seal diagnostics.
     *
     * @param vertexId blocked carve point
     * @return a multi-line diagnostic block
     */
    private String fanReport(int vertexId) {
        StringBuilder detail = new StringBuilder("\n vertex ").append(vertexId)
                .append(" ownerNode ").append(topology.ownerNodeByCopyVertex[vertexId])
                .append(" ownerArc ").append(topology.ownerArcByCopyVertex[vertexId]);
        for (int index = 0; index < topology.copy.vertexFaceCount(vertexId); index++) {
            int faceId = topology.copy.vertexFaceAt(vertexId, index);
            detail.append("\n  face ").append(faceId)
                    .append(" source ").append(topology.sourceFaceByCopyFace[faceId])
                    .append(" corners");
            for (int corner = 0; corner < CORNERS; corner++) {
                int cornerVertex = topology.copy.faceVertexAt(faceId, corner);
                detail.append(' ').append(cornerVertex)
                        .append("(n").append(topology.ownerNodeByCopyVertex[cornerVertex])
                        .append(",a").append(topology.ownerArcByCopyVertex[cornerVertex])
                        .append(')');
            }
        }
        return detail.toString();
    }

    /**
     * Take one step of the march: split the exit edge at the chord's crossing,
     * else pass through an endpoint. With a pre-placed target, rounding collapse
     * (degenerate signs, foreign claims) reports for the caller's fan search.
     *
     * @param arcId             arc being carved
     * @param sourceFace        source active face
     * @param childFace         child face the chord is leaving
     * @param head              path head, a corner of the child face
     * @param headBarycentric   head's barycentric in the source face
     * @param targetBarycentric target's barycentric in the source face
     * @param pathVertices      path vertices, extended in place
     * @param targetVertex      pre-placed target vertex, or
     *                          {@link EmbeddedMeshTopology#UNCLAIMED}
     * @return the new head, or {@link EmbeddedMeshTopology#UNCLAIMED} on collapse
     */
    private int advance(int arcId, int sourceFace, int childFace, int head,
            double[] headBarycentric, double[] targetBarycentric, List<Integer> pathVertices,
            int targetVertex) {
        boolean recoverable = targetVertex != EmbeddedMeshTopology.UNCLAIMED;
        int exitEdge = oppositeEdge(childFace, head);
        int halfEdge = topology.copy.edgeHalfEdge(exitEdge);
        int from = topology.copy.halfEdgeVertex(halfEdge);
        int to = topology.copy.halfEdgeEndVertex(halfEdge);
        double[] fromBarycentric = requireBarycentric(sourceFace, from);
        double[] toBarycentric = requireBarycentric(sourceFace, to);
        int fromSign = orientSign(headBarycentric, targetBarycentric, fromBarycentric);
        int toSign = orientSign(headBarycentric, targetBarycentric, toBarycentric);
        if (fromSign != 0 && fromSign == toSign) {
            if (recoverable) {
                return EmbeddedMeshTopology.UNCLAIMED;
            }
            throw new IllegalStateException("arc " + arcId + " chord misses the exit edge of copy face "
                    + childFace + " in source face " + sourceFace);
        }
        double fromArea = ExactBarycentricOrient.area(
                headBarycentric, targetBarycentric, fromBarycentric);
        double toArea = ExactBarycentricOrient.area(
                headBarycentric, targetBarycentric, toBarycentric);
        double parameter = fromArea / (fromArea - toArea);
        if (fromSign != 0 && toSign != 0 && parameter > 0.0 && parameter < 1.0) {
            if (recoverable && foreignClaim(arcId, exitEdge)) {
                return EmbeddedMeshTopology.UNCLAIMED;
            }
            requireUnclaimed(arcId, exitEdge);
            if (flippable(sourceFace, exitEdge, head, fromBarycentric, toBarycentric)) {
                topology.flipEdge(exitEdge);
                flipInsertCount++;
                return head;
            }
            interiorSplitCount++;
            int minted = topology.splitEdgeAtParameter(exitEdge, parameter);
            hop(arcId, pathVertices, head, minted);
            return minted;
        }
        int through = Math.abs(fromArea) <= Math.abs(toArea) ? from : to;
        boolean throughBlocked = through != targetVertex
                && (topology.ownerNodeByCopyVertex[through] != EmbeddedMeshTopology.UNCLAIMED
                        || foreignVertexClaim(arcId, through)
                        || !hopIsFree(head, through));
        if (recoverable && throughBlocked) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        vertexCrossingCount++;
        hop(arcId, pathVertices, head, through);
        return through;
    }

    /**
     * Whether a crossed diagonal may be flipped out of the chord's way: free,
     * untagged, both faces in the source face, and its quad strictly convex —
     * so the flip's new diagonal leaves the head and cannot cross the chord.
     *
     * @param sourceFace      source active face of the chord
     * @param exitEdge        crossed diagonal, opposite the head
     * @param head            path head, on the chord
     * @param fromBarycentric crossed diagonal's start corner barycentric
     * @param toBarycentric   crossed diagonal's end corner barycentric
     * @return true when the flip is legal and strictly convex
     */
    private boolean flippable(int sourceFace, int exitEdge, int head,
            double[] fromBarycentric, double[] toBarycentric) {
        if (topology.ownerArcByCopyEdge[exitEdge] != EmbeddedMeshTopology.UNCLAIMED
                || topology.sourceEdgeByCopyEdge[exitEdge] != EmbeddedMeshTopology.UNCLAIMED) {
            return false;
        }
        int halfEdge = topology.copy.edgeHalfEdge(exitEdge);
        int nearFace = topology.copy.halfEdgeFace(halfEdge);
        int farFace = topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(halfEdge));
        if (nearFace < 0 || farFace < 0
                || topology.sourceFaceByCopyFace[nearFace] != sourceFace
                || topology.sourceFaceByCopyFace[farFace] != sourceFace) {
            return false;
        }
        int farVertex = isCornerOf(nearFace, head)
                ? oppositeCorner(farFace, exitEdge)
                : oppositeCorner(nearFace, exitEdge);
        double[] farBarycentric = topology.barycentricOf(sourceFace, farVertex);
        if (farBarycentric == null) {
            return false;
        }
        double[] headBarycentric = requireBarycentric(sourceFace, head);
        int fromSide = orientSign(headBarycentric, farBarycentric, fromBarycentric);
        int toSide = orientSign(headBarycentric, farBarycentric, toBarycentric);
        if (fromSide == 0 || toSide == 0 || fromSide == toSide) {
            return false;
        }
        int headSide = orientSign(fromBarycentric, toBarycentric, headBarycentric);
        int farSide = orientSign(fromBarycentric, toBarycentric, farBarycentric);
        return headSide != 0 && farSide != 0 && headSide != farSide;
    }

    /**
     * The corner of a triangle not on one of its edges.
     *
     * @param faceId copy face to read
     * @param edgeId edge whose endpoints are excluded
     * @return the corner opposite the edge
     */
    private int oppositeCorner(int faceId, int edgeId) {
        int halfEdge = topology.copy.edgeHalfEdge(edgeId);
        int vertexA = topology.copy.halfEdgeVertex(halfEdge);
        int vertexB = topology.copy.halfEdgeEndVertex(halfEdge);
        for (int corner = 0; corner < CORNERS; corner++) {
            int vertex = topology.copy.faceVertexAt(faceId, corner);
            if (vertex != vertexA && vertex != vertexB) {
                return vertex;
            }
        }
        throw new IllegalStateException("copy face " + faceId
                + " has no corner off edge " + edgeId);
    }

    /**
     * Whether a vertex carries another arc's claim.
     *
     * @param arcId    arc being carved
     * @param vertexId copy vertex to test
     * @return true when a foreign arc owns the vertex
     */
    private boolean foreignVertexClaim(int arcId, int vertexId) {
        int owner = topology.ownerArcByCopyVertex[vertexId];
        return owner != EmbeddedMeshTopology.UNCLAIMED && owner != arcId;
    }

    /**
     * Whether an edge carries another arc's claim.
     *
     * @param arcId  arc being carved
     * @param edgeId child edge to test
     * @return true when a foreign arc owns the edge
     */
    private boolean foreignClaim(int arcId, int edgeId) {
        int owner = topology.ownerArcByCopyEdge[edgeId];
        return owner != EmbeddedMeshTopology.UNCLAIMED && owner != arcId;
    }


    /**
     * Materialize the target on the child face containing it: reuse a free corner it
     * coincides with, else split the crossed edge at the crossing, else split the face.
     * The farther endpoint of a crossed edge is never a snap candidate.
     *
     * @param arcId             arc being carved
     * @param sourceFace        source active face
     * @param childFace         child face containing the target
     * @param head              path head (never a snap target)
     * @param targetBarycentric target's barycentric in the source face
     * @return the copy vertex realizing the target
     */
    private int materialize(int arcId, int sourceFace, int childFace, int head,
            double[] targetBarycentric) {
        for (int corner = 0; corner < CORNERS; corner++) {
            int vertex = topology.copy.faceVertexAt(childFace, corner);
            if (!isAtCorner(sourceFace, childFace, corner, targetBarycentric)) {
                continue;
            }
            if (vertex == head) {
                return head;
            }
            if (isFree(vertex) && hopIsFree(head, vertex)) {
                snappedCrossingCount++;
                return vertex;
            }
            throw new IllegalStateException("arc " + arcId + " carve point coincides with "
                    + "copy vertex " + vertex + ", which another T-mesh element already claims"
                    + "; the trace passes exactly through it but cannot share it");
        }
        for (int corner = 0; corner < CORNERS; corner++) {
            int edgeId = topology.copy.faceEdgeAt(childFace, corner);
            double parameter = edgeParameterOf(sourceFace, edgeId, targetBarycentric);
            if (Double.isNaN(parameter)) {
                continue;
            }
            requireUnclaimed(arcId, edgeId);
            splitCrossingCount++;
            return topology.splitEdgeAtParameter(edgeId, parameter);
        }
        splitCrossingCount++;
        return topology.splitFaceAtBarycentric(childFace, targetBarycentric.clone());
    }

    /**
     * Append one hop, asserting the two vertices really are joined by a free edge —
     * which the walk's construction guarantees.
     *
     * @param arcId        arc being carved
     * @param pathVertices path vertices, extended in place
     * @param from         path head
     * @param to           vertex reached
     */
    private void hop(int arcId, List<Integer> pathVertices, int from, int to) {
        if (from == to) {
            return;
        }
        int edgeId = topology.edgeBetween(from, to);
        if (edgeId == EmbeddedMeshTopology.UNCLAIMED) {
            throw new IllegalStateException("arc " + arcId + " walk stepped from " + "copy vertex "
                    + from + " to " + to + " with no edge between them");
        }
        requireUnclaimed(arcId, edgeId);
        pathVertices.add(to);
    }

    /**
     * The child face of a source face, incident to the head, whose wedge at the head
     * contains the direction to the target. A target coinciding with the head is
     * degenerate and yields the first incident child face, which the caller's
     * coincidence check recognizes.
     *
     * @param sourceFace        source active face
     * @param head              path head
     * @param headBarycentric   head's barycentric in the source face
     * @param targetBarycentric target's barycentric in the source face
     * @return the child face, or {@link EmbeddedMeshTopology#UNCLAIMED} when none does
     */
    private int childFaceTowards(int sourceFace, int head, double[] headBarycentric,
            double[] targetBarycentric) {
        for (int index = 0; index < topology.copy.vertexFaceCount(head); index++) {
            int faceId = topology.copy.vertexFaceAt(head, index);
            if (topology.sourceFaceByCopyFace[faceId] != sourceFace) {
                continue;
            }
            int corner = cornerOf(faceId, head);
            double[] after = requireBarycentric(sourceFace,
                    topology.copy.faceVertexAt(faceId, (corner + 1) % CORNERS));
            double[] before = requireBarycentric(sourceFace,
                    topology.copy.faceVertexAt(faceId, (corner + 2) % CORNERS));
            if (orientSign(headBarycentric, after, targetBarycentric) >= 0
                    && orientSign(headBarycentric, targetBarycentric, before) >= 0) {
                return faceId;
            }
        }
        return EmbeddedMeshTopology.UNCLAIMED;
    }

    /**
     * Whether a point coincides exactly with one corner of a child face, tested as
     * collinearity with that corner along both edges leaving it. Exact: no distance and
     * no tolerance.
     *
     * @param sourceFace        source active face
     * @param childFace         child face to test against
     * @param corner            local corner index of the child face
     * @param targetBarycentric point's barycentric in the source face
     * @return true when the point is that corner
     */
    private boolean isAtCorner(int sourceFace, int childFace, int corner,
            double[] targetBarycentric) {
        double[] at = requireBarycentric(sourceFace,
                topology.copy.faceVertexAt(childFace, corner));
        double[] after = requireBarycentric(sourceFace,
                topology.copy.faceVertexAt(childFace, (corner + 1) % CORNERS));
        double[] before = requireBarycentric(sourceFace,
                topology.copy.faceVertexAt(childFace, (corner + 2) % CORNERS));
        return orientSign(at, after, targetBarycentric) == 0
                && orientSign(at, before, targetBarycentric) == 0;
    }

    /**
     * Whether the target lies inside or on the boundary of a child face.
     *
     * @param sourceFace        source active face
     * @param childFace         child face to test
     * @param targetBarycentric target's barycentric in the source face
     * @return true when the child face contains the target
     */
    private boolean targetContainedBy(int sourceFace, int childFace, double[] targetBarycentric) {
        for (int corner = 0; corner < CORNERS; corner++) {
            double[] from = requireBarycentric(sourceFace,
                    topology.copy.faceVertexAt(childFace, corner));
            double[] to = requireBarycentric(sourceFace,
                    topology.copy.faceVertexAt(childFace, (corner + 1) % CORNERS));
            if (orientSign(from, to, targetBarycentric) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parameter of a point lying on a child edge, measured from that edge's canonical
     * start vertex. On-edge is decided by an exact collinearity sign test; the parameter
     * itself is a projection.
     *
     * @param sourceFace        source active face
     * @param edgeId            child edge
     * @param targetBarycentric point's barycentric in the source face
     * @return parameter in {@code (0, 1)}, or {@link Double#NaN} when the point is not
     *         strictly inside the edge
     */
    private double edgeParameterOf(int sourceFace, int edgeId, double[] targetBarycentric) {
        int halfEdge = topology.copy.edgeHalfEdge(edgeId);
        double[] from = requireBarycentric(sourceFace, topology.copy.halfEdgeVertex(halfEdge));
        double[] to = requireBarycentric(sourceFace, topology.copy.halfEdgeEndVertex(halfEdge));
        if (orientSign(from, to, targetBarycentric) != 0) {
            return Double.NaN;
        }
        double spread = 0.0;
        double offset = 0.0;
        for (int index = 0; index < CORNERS; index++) {
            double delta = to[index] - from[index];
            spread += delta * delta;
            offset += delta * (targetBarycentric[index] - from[index]);
        }
        if (spread == 0.0) {
            return Double.NaN;
        }
        double parameter = offset / spread;
        if (!(parameter > 0.0 && parameter < 1.0)) {
            return Double.NaN;
        }
        return parameter;
    }


    /**
     * The edge of a triangle not incident to one of its corners.
     *
     * @param faceId   child face
     * @param vertexId corner to exclude
     * @return the opposite edge
     */
    private int oppositeEdge(int faceId, int vertexId) {
        int corner = cornerOf(faceId, vertexId);
        return topology.copy.faceEdgeAt(faceId, (corner + 1) % CORNERS);
    }

    /**
     * Local corner index of a vertex within a triangle.
     *
     * @param faceId   child face
     * @param vertexId corner to locate
     * @return local corner index
     */
    private int cornerOf(int faceId, int vertexId) {
        for (int corner = 0; corner < CORNERS; corner++) {
            if (topology.copy.faceVertexAt(faceId, corner) == vertexId) {
                return corner;
            }
        }
        throw new IllegalStateException(
                "copy vertex " + vertexId + " is not a corner of copy face " + faceId);
    }

    /**
     * Whether a vertex is a corner of a triangle.
     *
     * @param faceId   child face
     * @param vertexId vertex to look for
     * @return true when the vertex is a corner
     */
    private boolean isCornerOf(int faceId, int vertexId) {
        for (int corner = 0; corner < CORNERS; corner++) {
            if (topology.copy.faceVertexAt(faceId, corner) == vertexId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a vertex carries no node or arc claim, so an arc may pass through it
     * without touching another element of the T-mesh.
     *
     * @param vertexId copy vertex
     * @return true when the vertex is free
     */
    private boolean isFree(int vertexId) {
        return topology.ownerNodeByCopyVertex[vertexId] == EmbeddedMeshTopology.UNCLAIMED
                && topology.ownerArcByCopyVertex[vertexId] == EmbeddedMeshTopology.UNCLAIMED;
    }

    /**
     * Whether two vertices are joined by an existing unclaimed edge.
     *
     * @param from first vertex
     * @param to   second vertex
     * @return true when the hop is available
     */
    private boolean hopIsFree(int from, int to) {
        int edgeId = topology.edgeBetween(from, to);
        return edgeId != EmbeddedMeshTopology.UNCLAIMED
                && topology.ownerArcByCopyEdge[edgeId] == EmbeddedMeshTopology.UNCLAIMED;
    }

    /**
     * Assert that an edge the walk must use or cross carries no foreign arc's claim.
     * Consecutive carve points bound a chord that meets no other trace, so a foreign
     * lane in the way is an upstream invariant violation.
     *
     * @param arcId  arc being carved
     * @param edgeId child edge
     */
    private void requireUnclaimed(int arcId, int edgeId) {
        int owner = topology.ownerArcByCopyEdge[edgeId];
        if (owner != EmbeddedMeshTopology.UNCLAIMED && owner != arcId) {
            throw new IllegalStateException("arc " + arcId + " chord crosses copy edge " + edgeId
                    + " already claimed by arc " + owner);
        }
    }

    /**
     * Barycentric coordinate of a copy vertex in a source face, which the carve
     * requires to exist.
     *
     * @param sourceFace source active face
     * @param copyVertex copy vertex
     * @return the barycentric triple
     */
    private double[] requireBarycentric(int sourceFace, int copyVertex) {
        double[] barycentric = topology.barycentricOf(sourceFace, copyVertex);
        if (barycentric == null) {
            throw new IllegalStateException("copy vertex " + copyVertex
                    + " has no barycentric coordinate in source face " + sourceFace);
        }
        return barycentric;
    }

    /**
     * Exact orientation sign of three barycentric points of the source face.
     *
     * @param first  first point
     * @param second second point
     * @param third  third point
     * @return {@code 1}, {@code -1} or {@code 0}, the last meaning exactly collinear
     */
    private int orientSign(double[] first, double[] second, double[] third) {
        return ExactBarycentricOrient.sign(first, second, third);
    }
}
