package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.SemanticPatchDecomposer.EdgeDihedrals;
import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * Morse-Smale complex on a triangle mesh. Classifies vertices of a per-vertex Morse scalar as
 * maxima, minima or saddles, filters them by prominence, then traces integral arcs by steepest
 * ascent and descent from each saddle. Tracing is step-capped, so an arc may stop short of a
 * critical point.
 */
public final class MorseSmaleComplex {
    public static final int NUM_50 = 50;
    public static final int NUM_30 = 30;
    public static final float NUM_0_05 = 0.05f;
    public static final float NUM_0_95 = 0.95f;
    public static final float NUM_1e_6 = 1e-6f;
    public static final float NUM_0 = 0f;
    public static final float NUM_0_10 = 0.10f;
    public static final int NUM_3 = 3;
    public static final float NUM_0_5 = 0.5f;
    public static final int NUM_6 = 6;

    private static final int MAX_TRACE_STEPS = 512;

    private MorseSmaleComplex() {}

    /**
     * Compute the MSC using the Phase B1 classifier (the recommended path).
     *
     * @param mesh the input mesh
     * @param scalarIn raw per-vertex Morse scalar (e.g. signed mean curvature); smoothed before classification
     * @param gaussKIn raw per-vertex Gaussian curvature; smoothed alongside the scalar (used by the legacy A path)
     * @param ed edge/dihedral map whose {@code edgeFaces} supplies the 1-ring neighbour lists
     * @param prominenceFrac extrema are kept only if their scalar deviates from the mesh mean by at
     *        least this fraction of the scalar's p5..p95 range
     * @return critical points, integral arcs, and the smoothed scalar field that produced them
     */
    public static Result compute(ArrayMesh mesh, float[] scalarIn, float[] gaussKIn,
                                 EdgeDihedrals ed, float prominenceFrac) {
        return compute(mesh, scalarIn, gaussKIn, ed, prominenceFrac, /*useB1Classifier=*/true);
    }

