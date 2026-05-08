package ixdar.geometry.mesh.nodes.patch;

import org.joml.Vector3f;

/**
 * N-sided Charrot–Gregory patch evaluator. Given N cubic Bézier boundary
 * curves forming a closed loop (C_i(1) = C_{i+1}(0) for each i), produces a
 * smooth parametric surface that interpolates all N boundaries using only
 * positional information (no cross-derivative constraints required).
 *
 * <p>Direct Java port of the Blender-Procedural-Human implementation
 * ({@code procedural_human/geo_node_groups/charrot_gregory_patch.py}),
 * following the formulation from Salvi 2020 ("A multi-sided generalization
 * of the C0 Coons patch", arXiv:2002.11347). The math:
 * <ul>
 *   <li>Wachspress-style coordinates parameterize each domain point by per-edge
 *       perpendicular distances in a regular n-gon.
 *   <li>One Coons ribbon is formed over each consecutive triple of edges
 *       (C_{i-1}, C_i, C_{i+1}), with an "opposite" curve reconstructed from
 *       the tangents of C_{i-2} and C_{i+2}.
 *   <li>The final position is a (1-d_i)^2-weighted blend of the N ribbons,
 *       normalised by the weight sum.
 * </ul>
 *
 * <p>For n=3 the "opposite" curve degenerates to a point (C_{i+1}(1) = C_{i-1}(0))
 * — the construction handles this case automatically because tan_ip2 and
 * tan_im2 are the short edges of a degenerate bezier.
 *
 * <p>The Blender impl applies a quintic smoother-step to s_i and d_i before
 * the ribbon evaluation — this is a quality improvement over the paper's
 * raw formulation and we replicate it for parity.
 *
 * <p>Pure math, no dependency on Ixdar mesh types — kept testable in isolation
 * against fixtures exported from the Blender reference.
 */
public final class CharrotGregoryPatch {
    public static final int NUM_3 = 3;
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_1_5 = 1.5f;
    public static final float NUM_3_2 = 3f;
    public static final float NUM_6 = 6f;
    public static final float NUM_15 = 15f;
    public static final float NUM_10 = 10f;

    private static final float EPS = 1e-8f;
    private static final float TWO_PI = (float) (2.0 * Math.PI);
    private static final float PI = (float) Math.PI;

    private CharrotGregoryPatch() {}

    /**
     * Evaluate the patch at a domain point inside the canonical regular n-gon
     * (vertices on the unit circle at angles (i + 0.5) · 2π/n + π).
     *
     * @param boundaryCurves N cubic Bézier curves, each {P0, P1, P2, P3}.
     *     Curves must close: {@code boundaryCurves[i][3] == boundaryCurves[(i+1) % n][0]}.
     * @param u domain-space x of the sample point (canonical n-gon coordinates).
     * @param v domain-space y of the sample point.
     * @param out result vector (written in-place, also returned).
     * @throws IllegalArgumentException TODO: describe
     * @return {@code out} for chaining.
     */
    public static Vector3f evaluate(Vector3f[][] boundaryCurves, float u, float v, Vector3f out) {
        int n = boundaryCurves.length;
        if (n < NUM_3) {
            throw new IllegalArgumentException("Charrot-Gregory requires n >= 3 sides, got " + n);
        }

        // Pass B: accumulate the Wachspress normalisation sum.
        // sumW = Σ 1 / (D_i · D_{i+1}) over all edges.
        float sumW = NUM_0;
        for (int i = 0; i < n; i++) {
            float d0 = Math.max(domainEdgeDistance(i, n, u, v), EPS);
            float d1 = Math.max(domainEdgeDistance((i + 1) % n, n, u, v), EPS);
            sumW += NUM_1 / (d0 * d1);
        }

        // Pass C: for each side, compute the ribbon R_i and blend by B_i(d_i).
        Vector3f sumS = new Vector3f();
        float sumB = NUM_0;
        Vector3f tmp = new Vector3f();
        Vector3f acc = new Vector3f();

        for (int i = 0; i < n; i++) {
            int im1 = (i - 1 + n) % n;
            int ip1 = (i + 1) % n;
            int im2 = (i - 2 + n) % n;
            int ip2 = (i + 2) % n;

            float Di = Math.max(domainEdgeDistance(i, n, u, v), EPS);
            float Dip1 = Math.max(domainEdgeDistance(ip1, n, u, v), EPS);
            float Dim1 = Math.max(domainEdgeDistance(im1, n, u, v), EPS);

            float lamI = (NUM_1 / (Di * Dip1)) / sumW;
            float lamIm1 = (NUM_1 / (Dim1 * Di)) / sumW;

            float denomSD = Math.max(lamIm1 + lamI, EPS);
            float sRaw = clamp01(lamI / denomSD);
            float dRaw = clamp01(NUM_1 - (lamIm1 + lamI));
            float sI = smootherStep(sRaw);
            float dI = smootherStep(dRaw);

            Vector3f[] cI = boundaryCurves[i];
            Vector3f[] cIm1 = boundaryCurves[im1];
            Vector3f[] cIp1 = boundaryCurves[ip1];
            Vector3f[] cIm2 = boundaryCurves[im2];
            Vector3f[] cIp2 = boundaryCurves[ip2];

            // Opposite curve: reconstructed cubic bezier using tangents of
            // C_{i+2}(0) and C_{i-2}(1). The Blender impl uses the "1/3 of
            // derivative" form, which equals (P1 - P0) for a cubic bezier.
            Vector3f tanIp2 = new Vector3f(cIp2[1]).sub(cIp2[0]);
            Vector3f tanIm2 = new Vector3f(cIm2[NUM_3]).sub(cIm2[2]);
            Vector3f p0Opp = new Vector3f(cIp1[NUM_3]);           // = V_{i+2}
            Vector3f p3Opp = new Vector3f(cIm1[0]);           // = V_{i-1}
            Vector3f p1Opp = new Vector3f(p0Opp).add(tanIp2);
            Vector3f p2Opp = new Vector3f(p3Opp).sub(tanIm2);

            // Ribbon terms:
            //   termA = C_i(s_i)            · (1 - d_i)
            //   termB = C_opp(1 - s_i)      · d_i
            //   termC = C_{i-1}(1 - d_i)    · (1 - s_i)
            //   termD = C_{i+1}(d_i)        · s_i
            //   bilinear = interpolation of the 4 cage corners
            //   R_i     = termA + termB + termC + termD - bilinear
            Vector3f termA = bezierEval(cI[0], cI[1], cI[2], cI[NUM_3], sI, new Vector3f()).mul(NUM_1 - dI);
            Vector3f termB = bezierEval(p0Opp, p1Opp, p2Opp, p3Opp, NUM_1 - sI, new Vector3f()).mul(dI);
            Vector3f termC = bezierEval(cIm1[0], cIm1[1], cIm1[2], cIm1[NUM_3], NUM_1 - dI, new Vector3f()).mul(NUM_1 - sI);
            Vector3f termD = bezierEval(cIp1[0], cIp1[1], cIp1[2], cIp1[NUM_3], dI, new Vector3f()).mul(sI);

            // bilinear corner interpolation
            Vector3f l0 = new Vector3f(cI[0]).mul(NUM_1 - dI).add(new Vector3f(cIm1[0]).mul(dI));
            Vector3f l1 = new Vector3f(cI[NUM_3]).mul(NUM_1 - dI).add(new Vector3f(cIp1[NUM_3]).mul(dI));
            Vector3f bilinear = new Vector3f(l0).mul(NUM_1 - sI).add(l1.mul(sI));

            acc.set(termA).add(termB).add(termC).add(termD).sub(bilinear);

            float bI = (NUM_1 - dI) * (NUM_1 - dI);
            tmp.set(acc).mul(bI);
            sumS.add(tmp);
            sumB += bI;
        }

        float invSumB = NUM_1 / Math.max(sumB, EPS);
        out.set(sumS).mul(invSumB);
        return out;
    }

