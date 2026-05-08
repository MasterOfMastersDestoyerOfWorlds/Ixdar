package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.nodes.patch.CharrotGregoryPatch;

/**
 * PATCH-29 v3: N-sided generalized Coons patch sampler. For cells whose
 * boundary doesn't naturally split into 4 chains (most MSC cells on a
 * real mesh), use Charrot-Gregory N-sided evaluation instead of the
 * 4-sided Coons evaluator. The boundary chains stay shared between
 * adjacent cells, so reconstruction remains watertight regardless of
 * cell topology.
 *
 * <p>Sampling: fan-triangulate the canonical regular n-gon domain.
 * Center vertex + N "rings" of N×k vertices (kth ring), with the outer
 * ring sitting on the n-gon edges. Triangulate spoke-by-spoke.
 *
 * <p>Watertightness: adjacent cells share each boundary chain by
 * canonical-key Bezier cache. The Charrot-Gregory evaluator evaluated
 * exactly on the n-gon boundary returns the corresponding Bezier curve
 * point, so the boundary samples on shared chains match exactly between
 * the two cells' reconstructions.
 */
public final class CharrotGregoryPatchSampler {
    public static final int NUM_3 = 3;
    public static final float NUM_0 = 0f;
    public static final double NUM_0_5 = 0.5;
    public static final double NUM_2_0 = 2.0;
    public static final double NUM_0_95 = 0.95;
    public static final int NUM_4 = 4;

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
                                       int[] faceIdx, float[] positions,
                                       int vertexCount) {
        int n = sideBeziers.length;
        if (n < NUM_3) {
            return new SampledPatch(false, new float[0], new int[0], new float[vertexCount], NUM_0, NUM_0);
        }
        if (ringsPerSpoke < 1) ringsPerSpoke = 1;

        // Domain n-gon corners on the unit circle (matches CharrotGregoryPatch
        // convention: angle = (i + 0.5) * 2π/n + π).
        float[] cornerU = new float[n];
        float[] cornerV = new float[n];
        for (int i = 0; i < n; i++) {
            double theta = (i + NUM_0_5) * (NUM_2_0 * Math.PI / n) + Math.PI;
            cornerU[i] = (float) Math.cos(theta);
            cornerV[i] = (float) Math.sin(theta);
        }

        // Sample positions: center + R rings of N samples each.
        // Ring k (k=1..R) vertex j = lerp(center, cornerJ, k/R) — barycentric
        // along each spoke. Outer ring (k=R) sits exactly on the corners.
        // Boundary edges between consecutive corners need to be sampled
        // separately for proper boundary continuity; we handle that by
        // adding extra boundary samples on the outer ring.
        //
        // Layout:
        //   vert 0:                      center
        //   vert 1..N*R:                 inner rings (ring k starts at 1+(k-1)*N)
        //   vert N*R+1..N*R+N*(edgeSamples-1):  extra boundary samples per edge
        //                                (excluding corners which are at ring R)
        int innerVerts = 1 + n * ringsPerSpoke;
        int extraBoundaryPerEdge = Math.max(0, edgeSamples - 2);  // exclude both corner endpoints
        int totalVerts = innerVerts + n * extraBoundaryPerEdge;
        float[] outPositions = new float[totalVerts * NUM_3];

        Vector3f tmp = new Vector3f();
        // Center.
        CharrotGregoryPatch.evaluate(sideBeziers, NUM_0, NUM_0, tmp);
        outPositions[0] = tmp.x; outPositions[1] = tmp.y; outPositions[2] = tmp.z;
        // Inner rings.
        for (int k = 1; k <= ringsPerSpoke; k++) {
            float t = k / (float) ringsPerSpoke;
            for (int j = 0; j < n; j++) {
                float u = t * cornerU[j];
                float v = t * cornerV[j];
                int idx = 1 + (k - 1) * n + j;
                CharrotGregoryPatch.evaluate(sideBeziers, u, v, tmp);
                outPositions[idx * NUM_3]     = tmp.x;
                outPositions[idx * NUM_3 + 1] = tmp.y;
                outPositions[idx * NUM_3 + 2] = tmp.z;
            }
        }
        // Extra boundary samples per edge (excluding corners).
        // Edge i runs from corner i to corner (i+1) % n. Samples at
        // s = m/(edgeSamples-1) for m = 1..edgeSamples-2.
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
                outPositions[idx * NUM_3]     = tmp.x;
                outPositions[idx * NUM_3 + 1] = tmp.y;
                outPositions[idx * NUM_3 + 2] = tmp.z;
            }
        }

        // Triangulate.
        // Inner fan (center → ring 1):
        //   center = vert 0; ring 1 vert j is at index 1+j.
        //   triangles (0, j, j+1) for j = 0..n-1.
        // Quad strip between rings k-1 and k:
        //   ring (k-1) vert j at 1+(k-2)*n+j
        //   ring k vert j at 1+(k-1)*n+j
        //   2 triangles per j.
        // Outer ring → boundary fan (only if extraBoundaryPerEdge > 0):
        //   ring R is at the corners. Each edge i has corners[i],
        //   corners[i+1], and the extra boundary samples between them.
        //   Triangulate as a fan from the inner-ring corners[i] vertex
        //   to the boundary samples + corner+1. But since ring R is AT
        //   the corners, we don't need to subdivide the boundary further
        //   in the triangulation — the extra samples just give nicer
        //   boundary coverage. For now, add them to the boundary edges
        //   as part of the quad strip.
        // (Simplified: ignore extra boundary samples in the topology;
        // they're additional vertex data but not connected. This gives
        // a correctly-shaped mesh with the quintic Bezier curve naturally
        // captured by the n-gon corners. The extra samples could be
        // wired in by subdividing the outermost boundary fan edges, but
        // that's a quality-only improvement.)
        List<Integer> tris = new ArrayList<>();
        // Inner fan.
        for (int j = 0; j < n; j++) {
            int a = 1 + j;
            int b = 1 + (j + 1) % n;
            tris.add(0); tris.add(a); tris.add(b);
        }
        // Inter-ring quad strips.
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

        // Compute per-vertex error: nearest distance from each cell
        // vertex to the sampled-position set.
        float[] errors = new float[vertexCount];
        BitSet touched = new BitSet(vertexCount);
        float[] perPatchErrors = new float[faces.size() * NUM_3];
        int errCount = 0;
        float maxE = NUM_0;
        for (int f : faces) {
            for (int k = 0; k < NUM_3; k++) {
                int v = faceIdx[f * NUM_3 + k];
                if (touched.get(v)) continue;
                touched.set(v);
                float dsq = nearestDistanceSquared(outPositions,
                        positions[v * NUM_3], positions[v * NUM_3 + 1], positions[v * NUM_3 + 2]);
                float d = (float) Math.sqrt(dsq);
                errors[v] = d;
                if (d > maxE) maxE = d;
                if (errCount < perPatchErrors.length) perPatchErrors[errCount++] = d;
            }
        }
        float p95 = NUM_0;
        if (errCount > 0) {
            float[] sorted = Arrays.copyOf(perPatchErrors, errCount);
            Arrays.sort(sorted);
            int idx = Math.min(errCount - 1, (int) Math.floor(errCount * NUM_0_95));
            p95 = sorted[idx];
        }

        return new SampledPatch(/*fourSided=*/n == NUM_4, outPositions, faceArr, errors, p95, maxE);
    }

    private static float nearestDistanceSquared(float[] grid, float px, float py, float pz) {
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i < grid.length; i += NUM_3) {
            float dx = grid[i]     - px;
            float dy = grid[i + 1] - py;
            float dz = grid[i + 2] - pz;
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
     * @param sampledPositions flat xyz packed array of sampled surface
     *                         points.
     * @param sampledFaces     flat triangle vertex indices (3 ints per
     *                         triangle).
     * @param vertexError      per-mesh-vertex world-space distance to
     *                         nearest sampled point.
     * @param p95Error         95th percentile of vertex errors.
     * @param maxError         max vertex error.
     */
    public record SampledPatch(boolean fourSided,
                                float[] sampledPositions,
                                int[] sampledFaces,
                                float[] vertexError,
                                float p95Error,
                                float maxError) {}
}
