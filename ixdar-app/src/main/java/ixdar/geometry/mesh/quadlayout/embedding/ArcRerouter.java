package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.ActiveIdSet;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.model.EmbeddedMeshTopology;

/**
 * Dijkstra's shortest path between two vertices of the working copy, crossing
 * and touching no other arc; a path splitting fewer edges always settles first.
 *
 * <p>
 * See also: LCBK19 Section 6.1, "Operator Implementation"
 */
public final class ArcRerouter {

    private static final int CORNERS = 3;

    private static final int FRONTIER_INITIAL_CAPACITY = 1024;

    private static final int SPLIT_BITS_SHIFT = 32;

    private static final long LENGTH_BITS_MASK = 0xFFFFFFFFL;

    private static final int NODE_STATE_STRIDE = 4;

    private static final int MARK_SLOT = 0;

    private static final int SPLITS_SLOT = 1;

    private static final int LENGTH_SLOT = 2;

    private static final int PARENT_SLOT = 3;

    private static final int SETTLED_BIT = 1;

    private static final int LAST_VISIT_STAMP = Integer.MAX_VALUE >> 1;

    public final EmbeddedMeshTopology topology;

    /**
     * Stamp per source face admitting it to the current carve search; empty for the
     * unrestricted re-routes of the contraction operators. A carve stretch never
     * leaves its segment's source face.
     */
    public int[] sourceFaceStampBySourceFace = new int[0];

    /**
     * Stamp value marking the admitted faces in
     * {@link #sourceFaceStampBySourceFace}.
     */
    public int sourceFaceStamp;

    /**
     * Whether searches may only pass through face-interior vertices — the carve
     * sets this, since a traced course touches the face boundary solely at its own
     * crossings. Endpoints are always exempt.
     */
    public boolean interiorOnly;

    /**
     * Stamp per patch id admitting every face that patch covers, for a caller that
     * wants a route kept inside named patches; the arcs alone already keep a
     * re-route in the region they enclose.
     */
    public int[] patchStampByPatch = new int[0];

    /** Stamp value marking the admitted patches in {@link #patchStampByPatch}. */
    public int patchStamp;

    /** Whether the search honors {@link #patchStampByPatch}. */
    public boolean patchRestrictionActive;

    /**
     * Stamp per copy face the route's first hop may run through, the corner of the
     * start vertex that lies between the route's two patches.
     */
    public int[] departureStampByFace = new int[0];

    /**
     * Stamp per copy face the route's last hop may run through, likewise at the
     * target.
     */
    public int[] arrivalStampByFace = new int[0];

    /** Stamp value marking the admitted faces of both corner arrays. */
    public int cornerStamp;

    /** Whether a departure corner is open; with none the first hop is free. */
    public boolean departureCornerOpen;

    /** Whether an arrival corner is open; with none the last hop is free. */
    public boolean arrivalCornerOpen;

    /** Edges split to open a path through a region that had none. */
    public int refinedEdgeSplitCount;

    /**
     * Most edges any one route had to split. The paper's blockage costs a few
     * splits, so a large value means some hop is threading a channel rather than
     * rounding it.
     */
    public int mostSplitsInOneRoute;

    /** Calls to {@link #tryRoute}. */
    public int routeAttemptCount;

    /**
     * Corridor handed to callers by {@link #freshCorridor()}, reused across
     * attempts.
     */
    public final ActiveIdSet corridorScratch = new ActiveIdSet(0);

    /**
     * Frontier heap keys, split count in the high bits and path length in the low
     * bits, so a path that splits fewer edges always settles first.
     */
    public long[] frontierKeys = new long[FRONTIER_INITIAL_CAPACITY];

    /** Search node of each {@link #frontierKeys} entry. */
    public int[] frontierNodes = new int[FRONTIER_INITIAL_CAPACITY];

    /** Live entry count of the frontier heap. */
    public int frontierSize;

    /**
     * Per search node, four adjacent ints: mark (generation shifted left, low bit when
     * settled), split count, length bits, parent. Adjacent so one relaxation reads one cache
     * line.
     */
    public int[] stateByNode = new int[0];

