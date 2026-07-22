package ixdar.geometry.mesh.data;

import org.joml.Vector3f;

/**
 * Bilinear Coons patch evaluator over four cubic Bezier sides forming a quadrilateral, sampled
 * on a regular UV grid. Side orientation is fixed: {@code sideU0} corner₀₀→corner₁₀,
 * {@code sideU1} corner₀₁→corner₁₁, {@code sideV0} corner₀₀→corner₀₁, {@code sideV1}
 * corner₁₀→corner₁₁, and adjacent sides must meet at a shared corner. Near-equal corner control
 * points are averaged rather than rejected.
 */
public final class CoonsEvaluator {
    public static final int NUM_3 = 3;
    public static final float NUM_1 = 1f;
    public static final float NUM_0_5 = 0.5f;

    private CoonsEvaluator() {}

    /**
     * Sample the Coons patch on a {@code samples × samples} UV grid.
     * Returns a flat {@code float[samples*samples*3]} packed row-major
     * as {@code (v_row, u_col)} → xyz.
     *
     * @param sideU0 cubic Bezier along u at v=0 (4 control points)
     * @param sideU1 cubic Bezier along u at v=1
     * @param sideV0 cubic Bezier along v at u=0
     * @param sideV1 cubic Bezier along v at u=1
     * @param samples grid resolution; clamped to a minimum of 2
     * @return packed xyz triples laid out row-major in {@code (v, u)} order
     */
    public static float[] sampleGrid(Vector3f[] sideU0, Vector3f[] sideU1,
                                     Vector3f[] sideV0, Vector3f[] sideV1,
                                     int samples) {
        if (samples < 2) samples = 2;
        Vector3f c00 = averageCorners(sideU0[0], sideV0[0]);
        Vector3f c10 = averageCorners(sideU0[NUM_3], sideV1[0]);
        Vector3f c01 = averageCorners(sideU1[0], sideV0[NUM_3]);
        Vector3f c11 = averageCorners(sideU1[NUM_3], sideV1[NUM_3]);

        float[] out = new float[samples * samples * NUM_3];
        Vector3f pu0 = new Vector3f();
        Vector3f pu1 = new Vector3f();
        Vector3f pv0 = new Vector3f();
        Vector3f pv1 = new Vector3f();
        for (int j = 0; j < samples; j++) {
            float v = j / (float) (samples - 1);
            for (int i = 0; i < samples; i++) {
                float u = i / (float) (samples - 1);
                BezierFit.eval(sideU0, u, pu0);
                BezierFit.eval(sideU1, u, pu1);
                BezierFit.eval(sideV0, v, pv0);
                BezierFit.eval(sideV1, v, pv1);

                float loftUx = (NUM_1 - v) * pu0.x + v * pu1.x;
                float loftUy = (NUM_1 - v) * pu0.y + v * pu1.y;
                float loftUz = (NUM_1 - v) * pu0.z + v * pu1.z;

                float loftVx = (NUM_1 - u) * pv0.x + u * pv1.x;
                float loftVy = (NUM_1 - u) * pv0.y + u * pv1.y;
                float loftVz = (NUM_1 - u) * pv0.z + u * pv1.z;

                float blX = (NUM_1 - u) * (NUM_1 - v) * c00.x + u * (NUM_1 - v) * c10.x
                          + (NUM_1 - u) * v * c01.x + u * v * c11.x;
                float blY = (NUM_1 - u) * (NUM_1 - v) * c00.y + u * (NUM_1 - v) * c10.y
                          + (NUM_1 - u) * v * c01.y + u * v * c11.y;
                float blZ = (NUM_1 - u) * (NUM_1 - v) * c00.z + u * (NUM_1 - v) * c10.z
                          + (NUM_1 - u) * v * c01.z + u * v * c11.z;

                int base = (j * samples + i) * NUM_3;
                out[base]     = loftUx + loftVx - blX;
                out[base + 1] = loftUy + loftVy - blY;
                out[base + 2] = loftUz + loftVz - blZ;
            }
        }
        return out;
    }

