package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TPatch;

/**
 * Lyon 2021 §5.2 strip-based variable reduction (PATCH-72, extended PATCH-84).
 *
 * <p>A strip is a chain of consecutive T-mesh patches bounded by two parallel
 * traces. Within a strip, the arcs running ACROSS the strip (perpendicular
 * to its bounding traces) all share one quantization variable — Eq.(2)
 * consistency is then trivially satisfied. The paper bounds the resulting
 * variable count by {@code (3/2) · n_traces}, an order-of-magnitude smaller
 * than {@code n_arcs}.
 *
 * <p><b>Algorithm (PATCH-84).</b> Build per-arc patch incidence (each arc
 * appears in ≤ 2 patches, once per side). For each arc {@code a}, walk the
 * strip:
 * <ol>
 *   <li>Find {@code a}'s patch {@code p} and side {@code s}.</li>
 *   <li>On the opposite side {@code (s+2)%4} of {@code p}, find arc(s)
 *       {@code b}. If counts match, pair by reversed-position correspondence
 *       (arc[i] on side s ↔ arc[N-1-i] on side s+2 — opposite sides walk in
 *       reversed parametric direction). Union each pair.</li>
 *   <li>For each newly unified arc {@code b}, find {@code b}'s OTHER patch
 *       (the one on the other side of {@code b}); recurse.</li>
 * </ol>
 *
 * <p>When a side has {@code N ≠ M} arcs on its opposite side (true
 * T-junction), the strip ends there — no unification is possible.
 */
public final class StripEquivalence {

    private StripEquivalence() {}

    /** Result: per-arc class id, plus class count. */
    public record Result(int[] arcClass, int classCount) {

        public int[][] arcsByClass() {
            int[] sizes = new int[classCount];
            for (int c : arcClass) sizes[c]++;
            int[][] out = new int[classCount][];
            for (int i = 0; i < classCount; i++) out[i] = new int[sizes[i]];
            int[] idx = new int[classCount];
            for (int a = 0; a < arcClass.length; a++) {
                int c = arcClass[a];
                out[c][idx[c]++] = a;
            }
            return out;
        }
    }

    public static Result compute(TMesh tmesh) {
        int n = tmesh.arcs().size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        List<TPatch> patches = tmesh.patches();

        // ====================================================================
        // PASS 1 — Lyon §5.2 patch-level strip equivalence (the canonical
        // case from the paper). For each 4-sided patch, union opposite-side
        // arcs by reversed-position correspondence. Across-strip arcs
        // (perpendicular to the strip's bounding traces) chain through
        // adjacent patches via shared arcs, building strip equivalence
        // classes. ALL arcs in 4-sided patches participate; this pass alone
        // gives the correct answer on the toy 2x2 grid test.
        // ====================================================================
        for (int pi = 0; pi < patches.size(); pi++) {
            TPatch p = patches.get(pi);
            int[][] sides = p.arcsBySide();
            if (sides == null || sides.length != 4) continue;
            unionByReversedPosition(parent, sides[0], sides[2]);
            unionByReversedPosition(parent, sides[1], sides[3]);
        }

        // ====================================================================
        // PASS 2 — PATCH-92: node-level trace-continuation union for ARCS
        // NOT IN ANY 4-SIDED PATCH (orphans + arcs in non-rectangular cells).
        //
        // Pass 1 alone leaves 1457 of 2753 arcs (53% on rocker-arm) as
        // singleton classes because they sit inside long DCEL cycles, multi-
        // corner cells, or triangle wedges — none of which Pass 1 walks.
        // Per Lyon §3 these arcs SHOULD be in rectangular patches; the fact
        // they aren't is a T-mesh-topology issue. Until that's fixed
        // upstream, we approximate Lyon's strip equivalence at the NODE
        // level: at each TNode, an arc arriving in cardinal X (incoming-X)
        // and an arc leaving in cardinal X (outgoing-X) are collinear
        // continuations of one motorcycle trace. Eq.(2) on the surrounding
        // (possibly-virtual) patch implies q(incoming) = q(outgoing).
        // Union them.
        //
        // This is NOT Lyon's full strip equivalence (which would put across-
        // strip perpendicular arcs in the same class via patch chaining),
        // but it captures the trace-continuation portion that Pass 1 misses
        // for orphan arcs. Result: every arc in the T-mesh ends up in some
        // class, giving #Vars closer to Lyon's bound.
        // ====================================================================
        boolean[] inFourSidedPatch = new boolean[n];
        for (TPatch p : patches) {
            int[][] sides = p.arcsBySide();
            if (sides == null || sides.length != 4) continue;
            for (int s = 0; s < 4; s++) {
                for (int aId : sides[s]) {
                    if (aId >= 0 && aId < n) inFourSidedPatch[aId] = true;
                }
            }
        }

        List<ixdar.geometry.mesh.quadlayout.tmesh.TArc> arcs = tmesh.arcs();
        List<ixdar.geometry.mesh.quadlayout.tmesh.TNode> nodes = tmesh.nodes();
        int nodeCount = nodes.size();

        @SuppressWarnings("unchecked")
        List<int[]>[] arcsAtNode = new List[nodeCount];
        for (int aId = 0; aId < n; aId++) {
            ixdar.geometry.mesh.quadlayout.tmesh.TArc arc = arcs.get(aId);
            int sn = arc.startNode();
            int en = arc.endNode();
            int dirS = arc.directionAtStart();
            int dirE = arc.directionAtEnd();
            if (sn >= 0 && sn < nodeCount) {
                if (arcsAtNode[sn] == null) arcsAtNode[sn] = new ArrayList<>(4);
                arcsAtNode[sn].add(new int[]{aId, dirS, 0});
            }
            if (en >= 0 && en < nodeCount) {
                if (arcsAtNode[en] == null) arcsAtNode[en] = new ArrayList<>(4);
                arcsAtNode[en].add(new int[]{aId, dirE, 1});
            }
        }

        for (int nId = 0; nId < nodeCount; nId++) {
            List<int[]> incident = arcsAtNode[nId];
            if (incident == null || incident.size() < 2) continue;
            int[] outArcAtCard = {-1, -1, -1, -1};
            int[] inArcAtCard  = {-1, -1, -1, -1};
            for (int[] e : incident) {
                int aId = e[0], card = e[1], role = e[2];
                if (card < 0 || card > 3) continue;
                // Skip arcs already in a 4-sided patch — Pass 1 handled them.
                if (inFourSidedPatch[aId]) continue;
                if (role == 0) {
                    if (outArcAtCard[card] < 0) outArcAtCard[card] = aId;
                } else {
                    if (inArcAtCard[card] < 0) inArcAtCard[card] = aId;
                }
            }
            for (int x = 0; x < 4; x++) {
                int in = inArcAtCard[x];
                int out = outArcAtCard[x];
                if (in >= 0 && out >= 0) {
                    union(parent, in, out);
                }
            }
        }

        // Compress and reindex.
        int[] classOf = new int[n];
        HashMap<Integer, Integer> rootToClass = new HashMap<>();
        int classCount = 0;
        for (int i = 0; i < n; i++) {
            int r = find(parent, i);
            Integer c = rootToClass.get(r);
            if (c == null) {
                c = classCount++;
                rootToClass.put(r, c);
            }
            classOf[i] = c;
        }
        return new Result(classOf, classCount);
    }

