package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

/**
 * LCK21a §6's T-junction extension on the Figure-9 torus: <em>"we iteratively extend all
 * T-junctions to the opposite sides of a patch, connecting opposing T-junctions if the quantization
 * matches or splitting the corresponding opposite arc if not"</em>.
 *
 * <p>The fixture's stub vertical at major 2 is exactly such a T-junction — it reaches the middle
 * loop and stops — so contracting the torus leaves one and conforming must consume it. The torus
 * also makes the termination question real: its rows wrap, so an extension chain walks a closed
 * quad strip and has to land on a node rather than spin.
 */
class TJunctionExtensionTest {

    /** A torus is genus 1, so V - E + F is zero for any cell decomposition of it. */
    private static final int TORUS_EULER_CHARACTERISTIC = 0;

    /**
     * Conforming the contracted torus leaves no patch with a T-junction, keeps the T-mesh a cell
     * decomposition, and leaves every patch a rectangle — the three post-conditions the rest of
     * the pipeline reads as given.
     */
    @Test
    void conformingLeavesNoTJunction() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        contractRoundsOnly(fixtureNet);
        int tjunctionsBefore = tjunctionCount(fixtureNet);

        new NetworkContraction(fixtureNet).conform();

        assertTrue(tjunctionsBefore > 0,
                "the contracted torus should still carry the stub vertical's T-junction");
        assertEquals(0, tjunctionCount(fixtureNet),
                "conforming must extend every T-junction across its patch");
        assertEquals(TORUS_EULER_CHARACTERISTIC,
                eulerCharacteristic(fixtureNet),
                "an extension adds one node, two arcs and one patch, so V - E + F is unchanged");
    }

    /**
     * Every extension splits one patch, so the layout gains exactly one patch per inserted arc and
     * never more patches than the quantization has quads.
     */
    @Test
    void extensionCountMatchesThePatchesGained() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        contractRoundsOnly(fixtureNet);
        int patchesBefore = livePatchCount(fixtureNet);

        NetworkContraction contraction = new NetworkContraction(fixtureNet);
        contraction.conform();

        assertEquals(patchesBefore + contraction.extendTJunction.extensionCount,
                livePatchCount(fixtureNet),
                "each extension arc cuts exactly one patch in two");
        assertTrue(livePatchCount(fixtureNet) <= quantizedArea(fixtureNet),
                "a conforming patch covers at least one quad, so the layout cannot have more"
                        + " patches than the quantization has quads");
    }

    /**
     * Runs the {@code tmesh_contract} node's contraction rounds in place, with neither the recarve
     * nor the T-junction extension, so the stub's T-junction survives for {@code conform()} to
     * consume.
     *
     * @param tmesh T-mesh to contract in place
     */
    private void contractRoundsOnly(ArcNetwork tmesh) {
        new MapNodeContext(new NetworkContraction())
                .with(NetworkContraction.TMESH, tmesh)
                .with(NetworkContraction.CONFORM, Boolean.FALSE)
                .with(NetworkContraction.RECARVE, Boolean.FALSE)
                .eval();
    }

    /**
     * The number of interior side nodes carrying a third arc, over all live patches.
     *
     * @param tmesh T-mesh to scan
     * @return the count of T-junctions
     */
    private int tjunctionCount(ArcNetwork tmesh) {
        int count = 0;
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                List<Integer> sideNodes = patch.sideNodeIds.get(side);
                for (int index = 1; index < sideNodes.size() - 1; index++) {
                    if (tmesh.degree(sideNodes.get(index)) > 2) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * The T-mesh's {@code V - E + F} over its live elements.
     *
     * @param tmesh T-mesh to measure
     * @return the Euler characteristic
     */
    private int eulerCharacteristic(ArcNetwork tmesh) {
        int nodes = 0;
        for (int nodeId = 0; nodeId < tmesh.nodes.size(); nodeId++) {
            nodes += tmesh.nodes.get(nodeId).alive ? 1 : 0;
        }
        int arcs = 0;
        for (int arcId = 0; arcId < tmesh.arcs.size(); arcId++) {
            arcs += tmesh.arcs.get(arcId).alive ? 1 : 0;
        }
        return nodes - arcs + livePatchCount(tmesh);
    }

    /**
     * The number of patches still in the T-mesh.
     *
     * @param tmesh T-mesh to measure
     * @return the live patch count
     */
    private int livePatchCount(ArcNetwork tmesh) {
        int live = 0;
        for (EmbeddedPatch patch : tmesh.patches) {
            live += patch.alive ? 1 : 0;
        }
        return live;
    }

    /**
     * The layout's total quantized area, the sum over live patches of their two side lengths
     * multiplied.
     *
     * @param tmesh T-mesh to measure
     * @return the number of quads the quantization prescribes
     */
    private int quantizedArea(ArcNetwork tmesh) {
        int area = 0;
        for (EmbeddedPatch patch : tmesh.patches) {
            if (patch.alive) {
                area += tmesh.sideQuantizedLength(patch.patchId, 0)
                        * tmesh.sideQuantizedLength(patch.patchId, 1);
            }
        }
        return area;
    }
}
