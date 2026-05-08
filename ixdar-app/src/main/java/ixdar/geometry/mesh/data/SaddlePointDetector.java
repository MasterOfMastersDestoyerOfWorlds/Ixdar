package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.SemanticPatchDecomposer.EdgeDihedrals;

/**
 * Saddle-point separator detection (PATCH-13).
 *
 * <p>Complements {@link CrestLineDetector} for a case the Yoshizawa ridge
 * tracer geometrically cannot handle: adjacent teeth on a mandible. The
 * valley between two adjacent teeth runs ALONG the gum line, not ACROSS
 * the inter-tooth gap. Yoshizawa NMS walks along the min-curvature
 * direction, so it traces the gum-line valley but never produces the
 * perpendicular cuts needed to separate two teeth from each other.
 *
 * <p>At the bottom of an inter-tooth valley the surface is a saddle:
 * one principal curvature is strongly positive (sweeping up onto the
 * tooth to either side), the other strongly negative (the valley
 * running along the row). A short cut ALONG the positive-curvature
 * direction from that saddle bisects the inter-tooth gap.
 *
 * <p>Output is a set of "separator edges" that {@link SemanticPatchDecomposer}
 * unions into {@code allFeatureEdges} and {@code crest.crestEdges} so
 * region-growing cannot cross them and the small-patch merge cannot
 * stitch across them.
 */
public final class SaddlePointDetector {
    public static final int NUM_3 = 3;
    public static final float NUM_1e_12 = 1e-12f;
    public static final float NUM_0 = 0f;
    public static final int NUM_32 = 32;
    public static final long NUM_0xffffffff = 0xffffffffL;

    // Only fire at saddles whose magnitudes exceed these adaptive percentiles
    // of the per-vertex κ distributions. Keeps incidental skull saddles
    // quiet while the inter-tooth saddles — which sit in the top tail of
    // both curvatures — still trip the detector.
    private static final float KMAX_PERCENTILE = 0.90f;
    private static final float KMIN_PERCENTILE = 0.90f;
    // Steps per side to walk from each saddle seed along ±dirMax. A skull
    // tooth is ≈6–10 edges wide, so 6 steps per side (13 edges total)
    // reaches across an inter-tooth gap plus a margin onto the adjacent
    // tooth surfaces, which is what we need to interrupt region-growing
    // cleanly — a cut that only reaches the crown of one tooth lets flood
    // fill go around it.
    private static final int STEPS_PER_SIDE = 6;
    // Minimum edge/direction alignment to take a step. 0.3 ≈ 72° cone,
    // tight enough to avoid hairpin turns but loose enough to survive
    // irregular triangulation at tooth edges.
    private static final float MIN_DOT = 0.3f;

    private SaddlePointDetector() {}

    /**
     * TODO: document {@code detect}.
     *
     * @param mesh TODO: describe
     * @param ed TODO: describe
     * @param pdf TODO: describe
     * @return TODO: describe
     */
    public static SaddleSeparators detect(ArrayMesh mesh, EdgeDihedrals ed, PrincipalDirectionField pdf) {
        int nv = mesh.vertexCount();
        float[] positions = mesh.copyPositions();
        int[][] ring = buildOneRing(ed, nv);

        // Adaptive per-mesh thresholds on κMax and −κMin. Top 10% of each.
        float kMaxT = percentile(pdf, /*max=*/true, KMAX_PERCENTILE);
        float kMinT = percentile(pdf, /*max=*/false, KMIN_PERCENTILE);

        // Candidate saddles: both principals above the adaptive thresholds.
        boolean[] candidate = new boolean[nv];
        float[] strength = new float[nv];
        for (int v = 0; v < nv; v++) {
            float kmax = pdf.kappaMax(v);
            float kmin = pdf.kappaMin(v);
            if (kmax > kMaxT && kmin < -kMinT) {
                candidate[v] = true;
                // Saddle strength = limiting principal. min(|κ₁|,|κ₂|) keeps
                // both signs informative — a vertex with one strong axis
                // and one marginal axis scores below a vertex where both
                // axes are strongly opposite-signed.
                strength[v] = Math.min(kmax, -kmin);
            }
        }
        // NMS: only keep vertices whose saddle strength is maximal in their
        // 1-ring. Without this, a cluster of saddle-like vertices at the
        // bottom of one inter-tooth valley would all fire and produce
        // overlapping cuts that fragment the surrounding teeth.
        List<Integer> seeds = new ArrayList<>();
        for (int v = 0; v < nv; v++) {
            if (!candidate[v]) continue;
            boolean peak = true;
            for (int u : ring[v]) {
                if (candidate[u] && strength[u] > strength[v]) { peak = false; break; }
            }
            if (peak) seeds.add(v);
        }

        Set<Long> out = new HashSet<>();
        float[] dir = new float[NUM_3];
        for (int seed : seeds) {
            pdf.dirMax(seed, dir);
            walk(seed, dir, +1, STEPS_PER_SIDE, ring, positions, out);
            walk(seed, dir, -1, STEPS_PER_SIDE, ring, positions, out);
        }

        int[] saddleVerts = new int[seeds.size()];
        for (int i = 0; i < seeds.size(); i++) saddleVerts[i] = seeds.get(i);
        return new SaddleSeparators(out, saddleVerts);
    }