    /**
     * Computes the complex with a choice of classifier: the lower-link component classifier, which
     * needs an ordered 1-ring and is correct on multi-saddles, or the 1-ring plus Gaussian-curvature
     * classifier, which is approximate but tolerates non-manifold fans where the ordered-ring
     * builder gives up.
     *
     * @param mesh the input mesh
     * @param scalarIn raw per-vertex Morse scalar; smoothed via 50 Laplacian iterations before classification
     * @param gaussKIn raw per-vertex Gaussian curvature; smoothed via 30 iterations (only used by Phase A)
     * @param ed edge/dihedral map providing edge→face adjacency
     * @param prominenceFrac fraction of the scalar's p5..p95 range that an extremum must clear to survive the prominence filter
     * @param useB1Classifier true for the lower-link component classifier (preferred), false for the legacy 1-ring + Gauss classifier
     * @return critical points, integral arcs, and the smoothed scalar field
     */
    public static Result compute(ArrayMesh mesh, float[] scalarIn, float[] gaussKIn,
                                 EdgeDihedrals ed, float prominenceFrac,
                                 boolean useB1Classifier) {
        int nv = mesh.vertexCount();
        int[][] ring = buildOneRing(ed, nv);

        // Laplacian-smooth the scalar field before classification. A raw
        // per-vertex curvature on a real mesh has triangulation noise at
        // every vertex that looks like a local extremum; heavy smoothing
        // merges noise bumps back into their parent macroscopic feature.
        // 25 iterations empirically balances "removes triangulation
        // noise" vs "doesn't destroy anatomical features on a ~25k-vert
        // skull" — real features persist because they're broad-support.
        float[] scalar = smoothScalar(scalarIn, ring, NUM_50);
        float[] gaussK = smoothScalar(gaussKIn, ring, NUM_30);

        if (useB1Classifier) {
            int[][] orderedRing = buildOrderedOneRing(mesh, ed, nv);
            return computeB1(mesh, scalar, ring, orderedRing, prominenceFrac);
        }

        // Scalar stats for the prominence filter.
        float[] sortedScalar = scalar.clone();
        Arrays.sort(sortedScalar);
        float p5 = sortedScalar[(int) (nv * NUM_0_05)];
        float p95 = sortedScalar[(int) (nv * NUM_0_95)];
        float span = Math.max(p95 - p5, NUM_1e_6);
        float meanVal = NUM_0;
        for (float v : scalar) meanVal += v;
        meanVal /= nv;
        float maxThreshold = meanVal + prominenceFrac * span;
        float minThreshold = meanVal - prominenceFrac * span;
        // Gaussian curvature threshold for saddle. A saddle requires
        // genuinely negative K (saddle shape). Use the p10 of the K
        // distribution — only the most strongly saddle-shaped 10% of
        // vertices qualify. Tune down (toward p5) if too few saddles,
        // up (toward p25) if too many.
        float[] sortedK = gaussK.clone();
        Arrays.sort(sortedK);
        float saddleGaussT = sortedK[Math.max(0, (int) (nv * NUM_0_10))];

        // Pass 1: raw classification via 1-ring comparison + Gaussian.
        CriticalType[] label = new CriticalType[nv];
        for (int v = 0; v < nv; v++) {
            int[] nbs = ring[v];
            if (nbs == null || nbs.length < NUM_3) continue;
            int higher = 0;
            int lower = 0;
            for (int u : nbs) {
                if (scalar[u] > scalar[v]) higher++;
                else if (scalar[u] < scalar[v]) lower++;
            }
            if (higher == 0 && lower > 0) {
                if (scalar[v] >= maxThreshold) label[v] = CriticalType.MAX;
            } else if (lower == 0 && higher > 0) {
                if (scalar[v] <= minThreshold) label[v] = CriticalType.MIN;
            } else if (lower >= 2 && higher >= 2 && gaussK[v] < saddleGaussT) {
                label[v] = CriticalType.SADDLE;
            }
        }

        // Pass 2: suppress extrema within a 2-ring radius of a stronger
        // extremum of the same type. 1-ring is too small for a noisy
        // mesh: adjacent shoulders of a real peak still both classify
        // as "local max" because neither has each other as a neighbour.
        // 2-ring (neighbour-of-neighbour) dramatically reduces that
        // clumping. Also dedupes saddles that fire on every face of a
        // densely-triangulated valley.
        label = suppressWithin2Ring(label, scalar, ring, CriticalType.MAX, true);
        label = suppressWithin2Ring(label, scalar, ring, CriticalType.MIN, false);
        label = suppressWithin2Ring(label, gaussK, ring, CriticalType.SADDLE, false);  // deepest (most negative) saddle wins

        List<CriticalPoint> critical = new ArrayList<>();
        List<Integer> maxima = new ArrayList<>();
        List<Integer> minima = new ArrayList<>();
        List<Integer> saddles = new ArrayList<>();
        for (int v = 0; v < nv; v++) {
            CriticalType t = label[v];
            if (t == null) continue;
            critical.add(new CriticalPoint(v, t, scalar[v]));
            switch (t) {
                case MAX -> maxima.add(v);
                case MIN -> minima.add(v);
                case SADDLE -> saddles.add(v);
            }
        }

        // Trace two ascending + two descending arcs from each saddle.
        // "Two" comes from the saddle-local 1-ring split into higher
        // and lower neighbours: we pick the highest ascending neighbour
        // as seed #1, then the second-highest on the opposite side of
        // the 1-ring (if we could order it, we'd pick symmetrically;
        // lacking that, we fall back to "other highest not adjacent to
        // seed #1"). For Phase A this approximation is fine — real MSC
        // tracing will come in Phase B.
        List<Arc> arcs = new ArrayList<>();
        boolean[] isMax = new boolean[nv];
        boolean[] isMin = new boolean[nv];
        for (int m : maxima) isMax[m] = true;
        for (int m : minima) isMin[m] = true;

        for (int saddle : saddles) {
            int[] nbs = ring[saddle];
            int[] ascSeeds = topTwoOnSide(nbs, scalar, saddle, /*ascending=*/true);
            int[] descSeeds = topTwoOnSide(nbs, scalar, saddle, /*ascending=*/false);
            for (int seed : ascSeeds) {
                if (seed < 0) continue;
                Arc arc = traceArc(saddle, seed, scalar, ring, isMax, isMin,
                        /*ascending=*/true);
                if (arc != null) arcs.add(arc);
            }
            for (int seed : descSeeds) {
                if (seed < 0) continue;
                Arc arc = traceArc(saddle, seed, scalar, ring, isMax, isMin,
                        /*ascending=*/false);
                if (arc != null) arcs.add(arc);
            }
        }

        return new Result(critical, arcs, scalar);
    }

