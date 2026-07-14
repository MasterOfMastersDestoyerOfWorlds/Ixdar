package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.crossfield.DijkstraNode;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceArc;

/**
 * Embeds T-mesh arcs as edge paths on the working copy (LCBK19 §6.1) by
 * per-crossing integration: each point where the arc's traced polyline
 * crosses a mesh edge is materialized independently — snapped onto an
 * existing free vertex when one coincides, otherwise minted by splitting the
 * crossed edge exactly at the crossing ("snap all nodes and arcs onto nearby
 * vertices and edges … only if there are not enough vertices or edges is the
 * mesh split"). Distinct traces cross any edge at distinct parameters, so
 * integration never contends for elements and needs no routing order.
 * Consecutive crossing vertices share a face and are usually already
 * connected by the split retriangulation; the rare gap (a foreign chord
 * grazing the trace, a skipped degenerate sample) is bridged by a small
 * claims-respecting Dijkstra with blocked-edge refinement — the machinery of
 * the paper's re-embedding operators, kept strictly local.
 */
public final class ArcRouter {

    /**
     * Refine/grow rounds for a local bridge hop. Bridges span one or two
     * faces; the paper's remedy is "refinement with a few edge splits", so a
     * hop that stays blocked past this budget signals a real invariant
     * violation, not a bigger search problem.
     */
    public static final int REFINE_ROUND_CAP = 16;

    /**
     * Corridor ring growths allowed per bridge attempt; a bridge that needs
     * more area than this is no longer local and would mint a monster lane
     * even if it succeeded.
     */
    public static final int GROWTH_CAP = 4;

    /**
     * Refinement edge splits allowed per bridge attempt, bounding the mesh
     * churn a single stuck hop can cause.
     */
    public static final int SPLIT_BUDGET = 128;

    /**
     * A crossing snaps onto an existing unclaimed vertex lying within this
     * fraction of the crossed edge's length; farther vertices are genuinely
     * distinct and the crossing mints its own.
     */
    public static final float COINCIDENT_SNAP_RATIO = 0.02f;

    /**
     * Split positions clamp this far (as an edge fraction) inside the edge so
     * a crossing grazing an occupied endpoint cannot mint a degenerate
     * sliver.
     */
    public static final float SPLIT_ENDPOINT_CLAMP = 0.02f;

    /**
     * A polyline point counts as an edge crossing when it lies within this
     * fraction of the located edge's length; farther points are interior
     * samples (the arc's endpoints inside their faces) and integrate nothing.
     */
    public static final float ON_EDGE_RATIO = 0.05f;

    /**
     * Node-ball safety margin. Node placement snaps a node's vertex up to a
     * fraction of an edge away from the traced node position, but every
     * incident trace still radiates from the original position — crossings
     * within the placement error of it are materialized by the shared node
     * vertex, not by per-arc integration. The margin widens the ball so
     * borderline samples fall inside.
     */
    public static final float NODE_BALL_MARGIN = 1.5f;

    /**
     * March steps allowed when bridging a gap by splitting the child edges
     * the straight segment between two consecutive crossings passes; the
     * segment stays inside one original face, so a handful of child edges is
     * the true bound.
     */
    public static final int MARCH_STEP_LIMIT = 16;

    /**
     * A march split requires the segment to pass within this fraction of the
     * candidate edge's length; farther edges are not genuinely crossed.
     */
    public static final float MARCH_CROSS_TOLERANCE = 0.05f;

    /** Arc id whose integration decisions get a step-by-step debug trail, or -1. */
    public static final int DEBUG_ARC_ID = Integer.getInteger("embed.debugArc", -1);

    /** Skip-tally suffix of the bridge failure message. */
    private static final String SKIP_TALLY_FORMAT =
            " (skips interior=%d claimedEdge=%d duplicate=%d) — %s";

    /** Name prefix for arcs in diagnostics. */
    private static final String ARC_LABEL = "arc";

    /** Midpoint interpolation factor for refinement edge splits. */
    private static final float EDGE_MIDPOINT = 0.5f;

    public final EmbeddedMeshTopology topology;
    public final ArcStripIndex strips;

    /**
     * Whether each arc's path has been embedded (or will never be — zero
     * arcs). A bridge may not ride an edge crossed by a not-yet-embedded
     * arc's trace: the bridge would occupy that arc's channel. The
     * restriction lifts as arcs embed.
     */
    public final boolean[] embeddedByArc;

    /**
     * Node-ball center per arc start: the start anchor vertex's position.
     * Crossings inside the ball (radius = the anchor's placement error
     * scaled by {@link #NODE_BALL_MARGIN}) are materialized by the anchor
     * vertex and integrate nothing. Filled by the embedding after node
     * placement.
     */
    public final List<Vector3f> startBallCenterByArc;

    /** Node-ball radius per arc start. */
    public final float[] startBallByArc;

    /** Node-ball center per arc end. */
    public final List<Vector3f> endBallCenterByArc;

    /** Node-ball radius per arc end. */
    public final float[] endBallByArc;

    /**
     * Extra corridor source faces per arc: the union of its endpoint
     * clusters' zero-arc strips. A cluster anchor can sit a cluster-diameter
     * from an arc's traced endpoint, and the terminal connect legitimately
     * routes through the cluster's region — the channel the collapse
     * operators would have dragged the tail along. Filled by the embedding.
     */
    public final List<Set<Integer>> terminalFacesByArc;

    /**
     * Pre-seeded departure spoke per arc start, or {@link
     * EmbeddedMeshTopology#UNCLAIMED}: a vertex minted one march step from
     * the anchor along the arc's own departure direction, its spoke edge
     * pre-claimed for the arc. Seeding gives each of an anchor's incident
     * arcs a dedicated first element (LCBK19 §6.1 "only if there are not
     * enough vertices or edges is the mesh split" applied at nodes), so
     * terminal connects never fight over the shared fan.
     */
    public final int[] startSeedByArc;

    /** Pre-seeded arrival spoke per arc end. */
    public final int[] endSeedByArc;

    /**
     * Zero-arc chain geometry from each arc's start node to its start
     * anchor: the concatenated polylines of the cluster's zero arcs along
     * the way. Departure bridges pull toward this channel — the path the
     * collapse operators would have dragged the tail along — instead of
     * cutting across sibling departure geometry. Empty when the arc's node
     * is its cluster's anchor. Filled by the embedding.
     */
    public final List<List<Vector3f>> startChainByArc;

    /** Zero-arc chain geometry from each arc's end node to its end anchor. */
    public final List<List<Vector3f>> endChainByArc;

    /** Crossings snapped onto existing coincident vertices. */
    public int snapCount;

    /** Crossings minted by splitting the crossed edge. */
    public int splitCount;

