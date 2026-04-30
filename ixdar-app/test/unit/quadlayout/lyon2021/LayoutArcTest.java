package unit.quadlayout.lyon2021;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.lyon2021.LayoutArc;
import ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayout;
import ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutExtractor;
import ixdar.geometry.mesh.quadlayout.lyon2021.QuadLayoutPatch;
import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TNode;
import ixdar.geometry.mesh.quadlayout.tmesh.TPatch;

/**
 * PATCH-77: verify the §6 ¶1 T-junction extension correctly resolves a
 * single-T-junction synthetic patch into 2 conforming layout patches with a
 * new INTERIOR LayoutArc connecting the T-junction to its match.
 */
public class LayoutArcTest {

    /**
     * A single T-junction patch:
     * <pre>
     *   c0 ─a0─ T ─a1─ c1
     *   │              │
     *   a4             a2
     *   │              │
     *   c3 ────a3──── c2
     * </pre>
     * Side 0 = [a0, a1] (T-junction at the boundary). Side 1 = [a2].
     * Side 2 = [a3]. Side 3 = [a4].
     *
     * <p>Quantization {@code q = [1, 1, 2, 2, 2]} means side 0 sums to 2,
     * matching side 2's q[a3]=2. The T-junction sits at distance 1 from
     * corner 0 along side 0; the matching point on side 2 must be at
     * distance 1 from corner 3 — which falls MID-ARC inside a3 (length 2).
     * PATCH-80 splits a3 into two DERIVED arcs of length 1 each, creates a
     * synthetic node, and resolves the patch into 2 conforming sub-patches.
     */
    @Test
    void midArcMatchTriggersArcSplitting() {
        TMesh tmesh = buildSingleTJunctionPatch();
        int[] q = {1, 1, 2, 2, 2};

        QuadLayoutExtractor.Result r = QuadLayoutExtractor.extract(tmesh, q);

        assertEquals(2, r.layout().patchCount(),
                "PATCH-80 arc-split should resolve the patch into 2 sub-patches");
        assertEquals(0, r.skippedPatchIds().size());
        assertEquals(1, r.layout().tJunctionsResolved());

        // 5 INHERITED + 2 DERIVED (split of arc 3) + 1 INTERIOR = 8 layout arcs.
        assertEquals(8, r.layout().layoutArcs().size());
        long derivedCount = r.layout().layoutArcs().stream()
                .filter(la -> la.variant() == LayoutArc.Variant.DERIVED).count();
        long interiorCount = r.layout().layoutArcs().stream()
                .filter(la -> la.variant() == LayoutArc.Variant.INTERIOR).count();
        assertEquals(2, derivedCount, "2 DERIVED arcs (split of original arc 3)");
        assertEquals(1, interiorCount, "1 INTERIOR arc (T-junction extension)");
    }

    /**
     * A T-junction patch where the opposite side ALSO has a T-junction at
     * the matching parametric distance. With q chosen so the T-junctions
     * mirror exactly, the §6 ¶1 extension should resolve the patch into
     * 2 conforming layout patches.
     *
     * <pre>
     *   c0 ─a0─ T0 ─a1─ c1
     *   │               │
     *   a4              a2
     *   │               │
     *   c3 ─a3a─ M ─a3b─ c2
     * </pre>
     *
     * Side 0 = [a0, a1]; side 2 = [a3a, a3b]. With q[a0]=q[a1]=1 and
     * q[a3a]=q[a3b]=1, side-0 length 2 = side-2 length 2; T0 at distance 1
     * from c0; matching point at distance (2 - 1) = 1 from c3 = end of a3a
     * = node M. Match found, patch splits into:
     *   - HalfA: c0 → T0 → M → c3
     *   - HalfB: T0 → c1 → c2 → M
     */
    @Test
    void matchingNodeOnOppositeSideResolvesIntoTwoPatches() {
        TMesh tmesh = buildDoubleTJunctionPatch();
        // Arc index → side mapping: 0,1=side0; 2=side1; 3,4=side2; 5=side3.
        // Want side 0 length 2 (= 1+1), side 2 length 2 (= 1+1), and
        // side 1 length 2, side 3 length 2 for consistency on both axes.
        int[] q = {1, 1, 2, 1, 1, 2};

        QuadLayoutExtractor.Result r = QuadLayoutExtractor.extract(tmesh, q);

        assertEquals(2, r.layout().patchCount(),
                "T-junction extension should split 1 patch into 2");
        assertEquals(0, r.skippedPatchIds().size(), "no patches skipped");
        assertEquals(1, r.layout().tJunctionsResolved(),
                "exactly one T-junction extension fired");

        // 6 underlying TArcs + 1 INTERIOR layout arc.
        assertEquals(7, r.layout().layoutArcs().size(),
                "6 INHERITED + 1 INTERIOR layout arc");
        assertEquals(LayoutArc.Variant.INTERIOR,
                r.layout().layoutArcs().get(6).variant(),
                "the new arc is INTERIOR variant");

        // Each conforming patch has 4 single-arc sides.
        for (QuadLayoutPatch p : r.layout().patches()) {
            for (int s = 0; s < 4; s++) {
                assertEquals(1, p.arcsBySide()[s].length,
                        "every side single-arc");
            }
        }

        // The two halves share the INTERIOR arc.
        QuadLayoutPatch halfA = r.layout().patches().get(0);
        QuadLayoutPatch halfB = r.layout().patches().get(1);
        int interiorId = 6;
        boolean aHasInterior = false, bHasInterior = false;
        for (int s = 0; s < 4; s++) {
            if (halfA.arcsBySide()[s][0] == interiorId) aHasInterior = true;
            if (halfB.arcsBySide()[s][0] == interiorId) bHasInterior = true;
        }
        assertTrue(aHasInterior && bHasInterior,
                "both halves must reference the INTERIOR arc");
    }

