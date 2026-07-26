package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.ActiveIdSet;
import ixdar.geometry.mesh.quadlayout.crossfield.DijkstraNode;

/**
 * Re-embeds an arc whose endpoint node has just moved, by a Dijkstra search
 * restricted to cross or touch no other arc.
 *
 * <p>Each failed round splits the corridor edges walled in by claims.
 *
 * <p>See also: LCBK19 Section 6.1
 */
public final class ArcRerouter {

    /** Refine rounds allowed per re-route attempt. */
    public static final int REFINE_ROUND_CAP = 16;

    /**
     * Gate count beyond which a face passage is a wrong-homotopy detour around
     * the surface, not a route to open — treated as no passage at all.
     */
    public static final int PASSAGE_GATE_BOUND = 100_000;

    /** Split position of a midpoint refinement. */
    private static final double EDGE_MIDPOINT = 0.5;

    /**
     * Returned by the targeted refinement when the two vertices lie in different faces of the arc
     * arrangement, which no amount of refinement can change.
     */
    private static final int NO_PASSAGE = -1;

    /** Corners (and edges) of a triangle. */
    private static final int CORNERS = 3;

    public final EmbeddedMeshTopology topology;

    /**
     * Stamp per source face admitting it to the current carve search; empty for
     * the unrestricted re-routes of the contraction operators. A carve stretch
     * never leaves its segment's source face.
     */
    public int[] sourceFaceStampBySourceFace = new int[0];

    /** Stamp value marking the admitted faces in {@link #sourceFaceStampBySourceFace}. */
    public int sourceFaceStamp;

    /**
     * Whether searches may only pass through face-interior vertices — the carve
     * sets this, since a traced course touches the face boundary solely at its
     * own crossings. Endpoints are always exempt.
     */
    public boolean interiorOnly;

    /** Edges split to open a walled corridor. */
    public int refinedEdgeSplitCount;

    /** Passages discarded as oversized wrong-homotopy detours. */
    public int passageOverflowCount;

    /** Of {@link #refinedEdgeSplitCount}, those split as gates on a face passage. */
    public int gateSplitCount;

    /** Of {@link #refinedEdgeSplitCount}, those split to mint a free spoke into a vertex. */
    public int spokeSplitCount;

    /** Calls to {@link #tryRoute}. */
    public int routeAttemptCount;

    /** Refine rounds executed across all {@link #tryRoute} calls. */
    public int refineRoundCount;

    /** Re-routes that only succeeded after refinement. */
    public int refinedRetryCount;

    /** Vertices the last search settled, and the corridor it was allowed. */
    public int lastReachedCount;

    /** Corridor size at the end of the last attempt. */
    public int lastCorridorSize;

    /** The corridor set of the last attempt, for diagnosing which vertices refinement could reach. */
    public ActiveIdSet lastCorridorSet;

    /** Corridor handed to callers by {@link #freshCorridor()}, reused across attempts. */
    public final ActiveIdSet corridorScratch = new ActiveIdSet(0);

    /**
     * Reusable Dijkstra frontier, cleared per search. The same queue type as
     * before the primitive-scratch rewrite, so pop order among equal distances
     * is unchanged.
     */
    public final PriorityQueue<DijkstraNode> frontier = new PriorityQueue<>();

    /** Tentative distance per copy vertex, valid where {@link #vertexVisitStampByVertex} matches. */
    public float[] distanceByVertex = new float[0];

    /** Dijkstra parent per copy vertex, valid where {@link #vertexVisitStampByVertex} matches. */
    public int[] parentVertexByVertex = new int[0];

    /** Search generation that last wrote each vertex's distance and parent. */
    public int[] vertexVisitStampByVertex = new int[0];

    /** Walk generation that last visited each copy face. */
    public int[] faceVisitStampByFace = new int[0];

    /** BFS parent face per copy face, valid where {@link #faceVisitStampByFace} matches. */
    public int[] parentFaceByFace = new int[0];

    /** Edge crossed into each copy face, valid where {@link #faceVisitStampByFace} matches. */
    public int[] parentEdgeByFace = new int[0];

    /** Walk generation whose target-vertex faces each copy face belongs to. */
    public int[] targetFaceStampByFace = new int[0];

    /** FIFO face queue of the current face walk; every face is enqueued at most once. */
    public int[] faceQueue = new int[0];

    /**
     * Generation counter shared by the stamped scratch arrays; a stamp mismatch
     * means unvisited, so searches never clear the arrays.
     */
    public int visitStamp;