    /** Pass 1 helper: union arc[k] on sideA with arc[N-1-k] on sideB
     *  when arc counts match. Opposite sides walk in reversed parametric
     *  direction so the i-th arc on side A pairs with the (N-1-i)-th on
     *  side B. Patch chaining via shared arcs propagates strip class
     *  equivalence across patch boundaries. */
    private static void unionByReversedPosition(int[] parent,
                                                  int[] sideA, int[] sideB) {
        if (sideA == null || sideB == null) return;
        if (sideA.length == 0 || sideB.length == 0) return;
        if (sideA.length != sideB.length) return;
        int n = sideA.length;
        for (int i = 0; i < n; i++) {
            union(parent, sideA[i], sideB[n - 1 - i]);
        }
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    public static double[] aggregateTargets(Result strips, double[] arcTargets) {
        double[] sumLen = new double[strips.classCount()];
        int[] count = new int[strips.classCount()];
        for (int a = 0; a < arcTargets.length; a++) {
            int c = strips.arcClass()[a];
            sumLen[c] += arcTargets[a];
            count[c]++;
        }
        double[] out = new double[strips.classCount()];
        for (int c = 0; c < out.length; c++) {
            out[c] = count[c] == 0 ? 0.0 : sumLen[c] / count[c];
        }
        return out;
    }

    public static List<int[]> classSizes(Result strips) {
        int[] sizes = new int[strips.classCount()];
        for (int c : strips.arcClass()) sizes[c]++;
        List<int[]> out = new ArrayList<>();
        out.add(sizes);
        return out;
    }

    /** D1 diagnostic: print class size histogram + top-10 sizes to stdout. */
    public static void dumpStats(Result strips) {
        int[] sizes = new int[strips.classCount()];
        for (int c : strips.arcClass()) sizes[c]++;
        int totalArcs = 0;
        int max = 0;
        // bucket: 0=size1, 1=size2, 2=size3-5, 3=size6-10, 4=size>10
        int[] buckets = new int[5];
        for (int sz : sizes) {
            totalArcs += sz;
            if (sz > max) max = sz;
            if (sz == 1) buckets[0]++;
            else if (sz == 2) buckets[1]++;
            else if (sz <= 5) buckets[2]++;
            else if (sz <= 10) buckets[3]++;
            else buckets[4]++;
        }
        int[] sorted = sizes.clone();
        java.util.Arrays.sort(sorted);
        int[] top10 = new int[Math.min(10, sorted.length)];
        for (int i = 0; i < top10.length; i++) {
            top10[i] = sorted[sorted.length - 1 - i];
        }
        System.out.printf("[strip-eq] %d classes / %d arcs; max=%d; "
                + "buckets [size1=%d, size2=%d, 3-5=%d, 6-10=%d, >10=%d]; top10=%s%n",
                strips.classCount(), totalArcs, max,
                buckets[0], buckets[1], buckets[2], buckets[3], buckets[4],
                java.util.Arrays.toString(top10));
    }
}
