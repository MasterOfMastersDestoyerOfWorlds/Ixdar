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
 * Re-embeds an arc whose endpoint node has just moved, by one split-aware
 * Dijkstra restricted to cross or touch no other arc: virtual midpoints of
 * splittable edges join the graph at a premium, and only the winning path's
 * midpoints are materialized.
 *
 * <p>See also: LCBK19 Section 6.1
 */
public final class ArcRerouter {

    /** Retained for callers; the split-aware search needs no refine rounds. */
    public static final int REFINE_ROUND_CAP = 16;

    /**
     * Split premium as a multiple of the mesh radius: splits are dearer than
     * any local detour yet cheaper than a tour around the surface.
     */
    private static final float SPLIT_PREMIUM_RADIUS_FACTOR = 2f;

    /** Split position of a midpoint refinement. */
    private static final double EDGE_MIDPOINT = 0.5;

    /** Corners (and edges) of a triangle. */
    private static final int CORNERS = 3;

    /** Halves a summed pair of positions into a midpoint. */
    private static final float MIDPOINT_SCALE = 0.5f;

    public final EmbeddedMeshTopology topology;

    /**
     * Split premium, fixed at construction: refinement vertices are convex
     * combinations of existing ones, so the mesh radius never grows.
     */
    public final float splitPremium;

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

    /** Of {@link #refinedEdgeSplitCount}, those split as gates on a face passage. */
    public int gateSplitCount;

    /** Of {@link #refinedEdgeSplitCount}, those split to mint a free spoke into a vertex. */
    public int spokeSplitCount;

    /** Calls to {@link #tryRoute}. */
    public int routeAttemptCount;

    /** Refine rounds executed across all {@link #tryRoute} calls. */
    public int refineRoundCount;

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
     * Reusable Dijkstra frontier, cleared per search. The same queue type as
     * before the primitive-scratch rewrite, so pop order among equal distances
     * is unchanged.
     */
    public final PriorityQueue<DijkstraNode> frontier = new PriorityQueue<>();

    /** Tentative cost per search node, valid where {@link #vertexVisitStampByVertex} matches. */
    public float[] distanceByVertex = new float[0];

    /** Search parent per node, valid where {@link #vertexVisitStampByVertex} matches. */
    public int[] parentVertexByVertex = new int[0];