    /**
     * Generation counter behind the node marks; a mark from another generation means
     * unvisited, so searches never clear the state.
     */
    public int visitStamp;

    /**
     * Exclusive bound on real search nodes; the midpoint of an edge that a route
     * may split sits at this plus the edge's id.
     */
    public int vertexIdBound;

    /** Position of the search node being expanded. */
    public final Vector3f positionHere = new Vector3f();

    /** Position of the neighbour a move is being relaxed onto. */
    public final Vector3f positionCandidate = new Vector3f();

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
     * <p>
     * One reused set, not a fresh one — a corridor indexes the whole copy-vertex id
     * space, so the previous attempt's contents are invalid once this is called.
     *
     * @return the shared corridor set, emptied
     */
    public ActiveIdSet freshCorridor() {
        corridorScratch.clear();
        return corridorScratch;
    }

    /**
     * Opens a fresh patch restriction, retiring the previous one; until
     * {@link #clearPatchRestriction} the search may only walk faces covered by a
     * patch passed to {@link #admitPatch}.
     */
    public void beginPatchRestriction() {
        if (patchStamp == Integer.MAX_VALUE) {
            Arrays.fill(patchStampByPatch, 0);
            patchStamp = 0;
        }
        patchStamp++;
        patchRestrictionActive = true;
    }

    /**
     * Admits every face a patch covers, by id — the maintained cover labels make
     * this O(1) rather than a flood.
     *
     * @param patchId patch to admit; negative ids are ignored
     */
    public void admitPatch(int patchId) {
        if (patchId < 0) {
            return;
        }
        if (patchId >= patchStampByPatch.length) {
            patchStampByPatch = Arrays.copyOf(patchStampByPatch,
                    Math.max(patchId + 1, patchStampByPatch.length * 2));
        }
        patchStampByPatch[patchId] = patchStamp;
    }

    /** Closes the patch restriction, returning the search to the whole copy. */
    public void clearPatchRestriction() {
        patchRestrictionActive = false;
    }

    /**
     * Opens the corners of the next route: from here until {@link #closeCorners}
     * its first and last hops must run through faces {@link #admitCornerFace}
     * admits.
     */
    public void openCorners() {
        if (cornerStamp == Integer.MAX_VALUE) {
            Arrays.fill(departureStampByFace, 0);
            Arrays.fill(arrivalStampByFace, 0);
            cornerStamp = 0;
        }
        cornerStamp++;
        departureCornerOpen = false;
        arrivalCornerOpen = false;
    }

    /**
     * Admits one face to the start's or the target's corner.
     *
     * @param faceId  copy face the route's first or last hop may run through
     * @param arrival whether it belongs to the target's corner
     */
    public void admitCornerFace(int faceId, boolean arrival) {
        int[] stamps = arrival ? arrivalStampByFace : departureStampByFace;
        if (faceId >= stamps.length) {
            stamps = Arrays.copyOf(stamps, Math.max(faceId + 1, stamps.length * 2));
            if (arrival) {
                arrivalStampByFace = stamps;
            } else {
                departureStampByFace = stamps;
            }
        }
        stamps[faceId] = cornerStamp;
        arrivalCornerOpen |= arrival;
        departureCornerOpen |= !arrival;
    }

    /** Closes both corners, so any hop may start or end a route again. */
    public void closeCorners() {
        departureCornerOpen = false;
        arrivalCornerOpen = false;
    }

