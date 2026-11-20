package ixdar.geometry.mesh.data.patches;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import ixdar.geometry.mesh.data.representation.PackedPoints;

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
     * Sample the Coons patch on a {@code samples × samples} UV grid, row-major as
     * {@code (v_row, u_col)}.
     *
     * @param sideU0 cubic Bezier along u at v=0 (4 control points)
     * @param sideU1 cubic Bezier along u at v=1
     * @param sideV0 cubic Bezier along v at u=0
     * @param sideV1 cubic Bezier along v at u=1
     * @param samples grid resolution; clamped to a minimum of 2
     * @return the grid points laid out row-major in {@code (v, u)} order
     */
    public static PackedPoints sampleGrid(Vector3f[] sideU0, Vector3f[] sideU1,
                                          Vector3f[] sideV0, Vector3f[] sideV1,
                                          int samples) {
        if (samples < 2) samples = 2;
        Vector3f[] corners = {
            new Vector3f(sideU0[0]).lerp(sideV0[0], 0.5f), new Vector3f(sideU0[3]).lerp(sideV1[0], 0.5f),
            new Vector3f(sideU1[0]).lerp(sideV0[3], 0.5f), new Vector3f(sideU1[3]).lerp(sideV1[3], 0.5f),
        };
        PackedPoints out = new PackedPoints(samples * samples);
        for (int j = 0; j < samples; j++) {
            float v = j / (float) (samples - 1);
            for (int i = 0; i < samples; i++) {
                float u = i / (float) (samples - 1);
                out.set(j * samples + i, coonsPoint(BezierFit.eval(sideU0, u, new Vector3f()),
                        BezierFit.eval(sideU1, u, new Vector3f()), BezierFit.eval(sideV0, v, new Vector3f()),
                        BezierFit.eval(sideV1, v, new Vector3f()), corners, u, v));
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
     * @return the grid points laid out row-major in {@code (v, u)} order,
     *         {@code sideU0.length} columns by {@code sideV0.length} rows
     */
    public static PackedPoints blendGrid(Vector3f[] sideU0, Vector3f[] sideU1,
                                         Vector3f[] sideV0, Vector3f[] sideV1) {
        int columns = sideU0.length;
        int rows = sideV0.length;
        Vector3f[] corners = { sideU0[0], sideU0[columns - 1], sideU1[0], sideU1[columns - 1] };
        PackedPoints out = new PackedPoints(columns * rows);
        for (int j = 0; j < rows; j++) {
            float v = j / (float) (rows - 1);
            for (int i = 0; i < columns; i++) {
                float u = i / (float) (columns - 1);
                out.set(j * columns + i, coonsPoint(sideU0[i], sideU1[i], sideV0[j], sideV1[j], corners, u, v));
            }
        }
        return out;
    }

    /**
     * Squared distance from {@code point} to the nearest point in a grid produced by
     * {@link #sampleGrid}. Linear scan — O(N²) per query. Fine for the typical patch sizes we see
     * (≤500 verts × 256 grid points ≈ 128k distance evals per patch).
     *
     * @param grid  grid points (e.g. produced by {@link #sampleGrid})
     * @param point query point
     * @return squared distance to the closest point in {@code grid}
     */
    public static float nearestDistanceSquared(PackedPoints grid, Vector3fc point) {
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i < grid.size(); i++) {
            best = Math.min(best, grid.at(i).distanceSquared(point));
        }
        return best;
    }

    /**
     * One point of a bilinear Coons patch: the two side lofts less the bilinear blend of the
     * corners.
     *
     * @param pu0     the point at {@code u} on the side at v=0
     * @param pu1     the point at {@code u} on the side at v=1
     * @param pv0     the point at {@code v} on the side at u=0
     * @param pv1     the point at {@code v} on the side at u=1
     * @param corners corner₀₀, corner₁₀, corner₀₁, corner₁₁
     * @param u       parameter along the u sides
     * @param v       parameter along the v sides
     * @return a new point
     */
    private static Vector3f coonsPoint(Vector3fc pu0, Vector3fc pu1, Vector3fc pv0, Vector3fc pv1,
            Vector3fc[] corners, float u, float v) {
        Vector3f bilinear = new Vector3f(corners[0]).lerp(corners[1], u)
                .lerp(new Vector3f(corners[2]).lerp(corners[3], u), v);
        return new Vector3f(pu0).lerp(pu1, v).add(new Vector3f(pv0).lerp(pv1, u)).sub(bilinear);
    }
}