    /** Consecutive crossings connected by an existing edge. */
    public int directConnectCount;

    /** Consecutive crossings connected by the deterministic segment march. */
    public int marchConnectCount;

    /** Consecutive crossings bridged by the local Dijkstra. */
    public int bridgeConnectCount;

    /** Bridge hops that needed corridor refinement before passing. */
    public int refinedRetryCount;

    /** Blocked corridor edges split by {@link #refineBlockedEdges}. */
    public int refinedEdgeSplitCount;

    /** Corridor ring growths across all bridge hops. */
    public int corridorGrowthCount;

    /** Self-loop arcs embedded as closed edge loops. */
    public int loopRoutedCount;

    /** Crossings skipped this route because their located edge was claimed. */
    private int claimedEdgeSkips;

    /** Crossings skipped this route as interior samples. */
    private int interiorSkips;

    /** Crossings skipped this route as repeats of path vertices. */
    private int duplicateSkips;

    /**
     * Stores inputs for arc routing.
     *
     * @param topology working copy with claims
     * @param strips   per-arc face strips and polylines
     */
    public ArcRouter(EmbeddedMeshTopology topology, ArcStripIndex strips) {
        this.topology = topology;
        this.strips = strips;
        int arcCount = strips.polylineByArc.size();
        this.embeddedByArc = new boolean[arcCount];
        this.startBallByArc = new float[arcCount];
        this.endBallByArc = new float[arcCount];
        this.terminalFacesByArc = new ArrayList<>(arcCount);
        this.startBallCenterByArc = new ArrayList<>(arcCount);
        this.endBallCenterByArc = new ArrayList<>(arcCount);
        this.startSeedByArc = new int[arcCount];
        this.endSeedByArc = new int[arcCount];
        this.startChainByArc = new ArrayList<>(arcCount);
        this.endChainByArc = new ArrayList<>(arcCount);
        Arrays.fill(this.startSeedByArc, EmbeddedMeshTopology.UNCLAIMED);
        Arrays.fill(this.endSeedByArc, EmbeddedMeshTopology.UNCLAIMED);
        for (int arcId = 0; arcId < arcCount; arcId++) {
            this.terminalFacesByArc.add(Collections.emptySet());
            this.startBallCenterByArc.add(new Vector3f());
            this.endBallCenterByArc.add(new Vector3f());
            this.startChainByArc.add(Collections.emptyList());
            this.endChainByArc.add(Collections.emptyList());
        }
    }

    /**
     * Mint one arc's dedicated spoke at an anchor: the single march step from
     * the anchor vertex along the arc's departure direction, splitting the
     * crossed fan edge at the ray crossing and pre-claiming the spoke edge
     * and mint for the arc. Fails benignly (returns {@link
     * EmbeddedMeshTopology#UNCLAIMED}) when the crossed fan edge is already
     * claimed — the arc then connects through the shared machinery.
     *
     * @param arcId        arc receiving the spoke
     * @param anchorVertex the arc's anchor vertex
     * @param toward       a traced point giving the departure direction
     * @return the minted spoke vertex, or {@link EmbeddedMeshTopology#UNCLAIMED}
     */
    public int seedSpoke(int arcId, int anchorVertex, Vector3f toward) {
        Vector3f anchorPosition = topology.copy.vertexPosition(anchorVertex, new Vector3f());
        Vector3f positionA = new Vector3f();
        Vector3f positionB = new Vector3f();
        int exitEdge = EmbeddedMeshTopology.UNCLAIMED;
        float bestCross = Float.POSITIVE_INFINITY;
        Vector3f exitSplit = new Vector3f();
        for (int faceIndex = 0; faceIndex < topology.copy.vertexFaceCount(anchorVertex);
                faceIndex++) {
            int faceId = topology.copy.vertexFaceAt(anchorVertex, faceIndex);
            for (int corner = 0; corner < 3; corner++) {
                int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                int endpointA = topology.copy.halfEdgeVertex(halfEdge);
                int endpointB = topology.copy.halfEdgeEndVertex(halfEdge);
                if (endpointA == anchorVertex || endpointB == anchorVertex) {
                    continue;
                }
                topology.copy.vertexPosition(endpointA, positionA);
                topology.copy.vertexPosition(endpointB, positionB);
                float[] params = segmentIntersection(anchorPosition, toward, positionA,
                        positionB);
                if (params == null || params[0] >= bestCross) {
                    continue;
                }
                bestCross = params[0];
                exitEdge = edgeId;
                exitSplit.set(positionA).fma(params[1],
                        new Vector3f(positionB).sub(positionA));
            }
        }
        if (exitEdge == EmbeddedMeshTopology.UNCLAIMED
                || topology.ownerArcByCopyEdge[exitEdge] != EmbeddedMeshTopology.UNCLAIMED) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        int mint = topology.splitEdgeAtPoint(exitEdge, exitSplit);
        int spokeEdge = topology.edgeBetween(anchorVertex, mint);
        if (spokeEdge == EmbeddedMeshTopology.UNCLAIMED) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        topology.ownerArcByCopyEdge[spokeEdge] = arcId;
        topology.ownerArcByCopyVertex[mint] = arcId;
        return mint;
    }

    /**
     * Lift every trace-crossing obstacle — used by post-construction stages
     * (contraction, T-junction extension) where all arcs already have their
     * embeddings.
     */
    public void markAllEmbedded() {
        Arrays.fill(embeddedByArc, true);
    }

