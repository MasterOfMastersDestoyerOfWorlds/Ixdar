package ixdar.geometry.mesh.data.analysis.segmentation;
import java.util.ArrayList;

import java.util.ArrayDeque;
import java.util.HashMap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.patches.CoonsReconstructionError;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangleSoup;
import ixdar.geometry.mesh.data.representation.TriangulationRule;
import ixdar.geometry.mesh.data.representation.EdgeKey;

/**
 * Patch decomposer driven by Morse-Smale topology: it takes feature edges and the MSC result from
 * {@link SemanticPatchDecomposer}, labels each face by the maximum it ascends to, splits cells whose
 * Coons fit error is too large, and returns the same {@code DecompositionDiagnostics} shape as
 * {@link SemanticPatchDecomposer} so renderers and overlays are interchangeable.
 */
public final class MorseSmaleDecomposer {
    public static final int MIN_FACES_FOR_REFINEMENT = 8;
    public static final int SECOND_SEED_STRIDE = 6;

    private static final float T_COONS_ERROR_FRAC = 0.025f;
    private static final int   COONS_UV_SAMPLES   = 16;
    private static final int   MAX_REFINE_DEPTH   = 4;

    private MorseSmaleDecomposer() {}

    /**
     * Run the MSC-driven decomposition: borrow feature edges and the MSC result
     * from {@link SemanticPatchDecomposer}, label every face with its
     * ascending-manifold cell, snap boundaries onto the high-confidence feature
     * graph, and repackage as {@link SemanticPatchDecomposer.DecompositionDiagnostics}.
     *
     * @param mesh input mesh
     * @param resolution voxel resolution forwarded to the borrowed semantic decomposer
     * @return diagnostics whose patches/labels come from the MSC pipeline (other fields preserved from the borrow)
     */
    public static SemanticPatchDecomposer.DecompositionDiagnostics decomposeWithDiagnostics(
            MeshTopology mesh, int resolution) {
        SemanticPatchDecomposer.DecompositionDiagnostics borrowed =
                SemanticPatchDecomposer.decomposeWithDiagnostics(mesh, resolution);
        MorseSmaleComplex.Result msc = borrowed.morseSmale();
        if (msc == null || msc.smoothedScalar() == null) {
            return borrowed;
        }

        Set<Long> highConf = new HashSet<>(borrowed.crestEdges());
        highConf.addAll(borrowed.saddleSeparatorEdges());
        Set<Long> dih = borrowed.dihedralFeatureEdges();
        Set<Long> prin = borrowed.principalFeatureEdges();
        Set<Long> crest = borrowed.crestEdges();
        Set<Long> all = new HashSet<>(dih);
        all.addAll(prin);
        all.addAll(crest);
        for (long key : all) {
            int sources = (dih.contains(key) ? 1 : 0)
                        + (prin.contains(key) ? 1 : 0)
                        + (crest.contains(key) ? 1 : 0);
            if (sources >= 2) highConf.add(key);
        }

        int[] faceLabels = MscCellAssembly.ascendingManifold(
                mesh, msc.smoothedScalar(), msc, highConf);

        int[] snappedLabels = BoundarySnap.snap(mesh, faceLabels, highConf,
                borrowed.dihedralFeatureEdges(),
                borrowed.principalFeatureEdges(),
                borrowed.crestEdges());

        PackedPoints positions = SemanticPatchDecomposer.positionsById(mesh);
        float meshExtent = mesh.boundsDiagonal();

        int[] compacted = compactLabels(snappedLabels);
        PatchDecomposition decomposition = MscCellAssembly.toPatchDecomposition(
                mesh, compacted, positions);

        Set<Long> patchBoundary = computePatchBoundaryEdges(
                mesh.triangulate(TriangulationRule.FAN, null), compacted);

        float[] coonsError = borrowed.coonsError();

        return new SemanticPatchDecomposer.DecompositionDiagnostics(
                decomposition,
                compacted,
                borrowed.dihedralFeatureEdges(),
                borrowed.principalFeatureEdges(),
                borrowed.crestEdges(),
                borrowed.saddleSeparatorEdges(),
                borrowed.unionFeatureEdges(),
                patchBoundary,
                coonsError,
                T_COONS_ERROR_FRAC * meshExtent,
                msc);
    }