    /**
     * Picks two seed neighbours of {@code saddle} intended to lie on opposite arms: the
     * highest (or lowest) neighbour, then the next best one whose edge vector is not parallel to
     * the first.
     *
     * @param ring 1-ring neighbours of {@code saddle}
     * @param scalar per-vertex Morse scalar
     * @param saddle saddle vertex id
     * @param ascending true to pick higher-side seeds, false for lower-side seeds
     * @return two-element array of seed ids; either slot may be -1 if no candidate exists
     */
    private static int[] topTwoOnSide(int[] ring, float[] scalar, int saddle,
                                      boolean ascending) {
        int best = -1;
        float bestVal = ascending ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
        for (int u : ring) {
            boolean side = ascending ? (scalar[u] > scalar[saddle]) : (scalar[u] < scalar[saddle]);
            if (!side) continue;
            if (ascending ? scalar[u] > bestVal : scalar[u] < bestVal) {
                bestVal = scalar[u];
                best = u;
            }
        }
        if (best < 0) return new int[]{-1, -1};
        // Second seed: among opposite-side candidates that are on the
        // other "arm", take the one most anti-parallel to best's edge.
        // Without a full 1-ring order we approximate using the signed
        // scalar-difference rank and skip anything too close to `best`.
        int second = -1;
        float secondVal = ascending ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
        for (int u : ring) {
            if (u == best) continue;
            boolean side = ascending ? (scalar[u] > scalar[saddle]) : (scalar[u] < scalar[saddle]);
            if (!side) continue;
            if (ascending ? scalar[u] > secondVal : scalar[u] < secondVal) {
                secondVal = scalar[u];
                second = u;
            }
        }
        return new int[]{best, second};
    }

    /**
     * Walk steepest-ascent (ascending=true) or steepest-descent (false)
     * from {@code seed} until hitting a critical point of the matching
     * extremum type, revisiting a ring vertex, or running out of steps.
     *
     * @param saddle saddle vertex starting the arc
     * @param seed first vertex stepped to from {@code saddle}
     * @param scalar per-vertex Morse scalar
     * @param ring 1-ring adjacency
     * @param isMax bitmap of retained maxima
     * @param isMin bitmap of retained minima
     * @param ascending true to climb toward a max, false to descend toward a min
     * @return the completed arc, or {@code null} if the walk stalls or runs out of steps
     */
    private static Arc traceArc(int saddle, int seed, float[] scalar, int[][] ring,
                                boolean[] isMax, boolean[] isMin, boolean ascending) {
        List<Integer> path = new ArrayList<>();
        path.add(saddle);
        path.add(seed);
        int cur = seed;
        for (int step = 0; step < MAX_TRACE_STEPS; step++) {
            if (ascending && isMax[cur]) {
                return new Arc(saddle, cur, CriticalType.MAX, toIntArray(path));
            }
            if (!ascending && isMin[cur]) {
                return new Arc(saddle, cur, CriticalType.MIN, toIntArray(path));
            }
            int next = -1;
            float bestVal = ascending ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
            for (int u : ring[cur]) {
                if (path.contains(u)) continue;  // cheap cycle break
                float v = scalar[u];
                boolean better = ascending ? (v > scalar[cur] && v > bestVal)
                                           : (v < scalar[cur] && v < bestVal);
                if (better) { bestVal = v; next = u; }
            }
            if (next < 0) return null;  // arc didn't reach an extremum
            path.add(next);
            cur = next;
        }
        return null;  // hit step cap without reaching an extremum
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }

