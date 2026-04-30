package unit.quadlayout.quantization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.quantization.Quantization;
import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TNode;
import ixdar.geometry.mesh.quadlayout.tmesh.TPatch;

/**
 * PATCH-42 — unit tests for the T-mesh quantization ILP. Hand-builds tiny
 * T-meshes whose optimal {@code q} is provable so we can assert the solver
 * is correct independently of the upstream cross-field / IGM stages.
 */
public class QuantizationTest {

    /** Single 4-arc patch with all parametric lengths near integers. Optimal
     *  q is the rounded length on every arc. */
    @Test
    void singlePatchRoundsToParametricLength() throws Exception {
        // Synthetic patch: 4 nodes, 4 arcs forming one 4-cycle.
        // Arc lengths: left=top=right=bottom=2.0 → q=2 on every arc, obj=0.
        TMesh tmesh = makeSinglePatch(2.0f, 2.0f, 2.0f, 2.0f);
        Quantization.Result res = Quantization.solve(tmesh);
        assertNotNull(res);
        assertTrue(res.feasible(), "feasible expected on a balanced patch");
        assertEquals(4, res.arcQuantization().length);
        for (int q : res.arcQuantization()) {
            assertEquals(2, q, "every q expected to round to 2 on balanced patch");
        }
        assertEquals(0.0, res.objectiveValue(), 1e-9,
                "zero deviation when r is already integer");
    }

    /** Lengths 1.4 / 2.4 / 1.4 / 2.4: the rounded targets violate the
     *  consistency-equal constraint (1 vs 1, 2 vs 2 → matches; round to
     *  q=1 on left/right and q=2 on top/bottom). Objective = 4*0.4 = 1.6. */
    @Test
    void asymmetricLengthsHonourConsistency() throws Exception {
        TMesh tmesh = makeSinglePatch(1.4f, 2.4f, 1.4f, 2.4f);
        Quantization.Result res = Quantization.solve(tmesh);
        assertTrue(res.feasible());
        int[] q = res.arcQuantization();
        // Opposite arcs must equal.
        assertEquals(q[0], q[2], "left == right");
        assertEquals(q[1], q[3], "top == bottom");
        // Each q ≥ 1 (validity).
        for (int x : q) assertTrue(x >= 1, "q_i ≥ 1: " + x);
        // Per-arc deviation bounded — best integer near 1.4 is 1 or 2; same
        // for 2.4. Objective = 4 * 0.4 = 1.6 if rounded ideally.
        assertEquals(1.6, res.objectiveValue(), 1e-6,
                "balanced rounding gives 1.6 absolute deviation");
    }

    /** Near-zero target on one arc must be lifted to q=1 by the validity
     *  bound; the equality with its opposite forces the opposite to 1 as
     *  well even if its own target is small. */
    @Test
    void validityLowerBoundDominates() throws Exception {
        TMesh tmesh = makeSinglePatch(0.05f, 5.0f, 0.05f, 5.0f);
        Quantization.Result res = Quantization.solve(tmesh);
        assertTrue(res.feasible());
        int[] q = res.arcQuantization();
        assertTrue(q[0] >= 1, "left ≥ 1 by validity");
        assertEquals(q[0], q[2], "left == right");
        assertEquals(q[1], q[3], "top == bottom");
        assertEquals(5, q[1], "top should round to 5");
    }

    /** Public verifier rejects bad inputs and accepts good ones. */
    @Test
    void verifyConsistencyDetectsInvalidQ() throws Exception {
        TMesh tmesh = makeSinglePatch(2.0f, 2.0f, 2.0f, 2.0f);
        // Valid q satisfying both consistency rules + validity floor.
        assertTrue(Quantization.verifyConsistency(tmesh, new int[]{2, 2, 2, 2}));
        // Wrong length.
        assertTrue(!Quantization.verifyConsistency(tmesh, new int[]{2, 2}));
        // Validity floor violation (q[0] = 0).
        assertTrue(!Quantization.verifyConsistency(tmesh, new int[]{0, 2, 2, 2}));
        // Consistency violation: left != right.
        assertTrue(!Quantization.verifyConsistency(tmesh, new int[]{2, 2, 3, 2}));
        // Consistency violation: top != bottom.
        assertTrue(!Quantization.verifyConsistency(tmesh, new int[]{2, 3, 2, 2}));
    }