    /**
     * Walk up to {@code maxSteps} edges outward from {@code seed} along
     * {@code initialDir} (flipped for {@code sign < 0}). Each step picks
     * the 1-ring neighbour whose edge vector is most aligned with the
     * running direction, then updates direction to the traveled edge so
     * the cut follows the actual mesh geometry across the valley.
     */
    private static void walk(int seed, float[] initialDir, int sign, int maxSteps,
                             int[][] ring, float[] positions, Set<Long> out) {
        int v = seed;
        float dx = sign * initialDir[0];
        float dy = sign * initialDir[1];
        float dz = sign * initialDir[2];
        for (int step = 0; step < maxSteps; step++) {
            int best = -1;
            float bestDot = MIN_DOT;
            for (int u : ring[v]) {
                float ex = positions[u * NUM_3]     - positions[v * NUM_3];
                float ey = positions[u * NUM_3 + 1] - positions[v * NUM_3 + 1];
                float ez = positions[u * NUM_3 + 2] - positions[v * NUM_3 + 2];
                float elen = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (elen < NUM_1e_12) continue;
                float dot = (ex * dx + ey * dy + ez * dz) / elen;
                if (dot > bestDot) {
                    bestDot = dot;
                    best = u;
                }
            }
            if (best < 0) break;
            out.add(edgeKey(v, best));
            float ex = positions[best * NUM_3]     - positions[v * NUM_3];
            float ey = positions[best * NUM_3 + 1] - positions[v * NUM_3 + 1];
            float ez = positions[best * NUM_3 + 2] - positions[v * NUM_3 + 2];
            float elen = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (elen < NUM_1e_12) break;
            dx = ex / elen;
            dy = ey / elen;
            dz = ez / elen;
            v = best;
        }
    }

    private static float percentile(PrincipalDirectionField pdf, boolean max, float pct) {
        int nv = pdf.vertexCount();
        float[] samples = new float[nv];
        for (int v = 0; v < nv; v++) {
            samples[v] = max ? Math.max(pdf.kappaMax(v), NUM_0) : Math.max(-pdf.kappaMin(v), NUM_0);
        }
        Arrays.sort(samples);
        int idx = Math.min(nv - 1, Math.max(0, Math.round((nv - 1) * pct)));
        return samples[idx];
    }

    private static int[][] buildOneRing(EdgeDihedrals ed, int nv) {
        List<List<Integer>> tmp = new ArrayList<>(nv);
        for (int i = 0; i < nv; i++) tmp.add(new ArrayList<>(STEPS_PER_SIDE));
        for (Map.Entry<Long, int[]> e : ed.edgeFaces().entrySet()) {
            long key = e.getKey();
            int u = (int) (key >> NUM_32);
            int v = (int) (key & NUM_0xffffffff);
            tmp.get(u).add(v);
            tmp.get(v).add(u);
        }
        int[][] out = new int[nv][];
        for (int i = 0; i < nv; i++) {
            List<Integer> list = tmp.get(i);
            int[] arr = new int[list.size()];
            for (int j = 0; j < list.size(); j++) arr[j] = list.get(j);
            out[i] = arr;
        }
        return out;
    }

    private static long edgeKey(int u, int v) {
        return u < v ? ((long) u << NUM_32) | (v & NUM_0xffffffff) : ((long) v << NUM_32) | (u & NUM_0xffffffff);
    }

    /** Separator edges + seed vertex ids for diagnostic overlay. */
    public static final class SaddleSeparators {
        public final Set<Long> separatorEdges;
        public final int[] saddleVertices;

        SaddleSeparators(Set<Long> separatorEdges, int[] saddleVertices) {
            this.separatorEdges = separatorEdges;
            this.saddleVertices = saddleVertices;
        }
    }
}
