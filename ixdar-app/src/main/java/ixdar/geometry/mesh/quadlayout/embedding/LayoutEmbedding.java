package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshNode;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceArc;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutExtraction;
import ixdar.geometry.mesh.quadlayout.quantization.QuantizedMeshGrid;
import ixdar.geometry.mesh.quadlayout.quantization.TJunctionElimination;

/**
 * LCBK19 §6.1 T-mesh re-embedding, construction half: build a working copy of
 * the input mesh (the source is never mutated), give every T-mesh node a
 * dedicated copy vertex (snapping to nearby corners or splitting edges/faces),
 * and embed every arc as a claims-respecting edge path along its traced face
 * strip. The construction is total — every arc-referenced node is placed and
 * every arc routed, self-loop arcs as closed edge loops — so
 * {@link #pathByArc} holds no nulls; a placement or routing dead end is an
 * upstream invariant violation and throws. The zero-element contraction
 * operators and the final patch region assignment build on these products.
 */
public final class LayoutEmbedding {

    /** Corner-snap threshold as a fraction of the face's shortest edge. */
    public static final float NODE_CORNER_SNAP_RATIO = 0.25f;

    /** Edge-snap threshold as a fraction of the edge's length. */
    public static final float NODE_EDGE_SNAP_RATIO = 0.15f;

    /** Polyline points below which a same-node arc embeds as a point, not a loop. */
    public static final int LOOP_MIN_POLYLINE_POINTS = 4;

    public final TJunctionElimination conforming;
    public final MotorcycleGraph motorcycleGraph;
    public final QuantizedMeshGrid quantization;

    /** Working copy with provenance and claims. */
    public EmbeddedMeshTopology topology;

    /** Per-arc strips and traced polylines. */
    public ArcStripIndex strips;

    /** Copy vertex per T-mesh node id, or -1 for nodes no arc references. */
    public int[] vertexIdByNode;

    /**
     * Embedded path per arc id. Feature and positively quantized arcs are
     * never {@code null} after {@link #build}; zero-quantized arcs stay
     * {@code null} here — embedding hundreds of sub-face zero-web lanes just
     * to release them again is churn that walls the dense clusters shut —
     * and become single-vertex point paths when the contraction stage
     * collapses their clusters (LCBK19 Prop 6.1's end state).
     */
    public ArcEdgePath[] pathByArc;

    /**
     * LCBK19 Def 6.2 criticality per node id: singularity and feature nodes
     * hold prescribed integer positions and must never be moved by the
     * contraction operators. A non-critical node collapsed onto a critical
     * point becomes critical (the contraction stage updates this in place).
     */
    public boolean[] criticalByNode;

    /**
     * Whether each arc rides a feature trace — the closed-surface analog of
     * LCBK19's border arcs: nodes on a feature curve may only move along it,
     * so a zero feature arc is collapsible while a zero regular arc into a
     * feature-bound node is not.
     */
    public boolean[] featureByArc;

    /** Placements (nodes and loop waypoints) snapped onto an existing vertex. */
    public int snappedToVertexCount;

    /** Placements minted by splitting a nearby edge. */
    public int placedByEdgeSplitCount;

    /** Placements minted by splitting the containing face. */
    public int placedByFaceSplitCount;

    public int arcsRouted;

    /** Same-node arcs with degenerate polylines embedded as points. */
    public int pointArcCount;

    /**
     * Stores inputs for the re-embedding construction.
     *
     * @param conforming T-junction-eliminated layout over the T-mesh to embed
     */
    public LayoutEmbedding(TJunctionElimination conforming) {
        this.conforming = conforming;
        this.motorcycleGraph = conforming.motorcycleGraph;
        this.quantization = conforming.quantization;
    }

