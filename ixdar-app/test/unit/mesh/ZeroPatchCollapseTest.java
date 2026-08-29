package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.fixtures.TorusLayoutFixture;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroPatchCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroPatchSplitOperator;

/**
 * LCBK19 §6.1 operator (3) and the three operators composed: driving operator (1), (2), and
 * (3) to a fixed point on the Figure-9 torus fixture must leave a layout with no zero arcs and
 * no zero patches — the re-embedding LCBK19 Proposition 6.1 promises — while holding the
 * surface's Euler characteristic at zero throughout.
 */
class ZeroPatchCollapseTest {

    @Test
    void drivingAllThreeOperatorsClearsEveryZeroElement() {
        TorusLayoutFixture fixture = new TorusLayoutFixture();

        new NetworkContraction(fixture.tmesh).contract();

        for (int arcId = 0; arcId < fixture.tmesh.arcs.size(); arcId++) {
            var arc = fixture.tmesh.arcs.get(arcId);
            if (arc.alive) {
                assertNotEquals(0, arc.quantizedLength,
                        "arc " + arcId + " is a zero arc that survived contraction");
            }
        }
        for (int patchId = 0; patchId < fixture.tmesh.patches.size(); patchId++) {
            if (fixture.tmesh.patches.get(patchId).alive) {
                assertEquals(false, fixture.tmesh.isZeroPatch(patchId),
                        "patch " + patchId + " is a zero patch that survived contraction");
            }
        }
    }

    /**
     * Operator (3) removes exactly one arc and one patch — the discarded arc and the bigon it
     * bounded — so it must not move the Euler characteristic. Reaching a bigon needs operator
     * (1) to have collapsed a simple zero-patch's zero sides first, which this does by driving
     * (1) and (2) until the first bigon appears.
     */
    @Test
    void collapsingOneBigonRemovesOneArcAndOnePatch() {
        TorusLayoutFixture fixture = new TorusLayoutFixture();
        ZeroArcCollapseOperator collapseArc = new ZeroArcCollapseOperator(fixture.tmesh);
        ZeroPatchSplitOperator splitPatch = new ZeroPatchSplitOperator(fixture.tmesh);
        ZeroPatchCollapseOperator collapsePatch = new ZeroPatchCollapseOperator(fixture.tmesh);

        int guard = 0;
        while (collapsePatch.nextSimpleZeroPatch() == ArcNetwork.NONE) {
            int arc = collapseArc.mostContendedArc();
            if (arc != ArcNetwork.NONE) {
                collapseArc.collapse(arc);
            } else {
                int nonSimple = splitPatch.nextNonSimpleZeroPatch();
                assertNotEquals(ArcNetwork.NONE, nonSimple, "a bigon should become reachable");
                splitPatch.split(nonSimple);
            }
            fixture.tmesh.validate();
            if (++guard > 100) {
                throw new AssertionError("never reached a bigon");
            }
        }

        int liveArcsBefore = countLive(fixture.tmesh.arcs.size(), fixture.tmesh, true);
        int livePatchesBefore = countLive(fixture.tmesh.patches.size(), fixture.tmesh, false);

        collapsePatch.collapse(collapsePatch.nextSimpleZeroPatch());

        fixture.tmesh.validate();
        assertEquals(liveArcsBefore - 1, countLive(fixture.tmesh.arcs.size(), fixture.tmesh, true),
                "operator (3) discards exactly one arc");
        assertEquals(livePatchesBefore - 1,
                countLive(fixture.tmesh.patches.size(), fixture.tmesh, false),
                "operator (3) removes exactly one patch");
    }

    /**
     * The number of live arcs or patches in a T-mesh.
     *
     * @param count number of ids to scan
     * @param tmesh T-mesh to read
     * @param arcs  true to count arcs, false to count patches
     * @return count of live elements
     */
    private static int countLive(int count, ArcNetwork tmesh, boolean arcs) {
        int live = 0;
        for (int id = 0; id < count; id++) {
            if (arcs ? tmesh.arcs.get(id).alive : tmesh.patches.get(id).alive) {
                live++;
            }
        }
        return live;
    }
}
