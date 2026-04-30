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

    /** Per-arc patch incidence record: where this arc sits in a patch. */
    private record ArcInPatch(int patchIdx, int side, int posOnSide) {}

    public static Result compute(TMesh tmesh) {
        int n = tmesh.arcs().size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        List<TPatch> patches = tmesh.patches();
        // Build arc → list of (patch, side, position) incidences.
        @SuppressWarnings("unchecked")
        List<ArcInPatch>[] arcIncidence = new List[n];
        for (int pi = 0; pi < patches.size(); pi++) {
            TPatch p = patches.get(pi);
            int[][] sides = p.arcsBySide();
            if (sides == null || sides.length != 4) continue;
            for (int s = 0; s < 4; s++) {
                int[] sideArcs = sides[s];
                for (int k = 0; k < sideArcs.length; k++) {
                    int aId = sideArcs[k];
                    if (aId < 0 || aId >= n) continue;
                    if (arcIncidence[aId] == null) {
                        arcIncidence[aId] = new ArrayList<>(2);
                    }
                    arcIncidence[aId].add(new ArcInPatch(pi, s, k));
                }
            }
        }

        // For each (patch, side-pair), if both opposing sides have the same
        // arc count, union by reversed-position correspondence.
        for (int pi = 0; pi < patches.size(); pi++) {
            TPatch p = patches.get(pi);
            int[][] sides = p.arcsBySide();
            if (sides == null || sides.length != 4) continue;
            unionByReversedPosition(parent, sides[0], sides[2]);
            unionByReversedPosition(parent, sides[1], sides[3]);
        }

        // Strip-walking: for each arc, walk through its other patch's
        // opposite side. This propagates the union across patch chains
        // (Lyon §5.2 strip walking). The seed unions above prime the
        // reachability; this pass closes the chain.
        boolean changed = true;
        int safety = n * 4;
        while (changed && safety-- > 0) {
            changed = false;
            for (int aId = 0; aId < n; aId++) {
                List<ArcInPatch> incidences = arcIncidence[aId];
                if (incidences == null || incidences.size() < 2) continue;
                // Take the two patches sharing this arc; for each, check that
                // the opposite side's arc(s) are in the same equivalence class.
                for (ArcInPatch ip : incidences) {
                    int[][] sides = patches.get(ip.patchIdx).arcsBySide();
                    if (sides == null || sides.length != 4) continue;
                    int[] sideA = sides[ip.side];
                    int[] sideB = sides[(ip.side + 2) % 4];
                    if (sideA == null || sideB == null) continue;
                    if (sideA.length != sideB.length) continue;
                    int n2 = sideA.length;
                    int j = n2 - 1 - ip.posOnSide;
                    if (j < 0 || j >= n2) continue;
                    int otherArc = sideB[j];
                    if (otherArc < 0 || otherArc >= n) continue;
                    int rA = find(parent, aId);
                    int rB = find(parent, otherArc);
                    if (rA != rB) {
                        parent[rA] = rB;
                        changed = true;
                    }
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

    /** Union arc[k] on sideA with arc[N-1-k] on sideB when counts match. */
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
