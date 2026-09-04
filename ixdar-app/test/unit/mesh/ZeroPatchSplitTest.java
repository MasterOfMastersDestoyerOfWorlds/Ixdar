package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroPatchSplitOperator;

/**
 * LCBK19 §6.1 operator (2) on the Figure-9 torus fixture: splitting the one non-simple
 * zero-patch must extend its T-joint across to the opposite side and cut the patch into two
 * simple zero-patches — Figure 9(d)→(e) — while leaving the surface's decomposition intact.
 *
 * <p>The fixture has exactly one non-simple zero-patch (the middle-row patch over majors 0→4,
 * where the stub vertical at major 2 puts a T-joint on the bottom side against one arc on
 * top). After the split there must be no non-simple zero-patch left, and the Euler
 * characteristic — one node and two arcs and one patch all gained together — must stay zero.
 */
class ZeroPatchSplitTest {

    @Test
    void splittingTheNonSimpleZeroPatchYieldsTwoSimpleOnes() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        ZeroPatchSplitOperator operator = new ZeroPatchSplitOperator(fixtureNet);

        int patchId = operator.nextNonSimpleZeroPatch();
        assertNotEquals(ArcNetwork.NONE, patchId, "the fixture has one non-simple zero-patch");
        int liveArcsBefore = countLiveArcs(fixtureNet);
        int livePatchesBefore = countLivePatches(fixtureNet);

        operator.split(patchId);

        fixtureNet.validate();
        assertEquals(ArcNetwork.NONE, operator.nextNonSimpleZeroPatch(),
                "the two halves must both be simple zero-patches");
        assertEquals(liveArcsBefore + 2, countLiveArcs(fixtureNet),
                "the opposite arc splits in two and a new zero-arc is added");
        assertEquals(livePatchesBefore + 1, countLivePatches(fixtureNet),
                "the patch is cut into two");
    }

    /**
     * The inserted arc is quantized to zero and is a real edge path across the patch, so that
     * operator (1) can then collapse it. Both halves the split produces must be genuine
     * zero-patches with exactly two non-zero arcs.
     */
    @Test
    void theInsertedArcIsAZeroArcAndBothHalvesAreSimpleZeroPatches() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        ZeroPatchSplitOperator operator = new ZeroPatchSplitOperator(fixtureNet);
        int patchId = operator.nextNonSimpleZeroPatch();

        operator.split(patchId);

        boolean foundNewZeroArc = false;
        for (int arcId = 0; arcId < fixtureNet.arcs.size(); arcId++) {
            var arc = fixtureNet.arcs.get(arcId);
            if (arc.alive && arc.quantizedLength == 0 && arc.sourceArcId == ArcNetwork.NONE
                    && arc.path.copyEdgePath.size() >= 1) {
                foundNewZeroArc = true;
            }
        }
        assertTrue(foundNewZeroArc, "a minted zero-arc with a real edge path must exist");

        int simpleZeroPatches = 0;
        for (int id = 0; id < fixtureNet.patches.size(); id++) {
            if (fixtureNet.patches.get(id).alive && fixtureNet.isZeroPatch(id)
                    && fixtureNet.nonZeroArcCount(id) == 2) {
                simpleZeroPatches++;
            }
        }
        assertTrue(simpleZeroPatches >= 4,
                "the fixture's two original simple zero-patches plus the two new ones, got "
                        + simpleZeroPatches);
    }

    /**
     * Operator (2) then operator (1): once the non-simple patch is split, the inserted and
     * surrounding zero arcs must all collapse, leaving no zero arcs and holding Euler — the
     * two operators composing as the paper intends.
     */
    @Test
    void splitThenCollapseClearsEveryZeroArc() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        ZeroPatchSplitOperator splitter = new ZeroPatchSplitOperator(fixtureNet);
        for (int patchId = splitter.nextNonSimpleZeroPatch(); patchId != ArcNetwork.NONE;
                patchId = splitter.nextNonSimpleZeroPatch()) {
            splitter.split(patchId);
            fixtureNet.validate();
        }

        var collapser = new ixdar.geometry.mesh.quadlayout.embedding
                .ZeroArcCollapseOperator(fixtureNet);
        int guard = 0;
        for (int arcId = collapser.mostContendedArc(); arcId != ArcNetwork.NONE;
                arcId = collapser.mostContendedArc()) {
            collapser.collapse(arcId);
            fixtureNet.validate();
            if (++guard > fixtureNet.arcs.size()) {
                throw new AssertionError("collapse did not terminate");
            }
        }

        for (int arcId = 0; arcId < fixtureNet.arcs.size(); arcId++) {
            var arc = fixtureNet.arcs.get(arcId);
            if (arc.alive) {
                assertNotEquals(0, arc.quantizedLength,
                        "arc " + arcId + " is a zero arc that should have collapsed");
            }
        }
    }

    /**
     * The number of live arcs in a T-mesh.
     *
     * @param tmesh T-mesh to count
     * @return count of live arcs
     */
    private static int countLiveArcs(ArcNetwork tmesh) {
        int count = 0;
        for (int id = 0; id < tmesh.arcs.size(); id++) {
            if (tmesh.arcs.get(id).alive) {
                count++;
            }
        }
        return count;
    }

    /**
     * The number of live patches in a T-mesh.
     *
     * @param tmesh T-mesh to count
     * @return count of live patches
     */
    private static int countLivePatches(ArcNetwork tmesh) {
        int count = 0;
        for (int id = 0; id < tmesh.patches.size(); id++) {
            if (tmesh.patches.get(id).alive) {
                count++;
            }
        }
        return count;
    }
}
