package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.SemanticPatchDecomposer.EdgeDihedrals;

/**
 * Yoshizawa-Belyaev-style crest-line detection (PATCH-11).
 *
 * <p>Given a {@link PrincipalDirectionField}, identifies the vertices where
 * one of the principal curvatures is a local extremum perpendicular to its
 * eigenvector, and traces those ridge points into polylines. Mesh edges
 * whose endpoints are both ridge points (or both valley points) of the
 * same trace are emitted as "crest edges" — these feed into the
 * feature-edge set consumed by {@code SemanticPatchDecomposer} so the
 * region-growing pass cannot cross a ridge.
 *
 * <p>Not a full Ohtake 2004 / Yoshizawa 2005 implementation (no implicit
 * surface fitting, no Moreton-Sequin MVS sharpness) but enough of the
 * core idea — local NMS along the perpendicular eigenvector — to catch
 * anatomical ridges on organic meshes like the skull.
 */
public final class CrestLineDetector {
    public static final int NUM_3 = 3;
    public static final float NUM_1e_12 = 1e-12f;
    public static final float NUM_0_1 = 0.1f;
    public static final int NUM_6 = 6;
    public static final int NUM_32 = 32;
    public static final long NUM_0xffffffff = 0xffffffffL;

    // Adaptive threshold: a vertex is a ridge candidate when its
    // |kappaMax| is at least this multiple of the mesh median.
    private static final float T_RIDGE_SCALE = 1.4f;
    private static final float T_VALLEY_SCALE = 1.4f;
    // Moreton-Sequin-lite: reject near-umbilical points where the principal
    // curvatures are too close together (eigenvectors unstable there).
    private static final float T_MVS_SQUARED = 0.08f;
    // Cap traced polyline length to avoid infinite loops on pathological meshes.
    private static final int MAX_TRACE_STEPS = 512;

    private CrestLineDetector() {}

    /**
     * Runs ridge/valley candidate selection, eigenvector NMS, and polyline tracing
     * to return both polylines and the canonical-key edge set used by patch
     * decomposition.
     *
     * @param mesh source triangle mesh (for vertex count, faces, positions)
     * @param ed edge-dihedral metadata (used here to derive 1-ring neighbours)
     * @param pdf per-vertex principal curvatures and eigenvectors
     * @return ridge/valley polylines and the union edge set marking them
     */
    public static CrestLines detect(ArrayMesh mesh, EdgeDihedrals ed, PrincipalDirectionField pdf) {
        int nv = mesh.vertexCount();
        int[] faceIdx = mesh.copyFaceIndices();
        float[] positions = mesh.copyPositions();

        // 1-ring neighbourhoods.
        int[][] ring = buildOneRing(ed, nv);

        // --- Candidate selection (adaptive thresholds) ---
        float ridgeT = thresholdFromMedian(pdf, /*ridge=*/true) * T_RIDGE_SCALE;
        float valleyT = thresholdFromMedian(pdf, /*ridge=*/false) * T_VALLEY_SCALE;

        boolean[] isRidgeCandidate = new boolean[nv];
        boolean[] isValleyCandidate = new boolean[nv];
        for (int v = 0; v < nv; v++) {
            float kMax = pdf.kappaMax(v);
            float kMin = pdf.kappaMin(v);
            float mvs = kMax - kMin;
            boolean notUmbilical = (mvs * mvs) > T_MVS_SQUARED;
            if (notUmbilical) {
                if (Math.abs(kMax) > ridgeT) isRidgeCandidate[v] = true;
                if (-kMin > valleyT) isValleyCandidate[v] = true;
            }
        }

        // --- Non-maximum suppression along the eigenvector ---
        boolean[] isRidgePoint = new boolean[nv];
        boolean[] isValleyPoint = new boolean[nv];
        float[] dir = new float[NUM_3];
        for (int v = 0; v < nv; v++) {
            if (isRidgeCandidate[v]) {
                pdf.dirMax(v, dir);
                if (isLocalExtremum(v, ring[v], positions, pdf, dir, /*forRidge=*/true)) {
                    isRidgePoint[v] = true;
                }
            }
            if (isValleyCandidate[v]) {
                pdf.dirMin(v, dir);
                if (isLocalExtremum(v, ring[v], positions, pdf, dir, /*forRidge=*/false)) {
                    isValleyPoint[v] = true;
                }
            }
        }

        // --- Trace polylines + collect edges ---
        List<int[]> ridgeLines = tracePolylines(isRidgePoint, ring, pdf, positions, /*forRidge=*/true);
        List<int[]> valleyLines = tracePolylines(isValleyPoint, ring, pdf, positions, /*forRidge=*/false);

        Set<Long> crestEdges = new HashSet<>();
        collectEdges(ridgeLines, crestEdges);
        collectEdges(valleyLines, crestEdges);

        return new CrestLines(ridgeLines, valleyLines, crestEdges);
    }

