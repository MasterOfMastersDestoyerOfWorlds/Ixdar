package ixdar.geometry.mesh.data.patches;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * N-sided generalized Coons patch sampler for cells whose boundary does not split into four
 * chains, sampled by fan-triangulating a regular n-gon domain.
 *
 * <p>Watertightness requires adjacent cells to resolve a shared boundary chain through the same
 * canonical-key Bezier cache rather than fitting curves per cell.
 *
 * <p>See also: Charrot-Gregory 1984
 */
public final class CharrotGregoryPatchSampler {
    public static final double PERCENTILE_95 = 0.95;

    private CharrotGregoryPatchSampler() {}

    /**
     * Sample an N-sided Charrot-Gregory patch.
     *
     * @param faces       cell face indices for vertex-error computation.
     * @param sideBeziers N cubic Bezier control-point arrays in cyclic
     *                    order. Each side is {@code Vector3f[4]}. The
     *                    Beziers must close — {@code side[i][3] == side[(i+1)%N][0]}.
     * @param ringsPerSpoke radial subdivision count (≥1). Total samples
     *                    = 1 (center) + N × ringsPerSpoke.
     * @param edgeSamples per-edge boundary samples for vertex-error scan
     *                    (16 is plenty).
     * @param faceIdx     packed mesh triangle indices.
     * @param positions   packed mesh vertex positions.
     * @param vertexCount mesh vertex count (sizes errs array).
     * @return sampled patch geometry plus per-vertex / aggregate reconstruction error;
     *         an empty patch is returned when fewer than three sides are supplied
     */
    public static SampledPatch sample(List<Integer> faces,
                                       Vector3f[][] sideBeziers,
                                       int ringsPerSpoke,
                                       int edgeSamples,
                                       int[] faceIdx, PackedPoints positions,
                                       int vertexCount) {
        int n = sideBeziers.length;
        if (n < 3) {
            return new SampledPatch(false, new PackedPoints(0), new int[0], new float[vertexCount], 0f, 0f);
        }
        if (ringsPerSpoke < 1) ringsPerSpoke = 1;

        float[] cornerU = new float[n];
        float[] cornerV = new float[n];
        for (int i = 0; i < n; i++) {
            double theta = (i + 0.5) * (2.0 * Math.PI / n) + Math.PI;
            cornerU[i] = (float) Math.cos(theta);
            cornerV[i] = (float) Math.sin(theta);
        }

        int innerVerts = 1 + n * ringsPerSpoke;
        int extraBoundaryPerEdge = Math.max(0, edgeSamples - 2);  // exclude both corner endpoints
        int totalVerts = innerVerts + n * extraBoundaryPerEdge;
        PackedPoints outPositions = new PackedPoints(totalVerts);

        Vector3f tmp = new Vector3f();
        CharrotGregoryPatch.evaluate(sideBeziers, 0f, 0f, tmp);
        outPositions.set(0, tmp);
        for (int k = 1; k <= ringsPerSpoke; k++) {
            float t = k / (float) ringsPerSpoke;
            for (int j = 0; j < n; j++) {
                float u = t * cornerU[j];
                float v = t * cornerV[j];
                int idx = 1 + (k - 1) * n + j;
                CharrotGregoryPatch.evaluate(sideBeziers, u, v, tmp);
                outPositions.set(idx, tmp);
            }
        }
        for (int i = 0; i < n; i++) {
            int next = (i + 1) % n;
            float u0 = cornerU[i], v0 = cornerV[i];
            float u1 = cornerU[next], v1 = cornerV[next];
            for (int m = 1; m < edgeSamples - 1; m++) {
                float t = m / (float) (edgeSamples - 1);
                float u = u0 + t * (u1 - u0);
                float v = v0 + t * (v1 - v0);
                int idx = innerVerts + i * extraBoundaryPerEdge + (m - 1);
                CharrotGregoryPatch.evaluate(sideBeziers, u, v, tmp);
                outPositions.set(idx, tmp);
            }
        }

        List<Integer> tris = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            int a = 1 + j;
            int b = 1 + (j + 1) % n;
            tris.add(0); tris.add(a); tris.add(b);
        }
        for (int k = 2; k <= ringsPerSpoke; k++) {
            int innerBase = 1 + (k - 2) * n;
            int outerBase = 1 + (k - 1) * n;
            for (int j = 0; j < n; j++) {
                int jn = (j + 1) % n;
                int v00 = innerBase + j;
                int v10 = innerBase + jn;
                int v01 = outerBase + j;
                int v11 = outerBase + jn;
                tris.add(v00); tris.add(v01); tris.add(v11);
                tris.add(v00); tris.add(v11); tris.add(v10);
            }
        }
        int[] faceArr = tris.stream().mapToInt(Integer::intValue).toArray();

        float[] errors = new float[vertexCount];
        BitSet touched = new BitSet(vertexCount);
        float[] perPatchErrors = new float[faces.size() * 3];
        int errCount = 0;
        float maxE = 0f;
        for (int f : faces) {
            for (int k = 0; k < 3; k++) {
                int v = faceIdx[f * 3 + k];
                if (touched.get(v)) continue;
                touched.set(v);
                float dsq = nearestDistanceSquared(outPositions,
                        positions.get(v, 0), positions.get(v, 1), positions.get(v, 2));
                float d = (float) Math.sqrt(dsq);
                errors[v] = d;
                if (d > maxE) maxE = d;
                if (errCount < perPatchErrors.length) perPatchErrors[errCount++] = d;
            }
        }
        float p95 = 0f;
        if (errCount > 0) {
            float[] sorted = Arrays.copyOf(perPatchErrors, errCount);
            Arrays.sort(sorted);
            int idx = Math.min(errCount - 1, (int) Math.floor(errCount * PERCENTILE_95));
            p95 = sorted[idx];
        }

        return new SampledPatch(/*fourSided=*/n == 4, outPositions, faceArr, errors, p95, maxE);
    }

    private static float nearestDistanceSquared(PackedPoints grid, float px, float py, float pz) {
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i < grid.size(); i++) {
            float dx = grid.get(i, 0) - px;
            float dy = grid.get(i, 1) - py;
            float dz = grid.get(i, 2) - pz;
            float d = dx * dx + dy * dy + dz * dz;
            if (d < best) best = d;
        }
        return best;
    }

    /**
     * Result of a single Charrot-Gregory patch sampling: the triangulated surface
     * plus reconstruction-error statistics for the patch's source faces.
     *
     * @param fourSided        whether the input was a 4-sided patch (always
     *                         true for {@code sides.length == 4} since the
     *                         Coons evaluator handles it natively).
     * @param sampledPositions sampled surface points.
     * @param sampledFaces     flat triangle vertex indices (3 ints per
     *                         triangle).
     * @param vertexError      per-mesh-vertex world-space distance to
     *                         nearest sampled point.
     * @param p95Error         95th percentile of vertex errors.
     * @param maxError         max vertex error.
     */
    public record SampledPatch(boolean fourSided,
                                PackedPoints sampledPositions,
                                int[] sampledFaces,
                                float[] vertexError,
                                float p95Error,
                                float maxError) {}
}