    /**
     * Embed one arc by integrating each crossing of its traced polyline and
     * connecting them in order, claiming the path as it grows. A self-loop
     * arc ({@code startCopyVertex == endCopyVertex}) integrates its full
     * closed polyline back to the shared vertex.
     *
     * @param arcId           arc to embed
     * @param startCopyVertex copy vertex of the arc's start node
     * @param endCopyVertex   copy vertex of the arc's end node
     * @return the embedded path, never {@code null}
     * @throws IllegalStateException when a local bridge stays blocked — an
     *                               invariant violation, never a droppable
     *                               outcome
     */
    public ArcEdgePath route(int arcId, int startCopyVertex, int endCopyVertex) {
        boolean loop = startCopyVertex == endCopyVertex;
        claimedEdgeSkips = 0;
        interiorSkips = 0;
        duplicateSkips = 0;
        List<Integer> vertices = new ArrayList<>();
        vertices.add(startCopyVertex);
        List<Integer> edges = new ArrayList<>();
        if (startSeedByArc[arcId] != EmbeddedMeshTopology.UNCLAIMED) {
            appendHop(arcId, vertices, edges, startCopyVertex, startSeedByArc[arcId]);
        }
        List<Vector3f> polyline = strips.polylineByArc.get(arcId);
        List<Integer> pointFaces = strips.polylineFaceByArc.get(arcId);
        for (int pointIndex = 0; pointIndex < polyline.size(); pointIndex++) {
            if (insideNodeBall(arcId, polyline.get(pointIndex))) {
                continue;
            }
            integrateCrossing(arcId, vertices, edges, polyline.get(pointIndex),
                    pointFaces.get(pointIndex), pointIndex, endCopyVertex);
        }
        int lastPoint = pointFaces.isEmpty() ? -1 : pointFaces.size() - 1;
        int endSeed = endSeedByArc[arcId];
        List<Vector3f> endChain = endChainByArc.get(arcId);
        int currentVertex = vertices.get(vertices.size() - 1);
        if (currentVertex != endCopyVertex && endSeed != EmbeddedMeshTopology.UNCLAIMED
                && currentVertex != endSeed && !vertices.contains(endSeed)) {
            connect(arcId, vertices, edges, endSeed, lastPoint, endChain);
        }
        currentVertex = vertices.get(vertices.size() - 1);
        if (currentVertex != endCopyVertex
                && !appendHop(arcId, vertices, edges, currentVertex, endCopyVertex)) {
            connect(arcId, vertices, edges, endCopyVertex, lastPoint, endChain);
        }
        if (loop && vertices.size() < 2) {
            throw new IllegalStateException("loop arc " + arcId
                    + " integrated no crossings along its polyline");
        }
        ArcEdgePath path = new ArcEdgePath(arcId, vertices, edges);
        claimPath(arcId, path);
        embeddedByArc[arcId] = true;
        if (loop) {
            loopRoutedCount++;
        }
        return path;
    }

    /**
     * Integrate one traced point: locate the copy edge it crosses among the
     * descendants of its source face, snap onto a coincident free vertex or
     * split the edge at the crossing, and connect the new vertex to the path.
     * Interior samples (the arc's endpoints inside faces), crossings whose
     * edge is claimed by a foreign chord (the following crossing's bridge
     * detours around it), and re-hits of the current path head integrate
     * nothing.
     *
     * @param arcId         arc being embedded
     * @param vertices      path vertices, extended in place
     * @param edges         path edges, extended in place
     * @param point         traced point
     * @param sourceFace    source active face the point was traced in
     * @param pointIndex    index of the point along the polyline
     * @param endCopyVertex the arc's end vertex (never re-minted)
     */
    private void integrateCrossing(int arcId, List<Integer> vertices, List<Integer> edges,
            Vector3f point, int sourceFace, int pointIndex, int endCopyVertex) {
        int bestEdge = EmbeddedMeshTopology.UNCLAIMED;
        float bestDistance = Float.POSITIVE_INFINITY;
        Vector3f projection = new Vector3f();
        Vector3f bestProjection = new Vector3f();
        Vector3f positionA = new Vector3f();
        Vector3f positionB = new Vector3f();
        for (int copyFace : topology.copyFacesBySourceFace.get(sourceFace)) {
            for (int corner = 0; corner < 3; corner++) {
                int edgeId = topology.copy.faceEdgeAt(copyFace, corner);
                int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                topology.copy.vertexPosition(topology.copy.halfEdgeVertex(halfEdge), positionA);
                topology.copy.vertexPosition(topology.copy.halfEdgeEndVertex(halfEdge), positionB);
                float toEdge = projectOntoSegment(point, positionA, positionB, projection);
                if (toEdge < bestDistance) {
                    bestDistance = toEdge;
                    bestEdge = edgeId;
                    bestProjection.set(projection);
                }
            }
        }
        if (bestEdge == EmbeddedMeshTopology.UNCLAIMED) {
            return;
        }
        int halfEdge = topology.copy.edgeHalfEdge(bestEdge);
        int endpointA = topology.copy.halfEdgeVertex(halfEdge);
        int endpointB = topology.copy.halfEdgeEndVertex(halfEdge);
        topology.copy.vertexPosition(endpointA, positionA);
        topology.copy.vertexPosition(endpointB, positionB);
        float edgeLength = positionA.distance(positionB);
        if (bestDistance > ON_EDGE_RATIO * edgeLength) {
            interiorSkips++;
            return;
        }
        int currentVertex = vertices.get(vertices.size() - 1);
        int target;
        if (isSnappable(endpointA, arcId, currentVertex, endCopyVertex)
                && positionA.distance(bestProjection) <= COINCIDENT_SNAP_RATIO * edgeLength) {
            target = endpointA;
            snapCount++;
        } else if (isSnappable(endpointB, arcId, currentVertex, endCopyVertex)
                && positionB.distance(bestProjection) <= COINCIDENT_SNAP_RATIO * edgeLength) {
            target = endpointB;
            snapCount++;
        } else if (topology.ownerArcByCopyEdge[bestEdge] != EmbeddedMeshTopology.UNCLAIMED) {
            claimedEdgeSkips++;
            if (arcId == DEBUG_ARC_ID) {
                System.out.printf(
                        "[embed-debug] arc=%d point=%d SKIP claimed edge=%d owner=%d"
                                + " endpoints=%d(%s),%d(%s) dist=%.6f len=%.6f%n",
                        arcId, pointIndex, bestEdge, topology.ownerArcByCopyEdge[bestEdge],
                        endpointA, vertexState(endpointA), endpointB, vertexState(endpointB),
                        bestDistance, edgeLength);
            }
            return;
        } else {
            target = topology.splitEdgeAtPoint(bestEdge,
                    clampedSplitPoint(positionA, positionB, bestProjection));
            splitCount++;
        }
        if (arcId == DEBUG_ARC_ID) {
            System.out.printf(
                    "[embed-debug] arc=%d point=%d target=%d(%s) edge=%d dist=%.6f len=%.6f%n",
                    arcId, pointIndex, target, vertexState(target), bestEdge, bestDistance,
                    edgeLength);
        }
        if (target == currentVertex || vertices.contains(target)) {
            duplicateSkips++;
            return;
        }
        if (appendHop(arcId, vertices, edges, currentVertex, target)) {
            directConnectCount++;
        } else if (marchTo(arcId, vertices, edges, target)) {
            marchConnectCount++;
        } else {
            List<Vector3f> pull = vertices.size() <= 2
                    ? startChainByArc.get(arcId)
                    : Collections.emptyList();
            bridgeTo(arcId, vertices, edges, target, pointIndex, pull);
            bridgeConnectCount++;
        }
        claimLatest(arcId, vertices, edges);
    }