    /** Median of |kappaMax| (ridge) or -kappaMin (valley) across all vertices. */
    private static float thresholdFromMedian(PrincipalDirectionField pdf, boolean ridge) {
        int nv = pdf.vertexCount();
        float[] samples = new float[nv];
        for (int v = 0; v < nv; v++) {
            samples[v] = ridge ? Math.abs(pdf.kappaMax(v)) : -pdf.kappaMin(v);
        }
        Arrays.sort(samples);
        return samples[nv / 2];
    }

    /**
     * True when v's |kappa| is ≥ |kappa| of both 1-ring neighbours most
     * parallel / antiparallel to the eigenvector. The curvature along the
     * ridge is roughly constant; the curvature across it peaks at the
     * ridge. We test across-the-ridge — dirMax for ridges (max curvature
     * direction); v passes only if it locally maximizes that curvature.
     */
    private static boolean isLocalExtremum(int v, int[] ring, float[] positions,
                                           PrincipalDirectionField pdf, float[] dir,
                                           boolean forRidge) {
        float myVal = forRidge ? Math.abs(pdf.kappaMax(v)) : -pdf.kappaMin(v);
        int posBest = -1, negBest = -1;
        float posBestDot = 0, negBestDot = 0;
        for (int u : ring) {
            float ex = positions[u * NUM_3]     - positions[v * NUM_3];
            float ey = positions[u * NUM_3 + 1] - positions[v * NUM_3 + 1];
            float ez = positions[u * NUM_3 + 2] - positions[v * NUM_3 + 2];
            float elen = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (elen < NUM_1e_12) continue;
            float dot = (ex * dir[0] + ey * dir[1] + ez * dir[2]) / elen;
            if (dot > posBestDot) { posBestDot = dot; posBest = u; }
            if (dot < negBestDot) { negBestDot = dot; negBest = u; }
        }
        if (posBest < 0 || negBest < 0) return false;
        float posVal = forRidge ? Math.abs(pdf.kappaMax(posBest)) : -pdf.kappaMin(posBest);
        float negVal = forRidge ? Math.abs(pdf.kappaMax(negBest)) : -pdf.kappaMin(negBest);
        return myVal >= posVal && myVal >= negVal;
    }

    /**
     * Walk ridge/valley points into polylines by following the eigenvector
     * field. At each step, step to the 1-ring neighbour most aligned with
     * the current direction that is also a ridge/valley point. Terminate
     * when no aligned ridge-neighbour exists or we revisit a vertex.
     */
    private static List<int[]> tracePolylines(boolean[] isPoint, int[][] ring,
                                              PrincipalDirectionField pdf,
                                              float[] positions, boolean forRidge) {
        int nv = isPoint.length;
        boolean[] visited = new boolean[nv];
        List<int[]> out = new ArrayList<>();
        float[] dir = new float[NUM_3];
        for (int seed = 0; seed < nv; seed++) {
            if (!isPoint[seed] || visited[seed]) continue;
            // Trace in both directions from seed.
            List<Integer> forward = traceOneDirection(seed, +1, isPoint, ring, pdf, positions, visited, dir, forRidge);
            List<Integer> backward = traceOneDirection(seed, -1, isPoint, ring, pdf, positions, visited, dir, forRidge);
            // Combine reversed-backward + forward (seed appears once).
            List<Integer> full = new ArrayList<>(backward.size() + forward.size());
            for (int i = backward.size() - 1; i >= 0; i--) full.add(backward.get(i));
            full.add(seed);
            full.addAll(forward);
            visited[seed] = true;
            if (full.size() >= 2) {
                int[] arr = new int[full.size()];
                for (int i = 0; i < full.size(); i++) arr[i] = full.get(i);
                out.add(arr);
            }
        }
        return out;
    }

