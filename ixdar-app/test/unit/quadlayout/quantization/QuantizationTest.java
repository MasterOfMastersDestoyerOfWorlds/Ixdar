package unit.quadlayout.quantization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * PATCH-42 — unit tests for the T-mesh quantization ILP.
 *
 * <p><b>Note (PATCH-92):</b> these tests assert Lyon §4 Eq.(5)'s coarsening
 * objective {@code min Σ l⊥·q}. Validity Eq.(3) forces {@code q ≥ 1} on
 * every singularity-rooted arc class; the coarsening objective then drives
 * everything else (Eq.(2) consistency permitting) to {@code q = 1}.
 * The earlier "round to parametric length" expectations belonged to the
 * legacy {@code min Σ |q-r|} closeness objective which we no longer use.
 */
public class QuantizationTest {

    /** Single 4-arc patch. With validity Eq.(3) forcing q ≥ 1 per
     *  singularity-rooted class and Lyon Eq.(5) minimizing Σ l⊥·q, the
     *  optimal is q = 1 on every arc regardless of parametric length. */
    @Test
    void singlePatchCoarsensToValidityFloor() throws Exception {
        TMesh tmesh = makeSinglePatch(2.0f, 2.0f, 2.0f, 2.0f);
        Quantization.Result res = Quantization.solve(tmesh);
        assertNotNull(res);
        assertTrue(res.feasible(), "feasible expected on a balanced patch");
        assertEquals(4, res.arcQuantization().length);
        for (int q : res.arcQuantization()) {
            assertEquals(1, q, "Lyon Eq.(5) coarsens to validity floor q=1");
        }
        // Closeness diagnostic: |1-2.0| × 4 = 4.0
        assertEquals(4.0, res.objectiveValue(), 1e-6,
                "diagnostic |q-r| sum");
    }

    /** Asymmetric lengths: consistency Eq.(2) requires q[left]=q[right] and
     *  q[top]=q[bottom]; validity forces ≥1; coarsening picks q=1 for all. */
    @Test
    void asymmetricLengthsHonourConsistency() throws Exception {
        TMesh tmesh = makeSinglePatch(1.4f, 2.4f, 1.4f, 2.4f);
        Quantization.Result res = Quantization.solve(tmesh);
        assertTrue(res.feasible());
        int[] q = res.arcQuantization();
        // Opposite arcs equal (Eq.(2)).
        assertEquals(q[0], q[2], "left == right");
        assertEquals(q[1], q[3], "top == bottom");
        // Validity floor (Eq.(3)).
        for (int x : q) assertTrue(x >= 1, "q_i ≥ 1: " + x);
        // Coarsening drives all to 1.
        for (int x : q) assertEquals(1, x, "Lyon coarsening → 1 everywhere");
        // Closeness diagnostic: 2*|1-1.4| + 2*|1-2.4| = 0.8 + 2.8 = 3.6.
        assertEquals(3.6, res.objectiveValue(), 1e-6,
                "diagnostic |q-r| sum");
    }