    /**
     * Copy the mesh, place nodes, and route arcs; logs the {@code [embed]} summary.
     *
     * @return this, with all public products populated
     */
    public LayoutEmbedding build() {
        long startNanos = System.nanoTime();
        topology = new EmbeddedMeshTopology(motorcycleGraph.seamless.mesh);
        strips = new ArcStripIndex(motorcycleGraph).build();
        tagSourceEdges();
        markCriticality();
        insertNodes();
        ArcRouter router = routeArcs();
        topology.copy.computeNormals();
        System.out.printf(
                "[embed] placements snap=%d edgeSplit=%d faceSplit=%d |"
                        + " arcs routed=%d (snaps=%d splits=%d direct=%d march=%d bridges=%d"
                        + " loops=%d points=%d) refineSplits=%d |"
                        + " copy V=%d E=%d F=%d (+%d faceSplits +%d edgeSplits) %.2fs%n",
                snappedToVertexCount, placedByEdgeSplitCount, placedByFaceSplitCount,
                arcsRouted, router.snapCount, router.splitCount, router.directConnectCount,
                router.marchConnectCount, router.bridgeConnectCount,
                router.loopRoutedCount, pointArcCount, router.refinedEdgeSplitCount,
                topology.copy.vertexCount(), topology.copy.edgeCount(),
                topology.copy.faceCount(), topology.faceSplitCount,
                topology.edgeSplitCount, (System.nanoTime() - startNanos) / 1.0e9);
        return this;
    }