    /**
     * Bridge a gap deterministically by marching the straight segment from
     * the path head to the target, splitting each unclaimed child edge the
     * segment crosses. Consecutive crossings lie in one original face whose
     * children came from chords hugging their own traces, so the segment
     * crosses only unclaimed interior edges; the march cannot wander into a
     * foreign channel.
     *
     * @param arcId    arc being embedded
     * @param vertices path vertices, extended in place
     * @param edges    path edges, extended in place
     * @param target   vertex to reach
     * @return whether the march reached the target
     */
    private boolean marchTo(int arcId, List<Integer> vertices, List<Integer> edges, int target) {
        int entryVertexCount = vertices.size();
        int entryEdgeCount = edges.size();
        Vector3f targetPosition = topology.copy.vertexPosition(target, new Vector3f());
        Vector3f currentPosition = new Vector3f();
        Vector3f positionA = new Vector3f();
        Vector3f positionB = new Vector3f();
        for (int step = 0; step < MARCH_STEP_LIMIT; step++) {
            int currentVertex = vertices.get(vertices.size() - 1);
            if (appendHop(arcId, vertices, edges, currentVertex, target)) {
                return true;
            }
            topology.copy.vertexPosition(currentVertex, currentPosition);
            int exitEdge = EmbeddedMeshTopology.UNCLAIMED;
            float bestCross = Float.POSITIVE_INFINITY;
            Vector3f exitSplit = new Vector3f();
            for (int faceIndex = 0; faceIndex < topology.copy.vertexFaceCount(currentVertex);
                    faceIndex++) {
                int faceId = topology.copy.vertexFaceAt(currentVertex, faceIndex);
                for (int corner = 0; corner < 3; corner++) {
                    int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                    int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                    int endpointA = topology.copy.halfEdgeVertex(halfEdge);
                    int endpointB = topology.copy.halfEdgeEndVertex(halfEdge);
                    if (endpointA == currentVertex || endpointB == currentVertex) {
                        continue;
                    }
                    topology.copy.vertexPosition(endpointA, positionA);
                    topology.copy.vertexPosition(endpointB, positionB);
                    float[] params = segmentIntersection(currentPosition, targetPosition,
                            positionA, positionB);
                    if (params == null || params[0] >= bestCross) {
                        continue;
                    }
                    bestCross = params[0];
                    exitEdge = edgeId;
                    exitSplit.set(positionA).fma(params[1],
                            new Vector3f(positionB).sub(positionA));
                }
            }
            if (exitEdge == EmbeddedMeshTopology.UNCLAIMED
                    || topology.ownerArcByCopyEdge[exitEdge] != EmbeddedMeshTopology.UNCLAIMED
                    || edges.contains(exitEdge)) {
                break;
            }
            int minted = topology.splitEdgeAtPoint(exitEdge, exitSplit);
            if (!appendHop(arcId, vertices, edges, currentVertex, minted)) {
                break;
            }
        }
        while (vertices.size() > entryVertexCount) {
            vertices.remove(vertices.size() - 1);
        }
        while (edges.size() > entryEdgeCount) {
            edges.remove(edges.size() - 1);
        }
        return false;
    }

    /**
     * Intersection of the march segment with a candidate edge: the closest
     * points between the two segments, accepted when they lie within
     * {@link #MARCH_CROSS_TOLERANCE} of the edge's length and strictly inside
     * both segments (edge parameter clamped off the endpoints like a
     * crossing split).
     *
     * @param marchStart march segment start
     * @param marchEnd   march segment end
     * @param edgeStart  candidate edge start
     * @param edgeEnd    candidate edge end
     * @return {@code {march parameter, edge parameter}} or {@code null}
     */
    private float[] segmentIntersection(Vector3f marchStart, Vector3f marchEnd,
            Vector3f edgeStart, Vector3f edgeEnd) {
        Vector3f marchDirection = new Vector3f(marchEnd).sub(marchStart);
        Vector3f edgeDirection = new Vector3f(edgeEnd).sub(edgeStart);
        Vector3f offset = new Vector3f(marchStart).sub(edgeStart);
        float marchDot = marchDirection.dot(marchDirection);
        float crossDot = marchDirection.dot(edgeDirection);
        float edgeDot = edgeDirection.dot(edgeDirection);
        float marchOffset = marchDirection.dot(offset);
        float edgeOffset = edgeDirection.dot(offset);
        float denominator = marchDot * edgeDot - crossDot * crossDot;
        if (Math.abs(denominator) < Float.MIN_NORMAL) {
            return null;
        }
        float marchParam = (crossDot * edgeOffset - edgeDot * marchOffset) / denominator;
        float edgeParam = (marchDot * edgeOffset - crossDot * marchOffset) / denominator;
        if (marchParam <= 0f || marchParam > 1f
                || edgeParam < SPLIT_ENDPOINT_CLAMP || edgeParam > 1f - SPLIT_ENDPOINT_CLAMP) {
            return null;
        }
        Vector3f onMarch = new Vector3f(marchStart).fma(marchParam, marchDirection);
        Vector3f onEdge = new Vector3f(edgeStart).fma(edgeParam, edgeDirection);
        float edgeLength = (float) Math.sqrt(edgeDot);
        if (onMarch.distance(onEdge) > MARCH_CROSS_TOLERANCE * edgeLength) {
            return null;
        }
        return new float[] { marchParam, edgeParam };
    }

    /**
     * Whether a traced point lies inside one of an arc's node balls — the
     * neighborhoods of its anchor vertices where every crossing is
     * materialized by the anchor.
     *
     * @param arcId arc whose balls apply
     * @param point traced point to test
     * @return true when the point belongs to an anchor neighborhood
     */
    private boolean insideNodeBall(int arcId, Vector3f point) {
        return point.distance(startBallCenterByArc.get(arcId)) <= startBallByArc[arcId]
                || point.distance(endBallCenterByArc.get(arcId)) <= endBallByArc[arcId];
    }

    /**
     * Compact claim state of a vertex for the debug trail.
     *
     * @param vertex copy vertex
     * @return {@code "free"}, {@code "node<i>"}, or {@code "arc<i>"}
     */
    private String vertexState(int vertex) {
        if (topology.ownerNodeByCopyVertex[vertex] != EmbeddedMeshTopology.UNCLAIMED) {
            return "node" + topology.ownerNodeByCopyVertex[vertex];
        }
        if (topology.ownerArcByCopyVertex[vertex] != EmbeddedMeshTopology.UNCLAIMED) {
            return ARC_LABEL + topology.ownerArcByCopyVertex[vertex];
        }
        return "free";
    }

