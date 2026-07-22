package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedContraction;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.StackedZeroRowTorusFixture;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroPatchSplitOperator;

/**
 * Operator (2) may raise the termination measure on its own; only the round that follows it must
 * bring the measure down. Checking the measure after every single operator is a stronger claim than
 * LCBK19 makes, and it is false.
 *
 * <p>The measure is the paper's: the total number of live zero-arcs and zero-patches.
 *
 * <p>Appendix A.3 states the invariant across a round, not a step: <em>"While operator 2 creates (a
 * finite number of) additional zero-arcs within a (non-simple) zero-patch, the subsequent
 * application of operators 1 and 3 is able to collapse these while at the same time reducing the
 * total number of yet-to-be-collapsed zero-arcs and zero-patches relative to the state before
 * operator 2 was applied."</em>
 *
 * <p>The fixture stacks two zero rows. Splitting the lower row's non-simple patch must split the arc
 * they share, which hands the T-joint to the patch above and leaves the excess term unchanged while
 * minting one zero-arc and one zero-patch. This is the fertility contraction's {@code C}-key abort
 * (measure 3938 to 3940 on the split of patch 736, whose excess reappeared on patch 929) in eleven
 * arcs.
 *
 * <p>See also: LCBK19 Section 6.1, Appendix A.3
 */
class StackedZeroPatchSplitMeasureTest {

    @Test
    void aSplitMayRaiseTheMeasureButTheRoundAroundItMustLowerIt() {
        StackedZeroRowTorusFixture fixture = new StackedZeroRowTorusFixture();
        EmbeddedContraction contraction = new EmbeddedContraction(fixture.tmesh,
                StackedZeroRowTorusFixture.TORUS_EULER_CHARACTERISTIC);
        ZeroPatchSplitOperator splitter = contraction.splitPatch;

        assertEquals(fixture.nonSimplePatchId, splitter.nextNonSimpleZeroPatch(),
                "the lower zero row's major 0 to 4 patch is the fixture's only non-simple one");
        long before = contraction.terminationMeasure();

        splitter.split(fixture.nonSimplePatchId);
        fixture.tmesh.validate(StackedZeroRowTorusFixture.TORUS_EULER_CHARACTERISTIC);

        assertTrue(contraction.terminationMeasure() > before,
                "the split mints a zero-arc and a zero-patch, so it raises the measure on its own —"
                        + " measuring progress per operator asserts more than Appendix A.3 does");
        assertNotEquals(EmbeddedTMesh.NONE, splitter.nextNonSimpleZeroPatch(),
                "splitting the shared arc hands the T-joint to the patch stacked above, which is"
                        + " why the excess does not simply go away on this step");

        int guard = 0;
        while (collapseOneArcOrPatch(contraction)) {
            fixture.tmesh.validate(StackedZeroRowTorusFixture.TORUS_EULER_CHARACTERISTIC);
            if (++guard > fixture.tmesh.arcs.size() + fixture.tmesh.patches.size()) {
                throw new AssertionError("operators 1 and 3 did not reach a fixed point");
            }
        }

        assertTrue(contraction.terminationMeasure() < before,
                "after operators 1 and 3 have collapsed what the split minted, the measure must be"
                        + " strictly below where it was before the split — that is the invariant"
                        + " Appendix A.3 actually proves, and it is what the driver should check");
    }

    /**
     * The driver must contract this fixture to a fixed point. It aborts instead, because it tests
     * the measure after every operator rather than after the round, so operator (2)'s deliberate
     * step backwards reads as a failure to make progress.
     */
    @Test
    void theDriverContractsTheStackedFixtureWithoutAbortingOnTheMeasure() {
        StackedZeroRowTorusFixture fixture = new StackedZeroRowTorusFixture();

        new EmbeddedContraction(fixture.tmesh,
                StackedZeroRowTorusFixture.TORUS_EULER_CHARACTERISTIC).contract();

        for (int arcId = 0; arcId < fixture.tmesh.arcs.size(); arcId++) {
            assertTrue(!fixture.tmesh.arcs.get(arcId).alive
                            || fixture.tmesh.arcs.get(arcId).quantizedLength != 0,
                    "arc " + arcId + " is a zero arc left behind at the fixed point");
        }
    }

    /**
     * Applies one zero-arc collapse, or one simple zero-patch collapse when no arc is collapsible.
     *
     * <p>Operator (2) is deliberately excluded: the round being measured is the split already
     * applied plus the operators 1 and 3 that clean up after it.
     *
     * @param contraction contraction whose operators are driven
     * @return true when one of the two operators applied
     */
    private boolean collapseOneArcOrPatch(EmbeddedContraction contraction) {
        int arcId = contraction.collapseArc.nextCollapsibleArc();
        if (arcId != EmbeddedTMesh.NONE) {
            contraction.collapseArc.collapse(arcId);
            return true;
        }
        int patchId = contraction.collapsePatch.nextSimpleZeroPatch();
        if (patchId != EmbeddedTMesh.NONE) {
            contraction.collapsePatch.collapse(patchId);
            return true;
        }
        return false;
    }
}
