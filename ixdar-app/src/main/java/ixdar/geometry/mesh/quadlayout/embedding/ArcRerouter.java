package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.ActiveIdSet;

/**
 * Re-embeds an arc whose endpoint node has just moved, restricted to cross or
 * touch no other arc, splitting the fewest edges any such route can: never one
 * where an unrefined detour exists, however long that detour is.
 *
 * <p>See also: LCBK19 Section 6.1
 */
public final class ArcRerouter {

    /** Split position of a midpoint refinement. */
    private static final double EDGE_MIDPOINT = 0.5;

    /** Potential of a search node no gate pass reached — nothing may step onto it. */
    private static final int UNREACHED = -1;

    /** Corners (and edges) of a triangle. */
    private static final int CORNERS = 3;

    /** Halves a summed pair of positions into a midpoint. */
    private static final float MIDPOINT_SCALE = 0.5f;

    /** Starting capacity of the frontier heap; grows by doubling. */
    private static final int FRONTIER_INITIAL_CAPACITY = 1024;

    /** Bit offset of the cost half of a packed frontier entry. */
    private static final int COST_BITS_SHIFT = 32;

    /** Mask of the node-id half of a packed frontier entry. */
    private static final long NODE_ID_MASK = 0xFFFFFFFFL;

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

    /** Chords flipped instead of split, which costs the working mesh nothing. */
    public int chordFlipCount;

    /**
     * Most edges any one route had to split. The paper's blockage costs a few splits, so
     * a large value means some hop is threading a channel rather than rounding it.
     */
    public int mostSplitsInOneRoute;

    /** Calls to {@link #tryRoute}. */
    public int routeAttemptCount;

    /** Re-routes that only succeeded by materializing splits. */
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
     * Reusable allocation-free Dijkstra frontier: a binary min-heap of packed
     * entries, cost bits high and node id low, so entries order by cost first
     * and node id among equal costs.
     */
    public long[] frontierHeap = new long[FRONTIER_INITIAL_CAPACITY];

    /** Live entry count of {@link #frontierHeap}. */
    public int frontierSize;

    /** Tentative cost per search node, valid where {@link #vertexVisitStampByVertex} matches. */
    public float[] distanceByVertex = new float[0];

    /** Search parent per node, valid where {@link #vertexVisitStampByVertex} matches. */
    public int[] parentVertexByVertex = new int[0];

    /** Search generation that last wrote each node's cost and parent. */
    public int[] vertexVisitStampByVertex = new int[0];

    /** Search generation that settled each node; a settled node is final and never re-expanded. */
    public int[] settledStampByVertex = new int[0];

    /**
     * Generation counter shared by the stamped scratch arrays; a stamp mismatch
     * means unvisited, so searches never clear the arrays.
     */
    public int visitStamp;

    /** Splits still needed from each node, valid where {@link #gateStampByNode} matches. */
    public int[] splitPotentialByNode = new int[0];

    /** Gate-pass generation that last wrote each node's potential. */
    public int[] gateStampByNode = new int[0];

    /** Generation counter for {@link #gateStampByNode}. */
    public int gateStamp;

    /** Nodes reached at the split count the gate pass is draining. */
    public int[] gateBucket = new int[FRONTIER_INITIAL_CAPACITY];

    /** Live entry count of {@link #gateBucket}. */
    public int gateBucketSize;

    /** Nodes reached at one split more than the bucket being drained. */
    public int[] nextGateBucket = new int[FRONTIER_INITIAL_CAPACITY];

    /** Live entry count of {@link #nextGateBucket}. */
    public int nextGateBucketSize;

    /** Exclusive bound on real search nodes; a virtual midpoint node sits at this plus its edge id. */
    public int vertexIdBound;

