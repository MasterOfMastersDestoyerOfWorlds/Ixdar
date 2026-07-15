package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.List;

/**
 * Exact chord walk inside one source face (LCBK19 §6.1). Connects a path head to
 * a target point of the face by walking the straight chord between them through
 * the face's current child triangulation, materializing every child edge it
 * crosses. There is no graph search: the walk is a face-to-face march driven
 * purely by the chord's geometry.
 *
 * <p>Connectivity is a theorem, not a hope. {@link EmbeddedMeshTopology#splitEdgeAtParameter}
 * retriangulates both incident faces by joining the minted vertex to each face's
 * <em>opposite</em> corner, and the walk always exits a child face through the
 * edge opposite its head — so the minted vertex is joined to the head for free.
 * When instead the chord passes through a corner of the child face, that corner
 * shares the face with the head and is therefore adjacent for free as well. Either
 * way the next path vertex is adjacent to the previous one, which is why the paper
 * needs no routing to lay an arc down.
 *
 * <p>Everything happens in barycentric coordinates of the source face. The seamless
 * chart is affine on each triangle, so the traced iso-line — straight in chart space —
 * is straight in barycentric space too.
 *
 * <p><b>Every geometric decision here is a sign test, and every sign test is exact.</b>
 * The walk carries no tolerance. A chord either crosses the open exit edge (the two
 * endpoints have strictly opposite {@link ExactBarycentricOrient} signs) or it passes exactly
 * through one of them (that endpoint's sign is zero) — and those are different cases
 * with different code, not one case with a fudge factor. Because a split only ever
 * happens at a strictly transversal crossing, the split parameter lies strictly inside
 * {@code (0, 1)} as a consequence of the sign classification rather than as a clamped
 * hope, and no split can mint a vertex arbitrarily close to an existing one. That is
 * what keeps the child triangulation free of the degenerate slivers which previously
 * defeated the predicates.
 */
public final class FaceChordWalk {

    /** Corners (and edges) of a triangle. */
    private static final int CORNERS = 3;


    /** Message prefix naming the arc being carved. */
    private static final String ARC = "arc ";

    /** Message fragment naming a copy vertex. */
    private static final String COPY_VERTEX = "copy vertex ";

    /** Message fragment for a mesh element that is not available to take. */
    private static final String ALREADY_CLAIMED =
            ", which another T-mesh element already claims";

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

    /**
     * Stores the working copy the walk carves into.
     *
     * @param topology working copy with provenance and claims
     */
    public FaceChordWalk(EmbeddedMeshTopology topology) {
        this.topology = topology;
    }

    /**
     * Place a dedicated, claim-free copy vertex at an exact point of a source face,
     * for a T-mesh node. Reuses a free corner the point coincides with, splits the
     * edge it lies on, or splits the containing face — always landing on the point
     * itself, so the node never moves off its traced position.
     *
     * <p>Node placement runs before any arc is carved, so no edge is claimed yet and
     * the only way to run out of elements is two nodes competing for one vertex; the
     * split then mints a fresh one, which is LCBK19's "not enough vertices" fallback.
     *
     * @param sourceFace  source active face the point lies in
     * @param barycentric the point's barycentric coordinate in that face
     * @return a copy vertex at the point, owned by nobody
     * @throws IllegalStateException when the node's point coincides with a copy vertex
     *                               another T-mesh element already claims, which no
     *                               split can resolve because the point is the vertex
     */
    public int placeVertex(int sourceFace, double[] barycentric) {
        int childFace = locateChildFace(sourceFace, barycentric);
        for (int corner = 0; corner < CORNERS; corner++) {
            int vertex = topology.copy.faceVertexAt(childFace, corner);
            if (!isAtCorner(sourceFace, childFace, corner, barycentric)) {
                continue;
            }
            if (!isFree(vertex)) {
                throw new IllegalStateException("T-mesh node sits exactly on " + COPY_VERTEX
                        + vertex + ALREADY_CLAIMED + "; two nodes cannot share one mesh vertex");
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
     * @return the containing child face
     * @throws IllegalStateException when the point lies in no child face, which means
     *                               it was never inside the source face
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
     * @return the copy vertex the walk ended on
     * @throws IllegalStateException when the chord leaves the face, fails to converge,
     *                               or crosses a foreign arc's lane — all upstream
     *                               invariant violations, since consecutive carve
     *                               points bound a chord that meets no other trace
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
                throw new IllegalStateException(ARC + arcId + " chord leaves source face "
                        + sourceFace + " at " + COPY_VERTEX + head);
            }
            if (isAtCorner(sourceFace, childFace, cornerOf(childFace, head), targetBarycentric)) {
                return head;
            }
            if (targetVertex != EmbeddedMeshTopology.UNCLAIMED
                    && isCornerOf(childFace, targetVertex)) {
                hop(arcId, pathVertices, head, targetVertex);
                return targetVertex;
            }
            if (targetContainedBy(sourceFace, childFace, targetBarycentric)) {
                if (targetVertex != EmbeddedMeshTopology.UNCLAIMED) {
                    throw new IllegalStateException(ARC + arcId + " target node "
                            + COPY_VERTEX + targetVertex + " lies in copy face " + childFace
                            + " without being one of its corners, so a second copy vertex sits"
                            + " within rounding distance of the node's position");
                }
                int reached = materialize(arcId, sourceFace, childFace, head, targetBarycentric);
                hop(arcId, pathVertices, head, reached);
                return reached;
            }
            head = advance(arcId, sourceFace, childFace, head, headBarycentric,
                    targetBarycentric, pathVertices);
        }
        throw new IllegalStateException(ARC + arcId + " chord walk did not converge in source face "
                + sourceFace + " from " + COPY_VERTEX + startVertex);
    }

