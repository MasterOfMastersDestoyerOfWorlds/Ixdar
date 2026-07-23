package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Operator (1), the zero-arc collapse: one endpoint node is embedded onto the other,
 * dragging its incident arcs with it.
 *
 * <p>An endpoint may move when non-critical and either a non-border node or on a border
 * arc; with neither movable this throws. A loop is exempt.
 *
 * <p>See also: LCBK19 Def 6.2
 */
public final class ZeroArcCollapseOperator {

    public final EmbeddedTMesh tmesh;
    public final ArcRerouter rerouter;

    public int collapsedCount;

    /**
     * Stores the T-mesh to operate on and builds the re-router over its working copy.
     *
     * @param tmesh embedded T-mesh whose zero arcs are collapsed
     */
    public ZeroArcCollapseOperator(EmbeddedTMesh tmesh) {
        this.tmesh = tmesh;
        this.rerouter = new ArcRerouter(tmesh.topology);
    }

    /**
     * The id of a live, collapsible zero arc, or {@link EmbeddedTMesh#NONE} when none
     * remains — the driver's "is operator (1) applicable" test.
     *
     * @return a collapsible zero arc id, or {@link EmbeddedTMesh#NONE}
     */
    public int nextCollapsibleArc() {
        for (EmbeddedArc arc : tmesh.arcs) {
            if (arc.alive && arc.quantizedLength == 0 && movingEndpoint(arc) != EmbeddedTMesh.NONE) {
                return arc.arcId;
            }
        }
        return EmbeddedTMesh.NONE;
    }

    /**
     * Collapses one zero arc: moves its movable node onto the other, dragging every other
     * incident arc along, embeds the arc onto that point, and retires the moved node and the
     * arc. The T-mesh loses one node and one arc together, so its Euler characteristic is
     * unchanged.
     *
     * @param arcId zero arc to collapse
     * @throws IllegalStateException when the arc is not a collapsible zero arc
     */
    public void collapse(int arcId) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        if (!arc.alive || arc.quantizedLength != 0) {
            throw new IllegalStateException(EmbeddedTMesh.NONE == arcId ? "no arc" : "arc " + arcId
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
                        ? channel.get(channel.size() - 2) : channel.get(1);

        Map<Integer, Set<Integer>> regionByArc = new HashMap<>();
        for (int incidentArcId : tmesh.arcEndsByNode.get(movedNodeId)) {
            if (incidentArcId == arcId || !tmesh.arcs.get(incidentArcId).alive
                    || regionByArc.containsKey(incidentArcId)) {
                continue;
            }
            Set<Integer> region = tmesh.arcSideRegionVertices(
                    tmesh.arcs.get(incidentArcId).path.copyVertexPath, channel);
            region.addAll(channel);
            region.add(targetVertex);
            region.add(movedVertex);
            regionByArc.put(incidentArcId, region);
        }

        tmesh.setPath(arcId, List.of(targetVertex));
        for (int incidentArcId : incidentArcsInFanOrder(movedVertex, channelNeighbor, arcId,
                movedNodeId)) {
            if (!tmesh.arcs.get(incidentArcId).alive) {
                continue;
            }
            tmesh.dragArcEndOntoVertex(incidentArcId, movedVertex, targetVertex, rerouter, channel,
                    regionByArc.get(incidentArcId));
        }

        tmesh.mergeNodeInto(survivingNodeId, movedNodeId);
        tmesh.removeCollapsedArc(arcId, survivingNodeId != movedNodeId);
        collapsedCount++;
    }

    /**
     * The pivot's incident arcs in cyclic fan order, starting from the spoke adjacent to the
     * collapsing arc's channel, so a dragged arc cannot fence in a later one.
     *
     * <p>Rotates the copy mesh's half-edges around the pivot rather than reading
     * {@code arcEndsByNode}; arcs the rotation misses are appended.
     *
     * @param pivotVertex     the collapsing node's copy vertex
     * @param channelNeighbor the channel vertex adjacent to the pivot, whose spoke starts the fan
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
     * <p>A loop is always collapsible: both ends already sit on one point, so nothing moves.
     * {@link #isCollapsibleFrom} would interrogate that node twice and refuse every loop on a
     * critical one.
     *
     * @param arc zero arc to test
     * @return the movable node's id, preferring the one with fewer incident arcs and the
     *         lower id, the single node when the arc is a loop, or {@link EmbeddedTMesh#NONE}
     *         when both endpoints are fixed
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
     * Whether a zero arc is collapsible in the direction that moves the given node, per
     * LCBK19 Def 6.2: the node must be non-critical, and either the arc is a border arc or
     * the node is not a border node.
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