    /** Even when one parametric length is large (5.0), coarsening + validity
     *  picks q=1 since no Eq.(4) layout constraint forces a larger value. */
    @Test
    void coarseningIgnoresParametricLength() throws Exception {
        TMesh tmesh = makeSinglePatch(0.05f, 5.0f, 0.05f, 5.0f);
        Quantization.Result res = Quantization.solve(tmesh);
        assertTrue(res.feasible());
        int[] q = res.arcQuantization();
        for (int x : q) assertTrue(x >= 1, "validity floor");
        assertEquals(q[0], q[2], "left == right");
        assertEquals(q[1], q[3], "top == bottom");
        for (int x : q) assertEquals(1, x,
                "Lyon coarsening: q=1 even when l=5 (no layout pressure)");
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

    /** Free arcs (no patch): only validity (q ≥ 1) applies. Lyon coarsening
     *  drives every arc to q=1 regardless of its parametric length. */
    @Test
    void freeArcsCoarsenToValidityFloor() throws Exception {
        TNode a = new TNode(0, TNode.NodeKind.SINGULARITY, 0, 0f, 0f);
        TNode b = new TNode(1, TNode.NodeKind.SINGULARITY, 0, 1f, 0f);
        TArc loose1 = TArc.simple(0, a.id(), b.id(), new ArrayList<>(), 0, 2.7f);
        TArc loose2 = TArc.simple(1, a.id(), b.id(), new ArrayList<>(), 1, 0.2f);
        TMesh tmesh = newTMesh(Arrays.asList(a, b), Arrays.asList(loose1, loose2),
                java.util.Collections.emptyList());

        Quantization.Result res = Quantization.solve(tmesh);
        assertTrue(res.feasible());
        int[] q = res.arcQuantization();
        assertEquals(1, q[0], "Lyon coarsening → 1 (was 'round 2.7 to 3')");
        assertEquals(1, q[1], "validity floor: 0.2 lifted to 1");
    }

    /** Three patches share verticals; consistency cascades. With Lyon
     *  coarsening, all q's collapse to 1 (validity floor), satisfying every
     *  Eq.(2). */
    @Test
    void cascadingConsistencyAcrossThreePatches() throws Exception {
        TNode[] bl = new TNode[4];
        TNode[] tl = new TNode[4];
        for (int i = 0; i < 4; i++) {
            bl[i] = new TNode(i,     TNode.NodeKind.SINGULARITY, 0, 2f * i, 0f);
            tl[i] = new TNode(4 + i, TNode.NodeKind.SINGULARITY, 0, 2f * i, 2f);
        }
        TArc[] v = new TArc[4];
        for (int i = 0; i < 4; i++) {
            v[i] = TArc.simple(i, bl[i].id(), tl[i].id(),
                    new ArrayList<>(), 1, 2f);
        }
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

        // Eq.(2) cascading equality: all 4 verticals equal.
        for (int i = 1; i < 4; i++) {
            assertEquals(q[v[0].id()], q[v[i].id()],
                    "vertical " + i + " must equal vertical 0");
        }
        // Eq.(2) cascading equality: all 6 horizontals linked.
        for (int i = 0; i < 3; i++) {
            assertEquals(q[hTop[0].id()], q[hTop[i].id()],
                    "top " + i + " must equal top 0");
            assertEquals(q[hBot[0].id()], q[hBot[i].id()],
                    "bot " + i + " must equal bot 0");
            assertEquals(q[hTop[i].id()], q[hBot[i].id()],
                    "top/bot " + i + " consistency");
        }
        // Lyon coarsening drives both classes to validity floor q=1.
        assertEquals(1, q[v[0].id()]);
        assertEquals(1, q[hTop[0].id()]);
    }

    /** Two patches sharing one arc. Lyon validity Eq.(3) only constrains
     *  classes containing arcs that start at SINGULARITY nodes (= S_i* arcs
     *  per Lyon §4.2). Classes whose arcs all start at INTERSECTION nodes
     *  (continuation arcs past a crash) are unconstrained and coarsen to
     *  q=0 — which is the paper's coarsest behavior.
     *
     *  In this 2-patch test, P1's horizontals (de, ab) start at sings d,a;
     *  P1's verticals (ad, be) include ad at sing a; P2's verticals
     *  (be, cf) include cf at sing c. But P2's horizontals (ef, bc) start
     *  at intersections e and b — no validity constraint on that class →
     *  coarsens to q=0. */
    @Test
    void twoPatchesSharedArcSolved() throws Exception {
        TMesh tmesh = makeTwoPatchesSharedArc();
        Quantization.Result res = Quantization.solve(tmesh);
        assertTrue(res.feasible());
        int[] q = res.arcQuantization();
        // Eq.(2) consistency holds: opposite sides of each patch have equal q.
        // P1 sides 0,2 (ad, be) and 1,3 (de, ab).
        assertEquals(q[0], q[2], "P1: ad == be");
        assertEquals(q[1], q[3], "P1: de == ab");
        // P2 sides 0,2 (be, cf) and 1,3 (ef, bc).
        assertEquals(q[2], q[5], "P2: be == cf");
        assertEquals(q[4], q[6], "P2: ef == bc");
        // Validity-constrained classes (singularity-rooted) coarsen to 1.
        assertEquals(1, q[0], "ad's class (ad,be,cf) ≥1 from sing a");
        assertEquals(1, q[2], "be ↔ ad");
        assertEquals(1, q[5], "cf ↔ ad");
        assertEquals(1, q[1], "de's class (de,ab) ≥1 from sing d");
        assertEquals(1, q[3], "ab ↔ de");
        // Unconstrained class (ef,bc start at intersections) coarsens to 0.
        assertEquals(0, q[4], "ef in unconstrained class → coarsens to 0");
        assertEquals(0, q[6], "bc in unconstrained class → coarsens to 0");
    }

    // ---- helpers ----

    private static TMesh makeSinglePatch(float lLen, float tLen, float rLen, float bLen)
            throws Exception {
        TNode bl = new TNode(0, TNode.NodeKind.SINGULARITY, 0, 0f, 0f);
        TNode br = new TNode(1, TNode.NodeKind.SINGULARITY, 0, 1f, 0f);
        TNode tr = new TNode(2, TNode.NodeKind.SINGULARITY, 0, 1f, 1f);
        TNode tl = new TNode(3, TNode.NodeKind.SINGULARITY, 0, 0f, 1f);
        List<TNode> nodes = Arrays.asList(bl, br, tr, tl);

        TArc left   = new TArc(0, bl.id(), tl.id(), new ArrayList<>(), new ArrayList<>(), 1, lLen);
        TArc top    = new TArc(1, tl.id(), tr.id(), new ArrayList<>(), new ArrayList<>(), 0, tLen);
        TArc right  = new TArc(2, br.id(), tr.id(), new ArrayList<>(), new ArrayList<>(), 1, rLen);
        TArc bottom = new TArc(3, bl.id(), br.id(), new ArrayList<>(), new ArrayList<>(), 0, bLen);
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

        TArc ad = TArc.simple(0, a.id(), d.id(), new ArrayList<>(), 1, 2f);
        TArc de = TArc.simple(1, d.id(), e.id(), new ArrayList<>(), 0, 3f);
        TArc be = TArc.simple(2, b.id(), e.id(), new ArrayList<>(), 1, 2f);
        TArc ab = TArc.simple(3, a.id(), b.id(), new ArrayList<>(), 0, 3f);
        TArc ef = TArc.simple(4, e.id(), f.id(), new ArrayList<>(), 0, 3f);
        TArc cf = TArc.simple(5, c.id(), f.id(), new ArrayList<>(), 1, 2f);
        TArc bc = TArc.simple(6, b.id(), c.id(), new ArrayList<>(), 0, 3f);
        List<TArc> arcs = Arrays.asList(ad, de, be, ab, ef, cf, bc);

        TPatch p1 = TPatch.single(0,
                new int[]{ad.id(), de.id(), be.id(), ab.id()},
                new int[]{a.id(), b.id(), e.id(), d.id()});
        TPatch p2 = TPatch.single(1,
                new int[]{be.id(), ef.id(), cf.id(), bc.id()},
                new int[]{b.id(), c.id(), f.id(), e.id()});
        return newTMesh(nodes, arcs, List.of(p1, p2));
    }

    private static TMesh newTMesh(List<TNode> nodes, List<TArc> arcs, List<TPatch> patches) {
        return TMesh.fromComponents(nodes, arcs, patches);
    }
}