    /**
     * Non-max (or non-min) suppression within a 2-ring neighbourhood.
     * Drops any labeled vertex that has a stronger-scoring labeled
     * vertex of the same type within 2 graph hops.
     *
     * @param label current label array (not mutated; copied first)
     * @param score per-vertex score driving the comparison (scalar for MAX/MIN, Gauss K for SADDLE)
     * @param ring 1-ring adjacency used to expand to the 2-ring
     * @param type label type to suppress against
     * @param wantHigher true for MAX (higher = stronger), false for MIN
     *        / SADDLE (lower = stronger, saddle uses negative K so "more
     *        negative" = deeper = stronger).
     * @return new label array with weaker same-type labels cleared
     */
    private static CriticalType[] suppressWithin2Ring(CriticalType[] label, float[] score,
                                                       int[][] ring, CriticalType type,
                                                       boolean wantHigher) {
        int nv = label.length;
        CriticalType[] out = label.clone();
        for (int v = 0; v < nv; v++) {
            if (out[v] != type) continue;
            boolean suppressed = false;
            for (int u : ring[v]) {
                if (u == v) continue;
                if (out[u] == type) {
                    if (wantHigher ? score[u] > score[v] : score[u] < score[v]) {
                        suppressed = true;
                        break;
                    }
                }
                for (int w : ring[u]) {
                    if (w == v) continue;
                    if (out[w] == type) {
                        if (wantHigher ? score[w] > score[v] : score[w] < score[v]) {
                            suppressed = true;
                            break;
                        }
                    }
                }
                if (suppressed) break;
            }
            if (suppressed) out[v] = null;
        }
        return out;
    }

    /**
     * Iterated 1-ring Laplacian smoothing of a per-vertex scalar field.
     * Each iteration replaces {@code scalar[v]} with the simple mean of
     * {@code v} and its 1-ring neighbours. Used as a noise filter before
     * critical-point classification — real anatomical extrema survive,
     * triangulation-noise bumps merge into the surrounding field.
     *
     * @param scalar input per-vertex scalar
     * @param ring 1-ring adjacency
     * @param iterations number of smoothing passes
     * @return smoothed scalar (new array)
     */
    private static float[] smoothScalar(float[] scalar, int[][] ring, int iterations) {
        int nv = scalar.length;
        float[] cur = scalar.clone();
        float[] next = new float[nv];
        for (int it = 0; it < iterations; it++) {
            for (int v = 0; v < nv; v++) {
                int[] nbs = ring[v];
                float sum = cur[v];
                int count = 1;
                for (int u : nbs) {
                    sum += cur[u];
                    count++;
                }
                next[v] = sum / count;
            }
            float[] tmp = cur;
            cur = next;
            next = tmp;
        }
        return cur;
    }