    /**
     * Stores the working copy the re-routes carve into.
     *
     * @param topology working copy with provenance and claims
     */
    public ArcRerouter(EmbeddedMeshTopology topology) {
        this.topology = topology;
    }

    /**
     * An empty corridor to fill and hand to {@link #tryRoute}.
     *
     * <p>One reused set, not a fresh one — a corridor indexes the whole copy-vertex id space, so
     * the previous attempt's contents are invalid once this is called.
     *
     * @return the shared corridor set, emptied
     */
    public ActiveIdSet freshCorridor() {
        corridorScratch.clear();
        return corridorScratch;
    }

    /**
     * Re-route an arc between two vertices by a claims-respecting corridor Dijkstra, refining on
     * failure. A failed round splits every gate on the face passage between the two vertices, or,
     * when there is no such passage, blocked corridor edges at large under {@link #SPLIT_BUDGET}.
     * Splits made by failed attempts stay behind.
     *
     * @param arcId           arc being re-routed, for counters
     * @param vertices        path list; the start vertex is appended when empty, and
     *                        the routed continuation follows it
     * @param startCopyVertex hop source
     * @param endCopyVertex   hop target
     * @param corridor        allowed vertex set, mutated by refinement and growth
     * @param passThrough     a claimed vertex the search may transit anyway — the collapsing
     *                        node, which the arc must follow to its new home — or
     *                        {@link EmbeddedMeshTopology#UNCLAIMED} for none
     * @param roundCap        refine-round budget for this attempt
     * @return whether the path now ends at the target
     */
    public boolean tryRoute(int arcId, List<Integer> vertices, int startCopyVertex,
            int endCopyVertex, ActiveIdSet corridor, int passThrough, int roundCap) {
        if (vertices.isEmpty()) {
            vertices.add(startCopyVertex);
        }
        lastCorridorSet = corridor;
        boolean refined = false;
        routeAttemptCount++;
        for (int round = 0; round <= roundCap; round++) {
            refineRoundCount++;
            if (dijkstraSearch(vertices, startCopyVertex, endCopyVertex, corridor, passThrough)) {
                if (refined) {
                    refinedRetryCount++;
                }
                return true;
            }
            int splits = refineCorridorGates(startCopyVertex, endCopyVertex, corridor, passThrough);
            if (splits == NO_PASSAGE) {
                lastCorridorSize = corridor.size();
                return false;
            }
            splits += mintSpoke(startCopyVertex, corridor) ? 1 : 0;
            splits += mintSpoke(endCopyVertex, corridor) ? 1 : 0;
            if (splits == 0) {
                lastCorridorSize = corridor.size();
                return false;
            }
            refined = true;
        }
        lastCorridorSize = corridor.size();
        return false;
    }

    /**
     * Fill the edge list of a routed vertex path from consecutive vertex pairs,
     * continuing after any edges already present.
     *
     * @param vertices routed path vertices
     * @param edges    list receiving one edge id per remaining consecutive pair
     * @throws IllegalStateException when consecutive vertices share no edge
     */
    public void rebuildLegEdges(List<Integer> vertices, List<Integer> edges) {
        if (!tryLegEdges(vertices, edges)) {
            throw new IllegalStateException("routed path has consecutive vertices sharing no copy edge");
        }
    }

    /**
     * Fill the edge list of a vertex path, reporting failure instead of throwing when
     * a hop no longer exists. A path prefix kept across a back-off can have been cut
     * by an earlier attempt's refinement splits, which is a reason to back off further
     * rather than an invariant violation.
     *
     * @param vertices path vertices
     * @param edges    list receiving one edge id per remaining consecutive pair
     * @return whether every remaining consecutive pair shares an edge
     */
    public boolean tryLegEdges(List<Integer> vertices, List<Integer> edges) {
        for (int index = edges.size() + 1; index < vertices.size(); index++) {
            int edgeId = topology.edgeBetween(vertices.get(index - 1), vertices.get(index));
            if (edgeId == EmbeddedMeshTopology.UNCLAIMED) {
                return false;
            }
            edges.add(edgeId);
        }
        return true;
    }