    /** Search generation that last wrote each node's cost and parent. */
    public int[] vertexVisitStampByVertex = new int[0];

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
        this.splitPremium = SPLIT_PREMIUM_RADIUS_FACTOR * topology.copy.radius();
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
     * Route an arc between two vertices by one split-aware Dijkstra: real moves
     * walk unclaimed edges through free vertices; virtual moves stand on the
     * midpoint of a splittable edge at a premium. The cheapest path wins, and
     * only its midpoints are split.
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
     * @param roundCap        unused; retained for caller stability
     * @return whether the path now ends at the target
     */
    public boolean tryRoute(int arcId, List<Integer> vertices, int startCopyVertex,
            int endCopyVertex, ActiveIdSet corridor, int passThrough, int roundCap) {
        if (vertices.isEmpty()) {
            vertices.add(startCopyVertex);
        }
        lastCorridorSet = corridor;
        routeAttemptCount++;
        int vertexIdBound = topology.ownerArcByCopyVertex.length;
        int nodeIdBound = vertexIdBound + topology.ownerArcByCopyEdge.length;
        if (distanceByVertex.length < nodeIdBound) {
            distanceByVertex = Arrays.copyOf(distanceByVertex, nodeIdBound);
            parentVertexByVertex = Arrays.copyOf(parentVertexByVertex, nodeIdBound);
            vertexVisitStampByVertex = Arrays.copyOf(vertexVisitStampByVertex, nodeIdBound);
        }
        Vector3f positionHere = new Vector3f();
        Vector3f positionA = new Vector3f();
        Vector3f positionB = new Vector3f();
        Vector3f positionCandidate = new Vector3f();
        int reachedCount = 0;
        int stamp = 0;
        boolean reachedTarget = false;
        for (int phase = 0; phase < 2 && !reachedTarget; phase++) {
            boolean virtualMoves = phase == 1;
            stamp = nextVisitStamp();
            frontier.clear();
            distanceByVertex[startCopyVertex] = 0f;
            vertexVisitStampByVertex[startCopyVertex] = stamp;
            reachedCount++;
            frontier.add(new DijkstraNode(0f, startCopyVertex));
            while (!frontier.isEmpty()) {
            DijkstraNode head = frontier.poll();
            int node = head.vertexOrFace;
            if (head.distance > distanceByVertex[node]) {
                continue;
            }
            if (node == endCopyVertex) {
                reachedTarget = true;
                break;
            }
            if (node < vertexIdBound) {
                topology.copy.vertexPosition(node, positionHere);
                for (int index = 0; index < topology.copy.vertexEdgeCount(node); index++) {
                    int edgeId = topology.copy.vertexEdgeAt(node, index);
                    if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED
                            || !edgeInRestriction(edgeId)) {
                        continue;
                    }
                    int neighbor = topology.otherEndpoint(edgeId, node);
                    if (realAdmissible(neighbor, endCopyVertex, passThrough)) {
                        topology.copy.vertexPosition(neighbor, positionCandidate);
                        reachedCount += relax(node, neighbor, head.distance
                                + positionHere.distance(positionCandidate), stamp);
                    }
                }
                for (int index = 0; virtualMoves
                        && index < topology.copy.vertexFaceCount(node); index++) {
                    int faceId = topology.copy.vertexFaceAt(node, index);
                    if (!faceInRestriction(faceId)) {
                        continue;
                    }
                    for (int corner = 0; corner < CORNERS; corner++) {
                        int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                        int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                        if (topology.copy.halfEdgeVertex(halfEdge) == node
                                || topology.copy.halfEdgeEndVertex(halfEdge) == node
                                || !virtualAdmissible(edgeId)) {
                            continue;
                        }
                        midpointPosition(halfEdge, positionA, positionB, positionCandidate);
                        reachedCount += relax(node, vertexIdBound + edgeId, head.distance
                                + splitPremium + positionHere.distance(positionCandidate), stamp);
                    }
                }
            } else {
                int nodeEdge = node - vertexIdBound;
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
                        if (realAdmissible(neighbor, endCopyVertex, passThrough)) {
                            topology.copy.vertexPosition(neighbor, positionCandidate);
                            reachedCount += relax(node, neighbor, head.distance
                                    + positionHere.distance(positionCandidate), stamp);
                        }
                        int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                        if (edgeId == nodeEdge || !virtualAdmissible(edgeId)) {
                            continue;
                        }
                        midpointPosition(topology.copy.edgeHalfEdge(edgeId),
                                positionA, positionB, positionCandidate);
                        reachedCount += relax(node, vertexIdBound + edgeId, head.distance
                                + splitPremium + positionHere.distance(positionCandidate), stamp);
                    }
                }
            }
            }
        }
        lastReachedCount = reachedCount;
        if (!reachedTarget) {
            lastCorridorSize = corridor.size();
            return false;
        }
        List<Integer> nodePath = new ArrayList<>();
        for (int walk = endCopyVertex; walk != startCopyVertex; walk = parentVertexByVertex[walk]) {
            nodePath.add(walk);
        }
        Collections.reverse(nodePath);
        boolean split = false;
        for (int node : nodePath) {
            int realVertex = node;
            if (node >= vertexIdBound) {
                realVertex = topology.splitEdgeAtParameter(node - vertexIdBound, EDGE_MIDPOINT);
                refinedEdgeSplitCount++;
                gateSplitCount++;
                split = true;
            }
            corridor.add(realVertex);
            vertices.add(realVertex);
        }
        if (split) {
            refinedRetryCount++;
        }
        lastCorridorSize = corridor.size();
        return true;
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
        frontier.add(new DijkstraNode(newCost, toNode));
        return seen ? 0 : 1;
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
            visitStamp = 0;
        }
        visitStamp++;
        return visitStamp;
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