    /**
     * Routes an arc between two vertices along the shortest edge path that crosses
     * and touches no other arc, materializing the splits the chosen path asks for.
     * Search nodes are vertices and the midpoints of splittable edges.
     *
     * <p>
     * See also: LCBK19 Section 6.1
     *
     * @param arcId           arc being re-routed, for the claim the splits inherit
     * @param vertices        path list; the start vertex is appended when empty,
     *                        and the routed continuation follows it
     * @param startCopyVertex vertex the route leaves, which alone may split its own
     *                        incident edges: when claims wall it in, the only
     *                        escape crossings touch it
     * @param endCopyVertex   vertex the route reaches
     * @param corridor        reached-path set, grown with the routed vertices
     * @param passThrough     a claimed vertex the search may transit anyway, or
     *                        {@link EmbeddedMeshTopology#UNCLAIMED} for none
     * @throws IllegalStateException when a routed step between two existing
     *                               vertices has no edge between them
     * @return whether the path now ends at the target
     */
    public boolean tryRoute(int arcId, List<Integer> vertices, int startCopyVertex,
            int endCopyVertex, ActiveIdSet corridor, int passThrough) {
        HalfEdgeMesh copy = topology.copy;
        if (vertices.isEmpty()) {
            vertices.add(startCopyVertex);
        }
        routeAttemptCount++;
        vertexIdBound = topology.ownerArcByCopyVertex().length;
        int nodeIdBound = vertexIdBound + topology.ownerArcByCopyEdge().length;
        if (stateByNode.length < nodeIdBound * NODE_STATE_STRIDE) {
            stateByNode = Arrays.copyOf(stateByNode, nodeIdBound * NODE_STATE_STRIDE);
        }
        if (visitStamp == LAST_VISIT_STAMP) {
            Arrays.fill(stateByNode, 0);
            visitStamp = 0;
        }
        int stamp = ++visitStamp;
        int settledMark = stamp << 1 | SETTLED_BIT;
        frontierSize = 0;
        // Walking a face's corners through the flat half-edge arrays visits them in the order
        // of its corner lists, without their per-face object indirection.
        int[] faceHalfEdges = copy.faceHalfEdge;
        int[] halfEdgeNexts = copy.halfEdgeNext;
        int[] halfEdgeVertices = copy.halfEdgeVertex;
        int[] halfEdgeEdges = copy.halfEdgeEdge;
        int[] ownerArcByCopyEdge = topology.ownerArcByCopyEdge();
        relax(startCopyVertex, startCopyVertex, 0, 0f, stamp);
        boolean reachedTarget = false;
        while (frontierSize > 0) {
            int node = frontierNodes[0];
            frontierSize--;
            long movedKey = frontierKeys[frontierSize];
            int movedNode = frontierNodes[frontierSize];
            int hole = 0;
            int child = 1;
            while (child < frontierSize) {
                if (child + 1 < frontierSize && frontierKeys[child + 1] < frontierKeys[child]) {
                    child++;
                }
                if (frontierKeys[child] >= movedKey) {
                    break;
                }
                frontierKeys[hole] = frontierKeys[child];
                frontierNodes[hole] = frontierNodes[child];
                hole = child;
                child = 2 * hole + 1;
            }
            frontierKeys[hole] = movedKey;
            frontierNodes[hole] = movedNode;
            int nodeState = node * NODE_STATE_STRIDE;
            if (stateByNode[nodeState + MARK_SLOT] == settledMark) {
                continue;
            }
            stateByNode[nodeState + MARK_SLOT] = settledMark;
            if (node == endCopyVertex) {
                reachedTarget = true;
                break;
            }
            // A midpoint moves onto the corners and edge midpoints of its edge's faces; a vertex
            // along its free edges and onto its faces' edge midpoints.
            boolean atStart = node == startCopyVertex;
            float headLength = Float.intBitsToFloat(stateByNode[nodeState + LENGTH_SLOT]);
            int headSplits = stateByNode[nodeState + SPLITS_SLOT];
            if (node >= vertexIdBound) {
                int nodeEdge = node - vertexIdBound;
                copy.edgeMidpoint(nodeEdge, positionHere);
                for (int side = 0; side < 2; side++) {
                    int faceId = copy.edgeFace(nodeEdge, side);
                    if (!faceInRestriction(faceId)) {
                        continue;
                    }
                    int cornerHalfEdge = faceHalfEdges[faceId];
                    for (int corner = 0; corner < CORNERS; corner++) {
                        int neighbor = halfEdgeVertices[cornerHalfEdge];
                        int nextCornerHalfEdge = halfEdgeNexts[cornerHalfEdge];
                        boolean arrivesThroughCorner = neighbor != endCopyVertex
                                || inCorner(faceId, true);
                        if (arrivesThroughCorner
                                && standable(neighbor, endCopyVertex, passThrough)) {
                            copy.vertexPosition(neighbor, positionCandidate);
                            relax(node, neighbor, headSplits,
                                    headLength + positionHere.distance(positionCandidate), stamp);
                        }
                        int edgeId = halfEdgeEdges[cornerHalfEdge];
                        if (edgeId != nodeEdge && splittable(edgeId, neighbor,
                                halfEdgeVertices[nextCornerHalfEdge], endCopyVertex, passThrough)) {
                            copy.edgeMidpoint(edgeId, positionCandidate);
                            relax(node, vertexIdBound + edgeId, headSplits + 1,
                                    headLength + positionHere.distance(positionCandidate), stamp);
                        }
                        cornerHalfEdge = nextCornerHalfEdge;
                    }
                }
                continue;
            }
            copy.vertexPosition(node, positionHere);
            for (int index = 0; index < copy.vertexEdgeCount(node); index++) {
                int edgeId = copy.vertexEdgeAt(node, index);
                boolean edgeFree = ownerArcByCopyEdge[edgeId] == EmbeddedMeshTopology.UNCLAIMED;
                if (!edgeFree || !edgeInRestriction(edgeId)) {
                    continue;
                }
                int neighbor = copy.edgeOtherVertex(edgeId, node);
                int leftFace = copy.edgeFace(edgeId, 0);
                int rightFace = copy.edgeFace(edgeId, 1);
                boolean leavesThroughCorner = !atStart
                        || inCorner(leftFace, false) || inCorner(rightFace, false);
                boolean arrivesThroughCorner = neighbor != endCopyVertex
                        || inCorner(leftFace, true) || inCorner(rightFace, true);
                if (leavesThroughCorner && arrivesThroughCorner
                        && standable(neighbor, endCopyVertex, passThrough)) {
                    relax(node, neighbor, headSplits, headLength + topology.copy.edgeLength(edgeId),
                            stamp);
                }
            }
            for (int index = 0; index < copy.vertexFaceCount(node); index++) {
                int faceId = copy.vertexFaceAt(node, index);
                boolean leavesThroughCorner = !atStart || inCorner(faceId, false);
                if (!leavesThroughCorner || !faceInRestriction(faceId)) {
                    continue;
                }
                int cornerHalfEdge = faceHalfEdges[faceId];
                for (int corner = 0; corner < CORNERS; corner++) {
                    int nextCornerHalfEdge = halfEdgeNexts[cornerHalfEdge];
                    int tail = halfEdgeVertices[cornerHalfEdge];
                    int head = halfEdgeVertices[nextCornerHalfEdge];
                    int edgeId = halfEdgeEdges[cornerHalfEdge];
                    cornerHalfEdge = nextCornerHalfEdge;
                    boolean touchesNode = tail == node || head == node;
                    if (touchesNode && !atStart) {
                        continue;
                    }
                    if (splittable(edgeId, tail, head, endCopyVertex, passThrough)) {
                        copy.edgeMidpoint(edgeId, positionCandidate);
                        relax(node, vertexIdBound + edgeId, headSplits + 1,
                                headLength + positionHere.distance(positionCandidate), stamp);
                    }
                }
            }
        }
        if (!reachedTarget) {
            return false;
        }

        List<Integer> nodePath = new ArrayList<>();
        for (int walk = endCopyVertex; walk != startCopyVertex;
                walk = stateByNode[walk * NODE_STATE_STRIDE + PARENT_SLOT]) {
            nodePath.add(walk);
        }
        Collections.reverse(nodePath);
        int routeSplitCount = 0;
        int previousVertex = startCopyVertex;
        for (int node : nodePath) {
            int realVertex = node;
            if (node >= vertexIdBound) {
                realVertex = topology.splitEdgeAtMidpoint(node - vertexIdBound);
                refinedEdgeSplitCount++;
                routeSplitCount++;
            } else {
                int stepEdge = copy.edgeBetween(previousVertex, realVertex);
                if (stepEdge == MeshTopology.NONE) {
                    throw new IllegalStateException("arc " + arcId + " routed a step from "
                            + previousVertex + " to " + realVertex + " with no edge between"
                            + " them; every move the search makes is along an edge or through"
                            + " an edge midpoint");
                }
            }
            corridor.add(realVertex);
            vertices.add(realVertex);
            previousVertex = realVertex;
        }
        mostSplitsInOneRoute = Math.max(mostSplitsInOneRoute, routeSplitCount);
        return true;
    }

