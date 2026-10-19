package ixdar.geometry.mesh.data.analysis.curvature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer.EdgeDihedrals;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.EdgeKey;

/**
 * Crest-line detection from a {@link PrincipalDirectionField}: marks vertices where a principal
 * curvature is a local extremum along its eigenvector, traces them into polylines, and emits
 * edges joining two points of one trace as crest edges.
 *
 * <p>{@code SemanticPatchDecomposer} treats those edges as a barrier to region growing.
 *
 * <p>See also: Yoshizawa 2005
 */
public final class CrestLineDetector {
    public static final float EPSILON = 1e-12f;
    public static final float MIN_FORWARD_PROGRESS = 0.1f;

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
     * @param mesh source mesh, read in place by vertex id
     * @param ed edge-dihedral metadata of {@code mesh}, keyed by vertex ids (gives the 1-rings)
     * @param pdf per-vertex principal curvatures and eigenvectors of {@code mesh}
     * @return ridge/valley polylines of vertex ids and the union edge set marking them
     */
    public static CrestLines detect(MeshTopology mesh, EdgeDihedrals ed, PrincipalDirectionField pdf) {
        int vertexIdBound = mesh.vertexIdBound();
        float[] positions = ed.positions();

        // 1-ring neighbourhoods.
        int[][] ring = PrincipalDirectionField.oneRings(ed, vertexIdBound);

        // --- Candidate selection (adaptive thresholds) ---
        float ridgeT = thresholdFromMedian(mesh, pdf, /*ridge=*/true) * T_RIDGE_SCALE;
        float valleyT = thresholdFromMedian(mesh, pdf, /*ridge=*/false) * T_VALLEY_SCALE;

        boolean[] isRidgeCandidate = new boolean[vertexIdBound];
        boolean[] isValleyCandidate = new boolean[vertexIdBound];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            float kMax = pdf.kappaMax(vertexId);
            float kMin = pdf.kappaMin(vertexId);
            float mvs = kMax - kMin;
            boolean notUmbilical = (mvs * mvs) > T_MVS_SQUARED;
            if (notUmbilical) {
                if (Math.abs(kMax) > ridgeT) isRidgeCandidate[vertexId] = true;
                if (-kMin > valleyT) isValleyCandidate[vertexId] = true;
            }
        }

        // --- Non-maximum suppression along the eigenvector ---
        boolean[] isRidgePoint = new boolean[vertexIdBound];
        boolean[] isValleyPoint = new boolean[vertexIdBound];
        float[] dir = new float[3];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (isRidgeCandidate[vertexId]) {
                pdf.dirMax(vertexId, dir);
                if (isLocalExtremum(vertexId, ring[vertexId], positions, pdf, dir, /*forRidge=*/true)) {
                    isRidgePoint[vertexId] = true;
                }
            }
            if (isValleyCandidate[vertexId]) {
                pdf.dirMin(vertexId, dir);
                if (isLocalExtremum(vertexId, ring[vertexId], positions, pdf, dir, /*forRidge=*/false)) {
                    isValleyPoint[vertexId] = true;
                }
            }
        }

        // --- Trace polylines + collect edges ---
        List<int[]> ridgeLines = tracePolylines(mesh, isRidgePoint, ring, pdf, positions, /*forRidge=*/true);
        List<int[]> valleyLines = tracePolylines(mesh, isValleyPoint, ring, pdf, positions, /*forRidge=*/false);

        Set<Long> crestEdges = new HashSet<>();
        collectEdges(ridgeLines, crestEdges);
        collectEdges(valleyLines, crestEdges);

        return new CrestLines(ridgeLines, valleyLines, crestEdges);
    }

    /** Median of |kappaMax| (ridge) or -kappaMin (valley) across the mesh's vertices. */
    private static float thresholdFromMedian(MeshTopology mesh, PrincipalDirectionField pdf, boolean ridge) {
        int nv = mesh.vertexCount();
        float[] samples = new float[nv];
        for (int activeVertex = 0; activeVertex < nv; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            samples[activeVertex] = ridge ? Math.abs(pdf.kappaMax(vertexId)) : -pdf.kappaMin(vertexId);
        }
        Arrays.sort(samples);
        return samples[nv / 2];
    }

    /**
     * True when v's |kappa| is ≥ |kappa| of both 1-ring neighbours most parallel and
     * antiparallel to {@code dir}. The caller must pass the across-the-ridge eigenvector
     * (dirMax for ridges, dirMin for valleys); along-ridge curvature is near constant and
     * would admit every vertex on the trace.
     */
    private static boolean isLocalExtremum(int v, int[] ring, float[] positions,
                                           PrincipalDirectionField pdf, float[] dir,
                                           boolean forRidge) {
        float myVal = forRidge ? Math.abs(pdf.kappaMax(v)) : -pdf.kappaMin(v);
        int posBest = -1, negBest = -1;
        float posBestDot = 0, negBestDot = 0;
        for (int u : ring) {
            float ex = positions[u * 3]     - positions[v * 3];
            float ey = positions[u * 3 + 1] - positions[v * 3 + 1];
            float ez = positions[u * 3 + 2] - positions[v * 3 + 2];
            float elen = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (elen < EPSILON) continue;
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
    private static List<int[]> tracePolylines(MeshTopology mesh, boolean[] isPoint, int[][] ring,
                                              PrincipalDirectionField pdf,
                                              float[] positions, boolean forRidge) {
        boolean[] visited = new boolean[isPoint.length];
        List<int[]> out = new ArrayList<>();
        float[] dir = new float[3];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int seed = mesh.vertexIdAt(activeVertex);
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
            float bestDot = MIN_FORWARD_PROGRESS;  // require some forward progress
            float bestEx = 0, bestEy = 0, bestEz = 0;
            for (int u : ring[v]) {
                if (!isPoint[u]) continue;
                if (visited[u]) continue;
                float ex = positions[u * 3]     - positions[v * 3];
                float ey = positions[u * 3 + 1] - positions[v * 3 + 1];
                float ez = positions[u * 3 + 2] - positions[v * 3 + 2];
                float elen = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (elen < EPSILON) continue;
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

    private static long edgeKey(int u, int v) {
        return EdgeKey.undirected(u, v);
    }

    /** Result holder — polylines for debug/export plus edge set for patch cuts. */
    public static final class CrestLines {
        public final List<int[]> ridgePolylines;  // each int[] is a sequence of mesh vertex ids
        public final List<int[]> valleyPolylines;
        public final Set<Long> crestEdges;

        CrestLines(List<int[]> ridgePolylines, List<int[]> valleyPolylines, Set<Long> crestEdges) {
            this.ridgePolylines = ridgePolylines;
            this.valleyPolylines = valleyPolylines;
            this.crestEdges = crestEdges;
        }
    }
}
