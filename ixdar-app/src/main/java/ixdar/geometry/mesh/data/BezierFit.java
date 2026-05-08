package ixdar.geometry.mesh.data;

import org.joml.Vector3f;

/**
 * Least-squares fit of a cubic Bezier curve to a polyline sample.
 *
 * <p>Endpoints P₀ and P₃ are pinned to the polyline's first and last
 * points. Interior control points P₁ and P₂ are solved by minimising
 * Σ‖Pᵢ − B(tᵢ)‖² where tᵢ is the chord-length parameterisation and
 * B(t) is the cubic Bernstein expansion. Per-axis the system reduces
 * to a 2×2 linear solve, so this is closed-form arithmetic with no
 * external linear-algebra dependency.
 *
 * <p>Used by PATCH-16 (Coons reconstruction-error base case) to turn
 * each patch boundary side into a smooth curve before the Coons blend.
 */
public final class BezierFit {
    public static final int NUM_3 = 3;
    public static final float NUM_0 = 0f;
    public static final float NUM_1e_20 = 1e-20f;
    public static final float NUM_1 = 1f;
    public static final double NUM_3_0 = 3.0;
    public static final double NUM_1e_20_2 = 1e-20;
    public static final float NUM_3_2 = 3f;
    public static final float NUM_2 = 2f;

    private BezierFit() {}

    /**
     * Fit a cubic Bezier to a polyline. Returns the 4 control points
     * P₀, P₁, P₂, P₃ in order. If the polyline is degenerate (≤1 point
     * or zero total length) returns a straight-line Bezier with
     * P₀ == P₃ or P₀..P₃ collinear.
     *
     * @param indices vertex indices of the polyline samples in order
     * @param positions packed {@code xyz} vertex positions (float[nv*3])
     * @return TODO: describe
     */
    public static Vector3f[] fitCubic(int[] indices, float[] positions) {
        int n = indices == null ? 0 : indices.length;
        Vector3f[] out = new Vector3f[]{new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
        if (n == 0) return out;
        Vector3f p0 = point(indices[0], positions);
        Vector3f p3 = point(indices[n - 1], positions);
        out[0] = new Vector3f(p0);
        out[NUM_3] = new Vector3f(p3);

        if (n == 1) {
            out[1].set(p0);
            out[2].set(p0);
            return out;
        }

        // Chord-length parameterisation.
        float[] t = new float[n];
        float total = NUM_0;
        for (int i = 1; i < n; i++) {
            Vector3f a = point(indices[i - 1], positions);
            Vector3f b = point(indices[i], positions);
            total += a.distance(b);
            t[i] = total;
        }
        if (total < NUM_1e_20) {
            // All points coincide — straight-line Bezier is the only sensible answer.
            out[1].set(p0);
            out[2].set(p3);
            return out;
        }
        for (int i = 1; i < n; i++) t[i] /= total;
        t[n - 1] = NUM_1;

        // Build 2×2 normal-equation system per axis. Bernstein basis:
        //   A₀(t) = (1-t)³    A₁(t) = 3(1-t)²t
        //   A₂(t) = 3(1-t)t²  A₃(t) = t³
        // Residual rᵢ = Pᵢ - A₀ P₀ - A₃ P₃. Solve for [P₁; P₂] in
        //   M · [P₁; P₂] = b where
        //   M = [Σ A₁² , Σ A₁A₂; Σ A₁A₂ , Σ A₂²]
        //   b = [Σ A₁·r ; Σ A₂·r]  (per-axis, so 3 RHS).
        double m00 = 0, m01 = 0, m11 = 0;
        double bx0 = 0, bx1 = 0, by0 = 0, by1 = 0, bz0 = 0, bz1 = 0;
        for (int i = 0; i < n; i++) {
            double ti = t[i];
            double omt = 1.0 - ti;
            double a0 = omt * omt * omt;
            double a1 = NUM_3_0 * omt * omt * ti;
            double a2 = NUM_3_0 * omt * ti * ti;
            double a3 = ti * ti * ti;
            m00 += a1 * a1;
            m01 += a1 * a2;
            m11 += a2 * a2;
            Vector3f pi = point(indices[i], positions);
            double rx = pi.x - a0 * p0.x - a3 * p3.x;
            double ry = pi.y - a0 * p0.y - a3 * p3.y;
            double rz = pi.z - a0 * p0.z - a3 * p3.z;
            bx0 += a1 * rx; bx1 += a2 * rx;
            by0 += a1 * ry; by1 += a2 * ry;
            bz0 += a1 * rz; bz1 += a2 * rz;
        }
        double det = m00 * m11 - m01 * m01;
        if (Math.abs(det) < NUM_1e_20_2) {
            // Fallback: interpolate control points along the chord — happens
            // when the polyline is too short (≤3 points) so the system rank-deficits.
            out[1].set(p0).lerp(p3, NUM_1 / NUM_3_2);
            out[2].set(p0).lerp(p3, NUM_2 / NUM_3_2);
            return out;
        }
        double invDet = 1.0 / det;
        out[1].x = (float) ((m11 * bx0 - m01 * bx1) * invDet);
        out[1].y = (float) ((m11 * by0 - m01 * by1) * invDet);
        out[1].z = (float) ((m11 * bz0 - m01 * bz1) * invDet);
        out[2].x = (float) ((m00 * bx1 - m01 * bx0) * invDet);
        out[2].y = (float) ((m00 * by1 - m01 * by0) * invDet);
        out[2].z = (float) ((m00 * bz1 - m01 * bz0) * invDet);
        return out;
    }

    /**
     * Evaluate a cubic Bezier at parameter {@code t} ∈ [0, 1].
     *
     * @param ctl TODO: describe
     * @param t TODO: describe
     * @param out TODO: describe
     * @return TODO: describe
     */
    public static Vector3f eval(Vector3f[] ctl, float t, Vector3f out) {
        float omt = NUM_1 - t;
        float a0 = omt * omt * omt;
        float a1 = NUM_3_2 * omt * omt * t;
        float a2 = NUM_3_2 * omt * t * t;
        float a3 = t * t * t;
        out.set(
                a0 * ctl[0].x + a1 * ctl[1].x + a2 * ctl[2].x + a3 * ctl[NUM_3].x,
                a0 * ctl[0].y + a1 * ctl[1].y + a2 * ctl[2].y + a3 * ctl[NUM_3].y,
                a0 * ctl[0].z + a1 * ctl[1].z + a2 * ctl[2].z + a3 * ctl[NUM_3].z);
        return out;
    }

    private static Vector3f point(int idx, float[] positions) {
        return new Vector3f(positions[idx * NUM_3], positions[idx * NUM_3 + 1], positions[idx * NUM_3 + 2]);
    }
}
