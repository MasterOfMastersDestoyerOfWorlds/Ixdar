package ixdar.geometry.mesh.data;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import org.joml.Vector3f;

/**
 * Orchestrator for PATCH-16: given one mesh patch, return per-vertex
 * reconstruction error against a 4-sided cubic Coons patch fit to its
 * boundary. Per-vertex errors are distances from each patch vertex to
 * the nearest sampled point on the Coons surface.
 *
 * <p>Composition of three helpers: {@link PatchBoundaryWalker} to turn
 * the patch faces into 4 ordered boundary polylines, {@link BezierFit}
 * to turn each polyline into a cubic Bezier, and {@link CoonsEvaluator}
 * to sample the Coons blend.
 */
public final class CoonsReconstructionError {

    private CoonsReconstructionError() {}

    /**
     * @param fourSided true iff the boundary parsed into exactly 4 sides
     *                  (the only case the Coons fit is defined). When
     *                  false {@code vertexError} is all zero — the
     *                  caller should fall back to shape-proxy heuristics
     *                  for that patch.
     */
    public record PatchError(boolean fourSided, float[] vertexError, float p95Error, float maxError) {}

    /**
     * Compute per-patch reconstruction error.
     *
     * @param faces patch's face indices (triangle ids into faceIdx)
     * @param patchId id of this patch in {@code facePatch}
     * @param facePatch face → patch id mapping for the whole mesh
     * @param faceIdx packed triangle vertex indices
     * @param adj face-face adjacency from {@code buildFaceAdjacency}
     * @param positions packed vertex positions (float[nv*3])
     * @param vertexCount total mesh vertex count — sizes the return array
     * @param uvSamples resolution of the Coons UV grid (16 is plenty for
     *                  typical patches; larger = more accurate nearest-
     *                  point lookup but O(N²) distance scan cost).
     */
    public static PatchError compute(List<Integer> faces, int patchId, int[] facePatch,
                                     int[] faceIdx, int[][] adj, float[] positions,
                                     int vertexCount, int uvSamples) {
        float[] errors = new float[vertexCount];
        PatchBoundaryWalker.BoundarySides bs = PatchBoundaryWalker.extract(
                faces, facePatch, patchId, faceIdx, adj, positions);
        if (bs == null || bs.sides().size() != 4) {
            return new PatchError(false, errors, 0f, 0f);
        }

        // Build Beziers. Order matters for the Coons corner convention:
        //   side0 (u0) from corner₀ → corner₁
        //   side1 (v1) from corner₁ → corner₂
        //   side2 (u1-reversed) from corner₂ → corner₃  (Coons wants u=0→1 at v=1)
        //   side3 (v0-reversed) from corner₃ → corner₀
        // Concretely: if the walker's sides are s0..s3 in ring order
        // (corner0→1, 1→2, 2→3, 3→0), then feed the CoonsEvaluator:
        //   sideU0 = s0
        //   sideV1 = s1
        //   sideU1 = reverse(s2)
        //   sideV0 = reverse(s3)
        int[] s0 = bs.sides().get(0);
        int[] s1 = bs.sides().get(1);
        int[] s2 = bs.sides().get(2);
        int[] s3 = bs.sides().get(3);
        Vector3f[] bezU0 = BezierFit.fitCubic(s0, positions);
        Vector3f[] bezV1 = BezierFit.fitCubic(s1, positions);
        Vector3f[] bezU1 = BezierFit.fitCubic(reversed(s2), positions);
        Vector3f[] bezV0 = BezierFit.fitCubic(reversed(s3), positions);

        float[] grid = CoonsEvaluator.sampleGrid(bezU0, bezU1, bezV0, bezV1, uvSamples);

        // Walk the patch's vertices, compute distance-to-grid for each.
        BitSet touched = new BitSet(vertexCount);
        float p95 = 0f;
        float max = 0f;
        int count = 0;
        float[] perPatchErrors = new float[faces.size() * 3];
        for (int f : faces) {
            for (int k = 0; k < 3; k++) {
                int v = faceIdx[f * 3 + k];
                if (touched.get(v)) continue;
                touched.set(v);
                float dsq = CoonsEvaluator.nearestDistanceSquared(
                        grid,
                        positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]);
                float d = (float) Math.sqrt(dsq);
                errors[v] = d;
                if (d > max) max = d;
                if (count < perPatchErrors.length) perPatchErrors[count++] = d;
            }
        }
        if (count > 0) {
            float[] sorted = Arrays.copyOf(perPatchErrors, count);
            Arrays.sort(sorted);
            int idx = Math.min(count - 1, (int) Math.floor(count * 0.95));
            p95 = sorted[idx];
        }
        return new PatchError(true, errors, p95, max);
    }

    private static int[] reversed(int[] in) {
        int n = in.length;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = in[n - 1 - i];
        return out;
    }
}