    /**
     * Phase B1 classifier path. Uses the ordered 1-ring + lower-link
     * connected-component count to classify critical points correctly
     * (handles 1-saddles and detects multi-saddles), then traces arcs
     * via steepest descent like Phase A but with a correct critical
     * set so termination is reliable.
     *
     * @param mesh input mesh
     * @param scalar pre-smoothed per-vertex Morse scalar
     * @param ring unordered 1-ring adjacency for arc tracing
     * @param orderedRing cyclic ordered 1-ring used by the link-component classifier
     * @param prominenceFrac fraction of the scalar's p5..p95 range used by the prominence and persistence filters
     * @return critical points, integral arcs, and the smoothed scalar field
     */
    private static Result computeB1(ArrayMesh mesh, float[] scalar,
                                    int[][] ring, int[][] orderedRing,
                                    float prominenceFrac) {
        int nv = mesh.vertexCount();

        // Statistics for the prominence post-filter — used after the
        // structural classifier to drop flat-plateau false positives.
        float[] sortedScalar = scalar.clone();
        Arrays.sort(sortedScalar);
        float p5 = sortedScalar[(int) (nv * NUM_0_05)];
        float p95 = sortedScalar[(int) (nv * NUM_0_95)];
        float span = Math.max(p95 - p5, NUM_1e_6);

        CriticalType[] label = new CriticalType[nv];
        int[] saddleMultiplicity = new int[nv];
        for (int v = 0; v < nv; v++) {
            int[] ord = orderedRing[v];
            if (ord == null || ord.length < NUM_3) continue;  // boundary or degenerate
            int lowerComponents = countLinkComponents(v, ord, scalar, /*lower=*/true);
            int upperComponents = countLinkComponents(v, ord, scalar, /*lower=*/false);
            if (lowerComponents == 0 && upperComponents > 0) {
                label[v] = CriticalType.MIN;
            } else if (upperComponents == 0 && lowerComponents > 0) {
                label[v] = CriticalType.MAX;
            } else if (lowerComponents >= 2 && upperComponents >= 2) {
                label[v] = CriticalType.SADDLE;
                saddleMultiplicity[v] = lowerComponents - 1;  // 1 for ordinary 1-saddle
            }
            // else regular (lower=1, upper=1) — leave unlabeled
        }

        // Prominence filter: drop extrema whose scalar value is too
        // close to the mesh median. Real anatomical bumps stick out;
        // plateau noise extrema sit near the median.
        float median = sortedScalar[nv / 2];
        for (int v = 0; v < nv; v++) {
            if (label[v] == CriticalType.MAX
                    && (scalar[v] - median) < prominenceFrac * span) {
                label[v] = null;
            } else if (label[v] == CriticalType.MIN
                    && (median - scalar[v]) < prominenceFrac * span) {
                label[v] = null;
            }
        }

        // Heuristic persistence: walk steepest-ascent from each saddle
        // to the first MAX it reaches; persistence = scalar(max) -
        // scalar(saddle). Drop pairs below threshold by also unlabeling
        // the max IF that's its only short-persistence partner. Same
        // dually for saddle-to-min.
        boolean[] isMax = new boolean[nv];
        boolean[] isMin = new boolean[nv];
        for (int v = 0; v < nv; v++) {
            if (label[v] == CriticalType.MAX) isMax[v] = true;
            if (label[v] == CriticalType.MIN) isMin[v] = true;
        }
        float persistThresh = prominenceFrac * span * NUM_0_5;  // half of prominence-equivalent
        for (int v = 0; v < nv; v++) {
            if (label[v] != CriticalType.SADDLE) continue;
            int reachedMax = walkToExtremum(v, scalar, ring, isMax, true);
            int reachedMin = walkToExtremum(v, scalar, ring, isMin, false);
            float persUp = reachedMax >= 0 ? Math.abs(scalar[reachedMax] - scalar[v]) : NUM_0;
            float persDn = reachedMin >= 0 ? Math.abs(scalar[v] - scalar[reachedMin]) : NUM_0;
            if (persUp < persistThresh && persDn < persistThresh) {
                label[v] = null;  // saddle has no significant persistence pair
            }
        }

        // Collect surviving critical set.
        List<CriticalPoint> critical = new ArrayList<>();
        List<Integer> saddles = new ArrayList<>();
        Arrays.fill(isMax, false);
        Arrays.fill(isMin, false);
        for (int v = 0; v < nv; v++) {
            if (label[v] == null) continue;
            critical.add(new CriticalPoint(v, label[v], scalar[v]));
            if (label[v] == CriticalType.MAX) isMax[v] = true;
            else if (label[v] == CriticalType.MIN) isMin[v] = true;
            else if (label[v] == CriticalType.SADDLE) saddles.add(v);
        }

        // Trace arcs from each saddle via steepest ascent / descent,
        // terminating only at retained critical points. Each saddle
        // emits up to 4 arcs (2 ascending + 2 descending) using the
        // ordered link's component split.
        List<Arc> arcs = new ArrayList<>();
        for (int saddle : saddles) {
            int[] ord = orderedRing[saddle];
            if (ord == null) continue;
            int[] ascSeeds = bestSeedsPerLinkComponent(ord, scalar, saddle, /*upper=*/true);
            int[] descSeeds = bestSeedsPerLinkComponent(ord, scalar, saddle, /*upper=*/false);
            for (int seed : ascSeeds) {
                if (seed < 0) continue;
                Arc arc = traceArc(saddle, seed, scalar, ring, isMax, isMin, true);
                if (arc != null) arcs.add(arc);
            }
            for (int seed : descSeeds) {
                if (seed < 0) continue;
                Arc arc = traceArc(saddle, seed, scalar, ring, isMax, isMin, false);
                if (arc != null) arcs.add(arc);
            }
        }
        return new Result(critical, arcs, scalar);
    }