    /** No arcs, no patches: solver must return the trivial empty result
     *  without invoking ojAlgo (degenerate-input guard). */
    @Test
    void emptyTMeshReturnsTrivialResult() throws Exception {
        TMesh empty = newTMesh(java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList());
        Quantization.Result res = Quantization.solve(empty);
        assertNotNull(res);
        assertTrue(res.feasible());
        assertEquals(0, res.arcQuantization().length);
        assertEquals(0.0, res.objectiveValue(), 0.0);
    }

    /** Arcs without any enclosing patch: only validity (q ≥ 1) and the
     *  per-arc absolute-deviation objective apply. Each arc rounds to its
     *  nearest integer ≥ 1 independently. */
    @Test
    void freeArcsRoundIndependently() throws Exception {
        TNode a = new TNode(0, TNode.NodeKind.SINGULARITY, 0, 0f, 0f);
        TNode b = new TNode(1, TNode.NodeKind.SINGULARITY, 0, 1f, 0f);
        TArc loose1 = TArc.simple(0, a.id(), b.id(), new ArrayList<>(), 0, 2.7f);
        TArc loose2 = TArc.simple(1, a.id(), b.id(), new ArrayList<>(), 1, 0.2f);
        TMesh tmesh = newTMesh(Arrays.asList(a, b), Arrays.asList(loose1, loose2),
                java.util.Collections.emptyList());

        Quantization.Result res = Quantization.solve(tmesh);
        assertTrue(res.feasible());
        int[] q = res.arcQuantization();
        assertEquals(3, q[0], "2.7 rounds to nearest int = 3");
        assertEquals(1, q[1], "0.2 lifted to validity floor = 1");
    }

    /** Patches form a 1x3 horizontal strip: 3 patches × 4 arcs = 12 arcs but
     *  with shared verticals (4 unique verticals + 3*2=6 horizontals = 10).
     *  Consistency cascades — all 6 horizontals must equal AND all 4 verticals
     *  must equal. */
    @Test
    void cascadingConsistencyAcrossThreePatches() throws Exception {
        // bl0=(0,0) bl1=(2,0) bl2=(4,0) bl3=(6,0)
        // tl0=(0,2) tl1=(2,2) tl2=(4,2) tl3=(6,2)
        TNode[] bl = new TNode[4];
        TNode[] tl = new TNode[4];
        for (int i = 0; i < 4; i++) {
            bl[i] = new TNode(i,     TNode.NodeKind.SINGULARITY, 0, 2f * i, 0f);
            tl[i] = new TNode(4 + i, TNode.NodeKind.SINGULARITY, 0, 2f * i, 2f);
        }
        // Verticals: v[i] = arc bl[i] -> tl[i], length 2.
        TArc[] v = new TArc[4];
        for (int i = 0; i < 4; i++) {
            v[i] = TArc.simple(i, bl[i].id(), tl[i].id(),
                    new ArrayList<>(), 1, 2f);
        }
        // Horizontals top: h_top[i] = tl[i] -> tl[i+1], length 2.
        // Horizontals bottom: h_bot[i] = bl[i] -> bl[i+1], length 2.
        TArc[] hTop = new TArc[3];
        TArc[] hBot = new TArc[3];
        for (int i = 0; i < 3; i++) {
            hTop[i] = TArc.simple(4 + i,     tl[i].id(), tl[i + 1].id(),
                    new ArrayList<>(), 0, 2f);
            hBot[i] = TArc.simple(7 + i,     bl[i].id(), bl[i + 1].id(),
                    new ArrayList<>(), 0, 2f);
        }
        java.util.List<TNode> nodes = new ArrayList<>();
        for (TNode n : bl) nodes.add(n);
        for (TNode n : tl) nodes.add(n);
        java.util.List<TArc> arcs = new ArrayList<>();
        for (TArc a : v) arcs.add(a);
        for (TArc a : hTop) arcs.add(a);
        for (TArc a : hBot) arcs.add(a);

        java.util.List<TPatch> patches = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            patches.add(TPatch.single(i,
                    new int[]{v[i].id(), hTop[i].id(), v[i + 1].id(), hBot[i].id()},
                    new int[]{bl[i].id(), bl[i + 1].id(), tl[i + 1].id(), tl[i].id()}));
        }
        TMesh tmesh = newTMesh(nodes, arcs, patches);
        Quantization.Result res = Quantization.solve(tmesh);
        assertTrue(res.feasible());
        int[] q = res.arcQuantization();

