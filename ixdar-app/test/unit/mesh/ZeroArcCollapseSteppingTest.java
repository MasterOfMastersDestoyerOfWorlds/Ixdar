package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

/**
 * The resumable collapse API must be the one-shot collapse cut at drag boundaries: stepping
 * {@code beginCollapse}/{@code dragNextArc}/{@code finishCollapse} on the torus fixture leaves
 * the same live elements as {@code collapse}, and the result still validates.
 */
class ZeroArcCollapseSteppingTest {

    @Test
    void steppedCollapseMatchesOneShotAndValidates() {
        NodeGraphRuntime stepped = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork steppedNet = (ArcNetwork) stepped.lastOutput("net");
        steppedNet.labelPatchCovers();
        ZeroArcCollapseOperator steppedCollapse = new NetworkContraction(steppedNet).collapseArc;
        int steppedArcId = steppedCollapse.mostContendedArc();
        assertNotEquals(ArcNetwork.NONE, steppedArcId,
                "the zero row must offer a collapsible arc");
        steppedCollapse.beginCollapse(steppedArcId);
        while (steppedCollapse.dragNextArc()) {
            continue;
        }
        steppedCollapse.finishCollapse();
        steppedNet.validate();

        NodeGraphRuntime oneShot = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork oneShotNet = (ArcNetwork) oneShot.lastOutput("net");
        oneShotNet.labelPatchCovers();
        ZeroArcCollapseOperator oneShotCollapse = new NetworkContraction(oneShotNet).collapseArc;
        int oneShotArcId = oneShotCollapse.mostContendedArc();
        assertEquals(steppedArcId, oneShotArcId, "both runs must pick the same arc");
        oneShotCollapse.collapse(oneShotArcId);
        oneShotNet.validate();

        assertEquals(liveCounts(oneShotNet), liveCounts(steppedNet),
                "stepping the collapse must leave the same live element counts as one shot");
    }

    /**
     * The live node, arc and patch counts of a T-mesh, as comparable text.
     *
     * @param tmesh T-mesh to count
     * @return {@code nodes/arcs/patches} counts
     */
    private static String liveCounts(ArcNetwork tmesh) {
        int nodes = 0;
        for (int nodeId = 0; nodeId < tmesh.nodes.size(); nodeId++) {
            nodes += tmesh.nodes.get(nodeId).alive ? 1 : 0;
        }
        int arcs = 0;
        for (EmbeddedArc arc : tmesh.arcs) {
            arcs += arc.alive ? 1 : 0;
        }
        int patches = 0;
        for (EmbeddedPatch patch : tmesh.patches) {
            patches += patch.alive ? 1 : 0;
        }
        return nodes + "/" + arcs + "/" + patches;
    }
}
