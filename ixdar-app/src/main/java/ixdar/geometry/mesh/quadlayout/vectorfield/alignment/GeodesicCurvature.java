package ixdar.geometry.mesh.quadlayout.vectorfield.alignment;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;

/**
 * Per-edge geodesic curvature κ^g of the {@code a_min} line field, and the
 * derived per-face "smooth" classification.
 *
 * <h2>Citations</h2>
 * <ul>
 *   <li><b>CIE*16 §3.2 ¶3</b> "Geodesic Curvature" — defines κ(e) on the
 *       barycentric dual via Levi-Civita transport. Formula:
 *       {@code κ(e) = min‖a_min(f1) ± a_min(f2)‖ / d}.</li>
 *   <li><b>CIE*16 §3.2 ¶3</b> last sentence — face is smooth iff
 *       {@code κ(e) < |κ_max(f)|} for all 3 of its edges.</li>
 *   <li><b>CIE*16 §3.1 eq.(2)</b> — defines smooth region as a maximal
 *       connected region {@code S} where {@code κ^g(x) < |κ_max(x)|}.</li>
 *   <li><b>ACDLD03 §1 Figure 1 caption</b> + <b>CIE*16 §3.2 ¶2</b> — a_min is
 *       a line field, sign-ambiguous; the {@code min‖± ‖} resolves the
 *       ambiguity per edge.</li>
 * </ul>
 *
 * <p>Master citation index: {@code alignment/PAPERS.md}.
 */
public final class GeodesicCurvature {
    public static final double NUM_1e_30 = 1e-30;
    public static final int NUM_3 = 3;
    public static final float NUM_3_2 = 3f;

    private GeodesicCurvature() {}