    /**
     * Relaxes one search move, stamping and queueing the node when the move reaches
     * it with fewer splits, or with the same splits over a shorter path.
     *
     * @param fromNode  move source
     * @param toNode    move target, a vertex or an edge midpoint
     * @param newSplits splits spent reaching the target through the source
     * @param newLength path length of reaching the target through the source
     * @param stamp     this search's generation
     */
    private void relax(int fromNode, int toNode, int newSplits, float newLength, int stamp) {
        int toState = toNode * NODE_STATE_STRIDE;
        int mark = stateByNode[toState + MARK_SLOT];
        boolean seen = mark >>> 1 == stamp;
        int seenSplits = stateByNode[toState + SPLITS_SLOT];
        boolean noBetter = seenSplits < newSplits || seenSplits == newSplits
                && Float.intBitsToFloat(stateByNode[toState + LENGTH_SLOT]) <= newLength;
        if (seen && noBetter) {
            return;
        }
        stateByNode[toState + MARK_SLOT] = stamp << 1 | (seen ? mark & SETTLED_BIT : 0);
        stateByNode[toState + SPLITS_SLOT] = newSplits;
        stateByNode[toState + LENGTH_SLOT] = Float.floatToRawIntBits(newLength);
        stateByNode[toState + PARENT_SLOT] = fromNode;
        if (frontierSize == frontierKeys.length) {
            frontierKeys = Arrays.copyOf(frontierKeys, frontierKeys.length * 2);
            frontierNodes = Arrays.copyOf(frontierNodes, frontierNodes.length * 2);
        }
        long key = (long) newSplits << SPLIT_BITS_SHIFT
                | Float.floatToRawIntBits(newLength) & LENGTH_BITS_MASK;
        int hole = frontierSize++;
        while (hole > 0 && frontierKeys[(hole - 1) / 2] > key) {
            int parent = (hole - 1) / 2;
            frontierKeys[hole] = frontierKeys[parent];
            frontierNodes[hole] = frontierNodes[parent];
            hole = parent;
        }
        frontierKeys[hole] = key;
        frontierNodes[hole] = toNode;
    }

