package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * LCBK19 §6.1 operator (1), the zero-arc collapse, on the embedded T-mesh.
 *
 * <p>The paper: <em>"Let a be an arc collapsible from n0 to n1. Its collapse changes the
 * T-mesh embedding: n0 is embedded onto n1, pulling its incident arcs with it (their
 * embedding path is adjusted such that they connect to n0 at its new position). Arc a is
 * embedded onto a single point (coincident with the nodes n0 and n1). If n0 now lies at a
 * critical point, it is subsequently considered critical."</em>
 *
 * <p>Collapsibility is LCBK19 Def 6.2: a zero-arc {@code a} is collapsible in the direction
 * {@code n0 → n1} iff {@code n0} is non-critical, and either {@code a} is a border arc or
 * {@code n0} is a non-border node. Critical nodes hold prescribed integer positions and are
 * never moved; that restriction is what guarantees singularities and feature points stay
 * where the quantization put them. On a closed surface no node is a border node, so the
 * condition reduces to "the moving node is non-critical", but the border clause is honoured
 * so the same operator serves the trimmed case.
 *
 * <p>When both endpoints are movable the paper does not say which to move; this moves the
 * one with fewer incident arcs (a cheaper re-route), breaking ties toward the lower node id
 * so the choice is deterministic. When neither endpoint is movable — a zero arc between two
 * critical nodes — the quantization has placed two prescribed points at zero distance, which
 * its separation test is meant to forbid, so this throws rather than silently accepting it.
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

        for (int incidentArcId : new ArrayList<>(tmesh.arcEndsByNode.get(movedNodeId))) {
            if (incidentArcId == arcId || !tmesh.arcs.get(incidentArcId).alive) {
                continue;
            }
            tmesh.dragArcEndOntoVertex(incidentArcId, movedVertex, targetVertex, rerouter, channel);
        }

        tmesh.setPath(arcId, List.of(targetVertex));
        tmesh.mergeNodeInto(survivingNodeId, movedNodeId);
        tmesh.removeCollapsedArc(arcId);
        collapsedCount++;
    }

    /**
     * The endpoint of a zero arc that LCBK19 Def 6.2 permits to move, or
     * {@link EmbeddedTMesh#NONE} when neither may.
     *
     * @param arc zero arc to test
     * @return the movable node's id, preferring the one with fewer incident arcs and the
     *         lower id, or {@link EmbeddedTMesh#NONE} when both endpoints are fixed
     */
    private int movingEndpoint(EmbeddedArc arc) {
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