    /**
     * Gate stamp of the last failed pass, or zero for none; every vertex it left
     * unstamped provably cannot reach that target under any stricter claim state.
     */
    public int exhaustedFailureStamp;

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
     * Route an arc between two vertices without crossing or touching another arc,
     * splitting the fewest edges such a route can: a free pass first, and only
     * where that fails a gate pass and a refined pass confined to its corridor.
     *
     * <p>See also: LCBK19 Section 6.1
     *
     * @param arcId           arc being re-routed, for counters
     * @param vertices        path list; the start vertex is appended when empty, and
     *                        the routed continuation follows it
     * @param startCopyVertex hop source
     * @param endCopyVertex   hop target
     * @param corridor        reached-path set, grown with the routed vertices
     * @param passThrough     a claimed vertex the search may transit anyway — the collapsing
     *                        node, which the arc must follow to its new home — or
     *                        {@link EmbeddedMeshTopology#UNCLAIMED} for none
     * @return whether the path now ends at the target
     * @throws IllegalStateException when the gate pass promised a corridor the refined
     *                               pass could not walk, which means the two disagree
     */
    public boolean tryRoute(int arcId, List<Integer> vertices, int startCopyVertex,
            int endCopyVertex, ActiveIdSet corridor, int passThrough) {
        if (vertices.isEmpty()) {
            vertices.add(startCopyVertex);
        }
        lastCorridorSet = corridor;
        routeAttemptCount++;
        vertexIdBound = topology.ownerArcByCopyVertex.length;
        int nodeIdBound = vertexIdBound + topology.ownerArcByCopyEdge.length;
        if (distanceByVertex.length < nodeIdBound) {
            distanceByVertex = Arrays.copyOf(distanceByVertex, nodeIdBound);
            parentVertexByVertex = Arrays.copyOf(parentVertexByVertex, nodeIdBound);
            vertexVisitStampByVertex = Arrays.copyOf(vertexVisitStampByVertex, nodeIdBound);
            settledStampByVertex = Arrays.copyOf(settledStampByVertex, nodeIdBound);
        }
        Vector3f positionHere = new Vector3f();
        Vector3f positionA = new Vector3f();
        Vector3f positionB = new Vector3f();
        Vector3f positionCandidate = new Vector3f();
        int reachedCount = 0;
        int stamp = 0;
        boolean reachedTarget = false;
        for (int pass = 0; pass < 2 && !reachedTarget; pass++) {
            boolean refined = pass == 1;
            if (refined && gatePass(startCopyVertex, endCopyVertex, passThrough) == UNREACHED) {
                lastReachedCount = reachedCount;
                exhaustedFailureStamp = gateStamp;
                lastCorridorSize = corridor.size();
                return false;
            }
            stamp = nextVisitStamp();
            frontierSize = 0;
            distanceByVertex[startCopyVertex] = 0f;
            vertexVisitStampByVertex[startCopyVertex] = stamp;
            reachedCount++;
            frontierPush(0f, startCopyVertex);
            while (frontierSize > 0) {
                long entry = frontierPop();
                int node = (int) (entry & NODE_ID_MASK);
                if (settledStampByVertex[node] == stamp) {
                    continue;
                }
                settledStampByVertex[node] = stamp;
                if (node == endCopyVertex) {
                    reachedTarget = true;
                    break;
                }
                float headDistance = distanceByVertex[node];
                if (node < vertexIdBound) {
                    int headPotential = refined ? nodePotential(node) : 0;
                    for (int index = 0; index < topology.copy.vertexEdgeCount(node); index++) {
                        int edgeId = topology.copy.vertexEdgeAt(node, index);
                        if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED
                                || !edgeInRestriction(edgeId)) {
                            continue;
                        }
                        int neighbor = topology.otherEndpoint(edgeId, node);
                        if (realAdmissible(neighbor, endCopyVertex, passThrough)
                                && (!refined || tightStep(headPotential, nodePotential(neighbor), 0))) {
                            reachedCount += relax(node, neighbor, headDistance
                                    + topology.edgeLength(edgeId), stamp);
                        }
                    }
                    if (refined) {
                        topology.copy.vertexPosition(node, positionHere);
                    }
                    for (int index = 0; refined
                            && index < topology.copy.vertexFaceCount(node); index++) {
                        int faceId = topology.copy.vertexFaceAt(node, index);
                        if (!faceInRestriction(faceId)) {
                            continue;
                        }
                        int partner = flipPartner(node, faceId, endCopyVertex, passThrough);
                        if (partner != EmbeddedMeshTopology.UNCLAIMED
                                && tightStep(headPotential, nodePotential(partner), 0)) {
                            topology.copy.vertexPosition(partner, positionCandidate);
                            reachedCount += relax(node, partner,
                                    headDistance + positionHere.distance(positionCandidate), stamp);
                        }
                        for (int corner = 0; corner < CORNERS; corner++) {
                            int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                            int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                            if (topology.copy.halfEdgeVertex(halfEdge) == node
                                    || topology.copy.halfEdgeEndVertex(halfEdge) == node
                                    || !virtualAdmissible(edgeId)
                                    || !tightStep(headPotential, nodePotential(vertexIdBound + edgeId), 1)) {
                                continue;
                            }
                            midpointPosition(halfEdge, positionA, positionB, positionCandidate);
                            reachedCount += relax(node, vertexIdBound + edgeId, headDistance
                                    + positionHere.distance(positionCandidate), stamp);
                        }
                    }
                } else {
                    int nodeEdge = node - vertexIdBound;
                    int headPotential = nodePotential(node);
                    int nodeHalfEdge = topology.copy.edgeHalfEdge(nodeEdge);
                    midpointPosition(nodeHalfEdge, positionA, positionB, positionHere);
                    for (int side = 0; side < 2; side++) {
                        int faceId = side == 0 ? topology.copy.halfEdgeFace(nodeHalfEdge)
                                : topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(nodeHalfEdge));
                        if (faceId < 0 || !faceInRestriction(faceId)) {
                            continue;
                        }
                        for (int corner = 0; corner < CORNERS; corner++) {
                            int neighbor = topology.copy.faceVertexAt(faceId, corner);
                            if (realAdmissible(neighbor, endCopyVertex, passThrough)
                                    && tightStep(headPotential, nodePotential(neighbor), 0)) {
                                topology.copy.vertexPosition(neighbor, positionCandidate);
                                reachedCount += relax(node, neighbor, headDistance
                                        + positionHere.distance(positionCandidate), stamp);
                            }
                            int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                            if (edgeId == nodeEdge || !virtualAdmissible(edgeId)
                                    || !tightStep(headPotential, nodePotential(vertexIdBound + edgeId), 1)) {
                                continue;
                            }
                            midpointPosition(topology.copy.edgeHalfEdge(edgeId),
                                    positionA, positionB, positionCandidate);
                            reachedCount += relax(node, vertexIdBound + edgeId, headDistance
                                    + positionHere.distance(positionCandidate), stamp);
                        }
                    }
                }
            }
            if (!reachedTarget && refined) {
                throw new IllegalStateException("gate pass reached start vertex " + startCopyVertex
                        + " from target " + endCopyVertex + " at "
                        + nodePotential(startCopyVertex) + " split(s), but the refined pass could"
                        + " not walk that corridor; the two passes disagree about which moves the"
                        + " claims allow");
            }
        }
        lastReachedCount = reachedCount;
        if (!reachedTarget) {
            lastCorridorSize = corridor.size();
            return false;
        }
        exhaustedFailureStamp = 0;
        List<Integer> nodePath = new ArrayList<>();
        for (int walk = endCopyVertex; walk != startCopyVertex; walk = parentVertexByVertex[walk]) {
            nodePath.add(walk);
        }
        Collections.reverse(nodePath);
        int routeSplitCount = 0;
        int previousVertex = startCopyVertex;
        for (int node : nodePath) {
            int realVertex = node;
            if (node >= vertexIdBound) {
                realVertex = topology.splitEdgeAtParameter(node - vertexIdBound, EDGE_MIDPOINT);
                refinedEdgeSplitCount++;
                routeSplitCount++;
            } else if (topology.edgeBetween(previousVertex, realVertex)
                    == EmbeddedMeshTopology.UNCLAIMED) {
                flipChordBetween(previousVertex, realVertex);
            }
            corridor.add(realVertex);
            vertices.add(realVertex);
            previousVertex = realVertex;
        }
        if (routeSplitCount > 0) {
            refinedRetryCount++;
            mostSplitsInOneRoute = Math.max(mostSplitsInOneRoute, routeSplitCount);
        }
        lastCorridorSize = corridor.size();
        return true;
    }

    /**
     * Flood back from the target over the moves the refined pass walks, recording how many
     * splits a route from each node still needs.
     *
     * <p>Reaching the source ends the flood: no node the refined pass can stand on costs
     * more. See also: LCBK19 Section 6.1
     *
     * @param startCopyVertex hop source, whose potential the refined pass starts from
     * @param endCopyVertex   hop target, the flood's seed
     * @param passThrough     permitted claimed transit vertex
     * @return splits the start still needs, or {@link #UNREACHED} when no corridor reaches it
     */
    private int gatePass(int startCopyVertex, int endCopyVertex, int passThrough) {
        int nodeIdBound = vertexIdBound + topology.ownerArcByCopyEdge.length;
        if (gateStampByNode.length < nodeIdBound) {
            splitPotentialByNode = Arrays.copyOf(splitPotentialByNode, nodeIdBound);
            gateStampByNode = Arrays.copyOf(gateStampByNode, nodeIdBound);
        }
        gateStamp = nextGateStamp();
        gateBucketSize = 0;
        nextGateBucketSize = 0;
        reachGateNode(endCopyVertex, 0);
        int splitCount = 0;
        while (gateBucketSize > 0) {
            while (gateBucketSize > 0) {
                int node = gateBucket[--gateBucketSize];
                if (splitPotentialByNode[node] != splitCount) {
                    continue;
                }
                if (node < vertexIdBound) {
                    for (int index = 0; index < topology.copy.vertexEdgeCount(node); index++) {
                        int edgeId = topology.copy.vertexEdgeAt(node, index);
                        int neighbor = topology.otherEndpoint(edgeId, node);
                        if (topology.ownerArcByCopyEdge[edgeId] == EmbeddedMeshTopology.UNCLAIMED
                                && edgeInRestriction(edgeId)
                                && gateAdmissible(neighbor, startCopyVertex, endCopyVertex,
                                        passThrough)) {
                            reachGateNode(neighbor, splitCount);
                        }
                    }
                    for (int index = 0; index < topology.copy.vertexFaceCount(node); index++) {
                        int faceId = topology.copy.vertexFaceAt(node, index);
                        if (!faceInRestriction(faceId)) {
                            continue;
                        }
                        int partner = flipPartner(node, faceId, endCopyVertex, passThrough);
                        if (partner != EmbeddedMeshTopology.UNCLAIMED) {
                            reachGateNode(partner, splitCount);
                        }
                        for (int corner = 0; corner < CORNERS; corner++) {
                            int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                            if (virtualAdmissible(edgeId)) {
                                reachGateNode(vertexIdBound + edgeId, splitCount);
                            }
                        }
                    }
                } else {
                    int nodeEdge = node - vertexIdBound;
                    int nodeHalfEdge = topology.copy.edgeHalfEdge(nodeEdge);
                    int nodeTail = topology.copy.halfEdgeVertex(nodeHalfEdge);
                    int nodeHead = topology.copy.halfEdgeEndVertex(nodeHalfEdge);
                    for (int side = 0; side < 2; side++) {
                        int faceId = side == 0 ? topology.copy.halfEdgeFace(nodeHalfEdge)
                                : topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(nodeHalfEdge));
                        if (faceId < 0 || !faceInRestriction(faceId)) {
                            continue;
                        }
                        for (int corner = 0; corner < CORNERS; corner++) {
                            int cornerVertex = topology.copy.faceVertexAt(faceId, corner);
                            if (cornerVertex != nodeTail && cornerVertex != nodeHead
                                    && gateAdmissible(cornerVertex, startCopyVertex, endCopyVertex,
                                            passThrough)) {
                                reachGateNodeLater(cornerVertex, splitCount + 1);
                            }
                            int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                            if (edgeId != nodeEdge && virtualAdmissible(edgeId)) {
                                reachGateNodeLater(vertexIdBound + edgeId, splitCount + 1);
                            }
                        }
                    }
                }
            }
            if (nodePotential(startCopyVertex) != UNREACHED
                    && splitPotentialByNode[startCopyVertex] <= splitCount) {
                break;
            }
            splitCount++;
            int[] drained = gateBucket;
            gateBucket = nextGateBucket;
            nextGateBucket = drained;
            gateBucketSize = nextGateBucketSize;
            nextGateBucketSize = 0;
        }
        return nodePotential(startCopyVertex);
    }

    /**
     * Record a node the gate pass reached at the count it is draining, and queue it for
     * expansion in that same bucket.
     *
     * @param node       search node reached, real or virtual
     * @param splitCount splits still needed from there
     */
    private void reachGateNode(int node, int splitCount) {
        if (gateStampByNode[node] == gateStamp && splitPotentialByNode[node] <= splitCount) {
            return;
        }
        gateStampByNode[node] = gateStamp;
        splitPotentialByNode[node] = splitCount;
        if (gateBucketSize == gateBucket.length) {
            gateBucket = Arrays.copyOf(gateBucket, gateBucket.length * 2);
        }
        gateBucket[gateBucketSize++] = node;
    }

    /**
     * Record a node one split further out than the count being drained, and queue it for
     * the next bucket.
     *
     * @param node       search node reached, real or virtual
     * @param splitCount splits still needed from there
     */
    private void reachGateNodeLater(int node, int splitCount) {
        if (gateStampByNode[node] == gateStamp && splitPotentialByNode[node] <= splitCount) {
            return;
        }
        gateStampByNode[node] = gateStamp;
        splitPotentialByNode[node] = splitCount;
        if (nextGateBucketSize == nextGateBucket.length) {
            nextGateBucket = Arrays.copyOf(nextGateBucket, nextGateBucket.length * 2);
        }
        nextGateBucket[nextGateBucketSize++] = node;
    }

    /**
     * The corner a flip would connect a vertex to, across the chord opposite it.
     *
     * <p>A flip crosses a chord for nothing where a split costs a vertex forever, so it
     * needs one source face and a strictly convex quad. See also: LCBK19 Section 6.1
     *
     * @param copyVertex    vertex the search stands on
     * @param faceId        one of its faces, whose opposite edge is the candidate chord
     * @param endCopyVertex search target, always standable
     * @param passThrough   permitted claimed transit vertex
     * @return the corner across the flip, or {@link EmbeddedMeshTopology#UNCLAIMED} for none
     */
    private int flipPartner(int copyVertex, int faceId, int endCopyVertex, int passThrough) {
        int edgeId = oppositeEdge(faceId, copyVertex);
        if (edgeId == EmbeddedMeshTopology.UNCLAIMED || !virtualAdmissible(edgeId)
                || !chordEdge(edgeId, endCopyVertex, passThrough)) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        int halfEdge = topology.copy.edgeHalfEdge(edgeId);
        int twinFace = topology.copy.halfEdgeFace(halfEdge) == faceId
                ? topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(halfEdge))
                : topology.copy.halfEdgeFace(halfEdge);
        int sourceFace = topology.sourceFaceByCopyFace[faceId];
        if (twinFace < 0 || !faceInRestriction(twinFace)
                || sourceFace == EmbeddedMeshTopology.UNCLAIMED
                || sourceFace != topology.sourceFaceByCopyFace[twinFace]) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        int chordTail = topology.copy.halfEdgeVertex(halfEdge);
        int chordHead = topology.copy.halfEdgeEndVertex(halfEdge);
        int partner = topology.copy.faceOppositeCorner(twinFace, chordTail, chordHead);
        if (!realAdmissible(partner, endCopyVertex, passThrough)) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        double[] here = topology.barycentricOf(sourceFace, copyVertex);
        double[] across = topology.barycentricOf(sourceFace, partner);
        double[] tailSide = topology.barycentricOf(sourceFace, chordTail);
        double[] headSide = topology.barycentricOf(sourceFace, chordHead);
        if (here == null || across == null || tailSide == null || headSide == null) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        int tailSign = ExactBarycentricOrient.sign(here, across, tailSide);
        int headSign = ExactBarycentricOrient.sign(here, across, headSide);
        return tailSign == 0 || headSign == 0 || tailSign == headSign
                ? EmbeddedMeshTopology.UNCLAIMED : partner;
    }

    /**
     * Whether an edge can only be crossed by leaving it: neither endpoint is a vertex the
     * search may stand on. This is MPZ14's chord, the witness that the region between the
     * arcs is not 3-connected.
     *
     * @param edgeId        candidate copy edge
     * @param endCopyVertex search target, always standable
     * @param passThrough   permitted claimed transit vertex
     * @return true when neither endpoint is standable
     */
    private boolean chordEdge(int edgeId, int endCopyVertex, int passThrough) {
        int halfEdge = topology.copy.edgeHalfEdge(edgeId);
        return !realAdmissible(topology.copy.halfEdgeVertex(halfEdge), endCopyVertex, passThrough)
                && !realAdmissible(topology.copy.halfEdgeEndVertex(halfEdge), endCopyVertex,
                        passThrough);
    }

    /**
     * The edge of a triangle that does not touch one of its corners.
     *
     * @param faceId   triangle to read
     * @param vertexId corner to exclude
     * @return the opposite edge, or {@link EmbeddedMeshTopology#UNCLAIMED} when the corner
     *         is not on the face
     */
    private int oppositeEdge(int faceId, int vertexId) {
        for (int corner = 0; corner < CORNERS; corner++) {
            int edgeId = topology.copy.faceEdgeAt(faceId, corner);
            int halfEdge = topology.copy.edgeHalfEdge(edgeId);
            if (topology.copy.halfEdgeVertex(halfEdge) != vertexId
                    && topology.copy.halfEdgeEndVertex(halfEdge) != vertexId) {
                return edgeId;
            }
        }
        return EmbeddedMeshTopology.UNCLAIMED;
    }

    /**
     * Flip the chord separating two routed vertices, so the step between them becomes a
     * real edge. Recomputed against the live mesh, since an earlier flip on the same route
     * may have retriangulated the quad.
     *
     * @param fromVertex step source
     * @param toVertex   step target, which no edge yet reaches
     * @throws IllegalStateException when no flippable chord joins them
     */
    private void flipChordBetween(int fromVertex, int toVertex) {
        for (int index = 0; index < topology.copy.vertexFaceCount(fromVertex); index++) {
            int faceId = topology.copy.vertexFaceAt(fromVertex, index);
            int edgeId = oppositeEdge(faceId, fromVertex);
            if (edgeId == EmbeddedMeshTopology.UNCLAIMED
                    || topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            int halfEdge = topology.copy.edgeHalfEdge(edgeId);
            int twinFace = topology.copy.halfEdgeFace(halfEdge) == faceId
                    ? topology.copy.halfEdgeFace(topology.copy.halfEdgeTwin(halfEdge))
                    : topology.copy.halfEdgeFace(halfEdge);
            if (twinFace < 0) {
                continue;
            }
            int chordTail = topology.copy.halfEdgeVertex(halfEdge);
            int chordHead = topology.copy.halfEdgeEndVertex(halfEdge);
            if (topology.copy.faceOppositeCorner(twinFace, chordTail, chordHead) == toVertex) {
                topology.flipEdge(edgeId);
                chordFlipCount++;
                return;
            }
        }
        throw new IllegalStateException("routed step from " + fromVertex + " to " + toVertex
                + " has no edge and no flippable chord between them; the search took a move"
                + " the working mesh cannot realize");
    }

    /**
     * Splits still needed from a search node, per the last gate pass.
     *
     * @param node search node, real or virtual
     * @return the count, or {@link #UNREACHED} when no corridor reached it
     */
    private int nodePotential(int node) {
        return gateStampByNode[node] == gateStamp ? splitPotentialByNode[node] : UNREACHED;
    }

    /**
     * Whether the gate pass may stand on a vertex. The hop source counts however it is
     * claimed, since the route pass begins standing on it.
     *
     * @param vertex          candidate copy vertex
     * @param startCopyVertex hop source, where the route pass already stands
     * @param endCopyVertex   search target, always admissible
     * @param passThrough     permitted claimed transit vertex
     * @return true when the vertex is admissible
     */
    private boolean gateAdmissible(int vertex, int startCopyVertex, int endCopyVertex,
            int passThrough) {
        return vertex == startCopyVertex || realAdmissible(vertex, endCopyVertex, passThrough);
    }

    /**
     * Whether a move stays on a corridor of fewest splits: it must spend exactly the
     * splits it costs and no more, so the whole route spends the gate pass's minimum.
     *
     * @param fromPotential splits still needed at the move's source
     * @param toPotential   splits still needed at its target
     * @param splitCost     splits the move itself spends, zero or one
     * @return true when the move may be taken
     */
    private boolean tightStep(int fromPotential, int toPotential, int splitCost) {
        return toPotential != UNREACHED && toPotential == fromPotential - splitCost;
    }

    /**
     * Whether a vertex was left unreached by the last failed gate pass — from there the
     * target stays unreachable under any equal-or-stricter claim state, so a back-off
     * attempt starting on it can be skipped.
     *
     * @param copyVertex candidate search start, always a real copy vertex
     * @return true when the vertex provably cannot reach the last failed target
     */
    public boolean settledInExhaustedFailure(int copyVertex) {
        return exhaustedFailureStamp != 0 && copyVertex < gateStampByNode.length
                && gateStampByNode[copyVertex] != exhaustedFailureStamp;
    }

    /**
     * Forget the last failed search, required whenever claims are released —
     * a grown graph invalidates the unreachability proof.
     */
    public void clearFailureMemory() {
        exhaustedFailureStamp = 0;
    }

    /**
     * Relax one search move, stamping and queueing the node when it improves.
     *
     * @param fromNode  move source
     * @param toNode    move target, real or virtual
     * @param newCost   cost of reaching the target through the source
     * @param stamp     current search generation
     * @return one when the node was reached first, else zero
     */
    private int relax(int fromNode, int toNode, float newCost, int stamp) {
        boolean seen = vertexVisitStampByVertex[toNode] == stamp;
        if (newCost >= (seen ? distanceByVertex[toNode] : Float.POSITIVE_INFINITY)) {
            return 0;
        }
        vertexVisitStampByVertex[toNode] = stamp;
        distanceByVertex[toNode] = newCost;
        parentVertexByVertex[toNode] = fromNode;
        frontierPush(newCost, toNode);
        return seen ? 0 : 1;
    }

    /**
     * Push a search node onto the frontier heap. Costs are non-negative, so the
     * packed entry orders by cost as a signed long.
     *
     * @param cost tentative cost, the heap priority
     * @param node real or virtual search node id
     */
    private void frontierPush(float cost, int node) {
        if (frontierSize == frontierHeap.length) {
            frontierHeap = Arrays.copyOf(frontierHeap, frontierHeap.length * 2);
        }
        long entry = (long) Float.floatToRawIntBits(cost) << COST_BITS_SHIFT
                | node & NODE_ID_MASK;
        int hole = frontierSize++;
        while (hole > 0) {
            int parent = (hole - 1) / 2;
            if (frontierHeap[parent] <= entry) {
                break;
            }
            frontierHeap[hole] = frontierHeap[parent];
            hole = parent;
        }
        frontierHeap[hole] = entry;
    }

    /**
     * Pop the cheapest entry off the frontier heap.
     *
     * @return the packed entry, cost bits high and node id low
     */
    private long frontierPop() {
        long top = frontierHeap[0];
        long moved = frontierHeap[--frontierSize];
        int hole = 0;
        int child = 1;
        while (child < frontierSize) {
            if (child + 1 < frontierSize && frontierHeap[child + 1] < frontierHeap[child]) {
                child++;
            }
            if (frontierHeap[child] >= moved) {
                break;
            }
            frontierHeap[hole] = frontierHeap[child];
            hole = child;
            child = 2 * hole + 1;
        }
        frontierHeap[hole] = moved;
        return top;
    }

    /**
     * Whether the search may stand on a real vertex: the target, the permitted
     * pass-through, or a free (and, under {@link #interiorOnly}, interior) one.
     *
     * @param vertex        candidate copy vertex
     * @param endCopyVertex search target, always admissible
     * @param passThrough   permitted claimed transit vertex
     * @return true when the vertex is admissible
     */
    private boolean realAdmissible(int vertex, int endCopyVertex, int passThrough) {
        return vertex == endCopyVertex || vertex == passThrough
                || !(vertexClaimed(vertex) || interiorOnly && boundaryVertex(vertex));
    }

    /**
     * Whether an edge's midpoint may serve as a virtual search node: unclaimed,
     * admitted by the face restriction, and interior under {@link #interiorOnly}.
     *
     * @param edgeId candidate copy edge
     * @return true when the midpoint is admissible
     */
    private boolean virtualAdmissible(int edgeId) {
        return topology.ownerArcByCopyEdge[edgeId] == EmbeddedMeshTopology.UNCLAIMED
                && edgeInRestriction(edgeId)
                && !(interiorOnly
                        && topology.sourceEdgeByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED);
    }

    /**
     * Midpoint of an edge, from its two endpoint positions.
     *
     * @param halfEdge  a half-edge of the edge
     * @param scratchA  scratch for the first endpoint
     * @param scratchB  scratch for the second endpoint
     * @param out       receives the midpoint
     */
    private void midpointPosition(int halfEdge, Vector3f scratchA, Vector3f scratchB,
            Vector3f out) {
        topology.copy.vertexPosition(topology.copy.halfEdgeVertex(halfEdge), scratchA);
        topology.copy.vertexPosition(topology.copy.halfEdgeEndVertex(halfEdge), scratchB);
        out.set(scratchA).add(scratchB).mul(MIDPOINT_SCALE);
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
     * Advances the shared scratch-array generation, wrapping all stamp arrays back
     * to zero before overflow so a stale stamp can never collide with a live one.
     *
     * @return the fresh generation value to stamp this search's writes with
     */
    private int nextVisitStamp() {
        if (visitStamp == Integer.MAX_VALUE) {
            Arrays.fill(vertexVisitStampByVertex, 0);
            Arrays.fill(settledStampByVertex, 0);
            visitStamp = 0;
        }
        visitStamp++;
        return visitStamp;
    }

    /**
     * Advances the gate-pass generation, wrapping its stamp arrays back to zero before
     * overflow so a stale stamp can never collide with a live one.
     *
     * @return the fresh generation value to stamp this gate pass's writes with
     */
    private int nextGateStamp() {
        if (gateStamp == Integer.MAX_VALUE) {
            Arrays.fill(gateStampByNode, 0);
            gateStamp = 0;
        }
        gateStamp++;
        return gateStamp;
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
