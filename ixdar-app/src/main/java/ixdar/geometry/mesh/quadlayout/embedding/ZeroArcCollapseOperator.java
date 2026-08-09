package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.ActiveIdSet;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.ArcEdgePath;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;

/**
 * Operator (1), the zero-arc collapse: one endpoint node is embedded onto the
 * other, dragging its incident arcs with it.
 *
 * <p>
 * An endpoint may move when non-critical and either a non-border node or on a
 * border arc; with neither movable this throws. A loop is exempt.
 *
 * <p>
 * See also: LCBK19 Def 6.2
 */
public final class ZeroArcCollapseOperator {

    /** Starting capacity of the zero-arc candidate list; grows by doubling. */
    private static final int CANDIDATE_INITIAL_CAPACITY = 256;

    public final EmbeddedTMesh tmesh;
    public final ArcRerouter rerouter;

    public int collapsedCount;

    /**
     * First arc id the collapsible scan starts at. Only ever advanced past dead or
     * non-zero arcs, which can never become collapsible ({@code alive} is set only
     * in the constructor and {@code quantizedLength} never changes), so the scan
     * skips the growing retired prefix without missing a candidate.
     */
    public int collapsibleScanStart;

    /** Live zero arcs still worth testing, compacted as arcs die. */
    public int[] zeroArcCandidates = new int[0];

    /** Live entry count of {@link #zeroArcCandidates}. */
    public int zeroArcCandidateCount;

    /** Arc-list size already swept for new zero arcs; the split operator adds more. */
    public int scannedArcBound;

    /**
     * Stores the T-mesh to operate on and builds the re-router over its working
     * copy.
     *
     * @param tmesh embedded T-mesh whose zero arcs are collapsed
     */
    public ZeroArcCollapseOperator(EmbeddedTMesh tmesh) {
        this.tmesh = tmesh;
        this.rerouter = new ArcRerouter(tmesh.topology);
    }

    /**
     * The collapsible zero arc whose node has the most arcs on it, so crowded fans clear
     * while the mesh is still coarse. Ties keep the lowest arc id.
     *
     * <p>
     * Only zero arcs qualify and {@code alive} never returns, so candidates are appended
     * once per new arc and compacted as arcs die.
     *
     * @return the chosen zero arc id, or {@link EmbeddedTMesh#NONE} when none remains
     */
    public int mostContendedArc() {
        for (int arcId = scannedArcBound; arcId < tmesh.arcs.size(); arcId++) {
            if (tmesh.arcs.get(arcId).quantizedLength != 0) {
                continue;
            }
            if (zeroArcCandidateCount == zeroArcCandidates.length) {
                zeroArcCandidates = Arrays.copyOf(zeroArcCandidates,
                        Math.max(CANDIDATE_INITIAL_CAPACITY, zeroArcCandidateCount * 2));
            }
            zeroArcCandidates[zeroArcCandidateCount++] = arcId;
        }
        scannedArcBound = tmesh.arcs.size();

        int found = EmbeddedTMesh.NONE;
        int bestValence = 0;
        int keep = 0;
        for (int index = 0; index < zeroArcCandidateCount; index++) {
            int arcId = zeroArcCandidates[index];
            EmbeddedArc arc = tmesh.arcs.get(arcId);
            if (!arc.alive) {
                continue;
            }
            zeroArcCandidates[keep++] = arcId;
            int movedNodeId = movingEndpoint(arc);
            if (movedNodeId == EmbeddedTMesh.NONE) {
                continue;
            }
            int valence = tmesh.arcEndsByNode.get(movedNodeId).size();
            if (found == EmbeddedTMesh.NONE || valence > bestValence) {
                found = arcId;
                bestValence = valence;
            }
        }
        zeroArcCandidateCount = keep;
        return found;
    }