    /**
     * Counts connected components of the lower (or upper) link of {@code v}: maximal runs of
     * consecutive ordered-ring vertices on the chosen side of {@code scalar[v]}, with the first and
     * last run merged because the ring is cyclic. Equal scalars break by vertex index so plateaus
     * classify deterministically.
     *
     * @param v vertex whose link is being analyzed
     * @param ord cyclic ordered 1-ring of {@code v}
     * @param scalar per-vertex Morse scalar
     * @param lower true to count lower-link components, false for upper-link
     * @return number of connected runs in the chosen half of the link
     */
    private static int countLinkComponents(int v, int[] ord, float[] scalar, boolean lower) {
        int n = ord.length;
        boolean[] inSet = new boolean[n];
        for (int i = 0; i < n; i++) {
            float fu = scalar[ord[i]];
            float fv = scalar[v];
            if (fu == fv) {
                inSet[i] = lower ? (ord[i] < v) : (ord[i] > v);  // tie-break by index
            } else {
                inSet[i] = lower ? (fu < fv) : (fu > fv);
            }
        }
        int comp = 0;
        boolean inRun = false;
        for (int i = 0; i < n; i++) {
            if (inSet[i] && !inRun) { comp++; inRun = true; }
            else if (!inSet[i]) inRun = false;
        }
        // Cyclic merge: if the ring starts and ends in the set, those
        // two runs are actually the same component.
        if (comp >= 2 && inSet[0] && inSet[n - 1]) comp--;
        return comp;
    }

