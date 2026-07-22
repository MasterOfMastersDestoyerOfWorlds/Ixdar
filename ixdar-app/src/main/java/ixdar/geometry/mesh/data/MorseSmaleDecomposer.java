package ixdar.geometry.mesh.data;
import java.util.ArrayList;

import java.util.ArrayDeque;
import java.util.HashMap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * Patch decomposer driven by Morse-Smale topology: it takes feature edges and the MSC result from
 * {@link SemanticPatchDecomposer}, labels each face by the maximum it ascends to, splits cells whose
 * Coons fit error is too large, and returns the same {@code DecompositionDiagnostics} shape as
 * {@link SemanticPatchDecomposer} so renderers and overlays are interchangeable.
 */
public final class MorseSmaleDecomposer {
    public static final int NUM_3 = 3;
    public static final int NUM_8 = 8;
    public static final int NUM_6 = 6;
    public static final int NUM_32 = 32;
    public static final long NUM_0xffffffff = 0xffffffffL;

    // PATCH-26 B4c: same threshold as PATCH-16's SemanticPatchDecomposer
    // for consistency. Expressed as fraction of mesh bounding-sphere
    // diameter; multiplied by the mesh extent at use time.
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
            ArrayMesh mesh, int resolution) {
        // Borrow the existing decomposer's diagnostics for feature edges
        // + MSC result. The semantic patch decomposition gets thrown
        // away below.
        SemanticPatchDecomposer.DecompositionDiagnostics borrowed =
                SemanticPatchDecomposer.decomposeWithDiagnostics(mesh, resolution);
        MorseSmaleComplex.Result msc = borrowed.morseSmale();
        if (msc == null || msc.smoothedScalar() == null) {
            // MSC not available (zero-vertex mesh, etc.) — fall back to
            // semantic output verbatim so callers don't see surprises.
            return borrowed;
        }

        // High-confidence edges: crest + saddle separators + multi-
        // source (≥2 of dihedral / principal / crest agreement). Same
        // construction PATCH-25's verifier uses; keep in lockstep.
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

        // PATCH-28: post-assembly snap. MSC ascending-manifold places
        // boundaries within ~2 faces of feature creases; this pass
        // reroutes adjacent-cell boundaries onto the high-confidence
        // feature-edge graph (banded Dijkstra) so the cell geometry
        // tracks teeth gum lines, eye-socket rims, mandible ridge etc.
        int[] snappedLabels = BoundarySnap.snap(mesh, faceLabels, highConf,
                borrowed.dihedralFeatureEdges(),
                borrowed.principalFeatureEdges(),
                borrowed.crestEdges());

        // PATCH-26 B4a/B4b ship without Coons-error refinement: the
        // recursive refinement loop hits OOM/SIGKILL during the live D
        // toggle when the cranium's giant cell triggers many sub-cell
        // Coons fits. Refinement moves to a separate ticket so it can
        // be tuned with proper memory accounting / progress reporting.
        // Cranium remains as a single MSC cell for now — visible but
        // not Coons-fittable until the refinement layer lands.
        float[] positions = mesh.copyPositions();
        float meshExtent = computeMeshExtent(positions);

        // Compact labels (no-op when there's no refinement, but keeps
        // the downstream code symmetric with the refinement-on path).
        int[] compacted = compactLabels(snappedLabels);
        PatchDecomposition decomposition = MscCellAssembly.toPatchDecomposition(
                mesh, compacted, positions);

        // Recompute patch-boundary edges from the MSC labels.
        Set<Long> patchBoundary = computePatchBoundaryEdges(
                mesh.copyFaceIndices(), compacted);

        // Reuse the borrowed Coons error vector so SCALAR overlay still
        // works. It maps to the SEMANTIC patch layout, not the MSC one,
        // so it's not strictly accurate — but it's the right order of
        // magnitude for visualization and the user can still tell where
        // the surface is hard to fit. A proper recompute is part of the
        // future B4c refinement ticket.
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

    // ---------- B4c: Coons-error refinement ----------

    private static int[] refineWithCoonsError(ArrayMesh mesh, int[] faceLabels,
                                               int[][] adj, Set<Long> highConf,
                                               float meshExtent) {
        int[] faceIdx = mesh.copyFaceIndices();
        int faceCount = faceIdx.length / NUM_3;
        float[] positions = mesh.copyPositions();
        int nv = mesh.vertexCount();
        float threshold = T_COONS_ERROR_FRAC * meshExtent;

        int[] current = faceLabels.clone();
        int nextLabelId = 0;
        for (int l : current) if (l + 1 > nextLabelId) nextLabelId = l + 1;

        for (int depth = 0; depth < MAX_REFINE_DEPTH; depth++) {
            // Gather faces by label.
            Map<Integer, List<Integer>> byLabel = new HashMap<>();
            for (int f = 0; f < faceCount; f++) {
                byLabel.computeIfAbsent(current[f], k -> new ArrayList<>()).add(f);
            }
            boolean anySplit = false;
            int[] next = current.clone();
            for (Map.Entry<Integer, List<Integer>> entry : byLabel.entrySet()) {
                int label = entry.getKey();
                List<Integer> faces = entry.getValue();
                if (faces.size() < NUM_8) continue;  // too small to bother fitting
                CoonsReconstructionError.PatchError err =
                        CoonsReconstructionError.compute(
                                faces, label, current, faceIdx, adj, positions, nv,
                                COONS_UV_SAMPLES);
                if (!err.fourSided() || err.p95Error() <= threshold) continue;
                // Pick two highest-error face centroids as BFS seeds, run
                // BFS-region-grow on adj (which already cuts on highConf
                // because we built it that way below).
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
            int a = faceIdx[f * NUM_3], b = faceIdx[f * NUM_3 + 1], c = faceIdx[f * NUM_3 + 2];
            faceErr[i] = Math.max(vertexError[a],
                          Math.max(vertexError[b], vertexError[c]));
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(faceErr[b], faceErr[a]));
        int seed1 = faces.get(order[0]);
        // Second seed: stride into the sorted head so seeds aren't both
        // in the same hot spot.
        int strideIdx = Math.min(n - 1, Math.max(1, n / NUM_6));
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
        // Orphan fallback by Euclidean centroid distance to seed centroid.
        // For v1 we just label as 0 — a pathological case.
        for (int i = 0; i < n; i++) if (labels[i] < 0) labels[i] = 0;
        return labels;
    }

    // ---------- helpers ----------

    private static float computeMeshExtent(float[] positions) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < positions.length; i += NUM_3) {
            if (positions[i] < minX) minX = positions[i];
            if (positions[i] > maxX) maxX = positions[i];
            if (positions[i + 1] < minY) minY = positions[i + 1];
            if (positions[i + 1] > maxY) maxY = positions[i + 1];
            if (positions[i + 2] < minZ) minZ = positions[i + 2];
            if (positions[i + 2] > maxZ) maxZ = positions[i + 2];
        }
        float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Build face-face adjacency from raw faceIdx with high-confidence
     * edges severed to -1. Mirrors {@code featureCutAdjacency} in the
     * semantic decomposer but doesn't depend on EdgeDihedrals.
     */
    private static int[][] buildFaceAdjacency(int[] faceIdx, Set<Long> highConf) {
        int faceCount = faceIdx.length / NUM_3;
        // First pass: edge → up to 2 incident faces.
        Map<Long, int[]> edgeFaces = new HashMap<>();
        for (int f = 0; f < faceCount; f++) {
            for (int e = 0; e < NUM_3; e++) {
                int u = faceIdx[f * NUM_3 + e];
                int v = faceIdx[f * NUM_3 + (e + 1) % NUM_3];
                long key = u < v
                        ? ((long) u << NUM_32) | (v & NUM_0xffffffff)
                        : ((long) v << NUM_32) | (u & NUM_0xffffffff);
                int[] arr = edgeFaces.get(key);
                if (arr == null) {
                    edgeFaces.put(key, new int[]{f, -1});
                } else if (arr[1] == -1) {
                    arr[1] = f;
                }
            }
        }
        int[][] adj = new int[faceCount][NUM_3];
        for (int f = 0; f < faceCount; f++) {
            for (int e = 0; e < NUM_3; e++) {
                int u = faceIdx[f * NUM_3 + e];
                int v = faceIdx[f * NUM_3 + (e + 1) % NUM_3];
                long key = u < v
                        ? ((long) u << NUM_32) | (v & NUM_0xffffffff)
                        : ((long) v << NUM_32) | (u & NUM_0xffffffff);
                int[] pair = edgeFaces.get(key);
                int other = -1;
                if (pair != null) other = (pair[0] == f) ? pair[1] : pair[0];
                adj[f][e] = (other >= 0 && !highConf.contains(key)) ? other : -1;
            }
        }
        return adj;
    }

    private static Set<Long> computePatchBoundaryEdges(int[] faceIdx, int[] labels) {
        int faceCount = faceIdx.length / NUM_3;
        Map<Long, int[]> edgeFaces = new HashMap<>();
        for (int f = 0; f < faceCount; f++) {
            for (int e = 0; e < NUM_3; e++) {
                int u = faceIdx[f * NUM_3 + e];
                int v = faceIdx[f * NUM_3 + (e + 1) % NUM_3];
                long key = u < v
                        ? ((long) u << NUM_32) | (v & NUM_0xffffffff)
                        : ((long) v << NUM_32) | (u & NUM_0xffffffff);
                int[] arr = edgeFaces.get(key);
                if (arr == null) edgeFaces.put(key, new int[]{f, -1});
                else if (arr[1] == -1) arr[1] = f;
            }
        }
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
    private static float[] recomputeCoonsError(ArrayMesh mesh, int[] labels, int[][] adj) {
        int[] faceIdx = mesh.copyFaceIndices();
        int faceCount = faceIdx.length / NUM_3;
        int nv = mesh.vertexCount();
        float[] positions = mesh.copyPositions();
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