    /**
     * Take one step of the march: leave the child face through the edge opposite the
     * head, either by passing exactly through one of that edge's endpoints or by
     * splitting it at the strictly transversal crossing.
     *
     * <p>The cases are decided by the exact orientation signs of the exit edge's two
     * endpoints against the chord line. Equal nonzero signs mean the chord never meets
     * the edge at all, which contradicts the wedge that selected this child face, so it
     * throws. Strictly opposite signs mean the chord crosses the edge, and the edge is
     * split at the crossing. A zero sign means the chord runs exactly through that
     * endpoint, and the walk steps through the vertex instead of splitting — the case a
     * tolerance-based walk cannot see, and which, left unhandled, mints a duplicate
     * vertex beside an existing one and seeds the degenerate slivers that then defeat
     * every predicate downstream.
     *
     * <p>Passing through a vertex is not only the exactly-collinear case. The chord may
     * cross the open edge yet miss the endpoint by so little that no double lies
     * strictly between them: the crossing parameter then rounds to {@code 0} or
     * {@code 1}, and the split it asks for is one the mesh cannot represent. Rather
     * than round it back inside — which is how the duplicate vertex got minted in the
     * first place — the walk reads it for what it is. Below the resolution of the
     * coordinates, the chord and the vertex coincide, so the arc goes through the
     * vertex. This is LCBK19's rule exactly: an arc snaps onto an existing vertex, and
     * the mesh is split only when it must be.
     *
     * @param arcId             arc being carved
     * @param sourceFace        source active face
     * @param childFace         child face the chord is leaving
     * @param head              path head, a corner of the child face
     * @param headBarycentric   head's barycentric in the source face
     * @param targetBarycentric target's barycentric in the source face
     * @param pathVertices      path vertices, extended in place
     * @return the vertex the step landed on, the new head
     */
    private int advance(int arcId, int sourceFace, int childFace, int head,
            double[] headBarycentric, double[] targetBarycentric, List<Integer> pathVertices) {
        int exitEdge = oppositeEdge(childFace, head);
        int halfEdge = topology.copy.edgeHalfEdge(exitEdge);
        int from = topology.copy.halfEdgeVertex(halfEdge);
        int to = topology.copy.halfEdgeEndVertex(halfEdge);
        double[] fromBarycentric = requireBarycentric(sourceFace, from);
        double[] toBarycentric = requireBarycentric(sourceFace, to);
        int fromSign = orientSign(headBarycentric, targetBarycentric, fromBarycentric);
        int toSign = orientSign(headBarycentric, targetBarycentric, toBarycentric);
        if (fromSign != 0 && fromSign == toSign) {
            throw new IllegalStateException(ARC + arcId + " chord misses the exit edge of copy face "
                    + childFace + " in source face " + sourceFace);
        }
        double fromArea = ExactBarycentricOrient.area(
                headBarycentric, targetBarycentric, fromBarycentric);
        double toArea = ExactBarycentricOrient.area(
                headBarycentric, targetBarycentric, toBarycentric);
        double parameter = fromArea / (fromArea - toArea);
        if (fromSign != 0 && toSign != 0 && parameter > 0.0 && parameter < 1.0) {
            requireUnclaimed(arcId, exitEdge);
            interiorSplitCount++;
            int minted = topology.splitEdgeAtParameter(exitEdge, parameter);
            hop(arcId, pathVertices, head, minted);
            return minted;
        }
        int through = Math.abs(fromArea) <= Math.abs(toArea) ? from : to;
        vertexCrossingCount++;
        hop(arcId, pathVertices, head, through);
        return through;
    }


    /**
     * Materialize the target on the child face that contains it, applying LCBK19's
     * availability rule: reuse a free corner the target coincides with, else reuse the
     * free endpoint of the crossed edge on the target's own half of it, else split the
     * edge exactly at the crossing; a target strictly inside the face splits it.
     *
     * <p>The farther endpoint is deliberately not a candidate. Taking it would move the
     * crossing more than half an edge, past the midpoint, to somewhere the trace never
     * went — and splitting is always available, so nothing is gained.
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
            throw new IllegalStateException(ARC + arcId + " carve point coincides with "
                    + COPY_VERTEX + vertex + ALREADY_CLAIMED
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
            throw new IllegalStateException(ARC + arcId + " walk stepped from " + COPY_VERTEX
                    + from + " to " + to + " with no edge between them");
        }
        requireUnclaimed(arcId, edgeId);
        pathVertices.add(to);
    }

    /**
     * The child face of a source face, incident to the head, whose wedge at the head
     * contains the direction to the target. When the target coincides with the head the
     * direction is degenerate, every sign is zero, and the first incident child face is
     * returned — which is what the caller's coincidence check then recognizes.
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
     * Whether a point coincides exactly with one corner of a child face. A point that
     * is collinear with the corner along <em>both</em> edges leaving it can only be the
     * corner itself, since two distinct lines meet in one point — so this is an exact
     * equality test built from exact sign tests, with no distance and no tolerance.
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
     * start vertex. The point is on the edge exactly when it is collinear with the
     * edge's endpoints, which is an exact sign test; the parameter itself is then a
     * projection, and it is a coordinate rather than a decision.
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
                COPY_VERTEX + vertexId + " is not a corner of copy face " + faceId);
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
            throw new IllegalStateException(ARC + arcId + " chord crosses copy edge " + edgeId
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
            throw new IllegalStateException(COPY_VERTEX + copyVertex
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