    /**
     * Whether the search may stand on a real vertex: the route's own target, the
     * permitted pass-through, or a vertex no node and no arc holds — and, for an
     * interior-only search, one on no source edge.
     *
     * @param vertex        candidate copy vertex
     * @param endCopyVertex vertex the route reaches, always standable
     * @param passThrough   a claimed vertex the search may transit anyway
     * @return true when the vertex is free for this route
     */
    private boolean standable(int vertex, int endCopyVertex, int passThrough) {
        if (vertex == endCopyVertex || vertex == passThrough) {
            return true;
        }
        boolean heldByNode = topology.ownerNodeByCopyVertex()[vertex] != EmbeddedMeshTopology.UNCLAIMED;
        boolean heldByArc = topology.ownerArcByCopyVertex()[vertex] != EmbeddedMeshTopology.UNCLAIMED;
        if (heldByNode || heldByArc) {
            return false;
        }
        if (!interiorOnly) {
            return true;
        }
        for (int index = 0; index < topology.copy.vertexEdgeCount(vertex); index++) {
            int edgeId = topology.copy.vertexEdgeAt(vertex, index);
            if (topology.sourceEdgeByCopyEdge()[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the search may split an edge: it carries no arc, and it touches the
     * target or the pass-through, or the region walls the search in there, so no
     * free endpoint of it offers a way round.
     *
     * @param edgeId        candidate copy edge
     * @param tail          one endpoint of the edge
     * @param head          its other endpoint
     * @param endCopyVertex vertex the route reaches, standable however it is
     *                      claimed
     * @param passThrough   a claimed vertex the search may transit anyway
     * @return true when splitting the edge can be part of a route
     */
    private boolean splittable(int edgeId, int tail, int head, int endCopyVertex,
            int passThrough) {
        boolean heldByArc = topology.ownerArcByCopyEdge()[edgeId] != EmbeddedMeshTopology.UNCLAIMED;
        boolean onSourceEdge = topology.sourceEdgeByCopyEdge()[edgeId] != EmbeddedMeshTopology.UNCLAIMED;
        if (heldByArc || interiorOnly && onSourceEdge || !edgeInRestriction(edgeId)) {
            return false;
        }
        boolean touchesTarget = tail == endCopyVertex || head == endCopyVertex;
        boolean touchesPassThrough = tail == passThrough || head == passThrough;
        if (touchesTarget || touchesPassThrough) {
            return true;
        }
        return !standable(tail, endCopyVertex, passThrough)
                && !standable(head, endCopyVertex, passThrough);
    }

    /**
     * Whether a hop through one face is allowed at a route end.
     *
     * @param faceId  copy face the hop runs through, or {@link MeshTopology#NONE}
     * @param arrival whether the hop reaches the target rather than leaves the
     *                start
     * @return true when that end's corner is closed or holds the face
     */
    private boolean inCorner(int faceId, boolean arrival) {
        boolean cornerOpen = arrival ? arrivalCornerOpen : departureCornerOpen;
        if (!cornerOpen) {
            return true;
        }
        int[] stamps = arrival ? arrivalStampByFace : departureStampByFace;
        return faceId >= 0 && faceId < stamps.length && stamps[faceId] == cornerStamp;
    }

    /**
     * Whether an edge may be traversed or split under the current face restriction:
     * unrestricted, or beside an admitted face.
     *
     * @param edgeId copy edge to test
     * @return true when the edge is admissible
     */
    private boolean edgeInRestriction(int edgeId) {
        boolean unrestricted = sourceFaceStampBySourceFace.length == 0 && !patchRestrictionActive;
        return unrestricted || faceInRestriction(topology.copy.edgeFace(edgeId, 0))
                || faceInRestriction(topology.copy.edgeFace(edgeId, 1));
    }

    /**
     * Whether a face may be walked or refined under the current face restriction.
     *
     * @param faceId copy face to test, or {@link MeshTopology#NONE}
     * @return true when the face exists and is admissible
     */
    private boolean faceInRestriction(int faceId) {
        if (faceId < 0) {
            return false;
        }
        if (patchRestrictionActive) {
            int patchId = topology.resolvePatch(topology.patchLabelOf(faceId));
            boolean patchAdmitted = patchId < 0 || patchId < patchStampByPatch.length
                    && patchStampByPatch[patchId] == patchStamp;
            if (!patchAdmitted) {
                return false;
            }
        }
        return sourceFaceStampBySourceFace.length == 0
                || sourceFaceStampBySourceFace[topology.sourceFaceByCopyFace()[faceId]] == sourceFaceStamp;
    }

    /**
     * Fills the edge list of a routed vertex path from consecutive vertex pairs,
     * continuing after any edges already present.
     *
     * @param vertices routed path vertices
     * @param edges    list receiving one edge id per remaining consecutive pair
     * @throws IllegalStateException when consecutive vertices share no edge
     */
    public void rebuildLegEdges(List<Integer> vertices, List<Integer> edges) {
        for (int index = edges.size() + 1; index < vertices.size(); index++) {
            int edgeId = topology.copy.edgeBetween(vertices.get(index - 1), vertices.get(index));
            if (edgeId == MeshTopology.NONE) {
                throw new IllegalStateException("routed path has consecutive vertices "
                        + vertices.get(index - 1) + " and " + vertices.get(index)
                        + " sharing no copy edge");
            }
            edges.add(edgeId);
        }
    }
}