    /**
     * Whether a crossing may snap onto a vertex: it must be free of node and
     * arc claims (or be the arc's own end vertex) and not already on the
     * path.
     *
     * @param vertex        candidate vertex
     * @param arcId         arc being embedded
     * @param currentVertex current path head (never a snap target)
     * @param endCopyVertex the arc's end vertex, always snappable
     * @return true when the crossing may use the vertex
     */
    private boolean isSnappable(int vertex, int arcId, int currentVertex, int endCopyVertex) {
        if (vertex == currentVertex) {
            return false;
        }
        if (vertex == endCopyVertex) {
            return true;
        }
        return topology.ownerNodeByCopyVertex[vertex] == EmbeddedMeshTopology.UNCLAIMED
                && topology.ownerArcByCopyVertex[vertex] == EmbeddedMeshTopology.UNCLAIMED;
    }

    /**
     * Connect the path head to a target vertex with an existing edge or a
     * local bridge.
     *
     * @param arcId      arc being embedded
     * @param vertices   path vertices, extended in place
     * @param edges      path edges, extended in place
     * @param target     vertex to reach
     * @param pointIndex polyline index anchoring the bridge corridor
     * @param pull       positions pulling the bridge onto its channel, or empty
     */
    private void connect(int arcId, List<Integer> vertices, List<Integer> edges, int target,
            int pointIndex, List<Vector3f> pull) {
        int currentVertex = vertices.get(vertices.size() - 1);
        if (appendHop(arcId, vertices, edges, currentVertex, target)) {
            directConnectCount++;
        } else {
            bridgeTo(arcId, vertices, edges, target, pointIndex, pull);
            bridgeConnectCount++;
        }
        claimLatest(arcId, vertices, edges);
    }

    /**
     * Bridge the path head to a target across the local neighborhood: a
     * claims-respecting Dijkstra over a corridor seeded from the crossing's
     * surrounding source faces, refined and grown when blocked (LCBK19 §6.1
     * "resolved by refinement with a few edge splits").
     *
     * @param arcId      arc being embedded
     * @param vertices   path vertices, extended in place
     * @param edges      path edges, extended in place
     * @param target     vertex to reach
     * @param pointIndex polyline index anchoring the corridor
     * @param pull       positions pulling the bridge onto its channel, or empty
     * @throws IllegalStateException when the bridge stays blocked
     */
    private void bridgeTo(int arcId, List<Integer> vertices, List<Integer> edges, int target,
            int pointIndex, List<Vector3f> pull) {
        int currentVertex = vertices.get(vertices.size() - 1);
        Set<Integer> corridor = corridorVertices(arcId, currentVertex, target,
                pointIndex - 2, pointIndex + 1);
        if (!tryRoute(arcId, vertices, currentVertex, target, corridor, pull,
                REFINE_ROUND_CAP)) {
            throw new IllegalStateException(String.format(
                    "arc %d unroutable: bridge from copy vertex %d to %d stays blocked"
                            + SKIP_TALLY_FORMAT,
                    arcId, currentVertex, target, interiorSkips, claimedEdgeSkips,
                    duplicateSkips, corridorDiagnostic(arcId, currentVertex, target)));
        }
        rebuildLegEdges(vertices, edges);
    }

    /**
     * Claim the path's newest edges and the vertices that just became
     * interior, so later bridges of this arc cannot re-cross its own lane and
     * later arcs see it as occupied.
     *
     * @param arcId    owning arc
     * @param vertices path vertices
     * @param edges    path edges
     */
    private void claimLatest(int arcId, List<Integer> vertices, List<Integer> edges) {
        for (int index = 0; index < edges.size(); index++) {
            topology.ownerArcByCopyEdge[edges.get(index)] = arcId;
        }
        for (int index = 1; index < vertices.size() - 1; index++) {
            int vertex = vertices.get(index);
            if (topology.ownerNodeByCopyVertex[vertex] == EmbeddedMeshTopology.UNCLAIMED) {
                topology.ownerArcByCopyVertex[vertex] = arcId;
            }
        }
    }

    /**
     * The projection of a crossing onto its edge, clamped just inside the
     * edge by {@link #SPLIT_ENDPOINT_CLAMP} so a grazing crossing cannot mint
     * a degenerate sliver.
     *
     * @param start      edge start position
     * @param end        edge end position
     * @param projection unclamped crossing position on the edge
     * @return the clamped split position
     */
    private Vector3f clampedSplitPoint(Vector3f start, Vector3f end, Vector3f projection) {
        Vector3f direction = new Vector3f(end).sub(start);
        float lengthSquared = direction.lengthSquared();
        float t = lengthSquared < Float.MIN_NORMAL ? EDGE_MIDPOINT
                : new Vector3f(projection).sub(start).dot(direction) / lengthSquared;
        t = Math.max(SPLIT_ENDPOINT_CLAMP, Math.min(1f - SPLIT_ENDPOINT_CLAMP, t));
        return new Vector3f(start).fma(t, direction);
    }

    /**
     * Append one hop to the path when an edge between the vertices exists,
     * is unclaimed, and carries no unembedded foreign crossing — riding an
     * edge a later arc's trace still crosses would sweep across that trace
     * even when both hop endpoints are legitimate.
     *
     * @param arcId    arc being embedded
     * @param vertices path vertices
     * @param edges    path edges
     * @param from     current vertex
     * @param to       next vertex
     * @return whether the hop was appended
     */
    private boolean appendHop(int arcId, List<Integer> vertices, List<Integer> edges, int from,
            int to) {
        int edgeId = topology.edgeBetween(from, to);
        if (edgeId == EmbeddedMeshTopology.UNCLAIMED) {
            return false;
        }
        int owner = topology.ownerArcByCopyEdge[edgeId];
        if ((owner != EmbeddedMeshTopology.UNCLAIMED && owner != arcId)
                || edgeCrossedByUnembedded(edgeId, arcId)) {
            return false;
        }
        vertices.add(to);
        edges.add(edgeId);
        return true;
    }