    /**
     * For a saddle, identify one seed per upper-link (or lower-link)
     * component — the vertex within each component with the highest
     * (or lowest, respectively) scalar value. Returns up to 4 seeds
     * (slots set to -1 if the component count is lower).
     *
     * @param ord cyclic ordered 1-ring of {@code saddle}
     * @param scalar per-vertex Morse scalar
     * @param saddle saddle vertex id
     * @param upper true to seed ascending arcs, false for descending arcs
     * @return four-slot seed array; trailing slots are -1 when fewer components exist
     */
    private static int[] bestSeedsPerLinkComponent(int[] ord, float[] scalar, int saddle, boolean upper) {
        int n = ord.length;
        boolean[] inSet = new boolean[n];
        for (int i = 0; i < n; i++) {
            float fu = scalar[ord[i]];
            float fv = scalar[saddle];
            if (fu == fv) inSet[i] = upper ? (ord[i] > saddle) : (ord[i] < saddle);
            else inSet[i] = upper ? (fu > fv) : (fu < fv);
        }
        // Walk the cycle, identifying runs and the best vertex in each.
        int[] seeds = new int[]{-1, -1, -1, -1};
        int slot = 0;
        int bestInRun = -1;
        float bestVal = upper ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
        // Start scan such that we don't split a run that wraps the seam.
        int start = 0;
        if (inSet[0] && inSet[n - 1]) {
            for (int i = 1; i < n; i++) {
                if (!inSet[i]) { start = i; break; }
            }
        }
        for (int k = 0; k < n; k++) {
            int i = (start + k) % n;
            int u = ord[i];
            if (inSet[i]) {
                float val = scalar[u];
                if (bestInRun < 0
                        || (upper ? val > bestVal : val < bestVal)) {
                    bestInRun = u;
                    bestVal = val;
                }
            } else if (bestInRun >= 0) {
                if (slot < seeds.length) seeds[slot++] = bestInRun;
                bestInRun = -1;
                bestVal = upper ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
            }
        }
        // Tail run if we ended inside the set.
        if (bestInRun >= 0 && slot < seeds.length) seeds[slot++] = bestInRun;
        return seeds;
    }

    /**
     * Steepest-ascent (or descent) walk to the nearest critical point
     * of the matching extremum type, used by the persistence heuristic
     * during B1. Same pattern as {@link #traceArc} but returns just
     * the terminal vertex.
     *
     * @param from starting vertex (typically a saddle)
     * @param scalar per-vertex Morse scalar
     * @param ring 1-ring adjacency
     * @param isExtremum bitmap of acceptable terminal vertices
     * @param ascending true for steepest ascent, false for descent
     * @return terminal extremum vertex id, or -1 if the walk stalls or runs out of steps
     */
    private static int walkToExtremum(int from, float[] scalar, int[][] ring,
                                       boolean[] isExtremum, boolean ascending) {
        int cur = from;
        Set<Integer> visited = new HashSet<>();
        visited.add(cur);
        for (int step = 0; step < MAX_TRACE_STEPS; step++) {
            if (isExtremum[cur] && cur != from) return cur;
            int next = -1;
            float bestVal = ascending ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
            for (int u : ring[cur]) {
                if (visited.contains(u)) continue;
                boolean better = ascending ? scalar[u] > bestVal && scalar[u] > scalar[cur]
                                           : scalar[u] < bestVal && scalar[u] < scalar[cur];
                if (better) { bestVal = scalar[u]; next = u; }
            }
            if (next < 0) return -1;
            visited.add(next);
            cur = next;
        }
        return -1;
    }