    /**
     * Collapses one zero arc: moves its movable node onto the other, dragging every
     * other incident arc along, embeds the arc onto that point, and retires the
     * moved node and the arc. The T-mesh loses one node and one arc together, so
     * its Euler characteristic is unchanged.
     *
     * @param arcId zero arc to collapse
     * @throws IllegalStateException when the arc is not a collapsible zero arc
     */
    public void collapse(int arcId) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        if (!arc.alive || arc.quantizedLength != 0) {
            throw new IllegalStateException(EmbeddedTMesh.NONE == arcId ? "no arc"
                    : "arc " + arcId
                            + " is not a live zero arc");
        }
        int movedNodeId = movingEndpoint(arc);
        if (movedNodeId == EmbeddedTMesh.NONE) {
            throw new IllegalStateException("zero arc " + arcId + " is not collapsible: both of its"
                    + " nodes " + arc.startNodeId + " and " + arc.endNodeId + " are critical, so the"
                    + " quantization has placed two prescribed points at zero distance");
        }
        int survivingNodeId = arc.otherNode(movedNodeId);
        int movedVertex = tmesh.nodes.get(movedNodeId).copyVertex;
        int targetVertex = tmesh.nodes.get(survivingNodeId).copyVertex;
        List<Integer> channel = new ArrayList<>(arc.path.copyVertexPath);
        int channelNeighbor = channel.size() < 2 ? EmbeddedTMesh.NONE
                : channel.get(channel.size() - 1) == movedVertex
                        ? channel.get(channel.size() - 2)
                        : channel.get(1);

        tmesh.setPath(arcId, List.of(targetVertex));
        for (int incidentArcId : incidentArcsInFanOrder(movedVertex, channelNeighbor, arcId,
                movedNodeId)) {
            EmbeddedArc incidentArc = tmesh.arcs.get(incidentArcId);
            if (!incidentArc.alive) {
                continue;
            }
            dragArcEndOntoVertex(incidentArcId, movedVertex, targetVertex, rerouter, channel);
            if (incidentArc.isLoop() && incidentArc.startNodeId == movedNodeId) {
                dragArcEndOntoVertex(incidentArcId, movedVertex, targetVertex, rerouter, channel);
            }
        }