    // ---------------- fixtures ----------------

    /** 5 nodes (4 corners + 1 T-junction on side 0), 5 arcs, 1 patch. */
    private static TMesh buildSingleTJunctionPatch() {
        List<TNode> nodes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            nodes.add(new TNode(i, TNode.NodeKind.INTERSECTION, -1, 0f, 0f));
        }
        List<TArc> arcs = new ArrayList<>();
        // Side 0: c0(0) → T(4) → c1(1)
        arcs.add(TArc.simple(0, 0, 4, new ArrayList<>(), 0, 1f));
        arcs.add(TArc.simple(1, 4, 1, new ArrayList<>(), 0, 1f));
        // Side 1: c1(1) → c2(2)
        arcs.add(TArc.simple(2, 1, 2, new ArrayList<>(), 1, 2f));
        // Side 2: c2(2) → c3(3)
        arcs.add(TArc.simple(3, 2, 3, new ArrayList<>(), 2, 2f));
        // Side 3: c3(3) → c0(0)
        arcs.add(TArc.simple(4, 3, 0, new ArrayList<>(), 3, 2f));

        int[][] sides = {{0, 1}, {2}, {3}, {4}};
        TPatch patch = TPatch.multi(0, sides, new int[]{0, 1, 2, 3});
        return TMesh.fromComponents(nodes, arcs, List.of(patch));
    }

    /** 6 nodes (4 corners + 2 T-junctions on opposing sides), 6 arcs, 1 patch.
     *  Quantization for the test: q = {1, 1, 2, 2, 1, 1} — sides 0 & 2 both
     *  sum to 2, the two T-junctions sit at matching parametric distances. */
    private static TMesh buildDoubleTJunctionPatch() {
        List<TNode> nodes = new ArrayList<>();
        // 0=c0, 1=c1, 2=c2, 3=c3, 4=T0 (on side 0), 5=M (on side 2)
        for (int i = 0; i < 6; i++) {
            nodes.add(new TNode(i, TNode.NodeKind.INTERSECTION, -1, 0f, 0f));
        }
        List<TArc> arcs = new ArrayList<>();
        // Side 0 (c0 → c1): a0 = c0 → T0,  a1 = T0 → c1
        arcs.add(TArc.simple(0, 0, 4, new ArrayList<>(), 0, 1f));
        arcs.add(TArc.simple(1, 4, 1, new ArrayList<>(), 0, 1f));
        // Side 1 (c1 → c2): a2
        arcs.add(TArc.simple(2, 1, 2, new ArrayList<>(), 1, 2f));
        // Side 2 (c2 → c3): a3a = c2 → M, a3b = M → c3
        // BUT side 2 in the patch is walked corner-2 → corner-3, so the side
        // arcs in walk-order are [a3a, a3b]. a3a connects c2(2) → M(5);
        // a3b connects M(5) → c3(3).
        arcs.add(TArc.simple(3, 2, 5, new ArrayList<>(), 2, 1f));
        arcs.add(TArc.simple(4, 5, 3, new ArrayList<>(), 2, 1f));
        // Side 3 (c3 → c0): a4
        arcs.add(TArc.simple(5, 3, 0, new ArrayList<>(), 3, 2f));

        int[][] sides = {{0, 1}, {2}, {3, 4}, {5}};
        TPatch patch = TPatch.multi(0, sides, new int[]{0, 1, 2, 3});
        return TMesh.fromComponents(nodes, arcs, List.of(patch));
    }
}
