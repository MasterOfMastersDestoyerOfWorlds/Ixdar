package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceArc;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutExtraction;

/**
 * Zero-arc collapse (operator 1): every zero-quantized arc is re-embedded onto a
 * point by moving its movable node onto the other, dragging the incident arcs'
 * paths. Critical nodes never move.
 *
 * <p>After {@link #build}, every zero arc's path is a single vertex.
 *
 * <p>See also: LCBK19 Section 6.1
 */
public final class ZeroElementContraction {

    /** Exception-message prefix naming an arc. */
    private static final String ARC_PREFIX = "arc ";

    /** Exception-message prefix naming a zero arc. */
    private static final String ZERO_ARC_PREFIX = "zero arc ";

    public final LayoutEmbedding embedding;
    public final EmbeddedMeshTopology topology;
    public final MotorcycleGraph motorcycleGraph;
    public final LayoutExtraction extraction;

    /** Zero arcs re-embedded onto a point by a node move. */
    public int zeroArcCollapsedCount;

    /** Zero arcs contracted as loops after both endpoints merged. */
    public int loopContractedCount;

    /** Nodes moved onto their cluster anchor. */
    public int nodesMovedCount;

    /** Incident-arc paths re-routed to follow a moved node. */
    public int tailRerouteCount;

    /** Re-route attempts that had to back off to an earlier path vertex. */
    public int backoffRetryCount;

    private ArcRerouter rerouter;
    private List<List<Integer>> arcIdsByNode;

    /**
     * Stores inputs for the contraction stage.
     *
     * @param embedding built construction-half embedding to contract in place
     */
    public ZeroElementContraction(LayoutEmbedding embedding) {
        this.embedding = embedding;
        this.topology = embedding.topology;
        this.motorcycleGraph = embedding.motorcycleGraph;
        this.extraction = embedding.conforming.layout;
    }

    /**
     * Contract every collapse-cluster onto its anchor vertex and validate the
     * result; logs the {@code [contract]} summary.
     *
     * @return this, with the embedding's paths and node vertices updated in
     *         place
     */
    public ZeroElementContraction build() {
        long startNanos = System.nanoTime();
        rerouter = new ArcRerouter(topology);
        arcIdsByNode = new ArrayList<>(motorcycleGraph.nodes.size());
        for (int node = 0; node < motorcycleGraph.nodes.size(); node++) {
            arcIdsByNode.add(new ArrayList<>());
        }
        for (TraceArc arc : motorcycleGraph.arcs) {
            arcIdsByNode.get(arc.startNodeId).add(arc.arcId);
            if (arc.endNodeId != arc.startNodeId) {
                arcIdsByNode.get(arc.endNodeId).add(arc.arcId);
            }
        }
        List<List<Integer>> membersByCluster = new ArrayList<>(extraction.clusterCount);
        List<List<Integer>> zeroArcsByCluster = new ArrayList<>(extraction.clusterCount);
        for (int cluster = 0; cluster < extraction.clusterCount; cluster++) {
            membersByCluster.add(new ArrayList<>());
            zeroArcsByCluster.add(new ArrayList<>());
        }
        for (int node = 0; node < motorcycleGraph.nodes.size(); node++) {
            if (embedding.vertexIdByNode[node] != EmbeddedMeshTopology.UNCLAIMED) {
                membersByCluster.get(extraction.clusterByNode[node]).add(node);
            }
        }
        for (TraceArc arc : motorcycleGraph.arcs) {
            if (embedding.quantization.quantizedLengthByArc[arc.arcId] == 0) {
                zeroArcsByCluster.get(extraction.clusterByNode[arc.startNodeId]).add(arc.arcId);
            }
        }
        int multiClusterCount = 0;
        for (int cluster = 0; cluster < extraction.clusterCount; cluster++) {
            if (zeroArcsByCluster.get(cluster).isEmpty()) {
                continue;
            }
            multiClusterCount++;
            contractCluster(membersByCluster.get(cluster), zeroArcsByCluster.get(cluster));
        }
        validate();
        System.out.printf(
                "[contract] clusters=%d contracted=%d nodesMoved=%d zeroCollapsed=%d"
                        + " loopsContracted=%d tailReroutes=%d backoffs=%d %.2fs%n",
                extraction.clusterCount, multiClusterCount, nodesMovedCount,
                zeroArcCollapsedCount, loopContractedCount, tailRerouteCount, backoffRetryCount,
                (System.nanoTime() - startNanos) / 1.0e9);
        return this;
    }