    /**
     * Blend four pre-sampled boundary polylines into a discrete bilinear Coons grid. All four
     * sides must have the same sample count and share exact corner points; each boundary
     * row/column of the output then reproduces its input side verbatim.
     *
     * @param sideU0 samples along u at v=0, corner₀₀ → corner₁₀
     * @param sideU1 samples along u at v=1, corner₀₁ → corner₁₁
     * @param sideV0 samples along v at u=0, corner₀₀ → corner₀₁
     * @param sideV1 samples along v at u=1, corner₁₀ → corner₁₁
     * @return packed xyz triples laid out row-major in {@code (v, u)} order,
     *         {@code samples × samples} grid points
     */
    public static float[] blendGrid(Vector3f[] sideU0, Vector3f[] sideU1,
                                    Vector3f[] sideV0, Vector3f[] sideV1) {
        int samples = sideU0.length;
        Vector3f c00 = sideU0[0];
        Vector3f c10 = sideU0[samples - 1];
        Vector3f c01 = sideU1[0];
        Vector3f c11 = sideU1[samples - 1];
        float[] out = new float[samples * samples * NUM_3];
        for (int j = 0; j < samples; j++) {
            float v = j / (float) (samples - 1);
            Vector3f pv0 = sideV0[j];
            Vector3f pv1 = sideV1[j];
            for (int i = 0; i < samples; i++) {
                float u = i / (float) (samples - 1);
                Vector3f pu0 = sideU0[i];
                Vector3f pu1 = sideU1[i];

                float loftUx = (NUM_1 - v) * pu0.x + v * pu1.x;
                float loftUy = (NUM_1 - v) * pu0.y + v * pu1.y;
                float loftUz = (NUM_1 - v) * pu0.z + v * pu1.z;

                float loftVx = (NUM_1 - u) * pv0.x + u * pv1.x;
                float loftVy = (NUM_1 - u) * pv0.y + u * pv1.y;
                float loftVz = (NUM_1 - u) * pv0.z + u * pv1.z;

                float blX = (NUM_1 - u) * (NUM_1 - v) * c00.x + u * (NUM_1 - v) * c10.x
                          + (NUM_1 - u) * v * c01.x + u * v * c11.x;
                float blY = (NUM_1 - u) * (NUM_1 - v) * c00.y + u * (NUM_1 - v) * c10.y
                          + (NUM_1 - u) * v * c01.y + u * v * c11.y;
                float blZ = (NUM_1 - u) * (NUM_1 - v) * c00.z + u * (NUM_1 - v) * c10.z
                          + (NUM_1 - u) * v * c01.z + u * v * c11.z;

                int base = (j * samples + i) * NUM_3;
                out[base]     = loftUx + loftVx - blX;
                out[base + 1] = loftUy + loftVy - blY;
                out[base + 2] = loftUz + loftVz - blZ;
            }
        }
        return out;
    }

    /**
     * Squared distance from {@code (px, py, pz)} to the nearest point
     * in a grid produced by {@link #sampleGrid}. Linear scan — O(N²)
     * per query. Fine for the typical patch sizes we see (≤500 verts
     * × 256 grid points ≈ 128k distance evals per patch).
     *
     * @param grid packed xyz triples (e.g. produced by {@link #sampleGrid})
     * @param px query point x
     * @param py query point y
     * @param pz query point z
     * @return squared distance to the closest point in {@code grid}
     */
    public static float nearestDistanceSquared(float[] grid, float px, float py, float pz) {
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i < grid.length; i += NUM_3) {
            float dx = grid[i] - px;
            float dy = grid[i + 1] - py;
            float dz = grid[i + 2] - pz;
            float d = dx * dx + dy * dy + dz * dz;
            if (d < best) best = d;
        }
        return best;
    }

    private static Vector3f averageCorners(Vector3f a, Vector3f b) {
        return new Vector3f(
                (a.x + b.x) * NUM_0_5,
                (a.y + b.y) * NUM_0_5,
                (a.z + b.z) * NUM_0_5);
    }
}