    /**
     * Claims-respecting shortest path over unclaimed edges and unclaimed interior vertices of the
     * corridor, weighted by plain Euclidean length.
     *
     * <p>Keeping an arc near its old lane is the caller's job, not a weight on this search.
     *
     * @param vertices      path list, extended with the found hop
     * @param startVertex   search source
     * @param endCopyVertex search target
     * @param corridor      allowed vertex set
     * @param passThrough   a claimed vertex the search may transit anyway, or
     *                      {@link EmbeddedMeshTopology#UNCLAIMED} for none
     * @return whether the target was reached
     */
    private boolean dijkstraSearch(List<Integer> vertices, int startVertex, int endCopyVertex,
            ActiveIdSet corridor, int passThrough) {
        int vertexIdBound = topology.ownerArcByCopyVertex.length;
        if (distanceByVertex.length < vertexIdBound) {
            distanceByVertex = Arrays.copyOf(distanceByVertex, vertexIdBound);
            parentVertexByVertex = Arrays.copyOf(parentVertexByVertex, vertexIdBound);
            vertexVisitStampByVertex = Arrays.copyOf(vertexVisitStampByVertex, vertexIdBound);
        }
        int stamp = nextVisitStamp();
        frontier.clear();
        distanceByVertex[startVertex] = 0f;
        vertexVisitStampByVertex[startVertex] = stamp;
        int reachedCount = 1;
        if (interiorOnly) {
            corridor.add(startVertex);
        }
        frontier.add(new DijkstraNode(0f, startVertex));
        Vector3f positionHere = new Vector3f();
        Vector3f positionOther = new Vector3f();
        while (!frontier.isEmpty()) {
            DijkstraNode head = frontier.poll();
            int vertex = head.vertexOrFace;
            if (head.distance > distanceByVertex[vertex]) {
                continue;
            }
            if (vertex == endCopyVertex) {
                List<Integer> hopVertices = new ArrayList<>();
                int walk = endCopyVertex;
                while (walk != startVertex) {
                    hopVertices.add(walk);
                    walk = parentVertexByVertex[walk];
                }
                Collections.reverse(hopVertices);
                vertices.addAll(hopVertices);
                return true;
            }
            topology.copy.vertexPosition(vertex, positionHere);
            for (int index = 0; index < topology.copy.vertexEdgeCount(vertex); index++) {
                int edgeId = topology.copy.vertexEdgeAt(vertex, index);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED
                        || !edgeInRestriction(edgeId)) {
                    continue;
                }
                int neighbor = topology.otherEndpoint(edgeId, vertex);
                if (neighbor != endCopyVertex && neighbor != passThrough
                        && (vertexClaimed(neighbor)
                                || interiorOnly && boundaryVertex(neighbor))) {
                    continue;
                }
                topology.copy.vertexPosition(neighbor, positionOther);
                float newDistance = head.distance + positionHere.distance(positionOther);
                boolean reached = vertexVisitStampByVertex[neighbor] == stamp;
                if (newDistance < (reached ? distanceByVertex[neighbor]
                        : Float.POSITIVE_INFINITY)) {
                    if (!reached) {
                        vertexVisitStampByVertex[neighbor] = stamp;
                        reachedCount++;
                        if (interiorOnly) {
                            corridor.add(neighbor);
                        }
                    }
                    distanceByVertex[neighbor] = newDistance;
                    parentVertexByVertex[neighbor] = vertex;
                    frontier.add(new DijkstraNode(newDistance, neighbor));
                }
            }
        }
        lastReachedCount = reachedCount;
        return false;
    }

    /**
     * Advances the shared scratch-array generation, wrapping all stamp arrays back
     * to zero before overflow so a stale stamp can never collide with a live one.
     *
     * @return the fresh generation value to stamp this search's writes with
     */
    private int nextVisitStamp() {
        if (visitStamp == Integer.MAX_VALUE) {
            Arrays.fill(vertexVisitStampByVertex, 0);
            Arrays.fill(faceVisitStampByFace, 0);
            Arrays.fill(targetFaceStampByFace, 0);
            visitStamp = 0;
        }
        visitStamp++;
        return visitStamp;
    }

    /**
     * Splits the gates blocking this route: the edges with both endpoints claimed along the
     * shortest face path from source to target, in corridor order.
     *
     * <p>Every gate must be split, so this takes no split allowance. {@link #NO_PASSAGE} means the
     * caller must give up rather than refine, since refinement never unclaims anything.
     *
     * @param startVertex source of the blocked search
     * @param endVertex   target of the blocked search
     * @param corridor    corridor vertex set; every vertex the passage runs along joins it, minted
     *                    or not, since the search must be allowed to stand either side of a gate
     * @param passThrough claimed vertex the search may transit, or
     *                    {@link EmbeddedMeshTopology#UNCLAIMED} for none
     * @return number of blocking gates split, or {@link #NO_PASSAGE} when the target is
     *         unreachable through the arrangement
     */
    private int refineCorridorGates(int startVertex, int endVertex, ActiveIdSet corridor,
            int passThrough) {
        List<Integer> crossings = corridorGateEdges(startVertex, endVertex);
        if (crossings == null && passThrough != EmbeddedMeshTopology.UNCLAIMED) {
            List<Integer> toPivot = corridorGateEdges(startVertex, passThrough);
            List<Integer> fromPivot = corridorGateEdges(passThrough, endVertex);
            if (toPivot != null && fromPivot != null) {
                crossings = new ArrayList<>(toPivot);
                crossings.addAll(fromPivot);
            }
        }
        if (crossings == null) {
            return NO_PASSAGE;
        }
        if (crossings.isEmpty()) {
            return splitSharedFace(startVertex, endVertex, corridor);
        }
        int splits = 0;
        for (int edgeId : crossings) {
            if (!topology.copy.hasEdge(edgeId)) {
                continue;
            }
            int halfEdge = topology.copy.edgeHalfEdge(edgeId);
            int endpointA = topology.copy.halfEdgeVertex(halfEdge);
            int endpointB = topology.copy.halfEdgeEndVertex(halfEdge);
            corridor.add(endpointA);
            corridor.add(endpointB);
            if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED
                    || !blockedVertex(endpointA) || !blockedVertex(endpointB)) {
                continue;
            }
            corridor.add(topology.splitEdgeAtParameter(edgeId, EDGE_MIDPOINT));
            refinedEdgeSplitCount++;
            gateSplitCount++;
            splits++;
        }
        return splits;
    }

    /**
     * Opens a route whose source and target are corners of one triangle whose edge between them
     * belongs to another arc, by splitting an arc-free edge of that shared face; the midpoint is
     * adjacent to both ends.
     *
     * <p>The boxed-vertex case gate refinement cannot express. See {@code BoxedVertexRerouteTest}.
     *
     * @param startVertex re-route source
     * @param endVertex   re-route target, a corner of a face the source also touches
     * @param corridor    corridor vertex set; the minted vertex and its neighbours join it
     * @return one when an edge was split, or zero when the shared face is entirely arc edges
     */
    private int splitSharedFace(int startVertex, int endVertex, ActiveIdSet corridor) {
        for (int faceIndex = 0; faceIndex < topology.copy.vertexFaceCount(startVertex); faceIndex++) {
            int faceId = topology.copy.vertexFaceAt(startVertex, faceIndex);
            if (!faceInRestriction(faceId)) {
                continue;
            }
            boolean touchesEnd = false;
            for (int corner = 0; corner < CORNERS; corner++) {
                touchesEnd |= topology.copy.faceVertexAt(faceId, corner) == endVertex;
            }
            if (!touchesEnd) {
                continue;
            }
            for (int corner = 0; corner < CORNERS; corner++) {
                int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                corridor.add(topology.copy.halfEdgeVertex(halfEdge));
                corridor.add(topology.copy.halfEdgeEndVertex(halfEdge));
                corridor.add(topology.splitEdgeAtParameter(edgeId, EDGE_MIDPOINT));
                refinedEdgeSplitCount++;
                gateSplitCount++;
                return 1;
            }
        }
        return 0;
    }

    /**
     * The edges crossed by the shortest face path between two vertices that never crosses a claimed
     * arc edge — the passage through the arc arrangement the vertex search must follow.
     *
     * @param startVertex path source, whose incident faces seed the walk
     * @param endVertex   path target, reached when any of its incident faces is entered
     * @return the crossed edges in order from source to target, or {@code null} when the two lie in
     *         different faces of the arc arrangement and no such path exists. An <em>empty</em>
     *         list is a different answer: the two already share a face and the passage crosses
     *         nothing.
     */
    private List<Integer> corridorGateEdges(int startVertex, int endVertex) {
        int faceIdBound = topology.sourceFaceByCopyFace.length;
        if (faceVisitStampByFace.length < faceIdBound) {
            faceVisitStampByFace = Arrays.copyOf(faceVisitStampByFace, faceIdBound);
            targetFaceStampByFace = Arrays.copyOf(targetFaceStampByFace, faceIdBound);
            parentFaceByFace = Arrays.copyOf(parentFaceByFace, faceIdBound);
            parentEdgeByFace = Arrays.copyOf(parentEdgeByFace, faceIdBound);
            faceQueue = Arrays.copyOf(faceQueue, faceIdBound);
        }
        int stamp = nextVisitStamp();
        for (int index = 0; index < topology.copy.vertexFaceCount(endVertex); index++) {
            int face = topology.copy.vertexFaceAt(endVertex, index);
            if (faceInRestriction(face)) {
                targetFaceStampByFace[face] = stamp;
            }
        }
        PriorityQueue<double[]> frontierFaces = new PriorityQueue<>(
                (left, right) -> Double.compare(left[0], right[0]));
        Vector3f centroid = new Vector3f();
        Vector3f neighborCentroid = new Vector3f();
        for (int index = 0; index < topology.copy.vertexFaceCount(startVertex); index++) {
            int face = topology.copy.vertexFaceAt(startVertex, index);
            if (faceInRestriction(face) && faceVisitStampByFace[face] != stamp) {
                faceVisitStampByFace[face] = stamp;
                parentFaceByFace[face] = EmbeddedMeshTopology.UNCLAIMED;
                frontierFaces.add(new double[] {0.0, face});
            }
        }
        int reachedFace = EmbeddedMeshTopology.UNCLAIMED;
        while (!frontierFaces.isEmpty()) {
            double[] entry = frontierFaces.poll();
            int face = (int) entry[1];
            if (targetFaceStampByFace[face] == stamp) {
                reachedFace = face;
                break;
            }
            faceCentroid(face, centroid);
            double distance = entry[0];
            for (int corner = 0; corner < CORNERS; corner++) {
                int edgeId = topology.copy.faceEdgeAt(face, corner);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                int neighborFace = topology.copy.halfEdgeFace(halfEdge) == face
                        ? topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(halfEdge))
                        : topology.copy.halfEdgeFace(halfEdge);
                if (neighborFace != EmbeddedMeshTopology.UNCLAIMED
                        && faceInRestriction(neighborFace)
                        && faceVisitStampByFace[neighborFace] != stamp) {
                    faceVisitStampByFace[neighborFace] = stamp;
                    parentFaceByFace[neighborFace] = face;
                    parentEdgeByFace[neighborFace] = edgeId;
                    double step = faceCentroid(neighborFace, neighborCentroid)
                            .distance(centroid);
                    frontierFaces.add(new double[] {distance + step, neighborFace});
                }
            }
        }
        if (reachedFace == EmbeddedMeshTopology.UNCLAIMED) {
            return null;
        }
        List<Integer> crossings = new ArrayList<>();
        for (int walk = reachedFace; parentFaceByFace[walk] != EmbeddedMeshTopology.UNCLAIMED;
                walk = parentFaceByFace[walk]) {
            crossings.add(parentEdgeByFace[walk]);
        }
        Collections.reverse(crossings);
        if (crossings != null && crossings.size() > PASSAGE_GATE_BOUND) {
            passageOverflowCount++;
            return null;
        }
        return crossings;
    }

    /**
     * Centroid of a copy face, for the passage search's distance weights.
     *
     * @param faceId copy face to average
     * @param out    receives the centroid
     * @return {@code out}
     */
    private Vector3f faceCentroid(int faceId, Vector3f out) {
        out.zero();
        Vector3f cornerPosition = new Vector3f();
        for (int corner = 0; corner < CORNERS; corner++) {
            topology.copy.vertexPosition(topology.copy.faceVertexAt(faceId, corner),
                    cornerPosition);
            out.add(cornerPosition);
        }
        return out.div(CORNERS);
    }

    /**
     * Mint a fresh free spoke into a vertex by splitting the edge <em>opposite</em> it
     * in one of its incident faces, raising its degree by one.
     *
     * <p>Fires only when the vertex has no free spoke at all; minting unconditionally
     * counts as progress and so keeps the round loop alive forever.
     *
     * @param vertexId vertex needing another free spoke
     * @param corridor corridor vertex set; the minted vertex joins it
     * @return whether a spoke was minted
     */
    private boolean mintSpoke(int vertexId, ActiveIdSet corridor) {
        for (int index = 0; index < topology.copy.vertexEdgeCount(vertexId); index++) {
            int edgeId = topology.copy.vertexEdgeAt(vertexId, index);
            if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            if (!interiorOnly) {
                return false;
            }
            int neighbor = topology.otherEndpoint(edgeId, vertexId);
            if (!vertexClaimed(neighbor) && !boundaryVertex(neighbor)) {
                return false;
            }
        }
        for (int preferReached = 1; preferReached >= 0; preferReached--) {
            for (int index = 0; index < topology.copy.vertexFaceCount(vertexId); index++) {
                int faceId = topology.copy.vertexFaceAt(vertexId, index);
                if (!faceInRestriction(faceId)) {
                    continue;
                }
                if (preferReached == 1 && !faceTouchesCorridor(faceId, vertexId, corridor)) {
                    continue;
                }
                int oppositeEdge = EmbeddedMeshTopology.UNCLAIMED;
                for (int corner = 0; corner < CORNERS; corner++) {
                    int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                    int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                    if (topology.copy.halfEdgeVertex(halfEdge) != vertexId
                            && topology.copy.halfEdgeEndVertex(halfEdge) != vertexId) {
                        oppositeEdge = edgeId;
                    }
                }
                if (oppositeEdge == EmbeddedMeshTopology.UNCLAIMED
                        || topology.ownerArcByCopyEdge[oppositeEdge]
                                != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int minted = topology.splitEdgeAtParameter(oppositeEdge, EDGE_MIDPOINT);
                corridor.add(minted);
                refinedEdgeSplitCount++;
                spokeSplitCount++;
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one of a fan face's other corners was already reached by the
     * search, so a spoke minted there connects to explored territory.
     *
     * @param faceId   fan face to test
     * @param vertexId fan apex, excluded from the test
     * @param corridor reached and refined vertex set
     * @return true when a corner is in the corridor
     */
    private boolean faceTouchesCorridor(int faceId, int vertexId, ActiveIdSet corridor) {
        for (int corner = 0; corner < CORNERS; corner++) {
            int cornerVertex = topology.copy.faceVertexAt(faceId, corner);
            if (cornerVertex != vertexId && corridor.contains(cornerVertex)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a copy vertex is owned by a T-mesh node or an embedded arc.
     *
     * @param copyVertex copy vertex to test
     * @return true when either ownership claim is set
     */
    private boolean vertexClaimed(int copyVertex) {
        return topology.ownerNodeByCopyVertex[copyVertex] != EmbeddedMeshTopology.UNCLAIMED
                || topology.ownerArcByCopyVertex[copyVertex] != EmbeddedMeshTopology.UNCLAIMED;
    }

    /**
     * Whether the search may not stand on a vertex: claimed, or banned as a
     * boundary vertex under {@link #interiorOnly}. Such vertices count as gate
     * endpoints for refinement.
     *
     * @param copyVertex copy vertex to test
     * @return true when the vertex blocks the search
     */
    private boolean blockedVertex(int copyVertex) {
        return vertexClaimed(copyVertex) || interiorOnly && boundaryVertex(copyVertex);
    }

    /**
     * Whether a copy vertex sits on a source edge — an original vertex or a
     * fragment split — recognizable by an incident source-tagged edge.
     *
     * @param copyVertex copy vertex to test
     * @return true when the vertex lies on a source edge
     */
    private boolean boundaryVertex(int copyVertex) {
        for (int index = 0; index < topology.copy.vertexEdgeCount(copyVertex); index++) {
            if (topology.sourceEdgeByCopyEdge[topology.copy.vertexEdgeAt(copyVertex, index)]
                    != EmbeddedMeshTopology.UNCLAIMED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an edge may be traversed or split under the current face
     * restriction: unrestricted, or incident to an admitted face.
     *
     * @param edgeId copy edge to test
     * @return true when the edge is admissible
     */
    private boolean edgeInRestriction(int edgeId) {
        if (sourceFaceStampBySourceFace.length == 0) {
            return true;
        }
        int halfEdge = topology.copy.edgeHalfEdge(edgeId);
        int faceA = topology.copy.halfEdgeFace(halfEdge);
        int faceB = topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(halfEdge));
        return faceA >= 0 && faceInRestriction(faceA) || faceB >= 0 && faceInRestriction(faceB);
    }

    /**
     * Whether a face may be walked or refined under the current face
     * restriction.
     *
     * @param faceId copy face to test
     * @return true when the face is admissible
     */
    private boolean faceInRestriction(int faceId) {
        return sourceFaceStampBySourceFace.length == 0
                || sourceFaceStampBySourceFace[topology.sourceFaceByCopyFace[faceId]]
                        == sourceFaceStamp;
    }
}