        // All 4 verticals must be equal (cascading equality across 3 patches).
        for (int i = 1; i < 4; i++) {
            assertEquals(q[v[0].id()], q[v[i].id()],
                    "vertical " + i + " must equal vertical 0");
        }
        // All 6 horizontals must be equal (3 top + 3 bot, all linked by EQ).
        for (int i = 0; i < 3; i++) {
            assertEquals(q[hTop[0].id()], q[hTop[i].id()],
                    "top " + i + " must equal top 0");
            assertEquals(q[hBot[0].id()], q[hBot[i].id()],
                    "bot " + i + " must equal bot 0");
            assertEquals(q[hTop[i].id()], q[hBot[i].id()],
                    "top/bot " + i + " consistency");
        }
        // Targets are all 2 → optimal is q=2 everywhere, deviation 0.
        assertEquals(2, q[v[0].id()]);
        assertEquals(2, q[hTop[0].id()]);
        assertEquals(0.0, res.objectiveValue(), 1e-6);
    }

    /** Two patches sharing one arc: the shared arc's quantization must be
     *  consistent with both patches. Force a conflict and confirm the
     *  solver picks an integer that satisfies both. */
    @Test
    void twoPatchesSharedArcSolved() throws Exception {
        // Layout (parametric):
        //   nodes a=(0,0), b=(3,0), c=(3,2), d=(0,2), e=(6,0), f=(6,2)
        //   patch P1 corners: a,b,c,d  arcs: ab(left=down), bc(top=right), cd(right=up), da(bottom=left)
        //     wait — convention: arcIds = [left, top, right, bottom],
        //     corners = [bl, br, tr, tl]. So P1 = arcs[ad, dc, bc, ab] no...
        // Let me restate using TPatch's documented order:
        //   arcIds[0] = left side  (vertical, bottom-left → top-left)
        //   arcIds[1] = top side   (horizontal, top-left → top-right)
        //   arcIds[2] = right side (vertical, bottom-right → top-right)
        //   arcIds[3] = bottom side(horizontal, bottom-left → bottom-right)
        //   cornerNodeIds = [bl, br, tr, tl]
        //
        // Two patches stacked horizontally sharing the middle vertical arc:
        //   nodes a=(0,0), b=(3,0), c=(6,0), d=(0,2), e=(3,2), f=(6,2)
        //   P1 (left): bl=a, br=b, tr=e, tl=d
        //              arcs: left=ad(v, len 2), top=de(u, len 3), right=be(v, len 2), bottom=ab(u, len 3)
        //   P2 (right): bl=b, br=c, tr=f, tl=e
        //              arcs: left=be(v, len 2), top=ef(u, len 3), right=cf(v, len 2), bottom=bc(u, len 3)
        //   shared arc = be (right of P1 = left of P2)
        TMesh tmesh = makeTwoPatchesSharedArc();
        Quantization.Result res = Quantization.solve(tmesh);
        assertTrue(res.feasible());
        int[] q = res.arcQuantization();
        // 7 arcs total. Arc indices: 0=ad, 1=de, 2=be, 3=ab, 4=ef, 5=cf, 6=bc.
        // All v-arcs (0,2,5) should round to 2; all u-arcs (1,3,4,6) to 3.
        // The shared arc (id=2, be) must satisfy BOTH P1 (right-of-P1 = ad)
        // AND P2 (left-of-P2 = cf): q[ad] = q[be] = q[cf] = 2.
        for (int x : q) assertTrue(x >= 1);
        assertEquals(2, q[0], "ad (left of P1, vertical) -> 2");
        assertEquals(2, q[2], "be (shared, vertical) -> 2");
        assertEquals(2, q[5], "cf (right of P2, vertical) -> 2");
        assertEquals(3, q[1], "de (top of P1) -> 3");
        assertEquals(3, q[3], "ab (bottom of P1) -> 3");
        assertEquals(3, q[4], "ef (top of P2) -> 3");
        assertEquals(3, q[6], "bc (bottom of P2) -> 3");
        // Targets exactly match → zero deviation.
        assertEquals(0.0, res.objectiveValue(), 1e-6,
                "near-integer targets give zero deviation");
    }

    // ---- helpers ----

    private static TMesh makeSinglePatch(float lLen, float tLen, float rLen, float bLen)
            throws Exception {
        // Corners: bl, br, tr, tl in (u, v) at (0,0), (1,0), (1,1), (0,1).
        TNode bl = new TNode(0, TNode.NodeKind.SINGULARITY, 0, 0f, 0f);
        TNode br = new TNode(1, TNode.NodeKind.SINGULARITY, 0, 1f, 0f);
        TNode tr = new TNode(2, TNode.NodeKind.SINGULARITY, 0, 1f, 1f);
        TNode tl = new TNode(3, TNode.NodeKind.SINGULARITY, 0, 0f, 1f);
        List<TNode> nodes = Arrays.asList(bl, br, tr, tl);

        // Direction codes: 0=+u, 1=+v, 2=-u, 3=-v.
        TArc left   = new TArc(0, bl.id(), tl.id(), new ArrayList<>(), new ArrayList<>(), /*+v*/1, lLen);
        TArc top    = new TArc(1, tl.id(), tr.id(), new ArrayList<>(), new ArrayList<>(), /*+u*/0, tLen);
        TArc right  = new TArc(2, br.id(), tr.id(), new ArrayList<>(), new ArrayList<>(), /*+v*/1, rLen);
        TArc bottom = new TArc(3, bl.id(), br.id(), new ArrayList<>(), new ArrayList<>(), /*+u*/0, bLen);
        List<TArc> arcs = Arrays.asList(left, top, right, bottom);

        TPatch patch = TPatch.single(0,
                new int[]{left.id(), top.id(), right.id(), bottom.id()},
                new int[]{bl.id(), br.id(), tr.id(), tl.id()});
        return newTMesh(nodes, arcs, List.of(patch));
    }

    private static TMesh makeTwoPatchesSharedArc() throws Exception {
        TNode a = new TNode(0, TNode.NodeKind.SINGULARITY, 0, 0f, 0f);
        TNode b = new TNode(1, TNode.NodeKind.INTERSECTION, 0, 3f, 0f);
        TNode c = new TNode(2, TNode.NodeKind.SINGULARITY, 0, 6f, 0f);
        TNode d = new TNode(3, TNode.NodeKind.SINGULARITY, 0, 0f, 2f);
        TNode e = new TNode(4, TNode.NodeKind.INTERSECTION, 0, 3f, 2f);
        TNode f = new TNode(5, TNode.NodeKind.SINGULARITY, 0, 6f, 2f);
        List<TNode> nodes = Arrays.asList(a, b, c, d, e, f);

        TArc ad = TArc.simple(0, a.id(), d.id(), new ArrayList<>(), 1, 2f);  // left of P1
        TArc de = TArc.simple(1, d.id(), e.id(), new ArrayList<>(), 0, 3f);  // top of P1
        TArc be = TArc.simple(2, b.id(), e.id(), new ArrayList<>(), 1, 2f);  // right of P1 = left of P2
        TArc ab = TArc.simple(3, a.id(), b.id(), new ArrayList<>(), 0, 3f);  // bottom of P1
        TArc ef = TArc.simple(4, e.id(), f.id(), new ArrayList<>(), 0, 3f);  // top of P2
        TArc cf = TArc.simple(5, c.id(), f.id(), new ArrayList<>(), 1, 2f);  // right of P2
        TArc bc = TArc.simple(6, b.id(), c.id(), new ArrayList<>(), 0, 3f);  // bottom of P2
        List<TArc> arcs = Arrays.asList(ad, de, be, ab, ef, cf, bc);

        TPatch p1 = TPatch.single(0,
                new int[]{ad.id(), de.id(), be.id(), ab.id()},
                new int[]{a.id(), b.id(), e.id(), d.id()});
        TPatch p2 = TPatch.single(1,
                new int[]{be.id(), ef.id(), cf.id(), bc.id()},
                new int[]{b.id(), c.id(), f.id(), e.id()});
        return newTMesh(nodes, arcs, List.of(p1, p2));
    }

    /** Synthetic TMesh build via the public test factory. */
    private static TMesh newTMesh(List<TNode> nodes, List<TArc> arcs, List<TPatch> patches) {
        return TMesh.fromComponents(nodes, arcs, patches);
    }
}