    /**
     * Convenience overload that allocates the output vector.
     *
     * @param boundaryCurves TODO: describe
     * @param u TODO: describe
     * @param v TODO: describe
     * @return TODO: describe
     */
    public static Vector3f evaluate(Vector3f[][] boundaryCurves, float u, float v) {
        return evaluate(boundaryCurves, u, v, new Vector3f());
    }

    /**
     * Perpendicular distance from point (u, v) to edge {@code edgeIndex} of the
     * canonical regular n-gon (vertices on the unit circle at angles
     * (i + 0.5) · 2π/n + π). The distance is signed positive for points inside.
     *
     * @param edgeIndex TODO: describe
     * @param n TODO: describe
     * @param u TODO: describe
     * @param v TODO: describe
     * @return TODO: describe
     */
    static float domainEdgeDistance(int edgeIndex, int n, float u, float v) {
        // Edge goes from vertex edgeIndex to vertex edgeIndex + 1.
        float angleStep = TWO_PI / n;
        float theta0 = (edgeIndex + NUM_0_5) * angleStep + PI;
        float theta1 = (edgeIndex + NUM_1_5) * angleStep + PI;
        float v0x = (float) Math.cos(theta0);
        float v0y = (float) Math.sin(theta0);
        float v1x = (float) Math.cos(theta1);
        float v1y = (float) Math.sin(theta1);

        // Signed perpendicular distance from p to the directed edge (v0 → v1).
        // For a CCW polygon traversed v0 → v1 on the outside, the left-hand
        // normal (90° CCW of edge direction) points into the polygon.
        //   edgeDir = (v1 - v0); leftNormal = (-dy, dx)
        //   signed distance = ((p - v0) · leftNormal) / |edgeDir|
        float ex = v1x - v0x;
        float ey = v1y - v0y;
        float edgeLen = (float) Math.sqrt(ex * ex + ey * ey);
        if (edgeLen < EPS) {
            return NUM_0;
        }
        float nx = -ey / edgeLen;
        float ny = ex / edgeLen;
        return (u - v0x) * nx + (v - v0y) * ny;
    }

    /**
     * Cubic Bézier evaluation at parameter t.
     *
     * @param p0 TODO: describe
     * @param p1 TODO: describe
     * @param p2 TODO: describe
     * @param p3 TODO: describe
     * @param t TODO: describe
     * @param out TODO: describe
     * @return TODO: describe
     */
    static Vector3f bezierEval(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t, Vector3f out) {
        float omt = NUM_1 - t;
        float b0 = omt * omt * omt;
        float b1 = NUM_3_2 * omt * omt * t;
        float b2 = NUM_3_2 * omt * t * t;
        float b3 = t * t * t;
        out.x = b0 * p0.x + b1 * p1.x + b2 * p2.x + b3 * p3.x;
        out.y = b0 * p0.y + b1 * p1.y + b2 * p2.y + b3 * p3.y;
        out.z = b0 * p0.z + b1 * p1.z + b2 * p2.z + b3 * p3.z;
        return out;
    }

    /**
     * Quintic smoother-step: t³(6t² − 15t + 10). C² continuous at 0 and 1.
     *
     * @param t TODO: describe
     * @return TODO: describe
     */
    static float smootherStep(float t) {
        t = clamp01(t);
        return t * t * t * (t * (t * NUM_6 - NUM_15) + NUM_10);
    }

    static float clamp01(float t) {
        if (t < NUM_0) return NUM_0;
        if (t > NUM_1) return NUM_1;
        return t;
    }
}