    /**
     * Contract one cluster onto an anchor vertex — a critical member's when one
     * exists, else the lowest node id's — then contract the remaining
     * intra-cluster zero arcs as loops.
     *
     * @param members  node ids in the cluster
     * @param zeroArcs zero-quantized arc ids within the cluster
     */
    private void contractCluster(List<Integer> members, List<Integer> zeroArcs) {
        int anchor = -1;
        for (int member : members) {
            if (!embedding.criticalByNode[member]) {
                continue;
            }
            if (anchor >= 0
                    && embedding.vertexIdByNode[anchor] != embedding.vertexIdByNode[member]) {
                throw new IllegalStateException("cluster merges critical nodes " + anchor
                        + " and " + member + " — quantization separation violated");
            }
            if (anchor < 0) {
                anchor = member;
            }
        }
        if (anchor < 0) {
            anchor = Collections.min(members);
        }
        int anchorVertex = embedding.vertexIdByNode[anchor];
        Set<Integer> reached = new HashSet<>();
        reached.add(anchor);
        Set<Integer> collapsedArcs = new HashSet<>();
        boolean progress = true;
        while (reached.size() < members.size() && progress) {
            progress = false;
            for (int arcId : zeroArcs) {
                if (collapsedArcs.contains(arcId)) {
                    continue;
                }
                TraceArc arc = motorcycleGraph.arcs.get(arcId);
                int movingNode;
                if (reached.contains(arc.startNodeId) && !reached.contains(arc.endNodeId)) {
                    movingNode = arc.endNodeId;
                } else if (reached.contains(arc.endNodeId) && !reached.contains(arc.startNodeId)) {
                    movingNode = arc.startNodeId;
                } else {
                    continue;
                }
                if (embedding.criticalByNode[movingNode]
                        || (!embedding.featureByArc[arcId] && featureBound(movingNode))) {
                    continue;
                }
                collapseZeroArc(arcId, movingNode, anchorVertex);
                collapsedArcs.add(arcId);
                reached.add(movingNode);
                progress = true;
            }
        }
        if (reached.size() < members.size()) {
            throw new IllegalStateException("cluster around node " + anchor + " has "
                    + (members.size() - reached.size())
                    + " members unreachable over movable zero arcs");
        }
        for (int arcId : zeroArcs) {
            if (!collapsedArcs.contains(arcId)) {
                contractLoopArc(arcId, anchorVertex);
            }
        }
        if (embedding.criticalByNode[anchor]) {
            for (int member : members) {
                embedding.criticalByNode[member] = true;
            }
        }
    }

