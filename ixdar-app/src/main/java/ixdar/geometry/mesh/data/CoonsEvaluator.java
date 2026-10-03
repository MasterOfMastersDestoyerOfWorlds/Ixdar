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
        Vector3f c10 = averageCorners(sideU0[3], sideV1[0]);
        Vector3f c01 = averageCorners(sideU1[0], sideV0[3]);
        Vector3f c11 = averageCorners(sideU1[3], sideV1[3]);

        float[] out = new float[samples * samples * 3];
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

                float loftUx = (1f - v) * pu0.x + v * pu1.x;
                float loftUy = (1f - v) * pu0.y + v * pu1.y;
                float loftUz = (1f - v) * pu0.z + v * pu1.z;

                float loftVx = (1f - u) * pv0.x + u * pv1.x;
                float loftVy = (1f - u) * pv0.y + u * pv1.y;
                float loftVz = (1f - u) * pv0.z + u * pv1.z;

                float blX = (1f - u) * (1f - v) * c00.x + u * (1f - v) * c10.x
                          + (1f - u) * v * c01.x + u * v * c11.x;
                float blY = (1f - u) * (1f - v) * c00.y + u * (1f - v) * c10.y
                          + (1f - u) * v * c01.y + u * v * c11.y;
                float blZ = (1f - u) * (1f - v) * c00.z + u * (1f - v) * c10.z
                          + (1f - u) * v * c01.z + u * v * c11.z;

                int base = (j * samples + i) * 3;
                out[base]     = loftUx + loftVx - blX;
                out[base + 1] = loftUy + loftVy - blY;
                out[base + 2] = loftUz + loftVz - blZ;
            }
        }
        return out;
    }

    /**
     * Blend four pre-sampled boundary polylines into a discrete bilinear Coons grid, reproducing
     * each input side verbatim along its own border. Opposite sides must have matching sample
     * counts and adjacent ones must share exact corner points.
     *
     * @param sideU0 samples along u at v=0, corner₀₀ → corner₁₀
     * @param sideU1 samples along u at v=1, corner₀₁ → corner₁₁
     * @param sideV0 samples along v at u=0, corner₀₀ → corner₀₁
     * @param sideV1 samples along v at u=1, corner₁₀ → corner₁₁
     * @return packed xyz triples laid out row-major in {@code (v, u)} order,
     *         {@code sideU0.length} columns by {@code sideV0.length} rows
     */
    public static float[] blendGrid(Vector3f[] sideU0, Vector3f[] sideU1,
                                    Vector3f[] sideV0, Vector3f[] sideV1) {
        int columns = sideU0.length;
        int rows = sideV0.length;
        Vector3f c00 = sideU0[0];
        Vector3f c10 = sideU0[columns - 1];
        Vector3f c01 = sideU1[0];
        Vector3f c11 = sideU1[columns - 1];
        float[] out = new float[columns * rows * 3];
        for (int j = 0; j < rows; j++) {
            float v = j / (float) (rows - 1);
            Vector3f pv0 = sideV0[j];
            Vector3f pv1 = sideV1[j];
            for (int i = 0; i < columns; i++) {
                float u = i / (float) (columns - 1);
                Vector3f pu0 = sideU0[i];
                Vector3f pu1 = sideU1[i];

                float loftUx = (1f - v) * pu0.x + v * pu1.x;
                float loftUy = (1f - v) * pu0.y + v * pu1.y;
                float loftUz = (1f - v) * pu0.z + v * pu1.z;

                float loftVx = (1f - u) * pv0.x + u * pv1.x;
                float loftVy = (1f - u) * pv0.y + u * pv1.y;
                float loftVz = (1f - u) * pv0.z + u * pv1.z;

                float blX = (1f - u) * (1f - v) * c00.x + u * (1f - v) * c10.x
                          + (1f - u) * v * c01.x + u * v * c11.x;
                float blY = (1f - u) * (1f - v) * c00.y + u * (1f - v) * c10.y
                          + (1f - u) * v * c01.y + u * v * c11.y;
                float blZ = (1f - u) * (1f - v) * c00.z + u * (1f - v) * c10.z
                          + (1f - u) * v * c01.z + u * v * c11.z;

                int base = (j * columns + i) * 3;
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
        for (int i = 0; i < grid.length; i += 3) {
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
                (a.x + b.x) * 0.5f,
                (a.y + b.y) * 0.5f,
                (a.z + b.z) * 0.5f);
    }
}