    /**
     * Tag every original copy edge with its source active edge index (the
     * indexing of {@link ArcStripIndex#crossingArcsBySourceEdge}), so bridge
     * hops can honor trace crossings across refinement splits.
     */
    private void tagSourceEdges() {
        for (Map.Entry<Integer, Integer> entry
                : motorcycleGraph.seamless.crossField.edgeIdToActive.entrySet()) {
            int halfEdge = motorcycleGraph.seamless.mesh.edgeHalfEdge(entry.getKey());
            int copyA = topology.copyVertexForSourceVertexId(
                    motorcycleGraph.seamless.mesh.halfEdgeVertex(halfEdge));
            int copyB = topology.copyVertexForSourceVertexId(
                    motorcycleGraph.seamless.mesh.halfEdgeEndVertex(halfEdge));
            if (copyA == EmbeddedMeshTopology.UNCLAIMED || copyB == EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            int copyEdge = topology.edgeBetween(copyA, copyB);
            if (copyEdge != EmbeddedMeshTopology.UNCLAIMED) {
                topology.sourceEdgeByCopyEdge[copyEdge] = entry.getValue();
            }
        }
    }

    /**
     * Mark LCBK19 Def 6.2 criticality: singularity and feature nodes are
     * critical (their integer positions are prescribed by the quantization),
     * and feature-trace arcs are critical curves. Trace-crossing intersection
     * nodes stay non-critical until a contraction collapse lands them on a
     * critical point.
     */
    private void markCriticality() {
        criticalByNode = new boolean[motorcycleGraph.nodes.size()];
        for (TMeshNode node : motorcycleGraph.nodes) {
            criticalByNode[node.nodeId] = node.type == TMeshNode.Type.SINGULARITY
                    || node.type == TMeshNode.Type.FEATURE;
        }
        featureByArc = new boolean[motorcycleGraph.arcs.size()];
        Map<Integer, Trace> traceById = new HashMap<>();
        for (Trace trace : motorcycleGraph.traces) {
            traceById.put(trace.traceId, trace);
        }
        for (TraceArc arc : motorcycleGraph.arcs) {
            featureByArc[arc.arcId] = traceById.get(arc.traceId).featureTrace;
        }
    }

    /**
     * Give every arc-referenced T-mesh node a dedicated copy vertex:
     * vertex-bound nodes claim their mapped copy vertex; face-interior nodes
     * snap to a nearby unclaimed corner, split a nearby edge, or split their
     * containing face (LCBK19 §6.1 node embedding). Total: one mesh vertex
     * never owns two T-mesh nodes (see {@link TMeshNode}), so a claim
     * conflict throws instead of dropping the node. The contraction stage
     * merges each collapse-cluster's nodes afterwards, dragging lanes one
     * zero-arc step at a time — the paper's incremental collapse.
     */
    private void insertNodes() {
        int nodeCount = motorcycleGraph.nodes.size();
        vertexIdByNode = new int[nodeCount];
        Arrays.fill(vertexIdByNode, EmbeddedMeshTopology.UNCLAIMED);
        boolean[] nodeUsed = new boolean[nodeCount];
        for (TraceArc arc : motorcycleGraph.arcs) {
            nodeUsed[arc.startNodeId] = true;
            nodeUsed[arc.endNodeId] = true;
        }
        for (TMeshNode node : motorcycleGraph.nodes) {
            if (!nodeUsed[node.nodeId]) {
                continue;
            }
            int copyVertex;
            if (node.vertexId >= 0) {
                copyVertex = topology.copyVertexForSourceVertexId(node.vertexId);
                if (copyVertex == EmbeddedMeshTopology.UNCLAIMED) {
                    throw new IllegalStateException("T-mesh node " + node.nodeId
                            + " references source vertex " + node.vertexId
                            + " which has no copy vertex");
                }
                int owner = topology.ownerNodeByCopyVertex[copyVertex];
                if (owner != EmbeddedMeshTopology.UNCLAIMED) {
                    throw new IllegalStateException("T-mesh nodes " + owner + " and "
                            + node.nodeId + " both sit on mesh vertex " + node.vertexId
                            + "; one mesh vertex never owns two T-mesh nodes");
                }
                snappedToVertexCount++;
            } else {
                copyVertex = placeFreeVertex(node.activeFace, node.position);
            }
            topology.ownerNodeByCopyVertex[copyVertex] = node.nodeId;
            vertexIdByNode[node.nodeId] = copyVertex;
        }
    }

    /**
     * Place a dedicated unclaimed copy vertex at a surface point: locate the
     * descendant copy face containing it, then snap to a nearby unclaimed
     * corner, split a nearby unclaimed edge, or split the face (LCBK19 §6.1 —
     * "only if there are not enough vertices or edges is the mesh split").
     * Always succeeds: the face split needs no free elements. Used for
     * face-interior nodes and for loop waypoints; the caller assigns ownership.
     *
     * @param sourceActiveFace source active face containing the point
     * @param position         3D position to place at
     * @return an unclaimed copy vertex at (or snapped near) the position
     */
    private int placeFreeVertex(int sourceActiveFace, Vector3f position) {
        if (sourceActiveFace < 0 || sourceActiveFace >= topology.copyFacesBySourceFace.size()) {
            throw new IllegalStateException(
                    "placement outside the mesh: source active face " + sourceActiveFace);
        }
        int copyFace = closestDescendantFace(sourceActiveFace, position);
        if (copyFace == EmbeddedMeshTopology.UNCLAIMED) {
            throw new IllegalStateException("source active face " + sourceActiveFace
                    + " has no descendant copy faces left");
        }
        float shortestEdge = Float.POSITIVE_INFINITY;
        Vector3f[] corners = new Vector3f[3];
        int[] cornerVertices = new int[3];
        for (int corner = 0; corner < 3; corner++) {
            cornerVertices[corner] = topology.copy.faceVertexAt(copyFace, corner);
            corners[corner] = topology.copy.vertexPosition(cornerVertices[corner], new Vector3f());
        }
        for (int corner = 0; corner < 3; corner++) {
            shortestEdge = Math.min(shortestEdge,
                    corners[corner].distance(corners[(corner + 1) % 3]));
        }
        int bestCorner = -1;
        float bestCornerDistance = Float.POSITIVE_INFINITY;
        for (int corner = 0; corner < 3; corner++) {
            float cornerDistance = corners[corner].distance(position);
            if (cornerDistance < bestCornerDistance) {
                bestCornerDistance = cornerDistance;
                bestCorner = corner;
            }
        }
        if (bestCornerDistance <= NODE_CORNER_SNAP_RATIO * shortestEdge
                && topology.ownerNodeByCopyVertex[cornerVertices[bestCorner]] == EmbeddedMeshTopology.UNCLAIMED
                && topology.ownerArcByCopyVertex[cornerVertices[bestCorner]] == EmbeddedMeshTopology.UNCLAIMED) {
            snappedToVertexCount++;
            return cornerVertices[bestCorner];
        }
        int bestEdgeCorner = -1;
        float bestEdgeDistance = Float.POSITIVE_INFINITY;
        Vector3f bestProjection = new Vector3f();
        Vector3f projection = new Vector3f();
        for (int corner = 0; corner < 3; corner++) {
            Vector3f start = corners[corner];
            Vector3f end = corners[(corner + 1) % 3];
            float edgeDistance = projectOntoSegment(position, start, end, projection);
            float edgeLength = start.distance(end);
            if (edgeDistance < bestEdgeDistance
                    && edgeDistance <= NODE_EDGE_SNAP_RATIO * edgeLength) {
                bestEdgeDistance = edgeDistance;
                bestEdgeCorner = corner;
                bestProjection.set(projection);
            }
        }
        if (bestEdgeCorner >= 0) {
            int edgeId = topology.copy.faceEdgeAt(copyFace, bestEdgeCorner);
            if (topology.ownerArcByCopyEdge[edgeId] == EmbeddedMeshTopology.UNCLAIMED) {
                placedByEdgeSplitCount++;
                return topology.splitEdgeAtPoint(edgeId, new Vector3f(bestProjection));
            }
        }
        placedByFaceSplitCount++;
        return topology.splitFaceAtPoint(copyFace, new Vector3f(position));
    }

    /**
     * Compute each arc's node balls: centered on its endpoint anchors'
     * placed vertices, with radius the anchor's placement error (the
     * distance from the anchor node's traced position to the vertex it was
     * placed on), widened by {@link ArcRouter#NODE_BALL_MARGIN}. Crossings
     * inside a ball are materialized by the anchor vertex — placement moved
     * the anchor, but every incident trace still radiates from the original
     * position, so per-arc integration must not mint them there.
     *
     * @param router router receiving the ball centers and radii
     */
    private void computeNodeBalls(ArcRouter router) {
        Vector3f placed = new Vector3f();
        for (TraceArc arc : motorcycleGraph.arcs) {
            int startVertex = vertexIdByNode[arc.startNodeId];
            if (startVertex != EmbeddedMeshTopology.UNCLAIMED) {
                topology.copy.vertexPosition(startVertex, placed);
                router.startBallCenterByArc.get(arc.arcId).set(placed);
                router.startBallByArc[arc.arcId] =
                        ArcRouter.NODE_BALL_MARGIN * anchorPlacementError(startVertex, placed);
            }
            int endVertex = vertexIdByNode[arc.endNodeId];
            if (endVertex != EmbeddedMeshTopology.UNCLAIMED) {
                topology.copy.vertexPosition(endVertex, placed);
                router.endBallCenterByArc.get(arc.arcId).set(placed);
                router.endBallByArc[arc.arcId] =
                        ArcRouter.NODE_BALL_MARGIN * anchorPlacementError(endVertex, placed);
            }
        }
    }

    /**
     * The distance an anchor moved from its node's traced position when it
     * was placed on its copy vertex.
     *
     * @param anchorVertex   the anchor's copy vertex
     * @param vertexPosition the vertex's position
     * @return placement error, or zero when the vertex owns no node
     */
    private float anchorPlacementError(int anchorVertex, Vector3f vertexPosition) {
        int anchorNodeId = topology.ownerNodeByCopyVertex[anchorVertex];
        if (anchorNodeId == EmbeddedMeshTopology.UNCLAIMED) {
            return 0f;
        }
        return vertexPosition.distance(motorcycleGraph.nodes.get(anchorNodeId).position);
    }

    /**
     * Fill each persistent arc's terminal chain channels: the concatenated
     * polylines of the zero arcs joining its endpoint node to its cluster's
     * anchor node, oriented node-to-anchor. Terminal bridges pull toward
     * this geometry — the path LCBK19's collapse operators would drag the
     * tail along — so tails of different arcs thread the cluster region
     * beside their own channels instead of cutting across each other's.
     *
     * @param router router receiving the chain geometry
     */
    private void computeTerminalChains(ArcRouter router) {
        LayoutExtraction extraction = conforming.layout;
        Map<Integer, List<Integer>> zeroArcsByNode = new HashMap<>();
        for (TraceArc arc : motorcycleGraph.arcs) {
            if (quantization.quantizedLengthByArc[arc.arcId] == 0) {
                zeroArcsByNode.computeIfAbsent(arc.startNodeId, node -> new ArrayList<>())
                        .add(arc.arcId);
                if (arc.endNodeId != arc.startNodeId) {
                    zeroArcsByNode.computeIfAbsent(arc.endNodeId, node -> new ArrayList<>())
                            .add(arc.arcId);
                }
            }
        }
        Map<Integer, List<Vector3f>> chainByNode = new HashMap<>();
        for (TraceArc arc : motorcycleGraph.arcs) {
            if (!featureByArc[arc.arcId]
                    && quantization.quantizedLengthByArc[arc.arcId] == 0) {
                continue;
            }
            router.startChainByArc.set(arc.arcId,
                    chainByNode.computeIfAbsent(arc.startNodeId,
                            node -> chainToAnchor(node, extraction, zeroArcsByNode)));
            router.endChainByArc.set(arc.arcId,
                    chainByNode.computeIfAbsent(arc.endNodeId,
                            node -> chainToAnchor(node, extraction, zeroArcsByNode)));
        }
    }

    /**
     * The zero-arc chain geometry from a node to its cluster's anchor node:
     * a breadth-first search over the cluster's zero arcs, with the found
     * arcs' polylines concatenated in travel orientation.
     *
     * @param nodeId         node the chain starts at
     * @param extraction     cluster structure
     * @param zeroArcsByNode zero-arc adjacency per node id
     * @return chain positions node-to-anchor, empty when the node is the
     *         anchor or no chain exists
     */
    private List<Vector3f> chainToAnchor(int nodeId, LayoutExtraction extraction,
            Map<Integer, List<Integer>> zeroArcsByNode) {
        int anchorVertex = vertexIdByNode[nodeId];
        if (anchorVertex == EmbeddedMeshTopology.UNCLAIMED) {
            return new ArrayList<>(0);
        }
        int anchorNodeId = topology.ownerNodeByCopyVertex[anchorVertex];
        if (anchorNodeId == EmbeddedMeshTopology.UNCLAIMED || anchorNodeId == nodeId) {
            return new ArrayList<>(0);
        }
        Map<Integer, Integer> parentArcByNode = new HashMap<>();
        List<Integer> frontier = new ArrayList<>();
        frontier.add(nodeId);
        parentArcByNode.put(nodeId, EmbeddedMeshTopology.UNCLAIMED);
        for (int scan = 0; scan < frontier.size() && !parentArcByNode.containsKey(anchorNodeId);
                scan++) {
            int current = frontier.get(scan);
            for (int zeroArcId : zeroArcsByNode.getOrDefault(current, List.of())) {
                TraceArc zeroArc = motorcycleGraph.arcs.get(zeroArcId);
                int neighbor = zeroArc.startNodeId == current
                        ? zeroArc.endNodeId
                        : zeroArc.startNodeId;
                if (extraction.clusterByNode[neighbor] != extraction.clusterByNode[nodeId]
                        || parentArcByNode.containsKey(neighbor)) {
                    continue;
                }
                parentArcByNode.put(neighbor, zeroArcId);
                frontier.add(neighbor);
            }
        }
        if (!parentArcByNode.containsKey(anchorNodeId)) {
            return new ArrayList<>(0);
        }
        List<Integer> pathArcs = new ArrayList<>();
        List<Integer> pathNodes = new ArrayList<>();
        int walk = anchorNodeId;
        while (walk != nodeId) {
            int arcId = parentArcByNode.get(walk);
            pathArcs.add(arcId);
            pathNodes.add(walk);
            TraceArc zeroArc = motorcycleGraph.arcs.get(arcId);
            walk = zeroArc.startNodeId == walk ? zeroArc.endNodeId : zeroArc.startNodeId;
        }
        List<Vector3f> chain = new ArrayList<>();
        for (int index = pathArcs.size() - 1; index >= 0; index--) {
            TraceArc zeroArc = motorcycleGraph.arcs.get(pathArcs.get(index));
            List<Vector3f> polyline = strips.polylineByArc.get(pathArcs.get(index));
            boolean forward = zeroArc.endNodeId == pathNodes.get(index);
            if (forward) {
                chain.addAll(polyline);
            } else {
                for (int point = polyline.size() - 1; point >= 0; point--) {
                    chain.add(polyline.get(point));
                }
            }
        }
        return chain;
    }

    /**
     * Reserve one departure and one arrival spoke per persistent arc at its
     * anchors before any routing: each spoke is a single split of the
     * anchor's fan along the arc's own traced direction, pre-claimed for the
     * arc. This applies LCBK19 §6.1's "only if there are not enough vertices
     * or edges is the mesh split" at nodes — an anchor with many incident
     * arcs gets exactly the fan elements they need, so terminal connects
     * never contend.
     *
     * @param router  router receiving the seeds
     * @param ordered persistent arc ids in routing order
     */
    private void seedAnchorSpokes(ArcRouter router, List<Integer> ordered) {
        for (int arcId : ordered) {
            TraceArc arc = motorcycleGraph.arcs.get(arcId);
            List<Vector3f> polyline = strips.polylineByArc.get(arcId);
            int startVertex = vertexIdByNode[arc.startNodeId];
            for (Vector3f point : polyline) {
                if (point.distance(router.startBallCenterByArc.get(arcId))
                        > router.startBallByArc[arcId]) {
                    router.startSeedByArc[arcId] = router.seedSpoke(arcId, startVertex, point);
                    break;
                }
            }
            int endVertex = vertexIdByNode[arc.endNodeId];
            for (int index = polyline.size() - 1; index >= 0; index--) {
                Vector3f point = polyline.get(index);
                if (point.distance(router.endBallCenterByArc.get(arcId))
                        > router.endBallByArc[arcId]) {
                    router.endSeedByArc[arcId] = router.seedSpoke(arcId, endVertex, point);
                    break;
                }
            }
        }
    }

    /**
     * Fill each persistent arc's terminal corridor region: the union of its
     * endpoint clusters' zero-arc strip faces. The cluster anchor can sit a
     * cluster-diameter from the arc's traced endpoint, and the terminal
     * connect routes through this region — the channel LCBK19's collapse
     * operators would have dragged the tail along.
     *
     * @param router router receiving the per-arc regions
     */
    private void computeTerminalRegions(ArcRouter router) {
        LayoutExtraction extraction = conforming.layout;
        List<Set<Integer>> facesByCluster = new ArrayList<>(extraction.clusterCount);
        for (int cluster = 0; cluster < extraction.clusterCount; cluster++) {
            facesByCluster.add(new HashSet<>());
        }
        for (TraceArc arc : motorcycleGraph.arcs) {
            if (quantization.quantizedLengthByArc[arc.arcId] == 0) {
                facesByCluster.get(extraction.clusterByNode[arc.startNodeId])
                        .addAll(strips.stripFacesByArc.get(arc.arcId));
            }
        }
        for (TraceArc arc : motorcycleGraph.arcs) {
            if (!featureByArc[arc.arcId] && quantization.quantizedLengthByArc[arc.arcId] == 0) {
                continue;
            }
            Set<Integer> region = new HashSet<>(
                    facesByCluster.get(extraction.clusterByNode[arc.startNodeId]));
            region.addAll(facesByCluster.get(extraction.clusterByNode[arc.endNodeId]));
            router.terminalFacesByArc.set(arc.arcId, region);
        }
    }

    /**
     * Route the layout's persistent arcs between their endpoint node
     * vertices: feature arcs first (they must claim the alignment edges they
     * ride), then positively quantized arcs. Per-crossing integration is
     * contention-free, so a single ordered sweep embeds every arc; there are
     * no retries. Zero-quantized arcs are not embedded — the contraction
     * stage collapses them onto points directly. Same-node arcs with
     * degenerate polylines embed as single-vertex point paths; real loops
     * integrate their full closed polyline.
     *
     * @return the router, for its outcome counters
     */
    private ArcRouter routeArcs() {
        ArcRouter router = new ArcRouter(topology, strips);
        computeNodeBalls(router);
        computeTerminalRegions(router);
        computeTerminalChains(router);
        pathByArc = new ArcEdgePath[motorcycleGraph.arcs.size()];
        List<Integer> featureArcs = new ArrayList<>();
        List<Integer> positiveArcs = new ArrayList<>();
        for (TraceArc arc : motorcycleGraph.arcs) {
            if (featureByArc[arc.arcId]) {
                featureArcs.add(arc.arcId);
            } else if (quantization.quantizedLengthByArc[arc.arcId] > 0) {
                positiveArcs.add(arc.arcId);
            } else {
                router.embeddedByArc[arc.arcId] = true;
            }
        }
        List<Integer> ordered = new ArrayList<>(featureArcs);
        ordered.addAll(positiveArcs);
        seedAnchorSpokes(router, ordered);
        for (int arcId : ordered) {
            TraceArc arc = motorcycleGraph.arcs.get(arcId);
            if (arc.startNodeId == arc.endNodeId
                    && strips.polylineByArc.get(arcId).size() < LOOP_MIN_POLYLINE_POINTS) {
                pointArcCount++;
                List<Integer> singleVertex = new ArrayList<>(1);
                singleVertex.add(vertexIdByNode[arc.startNodeId]);
                pathByArc[arcId] = new ArcEdgePath(arcId, singleVertex, new ArrayList<>(0));
            } else {
                pathByArc[arcId] = router.route(arcId, vertexIdByNode[arc.startNodeId],
                        vertexIdByNode[arc.endNodeId]);
            }
            arcsRouted++;
        }
        return router;
    }

    /**
     * The descendant copy face of a source face that lies closest to a point
     * (containment score by projected barycentrics; the point sits on the source
     * face's surface, so the best-scoring child contains it up to refinement
     * noise).
     *
     * @param sourceActiveFace source active face the point belongs to
     * @param point            3D query point
     * @return best descendant copy face, or -1 when none remain
     */
    private int closestDescendantFace(int sourceActiveFace, Vector3f point) {
        int bestFace = EmbeddedMeshTopology.UNCLAIMED;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int copyFace : topology.copyFacesBySourceFace.get(sourceActiveFace)) {
            float score = containmentScore(copyFace, point);
            if (score > bestScore) {
                bestScore = score;
                bestFace = copyFace;
            }
        }
        return bestFace;
    }

