package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.crossfield.DijkstraNode;

/**
 * Re-embeds an arc whose endpoint node has just moved, for the LCBK19 §6.1
 * collapse operators. This is the <em>only</em> place the paper uses a graph
 * search: <em>"in operators (1) and (2) edge paths to (re-)embed arcs are
 * determined simply using Dijkstra's shortest path algorithm between the
 * respective two vertices on the triangle mesh. The graph search is restricted
 * to not intersect (cross or touch) other arcs in order to preserve the topology
 * of the embedded T-mesh."</em>
 *
 * <p>Laying an arc down in the first place is not a search problem — the traced
 * path already is the embedding, and {@link TraceCarve} carves it directly. Using
 * Dijkstra for that instead is what walls arcs in inside a dense zero web.
 *
 * <p>When the claim-respecting search finds no path, the remedy is the paper's:
 * <em>"this is easily resolved by refinement with a few edge splits"</em>. Each
 * failed round splits the corridor edges walled in by claims and widens the
 * corridor by one vertex ring.
 */
public final class ArcRerouter {

    /** Refine/grow rounds allowed per re-route attempt. */
    public static final int REFINE_ROUND_CAP = 16;

    /** Corridor ring growths allowed per re-route attempt. */
    public static final int GROWTH_CAP = 4;

    /** Refinement edge splits allowed per re-route attempt. */
    public static final int SPLIT_BUDGET = 128;

    /** Split position of a midpoint refinement. */
    private static final double EDGE_MIDPOINT = 0.5;

    /** Corners (and edges) of a triangle. */
    private static final int CORNERS = 3;

    public final EmbeddedMeshTopology topology;

    /** Edges split to open a walled corridor. */
    public int refinedEdgeSplitCount;

    /** Corridor ring growths performed. */
    public int corridorGrowthCount;

    /** Re-routes that only succeeded after refinement. */
    public int refinedRetryCount;

    /** Vertices the last search settled, and the corridor it was allowed. */
    public int lastReachedCount;

    /** Corridor size at the end of the last attempt. */
    public int lastCorridorSize;

    /** The corridor set of the last attempt, for diagnosing which vertices refinement could reach. */
    public Set<Integer> lastCorridorSet;

    /**
     * Stores the working copy the re-routes carve into.
     *
     * @param topology working copy with provenance and claims
     */
    public ArcRerouter(EmbeddedMeshTopology topology) {
        this.topology = topology;
    }