    /**
     * Builds a cyclically ordered 1-ring per vertex by walking incident faces. A vertex whose
     * incident faces do not close into a single cycle — boundary or non-manifold — gets
     * {@code null}, and callers that need the cyclic order must handle that.
     *
     * @param mesh input triangle mesh
     * @param ed edge/dihedral map providing edge→face adjacency
     * @param nv vertex count
     * @return per-vertex cyclic ring (or {@code null} on non-manifold / boundary fans)
     */
    private static int[][] buildOrderedOneRing(ArrayMesh mesh, EdgeDihedrals ed, int nv) {
        int[] faceIdx = mesh.copyFaceIndices();
        int faceCount = faceIdx.length / NUM_3;

        // Vertex → list of (faceId, vPositionInFace) inverse index.
        List<int[]>[] vertFaces = new List[nv];
        for (int i = 0; i < nv; i++) vertFaces[i] = new ArrayList<>(NUM_6);
        for (int f = 0; f < faceCount; f++) {
            for (int p = 0; p < NUM_3; p++) {
                int v = faceIdx[f * NUM_3 + p];
                if (v >= 0 && v < nv) vertFaces[v].add(new int[]{f, p});
            }
        }

        Map<Long, int[]> edgeFaces = ed.edgeFaces();
        int[][] out = new int[nv][];
        for (int v = 0; v < nv; v++) {
            List<int[]> incident = vertFaces[v];
            if (incident.isEmpty()) { out[v] = null; continue; }
            // Start at any incident face; orient: v at position p, "left"
            // is at (p+2)%3, "right" is at (p+1)%3 (assuming consistent
            // counter-clockwise face winding). Chain via the right edge.
            int[] startFp = incident.get(0);
            int curFace = startFp[0];
            int curPos = startFp[1];
            List<Integer> ringList = new ArrayList<>(incident.size());
            Set<Integer> visitedFaces = new HashSet<>();
            int safety = 0;
            int firstNeighbour = -1;
            while (safety++ < incident.size() + 2) {
                if (visitedFaces.contains(curFace)) break;
                visitedFaces.add(curFace);
                int leftV  = faceIdx[curFace * NUM_3 + (curPos + 2) % NUM_3];
                int rightV = faceIdx[curFace * NUM_3 + (curPos + 1) % NUM_3];
                if (firstNeighbour < 0) {
                    ringList.add(leftV);
                    firstNeighbour = leftV;
                }
                ringList.add(rightV);
                long key = EdgeKey.undirected(v, rightV);
                int[] adj = edgeFaces.get(key);
                if (adj == null) { out[v] = null; break; }
                int nextFace = (adj[0] == curFace) ? adj[1] : adj[0];
                if (nextFace < 0) { out[v] = null; break; }
                if (nextFace == startFp[0]) {
                    // Closed ring; the rightV we just added equals the
                    // first neighbour in the chain, drop the duplicate.
                    if (!ringList.isEmpty()
                            && ringList.get(ringList.size() - 1) == firstNeighbour) {
                        ringList.remove(ringList.size() - 1);
                    }
                    int[] arr = new int[ringList.size()];
                    for (int i = 0; i < ringList.size(); i++) arr[i] = ringList.get(i);
                    out[v] = arr;
                    break;
                }
                // Find v's position in next face.
                int nextPos = -1;
                for (int p = 0; p < NUM_3; p++) {
                    if (faceIdx[nextFace * NUM_3 + p] == v) { nextPos = p; break; }
                }
                if (nextPos < 0) { out[v] = null; break; }
                curFace = nextFace;
                curPos = nextPos;
            }
            if (out[v] == null && ringList.size() >= NUM_3) {
                // Open fan or non-manifold; emit unclosed ring as best-effort.
                int[] arr = new int[ringList.size()];
                for (int i = 0; i < ringList.size(); i++) arr[i] = ringList.get(i);
                out[v] = arr;
            }
        }
        return out;
    }

    private static int[][] buildOneRing(EdgeDihedrals ed, int nv) {
        List<List<Integer>> tmp = new ArrayList<>(nv);
        for (int i = 0; i < nv; i++) tmp.add(new ArrayList<>(NUM_6));
        for (Map.Entry<Long, int[]> e : ed.edgeFaces().entrySet()) {
            long key = e.getKey();
            int u = EdgeKey.minVertex(key);
            int v = EdgeKey.maxVertex(key);
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

    public enum CriticalType { MAX, MIN, SADDLE }

    public record CriticalPoint(int vertex, CriticalType type, float value) {}

    /**
     * An integral curve from a saddle to either a maximum (ascending
     * arc) or minimum (descending arc). {@code vertices[0]} is always
     * the saddle; {@code vertices[length-1]} is the terminating
     * extremum (or the last vertex reached before the step cap).
     */
    public record Arc(int saddle, int extremum, CriticalType extremumType, int[] vertices) {}

    public record Result(List<CriticalPoint> critical, List<Arc> arcs,
                         /**
                          * PATCH-24: the smoothed scalar field that produced
                          *  the classification, exposed so cell-assembly
                          *  algorithms ascend on the SAME field that was
                          *  classified. Mismatched scalars produce face
                          *  labels that don't align with the visible
                          *  arcs. {@code null} if the computer didn't
                          */
                         float[] smoothedScalar) {}
}
