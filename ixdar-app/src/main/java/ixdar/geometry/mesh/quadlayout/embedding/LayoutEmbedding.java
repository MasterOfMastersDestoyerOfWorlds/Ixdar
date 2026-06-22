package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshNode;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceArc;
import ixdar.geometry.mesh.quadlayout.quantization.QuantizedMeshGrid;
import ixdar.geometry.mesh.quadlayout.quantization.TJunctionElimination;

/**
 * LCBK19 §6.1 T-mesh re-embedding, construction half: build a working copy of
 * the input mesh (the source is never mutated), give every T-mesh node a
 * dedicated copy vertex (snapping to nearby corners or splitting edges/faces),
 * and embed every arc as a claims-respecting edge path along its traced face
 * strip. The zero-element contraction operators and the final patch region
 * assignment build on these products.
 */
public final class LayoutEmbedding {

    /** Corner-snap threshold as a fraction of the face's shortest edge. */
    public static final float NODE_CORNER_SNAP_RATIO = 0.25f;

    /** Edge-snap threshold as a fraction of the edge's length. */
    public static final float NODE_EDGE_SNAP_RATIO = 0.15f;

    /** Route failures dumped with details before going quiet. */
    public static final int ROUTE_FAILURE_SAMPLE_LIMIT = 10;

    public final TJunctionElimination conforming;
    public final MotorcycleGraph motorcycleGraph;
    public final QuantizedMeshGrid quantization;

    /** Working copy with provenance and claims. */
    public EmbeddedMeshTopology topology;

    /** Per-arc strips and traced polylines. */
    public ArcStripIndex strips;

    /** Copy vertex per T-mesh node id, or -1 for unused/unplaced nodes. */
    public int[] vertexIdByNode;

    /** Embedded path per arc id, or {@code null} when routing failed. */
    public ArcEdgePath[] pathByArc;

