package ixdar.geometry.mesh.data.analysis.curvature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer.EdgeDihedrals;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.EdgeKey;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangleSoup;

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

    private static final float T_RIDGE_SCALE = 1.4f;
    private static final float T_VALLEY_SCALE = 1.4f;
    private static final float T_MVS_SQUARED = 0.08f;
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
        PackedPoints positions = ed.positions();

        int[][] ring = TriangleSoup.oneRings(ed.triangles(), vertexIdBound);

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

        boolean[] isRidgePoint = new boolean[vertexIdBound];
        boolean[] isValleyPoint = new boolean[vertexIdBound];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (isRidgeCandidate[vertexId]) {
                if (isLocalExtremum(vertexId, ring[vertexId], positions, pdf, pdf.dirMax(vertexId),
                        /*forRidge=*/true)) {
                    isRidgePoint[vertexId] = true;
                }
            }
            if (isValleyCandidate[vertexId]) {
                if (isLocalExtremum(vertexId, ring[vertexId], positions, pdf, pdf.dirMin(vertexId),
                        /*forRidge=*/false)) {
                    isValleyPoint[vertexId] = true;
                }
            }
        }

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
    private static boolean isLocalExtremum(int v, int[] ring, PackedPoints positions,
                                           PrincipalDirectionField pdf, Vector3f dir,
                                           boolean forRidge) {
        float myVal = forRidge ? Math.abs(pdf.kappaMax(v)) : -pdf.kappaMin(v);
        int posBest = -1, negBest = -1;
        float posBestDot = 0, negBestDot = 0;
        Vector3f origin = positions.at(v);
        for (int u : ring) {
            Vector3f edge = positions.at(u).sub(origin);
            float elen = edge.length();
            if (elen < EPSILON) continue;
            float dot = edge.dot(dir) / elen;
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
                                              PackedPoints positions, boolean forRidge) {
        boolean[] visited = new boolean[isPoint.length];
        List<int[]> out = new ArrayList<>();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int seed = mesh.vertexIdAt(activeVertex);
            if (!isPoint[seed] || visited[seed]) continue;
            List<Integer> forward = traceOneDirection(seed, +1, isPoint, ring, pdf, positions, visited, forRidge);
            List<Integer> backward = traceOneDirection(seed, -1, isPoint, ring, pdf, positions, visited, forRidge);
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
                                                   PackedPoints positions, boolean[] visited,
                                                   boolean forRidge) {
        List<Integer> out = new ArrayList<>();
        int v = seed;
        Vector3f prevStep = null;
        for (int step = 0; step < MAX_TRACE_STEPS; step++) {
            // A ridge runs along the min-curvature direction, a valley along the max.
            Vector3f dir = forRidge ? pdf.dirMin(v) : pdf.dirMax(v);
            if (prevStep != null) {
                float d = dir.dot(prevStep);
                if ((sign > 0 && d < 0) || (sign < 0 && d > 0)) {
                    dir.negate();
                }
            } else if (sign < 0) {
                dir.negate();
            }
            int bestU = -1;
            float bestDot = MIN_FORWARD_PROGRESS;  // require some forward progress
            Vector3f bestStep = null;
            Vector3f origin = positions.at(v);
            for (int u : ring[v]) {
                if (!isPoint[u]) continue;
                if (visited[u]) continue;
                Vector3f edge = positions.at(u).sub(origin);
                float elen = edge.length();
                if (elen < EPSILON) continue;
                float dot = edge.dot(dir) / elen;
                if (dot > bestDot) {
                    bestDot = dot;
                    bestU = u;
                    bestStep = edge.div(elen);
                }
            }
            if (bestU < 0) break;
            visited[bestU] = true;
            out.add(bestU);
            prevStep = bestStep;
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