    /**
     * Compute κ^g for every interior edge of {@code mesh}. Length of the
     * returned array equals {@code mesh.edgeCount()}; boundary edges have
     * value 0.
     *
     * <p>CIE*16 §3.2 ¶3:
     * <pre>
     *   κ(e) = min‖a_min(f1) ± Π(a_min(f2))‖ / d
     * </pre>
     * where {@code Π(·)} is Levi-Civita transport from f2's tangent plane to
     * f1's via a rotation about {@code n1 × n2} by {@code arccos(n1·n2)}, and
     * {@code d} is the barycenter distance between the adjacent faces.
     *
     * @param mesh triangle mesh
     * @param pdf  per-face principal-curvature field providing {@code a_min}
     *             and the per-face robust normals
     * @return array of length {@code mesh.edgeCount()}; boundary entries are 0
     */
    public static double[] computePerEdge(ArrayMesh mesh, PrincipalCurvatureField pdf) {
        int E = mesh.edgeCount();
        double[] kappaG = new double[E];
        if (E == 0) return kappaG;

        Vector3f c1 = new Vector3f();
        Vector3f c2 = new Vector3f();
        Vector3f n1 = new Vector3f();
        Vector3f n2 = new Vector3f();
        Vector3f a1 = new Vector3f();
        Vector3f a2 = new Vector3f();
        Vector3f axis = new Vector3f();
        Vector3f a2Transp = new Vector3f();
        Vector3f tmp = new Vector3f();

        for (int eId = 0; eId < E; eId++) {
            if (mesh.isBoundaryEdge(eId)) {
                kappaG[eId] = 0.0;
                continue;
            }
            int he = mesh.edgeHalfEdge(eId);
            int twin = mesh.halfEdgeTwin(he);
            int fA = mesh.halfEdgeFace(he);
            int fB = mesh.halfEdgeFace(twin);
            if (fA < 0 || fB < 0) {
                kappaG[eId] = 0.0;
                continue;
            }

            faceCentroid(mesh, fA, c1);
            faceCentroid(mesh, fB, c2);
            double dx = c2.x - c1.x, dy = c2.y - c1.y, dz = c2.z - c1.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < NUM_1e_30) {
                kappaG[eId] = 0.0;
                continue;
            }

            pdf.normal(fA, n1);
            pdf.normal(fB, n2);
            pdf.aMin(fA, a1);
            pdf.aMin(fB, a2);

            // CIE*16 §3.2 ¶3: Levi-Civita transport — rotate a2 from n2's
            //   tangent plane into n1's, via rotation about n1×n2 by arccos(n1·n2).
            double dot = Math.max(-1.0, Math.min(1.0, (double) n1.dot(n2)));
            transportLineField(a2, n1, n2, dot, axis, a2Transp, tmp);

            // CIE*16 §3.2 ¶3:  κ(e) = min‖a1 ± a2_transp‖ / d
            double dPlusX = a1.x - a2Transp.x;
            double dPlusY = a1.y - a2Transp.y;
            double dPlusZ = a1.z - a2Transp.z;
            double normPlus = Math.sqrt(dPlusX * dPlusX + dPlusY * dPlusY + dPlusZ * dPlusZ);
            double dMinusX = a1.x + a2Transp.x;
            double dMinusY = a1.y + a2Transp.y;
            double dMinusZ = a1.z + a2Transp.z;
            double normMinus = Math.sqrt(dMinusX * dMinusX + dMinusY * dMinusY + dMinusZ * dMinusZ);
            kappaG[eId] = Math.min(normPlus, normMinus) / d;
        }
        return kappaG;
    }

    /**
     * Rotate vector {@code v} (assumed unit, in the tangent plane of
     * {@code n2}) into the tangent plane of {@code n1}, via the Levi-Civita
     * connection on the discrete surface — a rotation about {@code n1 × n2}
     * by angle {@code arccos(n1·n2)}.
     *
     * <p>CIE*16 §3.2 ¶3: "transformed into a common one using the Levi-Civita
     * connection. ... this amounts to a simple rotation about the axis
     * n1 × n2 by arccos(n1·n2)".
     *
     * <p>Output written into {@code dest}.
     *
     * @param v            unit vector in {@code n2}'s tangent plane
     * @param n1           target tangent-plane normal (unit)
     * @param n2           source tangent-plane normal (unit)
     * @param n1DotN2      pre-computed {@code n1 . n2} (clamped to [-1, 1])
     * @param axisScratch  scratch buffer for the rotation axis (overwritten)
     * @param dest         output rotated vector (overwritten)
     * @param tmpScratch   scratch buffer for the {@code k x v} term (overwritten)
     */
    static void transportLineField(Vector3f v, Vector3f n1, Vector3f n2, double n1DotN2,
                                   Vector3f axisScratch, Vector3f dest, Vector3f tmpScratch) {
        // axis = n2 × n1   (transport from n2's frame TO n1's frame)
        axisScratch.set(n2).cross(n1);
        double axisLen = axisScratch.length();
        if (axisLen < NUM_1e_30) {
            // n1 ≈ ± n2: no rotation (or 180° flip — but for line field, equivalent).
            dest.set(v);
            return;
        }
        axisScratch.mul((float) (1.0 / axisLen));
        double angle = Math.acos(n1DotN2);
        double cT = Math.cos(angle);
        double sT = Math.sin(angle);
        // Rodrigues' rotation:  v_rot = v·cos + (k × v)·sin + k·(k·v)·(1 − cos)
        tmpScratch.set(axisScratch).cross(v); // k × v
        double kdv = axisScratch.dot(v);
        dest.set(
                (float) (v.x * cT + tmpScratch.x * sT + axisScratch.x * kdv * (1.0 - cT)),
                (float) (v.y * cT + tmpScratch.y * sT + axisScratch.y * kdv * (1.0 - cT)),
                (float) (v.z * cT + tmpScratch.z * sT + axisScratch.z * kdv * (1.0 - cT)));
    }

    /**
     * Per-face smoothness classification.
     *
     * <p>CIE*16 §3.2 ¶3 last sentence: "If this value κ(e) exceeds the
     * threshold (|κ_max(f)|) for any one of the three adjacent edges, the
     * triangle f is not part of a smooth region (non-smooth); otherwise, it
     * is (smooth)".
     *
     * @param mesh   triangle mesh
     * @param kappaG per-edge geodesic curvature (output of
     *               {@link #computePerEdge})
     * @param pdf    principal-curvature field providing {@code |kappa_max|}
     *               per face
     * @return per-face mask: {@code true} iff every adjacent edge satisfies
     *         {@code kappa(e) < |kappa_max(f)|}
     */
    public static boolean[] computeSmoothFaces(ArrayMesh mesh, double[] kappaG,
                                                PrincipalCurvatureField pdf) {
        int F = mesh.faceCount();
        boolean[] smooth = new boolean[F];
        // For each face, walk its 3 mesh edges; non-smooth iff any κ(e) ≥ |κ_max|.
        for (int f = 0; f < F; f++) {
            double thresh = Math.abs(pdf.kappaMax(f));
            boolean isSmooth = true;
            for (int c = 0; c < NUM_3; c++) {
                int eId = faceEdgeId(mesh, f, c);
                if (eId < 0) continue;             // shouldn't happen on triangle meshes
                if (mesh.isBoundaryEdge(eId)) continue; // CIE*16 doesn't say; treat as smooth (no neighbor)
                if (kappaG[eId] >= thresh) { isSmooth = false; break; }
            }
            smooth[f] = isSmooth;
        }
        return smooth;
    }

    private static void faceCentroid(ArrayMesh mesh, int f, Vector3f dest) {
        Vector3f p = new Vector3f();
        float x = 0, y = 0, z = 0;
        for (int c = 0; c < NUM_3; c++) {
            mesh.vertexPosition(mesh.faceVertexAt(f, c), p);
            x += p.x; y += p.y; z += p.z;
        }
        dest.set(x / NUM_3_2, y / NUM_3_2, z / NUM_3_2);
    }

    /**
     * Mesh-edge id for the c-th edge of face f (between corners c and c+1).
     *
     * @param mesh triangle mesh
     * @param f    face id
     * @param c    corner index (which face half-edge to read)
     * @return mesh edge id of the c-th half-edge, or {@code -1} if absent
     */
    private static int faceEdgeId(ArrayMesh mesh, int f, int c) {
        int he = mesh.faceHalfEdgeAt(f, c);
        return mesh.halfEdgeEdge(he);
    }
}