    private static int[] refineWithCoonsError(MeshTopology mesh, int[] faceLabels,
                                               int[][] adj, Set<Long> highConf,
                                               float meshExtent) {
        int[] faceIdx = mesh.triangulate(TriangulationRule.FAN, null);
        int faceCount = faceIdx.length / 3;
        PackedPoints positions = SemanticPatchDecomposer.positionsById(mesh);
        int nv = mesh.vertexIdBound();
        float threshold = T_COONS_ERROR_FRAC * meshExtent;

        int[] current = faceLabels.clone();
        int nextLabelId = 0;
        for (int l : current) if (l + 1 > nextLabelId) nextLabelId = l + 1;

        for (int depth = 0; depth < MAX_REFINE_DEPTH; depth++) {
            Map<Integer, List<Integer>> byLabel = new HashMap<>();
            for (int f = 0; f < faceCount; f++) {
                byLabel.computeIfAbsent(current[f], k -> new ArrayList<>()).add(f);
            }
            boolean anySplit = false;
            int[] next = current.clone();
            for (Map.Entry<Integer, List<Integer>> entry : byLabel.entrySet()) {
                int label = entry.getKey();
                List<Integer> faces = entry.getValue();
                if (faces.size() < MIN_FACES_FOR_REFINEMENT) continue;  // too small to bother fitting
                CoonsReconstructionError.PatchError err =
                        CoonsReconstructionError.compute(
                                faces, label, current, faceIdx, adj, positions, nv,
                                COONS_UV_SAMPLES);
                if (!err.fourSided() || err.p95Error() <= threshold) continue;
                int[] seedFaces = pickHighErrorSeeds(faces, err.vertexError(),
                        faceIdx);
                int[] sub = bfsRegionGrowInsideCell(faces, seedFaces, adj);
                int newLabelA = label;
                int newLabelB = nextLabelId++;
                for (int i = 0; i < faces.size(); i++) {
                    int f = faces.get(i);
                    next[f] = (sub[i] == 0) ? newLabelA : newLabelB;
                }
                anySplit = true;
            }
            if (!anySplit) break;
            current = next;
        }
        return current;
    }

    private static int[] pickHighErrorSeeds(List<Integer> faces, float[] vertexError,
                                             int[] faceIdx) {
        int n = faces.size();
        float[] faceErr = new float[n];
        for (int i = 0; i < n; i++) {
            int f = faces.get(i);
            int a = faceIdx[f * 3], b = faceIdx[f * 3 + 1], c = faceIdx[f * 3 + 2];
            faceErr[i] = Math.max(vertexError[a],
                          Math.max(vertexError[b], vertexError[c]));
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(faceErr[b], faceErr[a]));
        int seed1 = faces.get(order[0]);
        int strideIdx = Math.min(n - 1, Math.max(1, n / SECOND_SEED_STRIDE));
        int seed2 = faces.get(order[strideIdx]);
        return new int[]{seed1, seed2};
    }

