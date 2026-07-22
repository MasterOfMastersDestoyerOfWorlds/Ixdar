package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.PlaneLayoutFixture;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroPatchSplitOperator;

/**
 * LCBK19 operator (2) must be able to run while the contraction is still in progress, because that
 * is the only time it is ever applicable.
 *
 * <p>The operator extends a T-joint across a non-simple zero-patch by inserting a zero-arc
 * (Figure 9d→e), and to route that arc it needs the faces the patch covers. It used to obtain them
 * by decomposing the <em>whole</em> layout into regions — which also asserts that every patch
 * corresponds to one connected region of faces, a property that only holds once the contraction has
 * finished. A zero-patch is by definition embedded onto a point or a curve, so while it lives it
 * encloses no faces and has no region to correspond to; demanding otherwise mid-contraction throws
 * "the layout is torn" every time the operator fires.
 *
 * <p>That is not a rare path, it is the only path. Operator (2) applies exactly when a zero-patch is
 * non-simple, which is exactly before the contraction has finished. The sphere never revealed it
 * because the sphere's contraction reports {@code 0 split(s)} — the operator has never once run
 * there — and fertility dies the moment it first needs it.
 *
 * <p>{@link PlaneLayoutFixture} is the smallest thing that makes the operator fire: Figure 9(a)'s
 * blue patch, whose two vertical sides are quantized zero and whose horizontal sides carry three
 * non-zero arcs between them, so the T-joint makes it non-simple.
 */
class ZeroPatchSplitCorridorTest {

    @Test
    void aNonSimpleZeroPatchSplitsWhileTheContractionIsStillUnfinished() {
        PlaneLayoutFixture fixture = new PlaneLayoutFixture();
        ZeroPatchSplitOperator operator = new ZeroPatchSplitOperator(fixture.tmesh);

        int nonSimple = operator.nextNonSimpleZeroPatch();
        assertNotEquals(EmbeddedTMesh.NONE, nonSimple,
                "the fixture carries Figure 9's non-simple zero-patch");
        assertTrue(fixture.tmesh.nonZeroArcCount(nonSimple) > 2,
                "non-simple means more than two non-zero arcs are involved");

        long patchesBefore = fixture.tmesh.patches.stream().filter(patch -> patch.alive).count();

        operator.split(nonSimple);

        fixture.tmesh.validate(PlaneLayoutFixture.PLANE_EULER_CHARACTERISTIC);
        assertEquals(patchesBefore + 1,
                fixture.tmesh.patches.stream().filter(patch -> patch.alive).count(),
                "extending the T-joint across the patch cuts it in two");
        assertEquals(1, operator.splitCount, "the operator records the split it made");
    }
}