        tmesh.mergeNodeInto(survivingNodeId, movedNodeId);
        tmesh.removeCollapsedArc(arcId, survivingNodeId != movedNodeId);
        collapsedCount++;
    }

    /**
     * Re-routes the dragged end of an arc onto its moving node's new vertex.
     *
     * <p>
     * Only the tail past the longest still-reaching prefix is re-routed, or the
     * wrong patches separate. See also: LCBK19 Section 6.1
     *
     * @param arcId        arc whose end is being dragged
     * @param movedVertex  the moving node's old copy vertex, an endpoint of the
     *                     arc's path
     * @param targetVertex the moving node's new copy vertex
     * @param rerouter     the claims-respecting router
     * @param channel      the collapsing arc's released path vertices, opening the
     *                     pivot spoke
     * @throws IllegalStateException when the arc's path does not end at the moved
     *                               vertex
     */
    public void dragArcEndOntoVertex(int arcId, int movedVertex, int targetVertex,
            ArcRerouter rerouter, List<Integer> channel) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        List<Integer> vertices = new ArrayList<>(arc.path.copyVertexPath);
        if (vertices.size() == 1) {
            if (vertices.get(0) == targetVertex) {
                return;
            }
            if (vertices.get(0) == movedVertex) {
                tmesh.setPath(arcId, List.of(targetVertex));
                return;
            }
            throw new IllegalStateException("arc " + arcId + " is embedded as the point "
                    + vertices.get(0) + ", which is neither the moving node's vertex "
                    + movedVertex + " nor its target " + targetVertex
                    + "; a point-embedded arc must sit on the node it belongs to");
        }
        boolean reversed = vertices.get(0) == movedVertex;
        if (reversed) {
            Collections.reverse(vertices);
        }
        if (vertices.get(vertices.size() - 1) != movedVertex) {
            throw new IllegalStateException("arc " + arcId + " path does not end at the moved node's"
                    + " vertex " + movedVertex);
        }
        tmesh.releaseClaims(arc.path);
        for (int passThrough : new int[] { EmbeddedMeshTopology.UNCLAIMED, movedVertex }) {
            rerouter.clearFailureMemory();
            // keep >= 1 preserves the arc's first edge at the fixed far node, so a shortest
            // reroute
            // cannot leave the node in a wrong angular sector and swap the cyclic arc order
            // — a tear
            // with no arc crossing that LCBK19's no-cross/no-touch does not prevent. keep
            // == 0
            // (rerouting from the node itself) is the last resort. See LCBK19 Section 6.1.
            for (int keepRank = 0; keepRank <= vertices.size() - 2; keepRank++) {
                int keep = keepRank < vertices.size() - 2 ? keepRank + 1 : 0;
                // keep == 0 claims a SHORTER prefix than the failed attempts, so the
                // unreachability proof does not transfer to the last resort.
                if (keep != 0 && rerouter.settledInExhaustedFailure(vertices.get(keep))) {
                    continue;
                }
                List<Integer> prefix = new ArrayList<>(vertices.subList(0, keep + 1));
                List<Integer> prefixEdges = new ArrayList<>(keep);
                if (!rerouter.tryLegEdges(prefix, prefixEdges)) {
                    continue;
                }
                ArcEdgePath prefixPath = new ArcEdgePath(arcId, prefix, prefixEdges);
                tmesh.topology.claimPath(arcId, prefixPath);
                List<Integer> attempt = new ArrayList<>(prefix);
                ActiveIdSet corridor = rerouter.freshCorridor();
                if (rerouter.tryRoute(arcId, attempt, vertices.get(keep), targetVertex, corridor,
                        passThrough)) {
                    List<Integer> edges = new ArrayList<>(prefixEdges);
                    rerouter.rebuildLegEdges(attempt, edges);
                    if (reversed) {
                        Collections.reverse(attempt);
                        Collections.reverse(edges);
                    }
                    arc.path = new ArcEdgePath(arcId, attempt, edges);
                    tmesh.topology.claimPath(arcId, arc.path);
                    return;
                }
                tmesh.releaseClaims(prefixPath);
            }
        }

        throw new IllegalStateException("arc " + arcId + " could not be re-routed onto vertex "
                + targetVertex + " from any back-off point of its old path; moved vertex "
                + movedVertex + " path " + vertices + " channel " + channel
                + fanReport(movedVertex) + fanReport(targetVertex));
    }

    /**
     * Neighborhood report of a blocked drag endpoint: its incident faces with
     * corner owners, for seal diagnostics.
     *
     * @param vertexId blocked endpoint
     * @return a multi-line diagnostic block
     */
    private String fanReport(int vertexId) {
        EmbeddedMeshTopology topology = tmesh.topology;
        StringBuilder detail = new StringBuilder("\n vertex ").append(vertexId)
                .append(" ownerNode ").append(topology.ownerNodeByCopyVertex[vertexId])
                .append(" ownerArc ").append(topology.ownerArcByCopyVertex[vertexId]);
        for (int index = 0; index < topology.copy.vertexFaceCount(vertexId); index++) {
            int faceId = topology.copy.vertexFaceAt(vertexId, index);
            detail.append("\n  face ").append(faceId).append(" corners");
            for (int corner = 0; corner < 3; corner++) {
                int cornerVertex = topology.copy.faceVertexAt(faceId, corner);
                detail.append(' ').append(cornerVertex)
                        .append("(n").append(topology.ownerNodeByCopyVertex[cornerVertex])
                        .append(",a").append(topology.ownerArcByCopyVertex[cornerVertex])
                        .append(')');
            }
        }
        return detail.toString();
    }

    /**
     * The pivot's incident arcs in cyclic fan order, starting from the spoke
     * adjacent to the collapsing arc's channel, so a dragged arc cannot fence in a
     * later one.
     *
     * <p>
     * Rotates the copy mesh's half-edges around the pivot rather than reading
     * {@code arcEndsByNode}; arcs the rotation misses are appended.
     *
     * @param pivotVertex     the collapsing node's copy vertex
     * @param channelNeighbor the channel vertex adjacent to the pivot, whose spoke
     *                        starts the fan
     * @param collapsingArcId the arc being collapsed, excluded from the fan
     * @param movedNodeId     the collapsing node id, for its full incident-arc set
     * @return the incident arcs (excluding the collapsing arc) in fan order
     */
    private List<Integer> incidentArcsInFanOrder(int pivotVertex, int channelNeighbor,
            int collapsingArcId, int movedNodeId) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        int rotationCap = copy.vertexEdgeCount(pivotVertex) + 2;
        int startHalfEdge = copy.vertexOutgoingHalfEdge(pivotVertex);
        int probe = startHalfEdge;
        for (int step = 0; step < rotationCap && probe >= 0; step++) {
            if (copy.halfEdgeEndVertex(probe) == channelNeighbor) {
                startHalfEdge = probe;
                break;
            }
            probe = copy.halfEdgeTwin(copy.halfEdgePrev(probe));
        }
        List<Integer> ordered = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int halfEdge = startHalfEdge;
        for (int step = 0; step < rotationCap && halfEdge >= 0; step++) {
            int owner = tmesh.topology.ownerArcByCopyEdge[copy.halfEdgeEdge(halfEdge)];
            if (owner != EmbeddedMeshTopology.UNCLAIMED && owner != collapsingArcId
                    && tmesh.arcs.get(owner).alive && seen.add(owner)) {
                ordered.add(owner);
            }
            halfEdge = copy.halfEdgeTwin(copy.halfEdgePrev(halfEdge));
            if (halfEdge == startHalfEdge) {
                break;
            }
        }
        for (int incidentArcId : tmesh.arcEndsByNode.get(movedNodeId)) {
            if (incidentArcId != collapsingArcId && tmesh.arcs.get(incidentArcId).alive
                    && seen.add(incidentArcId)) {
                ordered.add(incidentArcId);
            }
        }
        return ordered;
    }

    /**
     * The endpoint of a zero arc that LCBK19 Def 6.2 permits to move, or
     * {@link EmbeddedTMesh#NONE} when neither may.
     *
     * <p>
     * A loop is always collapsible: both ends already sit on one point, so nothing
     * moves. {@link #isCollapsibleFrom} would interrogate that node twice and
     * refuse every loop on a critical one.
     *
     * @param arc zero arc to test
     * @return the movable node's id, preferring the one with fewer incident arcs
     *         and the lower id, the single node when the arc is a loop, or
     *         {@link EmbeddedTMesh#NONE} when both endpoints are fixed
     */
    private int movingEndpoint(EmbeddedArc arc) {
        if (arc.isLoop()) {
            return arc.startNodeId;
        }
        boolean startMovable = isCollapsibleFrom(arc, arc.startNodeId);
        boolean endMovable = isCollapsibleFrom(arc, arc.endNodeId);
        if (!startMovable && !endMovable) {
            return EmbeddedTMesh.NONE;
        }
        if (startMovable != endMovable) {
            return startMovable ? arc.startNodeId : arc.endNodeId;
        }
        int startDegree = tmesh.degree(arc.startNodeId);
        int endDegree = tmesh.degree(arc.endNodeId);
        if (startDegree != endDegree) {
            return startDegree < endDegree ? arc.startNodeId : arc.endNodeId;
        }
        return Math.min(arc.startNodeId, arc.endNodeId);
    }

    /**
     * Whether a zero arc is collapsible in the direction that moves the given node,
     * per LCBK19 Def 6.2: the node must be non-critical, and either the arc is a
     * border arc or the node is not a border node.
     *
     * @param arc    zero arc
     * @param nodeId endpoint that would move
     * @return true when moving that node is permitted
     */
    private boolean isCollapsibleFrom(EmbeddedArc arc, int nodeId) {
        EmbeddedNode node = tmesh.nodes.get(nodeId);
        return !node.critical && (arc.feature || !node.border);
    }
}
