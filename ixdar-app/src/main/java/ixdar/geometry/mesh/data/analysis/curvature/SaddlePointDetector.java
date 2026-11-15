package ixdar.geometry.mesh.data.analysis.curvature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer.EdgeDihedrals;
import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.EdgeKey;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangleSoup;

/**
 * Saddle-point separator detection: at vertices where one principal curvature is strongly
 * positive and the other strongly negative, cuts a short chain of edges along the
 * positive-curvature direction, bisecting the perpendicular valley.
 *
 * <p>{@link SemanticPatchDecomposer} unions these edges into both the feature-edge and
 * crest-edge sets, blocking region growing and small-patch merging alike.
 */
public final class SaddlePointDetector {
    public static final float EPSILON = 1e-12f;

    private static final float KMAX_PERCENTILE = 0.90f;
    private static final float KMIN_PERCENTILE = 0.90f;
    private static final int STEPS_PER_SIDE = 6;
    private static final float MIN_DOT = 0.3f;

    private SaddlePointDetector() {}

    /**
     * Detect saddle vertices and grow short separator cuts along each saddle's
     * positive-curvature direction.
     *
     * @param mesh source surface mesh, read in place by vertex id
     * @param ed edge / face dihedral table of {@code mesh}, keyed by vertex ids; supplies the
     *           1-ring used for the cut walk
     * @param pdf per-vertex principal curvatures and directions of {@code mesh}
     * @return separator edges (as packed long keys of vertex ids) plus the saddle seed vertex ids
     */
    public static SaddleSeparators detect(MeshTopology mesh, EdgeDihedrals ed, PrincipalDirectionField pdf) {
        int vertexIdBound = mesh.vertexIdBound();
        float[] positions = ed.positions();
        int[][] ring = TriangleSoup.oneRings(ed.triangles(), vertexIdBound);

        float kMaxT = percentile(mesh, pdf, /*max=*/true, KMAX_PERCENTILE);
        float kMinT = percentile(mesh, pdf, /*max=*/false, KMIN_PERCENTILE);

        boolean[] candidate = new boolean[vertexIdBound];
        float[] strength = new float[vertexIdBound];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            float kmax = pdf.kappaMax(vertexId);
            float kmin = pdf.kappaMin(vertexId);
            if (kmax > kMaxT && kmin < -kMinT) {
                candidate[vertexId] = true;
                strength[vertexId] = Math.min(kmax, -kmin);
            }
        }
        List<Integer> seeds = new ArrayList<>();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (!candidate[vertexId]) continue;
            boolean peak = true;
            for (int neighbourId : ring[vertexId]) {
                if (candidate[neighbourId] && strength[neighbourId] > strength[vertexId]) {
                    peak = false;
                    break;
                }
            }
            if (peak) seeds.add(vertexId);
        }

        Set<Long> out = new HashSet<>();
        for (int seed : seeds) {
            Vector3f dir = pdf.dirMax(seed);
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
    private static void walk(int seed, Vector3f initialDir, int sign, int maxSteps,
                             int[][] ring, float[] positions, Set<Long> out) {
        int v = seed;
        Vector3f dir = new Vector3f(initialDir).mul(sign);
        for (int step = 0; step < maxSteps; step++) {
            int best = -1;
            float bestDot = MIN_DOT;
            Vector3f bestEdge = null;
            Vector3f origin = PackedPoints.at(positions, v * 3);
            for (int u : ring[v]) {
                Vector3f edge = PackedPoints.at(positions, u * 3).sub(origin);
                float elen = edge.length();
                if (elen < EPSILON) continue;
                float dot = edge.dot(dir) / elen;
                if (dot > bestDot) {
                    bestDot = dot;
                    best = u;
                    bestEdge = edge;
                }
            }
            if (best < 0) break;
            out.add(( EdgeKey.undirected(v, best)));
            dir = bestEdge.div(bestEdge.length());
            v = best;
        }
    }

    private static float percentile(MeshTopology mesh, PrincipalDirectionField pdf, boolean max, float pct) {
        int nv = mesh.vertexCount();
        float[] samples = new float[nv];
        for (int activeVertex = 0; activeVertex < nv; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            samples[activeVertex] = max ? Math.max(pdf.kappaMax(vertexId), 0f)
                    : Math.max(-pdf.kappaMin(vertexId), 0f);
        }
        Arrays.sort(samples);
        int idx = Math.min(nv - 1, Math.max(0, Math.round((nv - 1) * pct)));
        return samples[idx];
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