    /**
     * Minimum projected barycentric coordinate of the point against a copy face —
     * positive inside, increasingly negative outside.
     *
     * @param copyFace candidate triangle
     * @param point    query point
     * @return containment score
     */
    private float containmentScore(int copyFace, Vector3f point) {
        Vector3f a = topology.copy.vertexPosition(topology.copy.faceVertexAt(copyFace, 0), new Vector3f());
        Vector3f b = topology.copy.vertexPosition(topology.copy.faceVertexAt(copyFace, 1), new Vector3f());
        Vector3f c = topology.copy.vertexPosition(topology.copy.faceVertexAt(copyFace, 2), new Vector3f());
        Vector3f ab = new Vector3f(b).sub(a);
        Vector3f ac = new Vector3f(c).sub(a);
        Vector3f ap = new Vector3f(point).sub(a);
        float d00 = ab.dot(ab);
        float d01 = ab.dot(ac);
        float d11 = ac.dot(ac);
        float d20 = ap.dot(ab);
        float d21 = ap.dot(ac);
        float denominator = d00 * d11 - d01 * d01;
        if (Math.abs(denominator) < Float.MIN_NORMAL) {
            return Float.NEGATIVE_INFINITY;
        }
        float v = (d11 * d20 - d01 * d21) / denominator;
        float w = (d00 * d21 - d01 * d20) / denominator;
        float u = 1f - v - w;
        return Math.min(u, Math.min(v, w));
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