    /**
     * Re-route an arc between two vertices: a claims-respecting corridor Dijkstra,
     * splitting walled edges and widening the corridor once per failed round. Splits
     * made by failed attempts stay behind as harmless refinement.
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
            int endCopyVertex, Set<Integer> corridor, int passThrough, int roundCap) {
        if (vertices.isEmpty()) {
            vertices.add(startCopyVertex);
        }
        lastCorridorSet = corridor;
        boolean refined = false;
        Set<Integer> refineMints = new HashSet<>();
        int growths = 0;
        int splitBudget = SPLIT_BUDGET;
        for (int round = 0; round <= roundCap; round++) {
            if (dijkstraSearch(vertices, startCopyVertex, endCopyVertex, corridor, passThrough)) {
                if (refined) {
                    refinedRetryCount++;
                }
                return true;
            }
            int splits = splitBudget > 0
                    ? refineCorridorGates(startCopyVertex, endCopyVertex, corridor, passThrough,
                            splitBudget)
                    : 0;
            if (splits == 0 && splitBudget > 0) {
                splits = refineBlockedEdges(corridor, refineMints, splitBudget);
            }
            splits += mintSpoke(startCopyVertex, corridor) ? 1 : 0;
            splits += mintSpoke(endCopyVertex, corridor) ? 1 : 0;
            splitBudget -= splits;
            boolean grew = false;
            if (growths < GROWTH_CAP) {
                int sizeBefore = corridor.size();
                growCorridor(corridor);
                growths++;
                corridorGrowthCount++;
                grew = corridor.size() > sizeBefore;
            }
            if (splits == 0 && !grew) {
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
     * Claims-respecting shortest path over unclaimed edges and unclaimed interior
     * vertices of the corridor, biased onto the arc's old lane by the pull polyline —
     * the paper's "restricted to not intersect (cross or touch) other arcs".
     *
     * <p>The pull term is memoized per vertex. It depends only on the vertex's position, so it is
     * constant for the whole search, yet a vertex is relaxed once per incoming edge — recomputing it
     * each time walked the entire polyline again for an answer already known. On the sphere
     * contraction that recomputation was the second-largest cost in the profile.
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
            Set<Integer> corridor, int passThrough) {
        Map<Integer, Float> distance = new HashMap<>();
        Map<Integer, Integer> parentVertex = new HashMap<>();
        PriorityQueue<DijkstraNode> frontier = new PriorityQueue<>();
        distance.put(startVertex, 0f);
        frontier.add(new DijkstraNode(0f, startVertex));
        Vector3f positionHere = new Vector3f();
        Vector3f positionOther = new Vector3f();
        while (!frontier.isEmpty()) {
            DijkstraNode head = frontier.poll();
            int vertex = head.vertexOrFace;
            if (head.distance > distance.getOrDefault(vertex, Float.POSITIVE_INFINITY)) {
                continue;
            }
            if (vertex == endCopyVertex) {
                List<Integer> hopVertices = new ArrayList<>();
                int walk = endCopyVertex;
                while (walk != startVertex) {
                    hopVertices.add(walk);
                    walk = parentVertex.get(walk);
                }
                Collections.reverse(hopVertices);
                vertices.addAll(hopVertices);
                return true;
            }
            topology.copy.vertexPosition(vertex, positionHere);
            for (int index = 0; index < topology.copy.vertexEdgeCount(vertex); index++) {
                int edgeId = topology.copy.vertexEdgeAt(vertex, index);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int neighbor = topology.otherEndpoint(edgeId, vertex);
                if (neighbor != endCopyVertex && neighbor != passThrough
                        && (vertexClaimed(neighbor) || !corridor.contains(neighbor))) {
                    continue;
                }
                topology.copy.vertexPosition(neighbor, positionOther);
                float newDistance = head.distance + positionHere.distance(positionOther);
                if (newDistance < distance.getOrDefault(neighbor, Float.POSITIVE_INFINITY)) {
                    distance.put(neighbor, newDistance);
                    parentVertex.put(neighbor, vertex);
                    frontier.add(new DijkstraNode(newDistance, neighbor));
                }
            }
        }
        lastReachedCount = distance.size();
        return false;
    }

    /**
     * LCBK19 §6.1 refinement aimed at the edges that actually block this route — the paper's
     * <em>"a few edge splits"</em>, rather than a few hundred scattered ones.
     *
     * <p>Walks the shortest face path from the search's source to its target that crosses no claimed
     * arc edge. Such a path always exists when the two lie in the same face of the arc arrangement,
     * and it is exactly the passage the vertex search is trying to follow. Its crossing edges whose
     * <em>both</em> endpoints are claimed are the only ones the search cannot traverse — it can stand
     * on neither end — so those, and only those, are split.
     *
     * <p>Splitting them in corridor order also chains them: consecutive crossings share a face, so
     * once one is split the next split's midpoint joins the previous one across the retriangulated
     * sub-triangle, building a connected lane of free vertices through the pinch instead of isolated
     * midpoints.
     *
     * <p>When no such face path exists at all and the search is allowed to transit a claimed vertex,
     * the passage runs <em>through</em> that vertex and is refined as two legs instead. The
     * collapsing node is a cut vertex: claimed arcs radiating from it divide its fan into sectors
     * that meet only at the vertex and never across an edge, so the arc's body and the survivor can
     * sit in different sectors with no face path between them, while a vertex path may still step
     * through the node — which is legal precisely because the arc being pulled is incident to it.
     * The blocking gates then lie on the node-to-target leg, and asking only for a body-to-target
     * corridor would find nothing and refine nothing.
     *
     * @param startVertex source of the blocked search
     * @param endVertex   target of the blocked search
     * @param corridor    corridor vertex set; minted vertices join it
     * <p>Every vertex the passage runs along is admitted to the corridor, not just the midpoints
     * minted here. Splitting a gate is pointless if the search is then not allowed to stand on the
     * ordinary vertices either side of it, and the caller's corridor is built from the arc's old
     * path and the vacated channel — it has no reason to already contain the ground the passage
     * crosses. A leg can need no splits at all and still be unwalkable for exactly this reason.
     * Claimed vertices are admitted too and cost nothing: the search rejects them on their claim
     * regardless of corridor membership.
     *
     * @param passThrough claimed vertex the search may transit, or
     *                    {@link EmbeddedMeshTopology#UNCLAIMED} for none
     * @param splitBudget maximum splits this round may make
     * @return number of blocking gates split
     */
    private int refineCorridorGates(int startVertex, int endVertex, Set<Integer> corridor,
            int passThrough, int splitBudget) {
        List<Integer> crossings = corridorGateEdges(startVertex, endVertex);
        if (crossings.isEmpty() && passThrough != EmbeddedMeshTopology.UNCLAIMED) {
            crossings = new ArrayList<>(corridorGateEdges(startVertex, passThrough));
            crossings.addAll(corridorGateEdges(passThrough, endVertex));
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
            if (splits >= splitBudget
                    || topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED
                    || !vertexClaimed(endpointA) || !vertexClaimed(endpointB)) {
                continue;
            }
            corridor.add(topology.splitEdgeAtParameter(edgeId, EDGE_MIDPOINT));
            refinedEdgeSplitCount++;
            splits++;
        }
        return splits;
    }