    private static List<Integer> traceOneDirection(int seed, int sign, boolean[] isPoint,
                                                   int[][] ring, PrincipalDirectionField pdf,
                                                   float[] positions, boolean[] visited,
                                                   float[] dir, boolean forRidge) {
        List<Integer> out = new ArrayList<>();
        int v = seed;
        float prevStepX = 0, prevStepY = 0, prevStepZ = 0;
        boolean havePrevStep = false;
        for (int step = 0; step < MAX_TRACE_STEPS; step++) {
            if (forRidge) pdf.dirMin(v, dir);  // ridge runs along min-curvature dir
            else          pdf.dirMax(v, dir);  // valley runs along max-curvature dir
            // Orient dir to stay consistent with previous step (eigenvectors
            // have sign ambiguity; pick the direction that matches momentum).
            if (havePrevStep) {
                float d = dir[0] * prevStepX + dir[1] * prevStepY + dir[2] * prevStepZ;
                if ((sign > 0 && d < 0) || (sign < 0 && d > 0)) {
                    dir[0] = -dir[0]; dir[1] = -dir[1]; dir[2] = -dir[2];
                }
            } else if (sign < 0) {
                dir[0] = -dir[0]; dir[1] = -dir[1]; dir[2] = -dir[2];
            }
            // Find the best 1-ring neighbour: a ridge/valley point whose edge
            // direction is most aligned with `dir`.
            int bestU = -1;
            float bestDot = NUM_0_1;  // require some forward progress
            float bestEx = 0, bestEy = 0, bestEz = 0;
            for (int u : ring[v]) {
                if (!isPoint[u]) continue;
                if (visited[u]) continue;
                float ex = positions[u * NUM_3]     - positions[v * NUM_3];
                float ey = positions[u * NUM_3 + 1] - positions[v * NUM_3 + 1];
                float ez = positions[u * NUM_3 + 2] - positions[v * NUM_3 + 2];
                float elen = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (elen < NUM_1e_12) continue;
                float dot = (ex * dir[0] + ey * dir[1] + ez * dir[2]) / elen;
                if (dot > bestDot) {
                    bestDot = dot;
                    bestU = u;
                    bestEx = ex / elen;
                    bestEy = ey / elen;
                    bestEz = ez / elen;
                }
            }
            if (bestU < 0) break;
            visited[bestU] = true;
            out.add(bestU);
            prevStepX = bestEx;
            prevStepY = bestEy;
            prevStepZ = bestEz;
            havePrevStep = true;
            v = bestU;
        }
        return out;
    }

    /** For each consecutive (v, u) pair in the polylines, mark the edge as a crest edge. */
    private static void collectEdges(List<int[]> polylines, Set<Long> crestEdges) {
        for (int[] line : polylines) {
            for (int i = 0; i + 1 < line.length; i++) {
                crestEdges.add(edgeKey(line[i], line[i + 1]));
            }
        }
    }

    private static int[][] buildOneRing(EdgeDihedrals ed, int nv) {
        List<List<Integer>> tmp = new ArrayList<>(nv);
        for (int i = 0; i < nv; i++) tmp.add(new ArrayList<>(NUM_6));
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

    /** Result holder — polylines for debug/export plus edge set for patch cuts. */
    public static final class CrestLines {
        public final List<int[]> ridgePolylines;  // each int[] is a sequence of vertex indices
        public final List<int[]> valleyPolylines;
        public final Set<Long> crestEdges;

        CrestLines(List<int[]> ridgePolylines, List<int[]> valleyPolylines, Set<Long> crestEdges) {
            this.ridgePolylines = ridgePolylines;
            this.valleyPolylines = valleyPolylines;
            this.crestEdges = crestEdges;
        }
    }
}