    /**
     * Whether a node lies on a feature curve (incident to any feature arc);
     * such nodes may move only along feature arcs (LCBK19 Def 6.2, border
     * analog).
     *
     * @param nodeId node to test
     * @return true when any incident arc is a feature arc
     */
    private boolean featureBound(int nodeId) {
        for (int arcId : arcIdsByNode.get(nodeId)) {
            if (embedding.featureByArc[arcId]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Operator 1: collapse one zero arc by moving its movable node along the
     * arc's released path onto the target vertex, re-routing the node's other
     * incident arcs to follow.
     *
     * @param arcId        zero arc to collapse
     * @param movingNode   the arc endpoint that moves
     * @param targetVertex the cluster anchor vertex it moves onto
     */
    private void collapseZeroArc(int arcId, int movingNode, int targetVertex) {
        ArcEdgePath path = embedding.pathByArc[arcId];
        List<Integer> channel = new ArrayList<>(path.copyVertexPath);
        releaseClaims(arcId, path);
        moveNode(movingNode, targetVertex, channel, arcId);
        embedding.pathByArc[arcId] = pointPath(arcId, targetVertex);
        zeroArcCollapsedCount++;
    }

    /**
     * Contract a zero arc whose endpoints already share the anchor vertex (a
     * loop produced by earlier collapses): release its claims and embed it on
     * the anchor point. The quantization separation test guarantees such a
     * loop bounds a disk, so contracting it is sound.
     *
     * @param arcId        zero arc to contract
     * @param anchorVertex cluster anchor vertex
     */
    private void contractLoopArc(int arcId, int anchorVertex) {
        ArcEdgePath path = embedding.pathByArc[arcId];
        TraceArc arc = motorcycleGraph.arcs.get(arcId);
        if (embedding.vertexIdByNode[arc.startNodeId] != anchorVertex
                || embedding.vertexIdByNode[arc.endNodeId] != anchorVertex) {
            throw new IllegalStateException(ZERO_ARC_PREFIX + arcId
                    + " expected to be a loop at the cluster anchor");
        }
        releaseClaims(arcId, path);
        embedding.pathByArc[arcId] = pointPath(arcId, anchorVertex);
        loopContractedCount++;
    }

    /**
     * Move a node's copy vertex onto the target, re-routing every incident
     * arc's path to end at the new position (LCBK19 §6.1 — "pulling its
     * incident arcs with it").
     *
     * @param nodeId       node to move
     * @param targetVertex vertex it moves onto
     * @param channel      released path of the collapsing arc, seeding the
     *                     re-route corridor
     * @param skipArcId    the collapsing arc itself (already released)
     */
    private void moveNode(int nodeId, int targetVertex, List<Integer> channel, int skipArcId) {
        int sourceVertex = embedding.vertexIdByNode[nodeId];
        if (sourceVertex == targetVertex) {
            return;
        }
        if (topology.ownerNodeByCopyVertex[sourceVertex] == nodeId) {
            topology.ownerNodeByCopyVertex[sourceVertex] = EmbeddedMeshTopology.UNCLAIMED;
        }
        for (int arcId : arcIdsByNode.get(nodeId)) {
            if (arcId == skipArcId) {
                continue;
            }
            ArcEdgePath path = embedding.pathByArc[arcId];
            TraceArc arc = motorcycleGraph.arcs.get(arcId);
            if (arc.startNodeId == arc.endNodeId) {
                if (embedding.quantization.quantizedLengthByArc[arcId] != 0) {
                    throw new IllegalStateException("moving node " + nodeId
                            + " carries positive loop arc " + arcId
                            + " — a loop cannot be dragged onto a point");
                }
                releaseClaims(arcId, path);
                embedding.pathByArc[arcId] = pointPath(arcId, targetVertex);
                continue;
            }
            if (path.copyVertexPath.size() == 1) {
                if (path.copyVertexPath.get(0) == targetVertex) {
                    continue;
                }
                throw new IllegalStateException(ARC_PREFIX + arcId
                        + " is a point at a non-anchor vertex while node " + nodeId + " moves");
            }
            rerouteTail(arcId, path, sourceVertex, targetVertex, channel);
        }
        embedding.vertexIdByNode[nodeId] = targetVertex;
        nodesMovedCount++;
    }

    /**
     * Re-route the end of an arc's path from the moved node's old vertex onto
     * the target, keeping a prefix of the old path and backing off to an earlier
     * prefix when the search cannot pass. The stored path keeps its
     * start-node-first orientation.
     *
     * @param arcId        arc whose path follows the moved node
     * @param path         current embedded path
     * @param sourceVertex the moved node's old vertex (a path endpoint)
     * @param targetVertex the moved node's new vertex
     * @param channel      released collapse-channel vertices
     */
    private void rerouteTail(int arcId, ArcEdgePath path, int sourceVertex, int targetVertex,
            List<Integer> channel) {
        List<Integer> vertices = new ArrayList<>(path.copyVertexPath);
        boolean reversed = vertices.get(0) == sourceVertex
                && vertices.get(vertices.size() - 1) != sourceVertex;
        if (reversed) {
            Collections.reverse(vertices);
        }
        if (vertices.get(vertices.size() - 1) != sourceVertex) {
            throw new IllegalStateException(ARC_PREFIX + arcId + " path does not end at the moved"
                    + " node's vertex " + sourceVertex);
        }
        releaseClaims(arcId, path);
        List<Vector3f> pull = positionsOf(vertices);
        pull.addAll(positionsOf(channel));
        for (int keep = vertices.size() - 2; keep >= 0; keep--) {
            List<Integer> prefix = new ArrayList<>(vertices.subList(0, keep + 1));
            List<Integer> prefixEdges = new ArrayList<>(keep);
            if (!rerouter.tryLegEdges(prefix, prefixEdges)) {
                backoffRetryCount++;
                continue;
            }
            ArcEdgePath prefixPath = new ArcEdgePath(arcId, prefix, prefixEdges);
            topology.claimPath(arcId, prefixPath);
            List<Integer> attempt = new ArrayList<>(prefix);
            Set<Integer> corridor = new HashSet<>(vertices);
            corridor.addAll(channel);
            corridor.add(targetVertex);
            if (rerouter.tryRoute(arcId, attempt, vertices.get(keep), targetVertex, corridor,
                    EmbeddedMeshTopology.UNCLAIMED, ArcRerouter.REFINE_ROUND_CAP)) {
                List<Integer> edges = new ArrayList<>(prefixEdges);
                rerouter.rebuildLegEdges(attempt, edges);
                if (reversed) {
                    Collections.reverse(attempt);
                    Collections.reverse(edges);
                }
                ArcEdgePath rerouted = new ArcEdgePath(arcId, attempt, edges);
                topology.claimPath(arcId, rerouted);
                embedding.pathByArc[arcId] = rerouted;
                tailRerouteCount++;
                return;
            }
            releaseClaims(arcId, prefixPath);
            backoffRetryCount++;
        }
        throw new IllegalStateException(ARC_PREFIX + arcId + " could not be re-routed onto vertex "
                + targetVertex + " from any back-off point of its old path"
                + rerouteDiagnostic(arcId, vertices, channel, targetVertex));
    }

    /**
     * Why a re-route had nowhere to go: the shape of the old path and collapse
     * channel, and how many free spokes each endpoint still has.
     *
     * @param arcId        arc that could not be re-routed
     * @param vertices     its old path, oriented to end at the moved node's vertex
     * @param channel      the collapsing zero arc's released path
     * @param targetVertex the vertex the arc had to reach
     * @return a diagnostic suffix for the failure message
     */
    private String rerouteDiagnostic(int arcId, List<Integer> vertices, List<Integer> channel,
            int targetVertex) {
        int sourceVertex = vertices.get(vertices.size() - 1);
        return String.format(
                " — path=%dv channel=%dv | freeSpokes source=%d/%d target=%d/%d"
                        + " | zeroArc=%b quantized=%d reached=%d corridor=%d",
                vertices.size(), channel.size(),
                freeSpokes(sourceVertex), topology.copy.vertexEdgeCount(sourceVertex),
                freeSpokes(targetVertex), topology.copy.vertexEdgeCount(targetVertex),
                embedding.quantization.quantizedLengthByArc[arcId] == 0,
                embedding.quantization.quantizedLengthByArc[arcId],
                rerouter.lastReachedCount, rerouter.lastCorridorSize);
    }

    /**
     * How many edges at a vertex carry no arc claim.
     *
     * @param vertexId copy vertex
     * @return count of unclaimed incident edges
     */
    private int freeSpokes(int vertexId) {
        int free = 0;
        for (int index = 0; index < topology.copy.vertexEdgeCount(vertexId); index++) {
            if (topology.ownerArcByCopyEdge[topology.copy.vertexEdgeAt(vertexId, index)]
                    == EmbeddedMeshTopology.UNCLAIMED) {
                free++;
            }
        }
        return free;
    }

    /**
     * Release an arc's claims on its path edges and interior vertices,
     * leaving claims held by other arcs untouched.
     *
     * @param arcId owning arc
     * @param path  its current path
     */
    private void releaseClaims(int arcId, ArcEdgePath path) {
        for (int edgeId : path.copyEdgePath) {
            if (topology.ownerArcByCopyEdge[edgeId] == arcId) {
                topology.ownerArcByCopyEdge[edgeId] = EmbeddedMeshTopology.UNCLAIMED;
            }
        }
        for (int index = 1; index < path.copyVertexPath.size() - 1; index++) {
            int vertex = path.copyVertexPath.get(index);
            if (topology.ownerArcByCopyVertex[vertex] == arcId) {
                topology.ownerArcByCopyVertex[vertex] = EmbeddedMeshTopology.UNCLAIMED;
            }
        }
    }

    /**
     * Positions of a vertex id list on the working copy.
     *
     * @param vertices copy vertex ids
     * @return their 3D positions in order
     */
    private List<Vector3f> positionsOf(List<Integer> vertices) {
        List<Vector3f> positions = new ArrayList<>(vertices.size());
        for (int vertex : vertices) {
            positions.add(topology.copy.vertexPosition(vertex, new Vector3f()));
        }
        return positions;
    }

    /**
     * A single-vertex point path.
     *
     * @param arcId  arc embedded on the point
     * @param vertex the point's copy vertex
     * @return the point path
     */
    private ArcEdgePath pointPath(int arcId, int vertex) {
        List<Integer> singleVertex = new ArrayList<>(1);
        singleVertex.add(vertex);
        return new ArcEdgePath(arcId, singleVertex, new ArrayList<>(0));
    }

    /**
     * Post-conditions of the contraction (LCBK19 Prop 6.1): every zero arc is
     * a point at its cluster's single vertex, every positive arc's path
     * connects its endpoint nodes' current vertices in start-to-end
     * orientation, and all cluster members share one vertex.
     */
    private void validate() {
        for (TraceArc arc : motorcycleGraph.arcs) {
            ArcEdgePath path = embedding.pathByArc[arc.arcId];
            if (path == null) {
                throw new IllegalStateException(ARC_PREFIX + arc.arcId
                        + " has no embedding after contraction");
            }
            int startVertex = embedding.vertexIdByNode[arc.startNodeId];
            int endVertex = embedding.vertexIdByNode[arc.endNodeId];
            if (embedding.quantization.quantizedLengthByArc[arc.arcId] == 0) {
                if (path.copyVertexPath.size() != 1
                        || path.copyVertexPath.get(0) != startVertex || startVertex != endVertex) {
                    throw new IllegalStateException(ZERO_ARC_PREFIX + arc.arcId
                            + " is not contracted onto its cluster vertex");
                }
            } else if (path.copyVertexPath.get(0) != startVertex
                    || path.copyVertexPath.get(path.copyVertexPath.size() - 1) != endVertex) {
                throw new IllegalStateException("positive arc " + arc.arcId
                        + " path endpoints disagree with its nodes' vertices");
            }
        }
        for (int node = 0; node < motorcycleGraph.nodes.size(); node++) {
            int vertex = embedding.vertexIdByNode[node];
            if (vertex == EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            int clusterAnchorVertex = embedding.vertexIdByNode[node];
            for (int other = 0; other < motorcycleGraph.nodes.size(); other++) {
                if (extraction.clusterByNode[other] == extraction.clusterByNode[node]
                        && embedding.vertexIdByNode[other] != EmbeddedMeshTopology.UNCLAIMED
                        && embedding.vertexIdByNode[other] != clusterAnchorVertex) {
                    throw new IllegalStateException("cluster " + extraction.clusterByNode[node]
                            + " nodes sit on distinct vertices after contraction");
                }
            }
        }
    }
}