    /**
     * The edges crossed by the shortest face path between two vertices that never crosses a claimed
     * arc edge — the passage through the arc arrangement the vertex search must follow.
     *
     * @param startVertex path source, whose incident faces seed the walk
     * @param endVertex   path target, reached when any of its incident faces is entered
     * @return the crossed edges in order from source to target, empty when no such path exists
     */
    private List<Integer> corridorGateEdges(int startVertex, int endVertex) {
        Set<Integer> targetFaces = new HashSet<>();
        for (int index = 0; index < topology.copy.vertexFaceCount(endVertex); index++) {
            targetFaces.add(topology.copy.vertexFaceAt(endVertex, index));
        }
        Map<Integer, Integer> parentFace = new HashMap<>();
        Map<Integer, Integer> parentEdge = new HashMap<>();
        Deque<Integer> frontier = new ArrayDeque<>();
        for (int index = 0; index < topology.copy.vertexFaceCount(startVertex); index++) {
            int face = topology.copy.vertexFaceAt(startVertex, index);
            if (parentFace.putIfAbsent(face, EmbeddedMeshTopology.UNCLAIMED) == null) {
                frontier.add(face);
            }
        }
        int reachedFace = EmbeddedMeshTopology.UNCLAIMED;
        while (!frontier.isEmpty()) {
            int face = frontier.poll();
            if (targetFaces.contains(face)) {
                reachedFace = face;
                break;
            }
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
                        && parentFace.putIfAbsent(neighborFace, face) == null) {
                    parentEdge.put(neighborFace, edgeId);
                    frontier.add(neighborFace);
                }
            }
        }
        if (reachedFace == EmbeddedMeshTopology.UNCLAIMED) {
            return List.of();
        }
        List<Integer> crossings = new ArrayList<>();
        for (int walk = reachedFace; parentFace.get(walk) != EmbeddedMeshTopology.UNCLAIMED;
                walk = parentFace.get(walk)) {
            crossings.add(parentEdge.get(walk));
        }
        Collections.reverse(crossings);
        return crossings;
    }

    /**
     * LCBK19 §6.1 refinement for a walled corridor — "easily resolved by refinement
     * with a few edge splits". An unclaimed corridor edge whose endpoints are both
     * claimed splits at its midpoint, minting a free vertex between the claimed lanes
     * for the search to pass through.
     *
     * @param corridor    corridor vertex set; minted vertices join it
     * @param refineMints vertices minted by earlier rounds of this attempt; edges into
     *                    them are never re-split, which bounds the splitting
     * @param splitBudget maximum splits this round may make
     * @return number of edges split this round
     */
    private int refineBlockedEdges(Set<Integer> corridor, Set<Integer> refineMints,
            int splitBudget) {
        List<Integer> blockedEdges = new ArrayList<>();
        Set<Integer> seenEdges = new HashSet<>();
        for (int vertex : corridor) {
            for (int index = 0; index < topology.copy.vertexEdgeCount(vertex); index++) {
                int edgeId = topology.copy.vertexEdgeAt(vertex, index);
                if (!seenEdges.add(edgeId)
                        || topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int neighbor = topology.otherEndpoint(edgeId, vertex);
                if (corridor.contains(neighbor)
                        && (vertexClaimed(vertex) || vertexClaimed(neighbor))
                        && !refineMints.contains(vertex) && !refineMints.contains(neighbor)) {
                    blockedEdges.add(edgeId);
                }
            }
        }
        int splits = 0;
        for (int edgeId : blockedEdges) {
            if (splits >= splitBudget) {
                return splits;
            }
            if (!topology.copy.hasEdge(edgeId)
                    || topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            int minted = topology.splitEdgeAtParameter(edgeId, EDGE_MIDPOINT);
            refineMints.add(minted);
            corridor.add(minted);
            refinedEdgeSplitCount++;
            splits++;
        }
        return splits;
    }

    /**
     * Mint a fresh free spoke into a vertex by splitting the edge <em>opposite</em> it
     * in one of its incident faces — the retriangulation joins the new vertex to it,
     * so its degree grows by one.
     *
     * <p>This is LCBK19 §6.1's "not enough edges" case, and it is the only refinement
     * that answers it. Splitting an edge <em>incident</em> to the vertex replaces one
     * spoke with another and leaves the degree unchanged, so when a cluster anchor has
     * more arcs converging on it than it has free spokes, no amount of that helps. A
     * search blocked at its own endpoint is blocked for want of a spoke.
     *
     * @param vertexId vertex needing another free spoke
     * @param corridor corridor vertex set; the minted vertex joins it
     * @return whether a spoke was minted
     */
    private boolean mintSpoke(int vertexId, Set<Integer> corridor) {
        for (int index = 0; index < topology.copy.vertexFaceCount(vertexId); index++) {
            int faceId = topology.copy.vertexFaceAt(vertexId, index);
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
                    || topology.ownerArcByCopyEdge[oppositeEdge] != EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            int minted = topology.splitEdgeAtParameter(oppositeEdge, EDGE_MIDPOINT);
            corridor.add(minted);
            refinedEdgeSplitCount++;
            return true;
        }
        return false;
    }

    /**
     * Widen a corridor by one vertex ring.
     *
     * @param corridor corridor vertex set, grown in place
     */
    private void growCorridor(Set<Integer> corridor) {
        List<Integer> ring = new ArrayList<>();
        for (int vertex : corridor) {
            for (int index = 0; index < topology.copy.vertexEdgeCount(vertex); index++) {
                int neighbor = topology.otherEndpoint(topology.copy.vertexEdgeAt(vertex, index),
                        vertex);
                if (!corridor.contains(neighbor)) {
                    ring.add(neighbor);
                }
            }
        }
        corridor.addAll(ring);
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


}