    /**
     * Splits a cell's faces into labels 0 and 1 by BFS from two seeds. {@code adj} must already have
     * high-confidence edges cut to -1 so the growth stops at feature edges; faces unreachable from
     * either seed fall back to the seed with the nearer centroid.
     */
    private static int[] bfsRegionGrowInsideCell(List<Integer> faces, int[] seedFaces,
                                                  int[][] adj) {
        int n = faces.size();
        Map<Integer, Integer> faceToIdx = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) faceToIdx.put(faces.get(i), i);
        int[] labels = new int[n];
        Arrays.fill(labels, -1);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int s = 0; s < 2; s++) {
            Integer idx = faceToIdx.get(seedFaces[s]);
            if (idx == null) continue;
            if (labels[idx] < 0) {
                labels[idx] = s;
                queue.add(new int[]{idx, s});
            }
        }
        while (!queue.isEmpty()) {
            int[] head = queue.poll();
            int idx = head[0];
            int label = head[1];
            int faceId = faces.get(idx);
            for (int nb : adj[faceId]) {
                if (nb < 0) continue;
                Integer nbIdx = faceToIdx.get(nb);
                if (nbIdx == null) continue;
                if (labels[nbIdx] >= 0) continue;
                labels[nbIdx] = label;
                queue.add(new int[]{nbIdx, label});
            }
        }
        for (int i = 0; i < n; i++) if (labels[i] < 0) labels[i] = 0;
        return labels;
    }


    /**
     * Build face-face adjacency from raw faceIdx with high-confidence
     * edges severed to -1. Mirrors {@code featureCutAdjacency} in the
     * semantic decomposer but doesn't depend on EdgeDihedrals.
     */
    private static int[][] buildFaceAdjacency(int[] faceIdx, Set<Long> highConf) {
        int faceCount = faceIdx.length / 3;
        Map<Long, int[]> edgeFaces = TriangleSoup.edgeFaces(faceIdx);
        int[][] adj = new int[faceCount][3];
        for (int f = 0; f < faceCount; f++) {
            for (int e = 0; e < 3; e++) {
                int u = faceIdx[f * 3 + e];
                int v = faceIdx[f * 3 + (e + 1) % 3];
                long key = EdgeKey.undirected(u, v);
                int[] pair = edgeFaces.get(key);
                int other = -1;
                if (pair != null) other = (pair[0] == f) ? pair[1] : pair[0];
                adj[f][e] = (other >= 0 && !highConf.contains(key)) ? other : -1;
            }
        }
        return adj;
    }

    private static Set<Long> computePatchBoundaryEdges(int[] faceIdx, int[] labels) {
        Map<Long, int[]> edgeFaces = TriangleSoup.edgeFaces(faceIdx);
        Set<Long> boundary = new HashSet<>();
        for (Map.Entry<Long, int[]> e : edgeFaces.entrySet()) {
            int[] faces = e.getValue();
            if (faces[1] < 0) continue;
            if (labels[faces[0]] != labels[faces[1]]) boundary.add(e.getKey());
        }
        return boundary;
    }

    /**
     * Recompute per-vertex Coons reconstruction error against the final
     * patch layout. Mirrors the post-split Coons-error pass in
     * {@code SemanticPatchDecomposer.decomposeWithDiagnostics}.
     */
    private static float[] recomputeCoonsError(MeshTopology mesh, int[] labels, int[][] adj) {
        int[] faceIdx = mesh.triangulate(TriangulationRule.FAN, null);
        int faceCount = faceIdx.length / 3;
        int nv = mesh.vertexIdBound();
        PackedPoints positions = SemanticPatchDecomposer.positionsById(mesh);
        int maxLabel = 0;
        for (int l : labels) if (l + 1 > maxLabel) maxLabel = l + 1;
        List<List<Integer>> facesByLabel = new ArrayList<>();
        for (int i = 0; i < maxLabel; i++) facesByLabel.add(new ArrayList<>());
        for (int f = 0; f < faceCount; f++) facesByLabel.get(labels[f]).add(f);
        float[] errs = new float[nv];
        for (int label = 0; label < maxLabel; label++) {
            List<Integer> faces = facesByLabel.get(label);
            if (faces.isEmpty()) continue;
            CoonsReconstructionError.PatchError err =
                    CoonsReconstructionError.compute(
                            faces, label, labels, faceIdx, adj, positions, nv,
                            COONS_UV_SAMPLES);
            if (!err.fourSided()) continue;
            float[] pe = err.vertexError();
            for (int v = 0; v < nv; v++) {
                if (pe[v] > errs[v]) errs[v] = pe[v];
            }
        }
        return errs;
    }

    /** Renumber labels into a contiguous 0..k-1 range. */
    private static int[] compactLabels(int[] labels) {
        int max = 0;
        for (int l : labels) if (l + 1 > max) max = l + 1;
        int[] map = new int[max];
        Arrays.fill(map, -1);
        int next = 0;
        for (int l : labels) {
            if (map[l] < 0) map[l] = next++;
        }
        int[] out = new int[labels.length];
        for (int i = 0; i < labels.length; i++) out[i] = map[labels[i]];
        return out;
    }
}