    public int nodesSnappedToVertex;
    public int nodesPlacedByEdgeSplit;
    public int nodesPlacedByFaceSplit;
    public int nodePlacementConflictCount;
    public int arcsRouted;
    public int arcsFailed;
    public int selfLoopArcCount;
    public int fallbackHopCount;

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
        insertNodes();
        routeArcs();
        topology.copy.computeNormals();
        System.out.printf(
                "[embed] nodes snap=%d edgeSplit=%d faceSplit=%d conflicts=%d |"
                        + " arcs routed=%d failed=%d selfLoop=%d fallbackHops=%d |"
                        + " copy V=%d E=%d F=%d (+%d faceSplits +%d edgeSplits) %.2fs%n",
                nodesSnappedToVertex, nodesPlacedByEdgeSplit, nodesPlacedByFaceSplit,
                nodePlacementConflictCount, arcsRouted, arcsFailed, selfLoopArcCount,
                fallbackHopCount, topology.copy.vertexCount(), topology.copy.edgeCount(),
                topology.copy.faceCount(), topology.faceSplitCount, topology.edgeSplitCount,
                (System.nanoTime() - startNanos) / 1.0e9);
        return this;
    }

    /**
     * Give every arc-referenced T-mesh node a dedicated copy vertex: vertex-bound
     * nodes claim their mapped copy vertex; face-interior nodes snap to a nearby
     * unclaimed corner, split a nearby edge, or split their containing face (LCBK19
     * §6.1 node embedding).
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
            if (node.vertexId >= 0) {
                int copyVertex = topology.copyVertexForSourceVertexId(node.vertexId);
                if (copyVertex == EmbeddedMeshTopology.UNCLAIMED
                        || topology.ownerNodeByCopyVertex[copyVertex] != EmbeddedMeshTopology.UNCLAIMED) {
                    nodePlacementConflictCount++;
                    continue;
                }
                topology.ownerNodeByCopyVertex[copyVertex] = node.nodeId;
                vertexIdByNode[node.nodeId] = copyVertex;
                nodesSnappedToVertex++;
                continue;
            }
            placeInteriorNode(node);
        }
    }

    /**
     * Place one face-interior node: locate the descendant copy face containing its
     * position, then snap/split by proximity.
     *
     * @param node face-interior node ({@code activeFace >= 0})
     */
    private void placeInteriorNode(TMeshNode node) {
        if (node.activeFace < 0 || node.activeFace >= topology.copyFacesBySourceFace.size()) {
            nodePlacementConflictCount++;
            return;
        }
        int copyFace = closestDescendantFace(node.activeFace, node.position);
        if (copyFace == EmbeddedMeshTopology.UNCLAIMED) {
            nodePlacementConflictCount++;
            return;
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
            float cornerDistance = corners[corner].distance(node.position);
            if (cornerDistance < bestCornerDistance) {
                bestCornerDistance = cornerDistance;
                bestCorner = corner;
            }
        }
        if (bestCornerDistance <= NODE_CORNER_SNAP_RATIO * shortestEdge
                && topology.ownerNodeByCopyVertex[cornerVertices[bestCorner]] == EmbeddedMeshTopology.UNCLAIMED
                && topology.ownerArcByCopyVertex[cornerVertices[bestCorner]] == EmbeddedMeshTopology.UNCLAIMED) {
            int copyVertex = cornerVertices[bestCorner];
            topology.ownerNodeByCopyVertex[copyVertex] = node.nodeId;
            vertexIdByNode[node.nodeId] = copyVertex;
            nodesSnappedToVertex++;
            return;
        }
        int bestEdgeCorner = -1;
        float bestEdgeDistance = Float.POSITIVE_INFINITY;
        Vector3f bestProjection = new Vector3f();
        Vector3f projection = new Vector3f();
        for (int corner = 0; corner < 3; corner++) {
            Vector3f start = corners[corner];
            Vector3f end = corners[(corner + 1) % 3];
            float edgeDistance = projectOntoSegment(node.position, start, end, projection);
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
                int copyVertex = topology.splitEdgeAtPoint(edgeId, new Vector3f(bestProjection));
                topology.ownerNodeByCopyVertex[copyVertex] = node.nodeId;
                vertexIdByNode[node.nodeId] = copyVertex;
                nodesPlacedByEdgeSplit++;
                return;
            }
        }
        int copyVertex = topology.splitFaceAtPoint(copyFace, new Vector3f(node.position));
        topology.ownerNodeByCopyVertex[copyVertex] = node.nodeId;
        vertexIdByNode[node.nodeId] = copyVertex;
        nodesPlacedByFaceSplit++;
    }

    /**
     * Route every arc between its endpoint node vertices: feature arcs first (they
     * ride the alignment edges), then positively quantized arcs, then zero arcs.
     */
    private void routeArcs() {
        ArcRouter router = new ArcRouter(topology, strips);
        pathByArc = new ArcEdgePath[motorcycleGraph.arcs.size()];
        Map<Integer, Trace> traceById = new HashMap<>();
        for (Trace trace : motorcycleGraph.traces) {
            traceById.put(trace.traceId, trace);
        }
        List<Integer> featureArcs = new ArrayList<>();
        List<Integer> positiveArcs = new ArrayList<>();
        List<Integer> zeroArcs = new ArrayList<>();
        for (TraceArc arc : motorcycleGraph.arcs) {
            if (traceById.get(arc.traceId).featureTrace) {
                featureArcs.add(arc.arcId);
            } else if (quantization.quantizedLengthByArc[arc.arcId] > 0) {
                positiveArcs.add(arc.arcId);
            } else {
                zeroArcs.add(arc.arcId);
            }
        }
        routeArcGroup(router, featureArcs);
        routeArcGroup(router, positiveArcs);
        routeArcGroup(router, zeroArcs);
        fallbackHopCount = router.fallbackHopCount;
        System.out.printf("[embed-diag] fallback exhausted=%d capped=%d%n",
                router.fallbackExhaustedCount, router.fallbackCappedCount);
    }

    /**
     * Route one ordered group of arcs, counting outcomes.
     *
     * @param router shared router
     * @param arcIds arc ids to route, in deterministic order
     */
    private void routeArcGroup(ArcRouter router, List<Integer> arcIds) {
        for (int arcId : arcIds) {
            TraceArc arc = motorcycleGraph.arcs.get(arcId);
            int startVertex = vertexIdByNode[arc.startNodeId];
            int endVertex = vertexIdByNode[arc.endNodeId];
            if (startVertex == EmbeddedMeshTopology.UNCLAIMED
                    || endVertex == EmbeddedMeshTopology.UNCLAIMED) {
                arcsFailed++;
                continue;
            }
            if (startVertex == endVertex && arc.parametricLength > 1e-9) {
                selfLoopArcCount++;
                continue;
            }
            ArcEdgePath path = router.route(arcId, startVertex, endVertex);
            if (path == null) {
                arcsFailed++;
                if (arcsFailed <= ROUTE_FAILURE_SAMPLE_LIMIT) {
                    Vector3f startPosition = topology.copy.vertexPosition(startVertex, new Vector3f());
                    Vector3f endPosition = topology.copy.vertexPosition(endVertex, new Vector3f());
                    System.out.printf(
                            "[embed-diag] route failed arc=%d trace=%d q=%d polylinePoints=%d"
                                    + " stripFaces=%d span=%.5f%n",
                            arcId, arc.traceId, quantization.quantizedLengthByArc[arcId],
                            strips.polylineByArc.get(arcId).size(),
                            strips.stripFacesByArc.get(arcId).size(),
                            startPosition.distance(endPosition));
                }
            } else {
                pathByArc[arcId] = path;
                arcsRouted++;
            }
        }
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
