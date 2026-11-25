package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.model.EmbeddedMeshTopology;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * Crawfish IMG_4114's blocked drag: the survivor's only spoke is the zero arc, so the first
 * dragged arc may arrive on either side of it, and every later arrival corner must be read
 * relative to that first arc. LCBK19 Section 6.1, operator (1).
 */
class SiblingPairCollapseFixtureTest {

    private static final String DSL_PATH = "dsl/fixtures/sibling_pair_collapse.dsl";

    private static final String NETWORK_OUTPUT = "net";

    private static final String SURVIVOR_NODE = "survivorNodeId";

    private static final String MOVED_NODE = "movedNodeId";

    private static final String ZERO_ARC = "zeroArcId";

    private static final String NORTH_SIBLING = "northSiblingArcId";

    @Test
    void fixtureGivesTheSurvivorNoSpokeButTheZeroArc() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork tmesh = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);

        assertEquals(1, tmesh.degree(fixture.intOutput(SURVIVOR_NODE)),
                "the zero arc is the survivor's one spoke");
        assertEquals(Set.of(fixture.intOutput(NORTH_SIBLING),
                fixture.intOutput("westArcId"), fixture.intOutput("southSiblingArcId")),
                Set.copyOf(canonicalRotation(spokeOwners(tmesh, nodeVertex(tmesh,
                        fixture.intOutput(MOVED_NODE))), fixture.intOutput(ZERO_ARC),
                        fixture.intOutput(NORTH_SIBLING))),
                "the moving node carries the two siblings and the west arc beside the zero arc");
        assertEquals(fixture.intOutput(SURVIVOR_NODE),
                tmesh.arcs.get(fixture.intOutput(ZERO_ARC)).endNodeId,
                "the zero arc runs onto the survivor");
    }

    /**
     * The regression: on master the second sibling's arrival corner was the wedge the first
     * one had just closed off, and the drag threw "could not be re-embedded".
     */
    @Test
    void collapseKeepsTheMovingNodesRotationAtTheSurvivor() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork tmesh = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);
        int zeroArc = fixture.intOutput(ZERO_ARC);
        int northSibling = fixture.intOutput(NORTH_SIBLING);
        List<Integer> rotationBefore = canonicalRotation(spokeOwners(tmesh,
                nodeVertex(tmesh, fixture.intOutput(MOVED_NODE))), zeroArc, northSibling);
        ZeroArcCollapseOperator collapse = new NetworkContraction(tmesh).collapseArc;

        collapse.collapse(zeroArc);

        assertEquals(0, collapse.blockedDragCount, "no drag blocks");
        assertEquals(rotationBefore, canonicalRotation(spokeOwners(tmesh,
                nodeVertex(tmesh, fixture.intOutput(SURVIVOR_NODE))), zeroArc, northSibling),
                "the survivor carries the moving node's arcs in the moving node's cyclic order");
    }

    /**
     * The copy vertex a node stands on.
     *
     * @param tmesh  network holding the node
     * @param nodeId node to read
     * @return its copy vertex
     */
    private static int nodeVertex(ArcNetwork tmesh, int nodeId) {
        return tmesh.nodes.get(nodeId).copyVertex;
    }

    /**
     * The arcs holding the spokes of a vertex, in rotation order, one entry per spoke.
     *
     * @param tmesh  network whose claims are read
     * @param vertex copy vertex to rotate around
     * @return owner arc per claimed spoke
     */
    private static List<Integer> spokeOwners(ArcNetwork tmesh, int vertex) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        List<Integer> owners = new ArrayList<>();
        int start = copy.vertexOutgoingHalfEdge(vertex);
        int halfEdge = start;
        do {
            int owner = tmesh.topology.ownerArcByCopyEdge()[copy.halfEdgeEdge(halfEdge)];
            if (owner != EmbeddedMeshTopology.UNCLAIMED) {
                owners.add(owner);
            }
            halfEdge = copy.halfEdgeTwin(copy.halfEdgePrev(halfEdge));
        } while (halfEdge >= 0 && halfEdge != start);
        return owners;
    }

    /**
     * A cyclic order with one arc dropped, rotated to start at another, so two rotations
     * compare as lists.
     *
     * @param owners  cyclic order of spoke owners
     * @param dropped arc to leave out
     * @param first   arc to start at
     * @return the rotated list
     */
    private static List<Integer> canonicalRotation(List<Integer> owners, int dropped, int first) {
        List<Integer> kept = new ArrayList<>(owners);
        kept.removeIf(owner -> owner == dropped);
        Collections.rotate(kept, -kept.indexOf(first));
        return kept;
    }
}