    /**
     * The refine-until-routed hop: claims-respecting corridor Dijkstra,
     * splitting blocked edges and growing the corridor one vertex ring per
     * failed round, up to the given round budget. Splits made by failed
     * attempts stay as harmless refinement.
     *
     * @param arcId           arc being routed, for counters
     * @param vertices        path list; the start vertex is appended when
     *                        empty, and the routed continuation follows it
     * @param startCopyVertex hop source
     * @param endCopyVertex   hop target
     * @param corridor        allowed vertex set, mutated by refinement/growth
     * @param pullPolyline    positions pulling the path onto the arc's lane
     * @param roundCap        refine-round budget for this attempt
     * @return whether the path now ends at the target
     */
    public boolean tryRoute(int arcId, List<Integer> vertices, int startCopyVertex,
            int endCopyVertex, Set<Integer> corridor, List<Vector3f> pullPolyline,
            int roundCap) {
        if (vertices.isEmpty()) {
            vertices.add(startCopyVertex);
        }
        boolean refined = false;
        Set<Integer> refineMints = new HashSet<>();
        int growths = 0;
        int splitBudget = SPLIT_BUDGET;
        for (int round = 0; round <= roundCap; round++) {
            if (dijkstraSearch(arcId, vertices, startCopyVertex, endCopyVertex, corridor,
                    pullPolyline)) {
                if (refined) {
                    refinedRetryCount++;
                }
                return true;
            }
            int splits = splitBudget > 0
                    ? refineBlockedEdges(arcId, corridor, refineMints, splitBudget)
                    : 0;
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
                return false;
            }
            refined = true;
        }
        return false;
    }

    /**
     * Whether a not-yet-embedded arc's trace actually crosses a copy edge:
     * the source-edge tag narrows the candidates, and the arc's recorded
     * crossing points (its polyline samples on the source edge) decide
     * geometrically — a child edge that no longer contains the crossing
     * point is free even though it inherits the tag. Every search honors
     * this, so a path can never occupy a channel a later crossing still
     * needs (LCBK19 §6.1's "restricted to not intersect other arcs", applied
     * to traced and embedded arcs alike) — including the routed arc's own
     * not-yet-integrated crossings: its tail must split them on arrival, not
     * ride over them. Crossings already integrated are vertex-coincident and
     * exempt.
     *
     * @param edgeId copy edge to test
     * @param arcId  arc being routed
     * @return true when an unembedded crossing lies inside the edge
     */
    private boolean edgeCrossedByUnembedded(int edgeId, int arcId) {
        int sourceEdge = topology.sourceEdgeByCopyEdge[edgeId];
        if (sourceEdge == EmbeddedMeshTopology.UNCLAIMED) {
            return false;
        }
        List<Integer> candidates = strips.crossingArcsBySourceEdge.get(sourceEdge);
        if (candidates.isEmpty()) {
            return false;
        }
        Vector3f positionA = new Vector3f();
        Vector3f positionB = new Vector3f();
        int halfEdge = topology.copy.edgeHalfEdge(edgeId);
        topology.copy.vertexPosition(topology.copy.halfEdgeVertex(halfEdge), positionA);
        topology.copy.vertexPosition(topology.copy.halfEdgeEndVertex(halfEdge), positionB);
        for (int crosser : candidates) {
            if (embeddedByArc[crosser]) {
                continue;
            }
            if (containedCrossingParam(crosser, positionA, positionB) >= 0f) {
                return true;
            }
        }
        return false;
    }

    /**
     * The parameter along an edge of the first crossing point of a foreign
     * arc lying on it without being materialized, or -1 when none does.
     * Crossing points are the foreign arc's polyline samples, which sit
     * exactly on the source edges its trace crosses; a crossing counts as
     * materialized when it coincides with an endpoint vertex, or when it
     * lies inside one of the foreign arc's node balls — there it belongs to
     * the shared node vertex, and terminal hops must be free to pass.
     *
     * @param foreignArcId arc whose crossings are tested
     * @param edgeStart    edge start position
     * @param edgeEnd      edge end position
     * @return contained crossing parameter, or -1
     */
    private float containedCrossingParam(int foreignArcId, Vector3f edgeStart,
            Vector3f edgeEnd) {
        List<Vector3f> polyline = strips.polylineByArc.get(foreignArcId);
        Vector3f direction = new Vector3f(edgeEnd).sub(edgeStart);
        float lengthSquared = direction.lengthSquared();
        if (lengthSquared < Float.MIN_NORMAL) {
            return -1f;
        }
        float edgeLength = (float) Math.sqrt(lengthSquared);
        float vertexEpsilon = COINCIDENT_SNAP_RATIO * edgeLength;
        Vector3f offset = new Vector3f();
        for (Vector3f point : polyline) {
            if (point.distance(edgeStart) <= vertexEpsilon
                    || point.distance(edgeEnd) <= vertexEpsilon
                    || insideNodeBall(foreignArcId, point)) {
                continue;
            }
            offset.set(point).sub(edgeStart);
            float param = offset.dot(direction) / lengthSquared;
            if (param <= 0f || param >= 1f) {
                continue;
            }
            float distance = offset.fma(-param, direction).length();
            if (distance <= vertexEpsilon) {
                return param;
            }
        }
        return -1f;
    }

    /**
     * Widen a corridor by one vertex ring: every neighbor of a current
     * corridor vertex joins.
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
     * Fill the edge list of a routed vertex path from consecutive vertex
     * pairs, continuing after any edges already present (so a bridge can
     * extend an integrated prefix's edge list).
     *
     * @param vertices routed path vertices
     * @param edges    list receiving one edge id per remaining consecutive pair
     * @throws IllegalStateException when consecutive vertices share no edge
     */
    public void rebuildLegEdges(List<Integer> vertices, List<Integer> edges) {
        for (int index = edges.size() + 1; index < vertices.size(); index++) {
            int edgeId = topology.edgeBetween(vertices.get(index - 1), vertices.get(index));
            if (edgeId == EmbeddedMeshTopology.UNCLAIMED) {
                throw new IllegalStateException("routed path vertices " + vertices.get(index - 1)
                        + " and " + vertices.get(index) + " share no copy edge");
            }
            edges.add(edgeId);
        }
    }

    /**
     * LCBK19 §6.1 refinement for a blocked corridor ("easily resolved by
     * refinement with a few edge splits"), in two forms. Claim walls: an
     * unclaimed corridor edge whose endpoints are both claimed splits at its
     * midpoint, minting a free vertex between the claimed lanes. Crossing
     * walls: a corridor edge barred by an unembedded foreign crossing splits
     * on its crossing-free side, minting a usable sub-edge while the other
     * half keeps the foreign crossing (its tag and geometry intact for the
     * foreign arc's own later integration) — this is how a search passes
     * between converging traces in a node fan without crossing any of them.
     *
     * @param arcId       arc the refinement serves
     * @param corridor    corridor vertex set; minted vertices join it
     * @param refineMints vertices minted by this attempt's earlier rounds;
     *                    edges into them are never re-split, which bounds the
     *                    claim-adjacent splitting
     * @param splitBudget maximum splits this round may make
     * @return number of edges split this round
     */
    private int refineBlockedEdges(int arcId, Set<Integer> corridor, Set<Integer> refineMints,
            int splitBudget) {
        List<Integer> blockedEdges = new ArrayList<>();
        List<Integer> crossedEdges = new ArrayList<>();
        Set<Integer> seenEdges = new HashSet<>();
        for (int vertex : corridor) {
            for (int index = 0; index < topology.copy.vertexEdgeCount(vertex); index++) {
                int edgeId = topology.copy.vertexEdgeAt(vertex, index);
                if (!seenEdges.add(edgeId)
                        || topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int neighbor = topology.otherEndpoint(edgeId, vertex);
                if (!corridor.contains(neighbor)) {
                    continue;
                }
                if (edgeCrossedByUnembedded(edgeId, arcId)) {
                    crossedEdges.add(edgeId);
                } else if ((vertexClaimed(vertex) || vertexClaimed(neighbor))
                        && !refineMints.contains(vertex) && !refineMints.contains(neighbor)) {
                    blockedEdges.add(edgeId);
                }
            }
        }
        int splits = 0;
        Vector3f positionA = new Vector3f();
        Vector3f positionB = new Vector3f();
        for (int edgeId : blockedEdges) {
            if (splits >= splitBudget) {
                return splits;
            }
            if (!topology.copy.hasEdge(edgeId)
                    || topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            int endpointA = topology.copy.halfEdgeVertex(topology.copy.edgeHalfEdge(edgeId));
            int endpointB = topology.otherEndpoint(edgeId, endpointA);
            topology.copy.vertexPosition(endpointA, positionA);
            topology.copy.vertexPosition(endpointB, positionB);
            int minted = topology.splitEdgeAtPoint(edgeId,
                    new Vector3f(positionA).add(positionB).mul(EDGE_MIDPOINT));
            refineMints.add(minted);
            corridor.add(minted);
            refinedEdgeSplitCount++;
            splits++;
        }
        for (int edgeId : crossedEdges) {
            if (splits >= splitBudget) {
                return splits;
            }
            if (!topology.copy.hasEdge(edgeId)
                    || topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            if (splitCrossingFreeSide(arcId, edgeId, corridor)) {
                splits++;
            }
        }
        return splits;
    }

    /**
     * Split a crossing-barred edge in the middle of its widest crossing-free
     * interval — an end interval or a gap between adjacent crossings. The
     * minted vertex is a free waypoint the search may pass <em>through</em>
     * (only edges are barred, never vertices), so successive gap splits chain
     * a passage through a fan wedge without crossing or touching any trace;
     * the sub-edges keeping the crossings stay barred and geometrically
     * intact for the crossing arcs' own later integration.
     *
     * @param arcId    arc the refinement serves
     * @param edgeId   edge barred by unembedded foreign crossings
     * @param corridor corridor vertex set; the minted vertex joins it
     * @return whether a usable gap split was made
     */
    private boolean splitCrossingFreeSide(int arcId, int edgeId, Set<Integer> corridor) {
        int sourceEdge = topology.sourceEdgeByCopyEdge[edgeId];
        if (sourceEdge == EmbeddedMeshTopology.UNCLAIMED) {
            return false;
        }
        Vector3f positionA = new Vector3f();
        Vector3f positionB = new Vector3f();
        int halfEdge = topology.copy.edgeHalfEdge(edgeId);
        topology.copy.vertexPosition(topology.copy.halfEdgeVertex(halfEdge), positionA);
        topology.copy.vertexPosition(topology.copy.halfEdgeEndVertex(halfEdge), positionB);
        List<Float> params = new ArrayList<>();
        params.add(0f);
        for (int crosser : strips.crossingArcsBySourceEdge.get(sourceEdge)) {
            if (embeddedByArc[crosser]) {
                continue;
            }
            float param = containedCrossingParam(crosser, positionA, positionB);
            if (param >= 0f) {
                params.add(param);
            }
        }
        params.add(1f);
        Collections.sort(params);
        float widestGap = 0f;
        float splitParam = -1f;
        for (int index = 1; index < params.size(); index++) {
            float gap = params.get(index) - params.get(index - 1);
            if (gap > widestGap) {
                widestGap = gap;
                splitParam = (params.get(index) + params.get(index - 1)) * EDGE_MIDPOINT;
            }
        }
        if (widestGap < 2f * SPLIT_ENDPOINT_CLAMP || splitParam < SPLIT_ENDPOINT_CLAMP
                || splitParam > 1f - SPLIT_ENDPOINT_CLAMP) {
            return false;
        }
        Vector3f direction = new Vector3f(positionB).sub(positionA);
        corridor.add(topology.splitEdgeAtPoint(edgeId,
                new Vector3f(positionA).fma(splitParam, direction)));
        refinedEdgeSplitCount++;
        return true;
    }

    /**
     * Minimum distance from a point to any segment of the arc's traced polyline.
     *
     * @param position query position
     * @param polyline traced polyline points in travel order
     * @return distance to the nearest polyline segment (or point for size 1)
     */
    private float distanceToPolyline(Vector3f position, List<Vector3f> polyline) {
        if (polyline.isEmpty()) {
            return 0f;
        }
        Vector3f projection = new Vector3f();
        float best = position.distance(polyline.get(0));
        for (int index = 1; index < polyline.size(); index++) {
            best = Math.min(best, projectOntoSegment(position, polyline.get(index - 1),
                    polyline.get(index), projection));
        }
        return best;
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
     * One-line description of a blocked bridge's corridor: how much of the
     * corridor's free region the start can reach, and which arcs own the
     * claimed corridor edges (the walls).
     *
     * @param arcId           stuck arc
     * @param startCopyVertex copy vertex the bridge starts at
     * @param endCopyVertex   copy vertex the bridge targets
     * @return diagnostic summary for the failure exception
     */
    public String corridorDiagnostic(int arcId, int startCopyVertex, int endCopyVertex) {
        int lastPoint = strips.polylineFaceByArc.get(arcId).size() - 1;
        Set<Integer> corridor = corridorVertices(arcId, startCopyVertex, endCopyVertex,
                0, lastPoint);
        Map<Integer, Integer> wallOwners = new HashMap<>();
        Set<Integer> seenEdges = new HashSet<>();
        for (int vertex : corridor) {
            for (int index = 0; index < topology.copy.vertexEdgeCount(vertex); index++) {
                int edgeId = topology.copy.vertexEdgeAt(vertex, index);
                if (!seenEdges.add(edgeId)) {
                    continue;
                }
                int owner = topology.ownerArcByCopyEdge[edgeId];
                if (owner != EmbeddedMeshTopology.UNCLAIMED && owner != arcId) {
                    wallOwners.merge(owner, 1, Integer::sum);
                }
            }
        }
        List<Integer> freeReach = new ArrayList<>();
        freeReach.add(startCopyVertex);
        Set<Integer> reached = new HashSet<>(freeReach);
        for (int scan = 0; scan < freeReach.size(); scan++) {
            int vertex = freeReach.get(scan);
            for (int index = 0; index < topology.copy.vertexEdgeCount(vertex); index++) {
                int edgeId = topology.copy.vertexEdgeAt(vertex, index);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int neighbor = topology.otherEndpoint(edgeId, vertex);
                if (!corridor.contains(neighbor) || reached.contains(neighbor)) {
                    continue;
                }
                if (neighbor != endCopyVertex && vertexClaimed(neighbor)) {
                    continue;
                }
                reached.add(neighbor);
                freeReach.add(neighbor);
            }
        }
        List<Map.Entry<Integer, Integer>> owners = new ArrayList<>(wallOwners.entrySet());
        owners.sort(Comparator.comparingInt(Map.Entry<Integer, Integer>::getValue).reversed());
        StringBuilder ownerSummary = new StringBuilder();
        for (int rank = 0; rank < Math.min(3, owners.size()); rank++) {
            ownerSummary.append(rank == 0 ? "" : ",").append(ARC_LABEL)
                    .append(owners.get(rank).getKey())
                    .append("x").append(owners.get(rank).getValue());
        }
        float wallGap = Float.NaN;
        if (!owners.isEmpty()) {
            List<Vector3f> ownPolyline = strips.polylineByArc.get(arcId);
            List<Vector3f> wallPolyline = strips.polylineByArc.get(owners.get(0).getKey());
            wallGap = Float.POSITIVE_INFINITY;
            for (Vector3f point : ownPolyline) {
                wallGap = Math.min(wallGap, distanceToPolyline(point, wallPolyline));
            }
        }
        String wallNodes = "";
        if (!owners.isEmpty()) {
            TraceArc wallArc = strips.motorcycleGraph.arcs.get(owners.get(0).getKey());
            wallNodes = String.format(" wallArc=%d(trace=%d n%d>n%d)", wallArc.arcId,
                    wallArc.traceId, wallArc.startNodeId, wallArc.endNodeId);
        }
        TraceArc selfArc = strips.motorcycleGraph.arcs.get(arcId);
        return String.format(
                "corridor=%dv freeReach=%d endReached=%b walls=[%s] wallGap=%.6f polyline=%d"
                        + " self(trace=%d n%d>n%d)%s",
                corridor.size(), freeReach.size(), reached.contains(endCopyVertex),
                ownerSummary, wallGap, strips.polylineByArc.get(arcId).size(),
                selfArc.traceId, selfArc.startNodeId, selfArc.endNodeId, wallNodes);
    }

    /**
     * All copy vertices of the descendant faces of one stretch of the arc's
     * traced source-face strip, plus the two endpoint vertices — the region a
     * bridge may use before growth widens it.
     *
     * @param arcId           arc whose strip defines the corridor
     * @param startCopyVertex copy vertex the bridge starts at
     * @param endCopyVertex   copy vertex the bridge ends at
     * @param fromPoint       first polyline point index of the stretch
     * @param toPoint         last polyline point index of the stretch
     * @return the corridor vertex set
     */
    private Set<Integer> corridorVertices(int arcId, int startCopyVertex, int endCopyVertex,
            int fromPoint, int toPoint) {
        Set<Integer> corridor = new HashSet<>();
        List<Integer> pointFaces = strips.polylineFaceByArc.get(arcId);
        Set<Integer> sourceFaces = new HashSet<>();
        for (int point = Math.max(0, fromPoint); point <= toPoint && point < pointFaces.size();
                point++) {
            sourceFaces.add(pointFaces.get(point));
        }
        if (sourceFaces.isEmpty()) {
            sourceFaces.addAll(strips.stripFacesByArc.get(arcId));
        }
        sourceFaces.addAll(terminalFacesByArc.get(arcId));
        for (int sourceFace : sourceFaces) {
            for (int copyFace : topology.copyFacesBySourceFace.get(sourceFace)) {
                for (int corner = 0; corner < 3; corner++) {
                    corridor.add(topology.copy.faceVertexAt(copyFace, corner));
                }
            }
        }
        corridor.add(startCopyVertex);
        corridor.add(endCopyVertex);
        return corridor;
    }

    /**
     * Claims-respecting Dijkstra over unclaimed copy edges and vertices from the
     * start vertex to the target, appending the found path to the given list.
     * Cost is edge length plus each vertex's distance to the pull polyline
     * when one is given.
     *
     * @param arcId    arc being routed (its own crossings never bar)
     * @param vertices path vertices (extended on success)
     * @param startVertex   search source
     * @param endCopyVertex search target
     * @param corridor      allowed vertex set
     * @param polyline      positions pulling the hop onto a lane, or empty
     * @return whether the target was reached
     */
    private boolean dijkstraSearch(int arcId, List<Integer> vertices, int startVertex,
            int endCopyVertex, Set<Integer> corridor, List<Vector3f> polyline) {
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
                if (neighbor != endCopyVertex) {
                    if (edgeCrossedByUnembedded(edgeId, arcId)) {
                        continue;
                    }
                    if (topology.ownerNodeByCopyVertex[neighbor] != EmbeddedMeshTopology.UNCLAIMED
                            || topology.ownerArcByCopyVertex[neighbor] != EmbeddedMeshTopology.UNCLAIMED
                            || !corridor.contains(neighbor)) {
                        continue;
                    }
                }
                topology.copy.vertexPosition(neighbor, positionOther);
                float newDistance = head.distance + positionHere.distance(positionOther)
                        + distanceToPolyline(positionOther, polyline);
                if (newDistance < distance.getOrDefault(neighbor, Float.POSITIVE_INFINITY)) {
                    distance.put(neighbor, newDistance);
                    parentVertex.put(neighbor, vertex);
                    frontier.add(new DijkstraNode(newDistance, neighbor));
                }
            }
        }
        return false;
    }

    /**
     * Claim a routed path's edges and interior vertices for its arc.
     *
     * @param arcId routed arc
     * @param path  reconstructed path
     */
    public void claimPath(int arcId, ArcEdgePath path) {
        for (int edgeId : path.copyEdgePath) {
            topology.ownerArcByCopyEdge[edgeId] = arcId;
        }
        for (int index = 1; index < path.copyVertexPath.size() - 1; index++) {
            topology.ownerArcByCopyVertex[path.copyVertexPath.get(index)] = arcId;
        }
    }

    /**
     * Project a point onto a segment, clamped to its extent.
     *
     * @param point      query point
     * @param start      segment start
     * @param end        segment end
     * @param projection output: the clamped projection
     * @return distance from the point to the projection
     */
    private float projectOntoSegment(Vector3f point, Vector3f start, Vector3f end,
            Vector3f projection) {
        Vector3f direction = new Vector3f(end).sub(start);
        float lengthSquared = direction.lengthSquared();
        if (lengthSquared < Float.MIN_NORMAL) {
            projection.set(start);
            return start.distance(point);
        }
        float t = new Vector3f(point).sub(start).dot(direction) / lengthSquared;
        t = Math.max(0f, Math.min(1f, t));
        projection.set(start).fma(t, direction);
        return projection.distance(point);
    }
}
