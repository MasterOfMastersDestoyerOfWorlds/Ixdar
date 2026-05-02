package ixdar.geometry.mesh.quadlayout.integergrid;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;

/**
 * Aligned parametrization (Campen 2014 thesis Eq. 6.1, 6.2 — PATCH-40 v2,
 * PATCH-54 per-vertex variant).
 *
 * <p>Solves the single sparse linear system
 * <pre>
 *   E = sum_{t in T} ( ||grad_t u - u_t||^2 + ||grad_t v - v_t||^2 ) * A_t  -> min
 * </pre>
 * with cross-edge transition constraints
 * <pre>
 *   (u, v)_t = R(r_st) (u, v)_s + (j_st, k_st)
 * </pre>
 * where {@code (u_t, v_t)} are the combed cross-field directions in face t,
 * {@code A_t} the face area, {@code r_st} the integer matching and
 * {@code (j_st, k_st)} real-valued translations on seam edges.
 *
 * <p><b>PATCH-54 — per-vertex layout.</b> Variables are now per chart-vertex
 * (one per (mesh_vertex, chart) pair) plus per-seam-edge (j, k):
 * {@code N = 2 * numCV + 2 * E_seam}. The per-corner formulation we
 * replaced gave {@code N = 6F + 2*E_seam}, with corners on the same vertex
 * inside a chart equalised only by SOFT seam-weight rows — that produced a
 * near-singular system that no MTJ preconditioner could converge on
 * meshes above ~5k faces. Per-vertex collapses those redundant unknowns,
 * eliminating the soft-equality null space and dropping {@code N} from
 * 120k to 21k on rocker-arm-20k.
 *
 * <p>The matrix build, gauge, and solve all live in {@link IgmHessian};
 * this class is a thin wrapper that exposes the per-corner public API
 * ({@link #u}, {@link #v}, {@link #uvSignedArea}) by re-projecting the
 * chart-vertex solution back to face corners.
 */
public final class AlignedParameterization {

    private final ArrayMesh mesh;
    private final FaceRosyField field;
    private final CombedField combed;

    private final int faceCount;
    private final float[] uCorner;
    private final float[] vCorner;
    private double energy;

    public AlignedParameterization(ArrayMesh mesh, FaceRosyField field, CombedField combed) {
        this.mesh = mesh;
        this.field = field;
        this.combed = combed;
        this.faceCount = mesh.faceCount();
        this.uCorner = new float[faceCount * 3];
        this.vCorner = new float[faceCount * 3];
        solve();
    }

    public float u(int faceId, int cornerIdx) {
        return uCorner[faceId * 3 + cornerIdx];
    }

    public float v(int faceId, int cornerIdx) {
        return vCorner[faceId * 3 + cornerIdx];
    }

    public double energy() { return energy; }

    /** Returns [uMin, uMax, vMin, vMax]. */
    public float[] uvBoundingBox() {
        if (faceCount == 0) return new float[]{0, 0, 0, 0};
        float uMin = uCorner[0], uMax = uCorner[0];
        float vMin = vCorner[0], vMax = vCorner[0];
        int n = faceCount * 3;
        for (int i = 1; i < n; i++) {
            if (uCorner[i] < uMin) uMin = uCorner[i];
            if (uCorner[i] > uMax) uMax = uCorner[i];
            if (vCorner[i] < vMin) vMin = vCorner[i];
            if (vCorner[i] > vMax) vMax = vCorner[i];
        }
        return new float[]{uMin, uMax, vMin, vMax};
    }

    /** Signed area in (u,v) of triangle f. Positive = correctly oriented. */
    public float uvSignedArea(int faceId) {
        int o = faceId * 3;
        float u0 = uCorner[o], v0 = vCorner[o];
        float u1 = uCorner[o + 1], v1 = vCorner[o + 1];
        float u2 = uCorner[o + 2], v2 = vCorner[o + 2];
        return 0.5f * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
    }

    private void solve() {
        if (faceCount == 0) return;
        IgmHessian H = new IgmHessian(mesh, field, combed, 1.0);
        double[] x = H.solveWithPins(null, null, null, null);
        // Project chart-vertex solution back to per-corner arrays.
        for (int f = 0; f < faceCount; f++) {
            for (int c = 0; c < 3; c++) {
                int cv = H.chart.chartVertexAt(f, c);
                uCorner[f * 3 + c] = (float) x[H.uBase + cv];
                vCorner[f * 3 + c] = (float) x[H.vBase + cv];
            }
        }
        energy = computeEnergy(H);
    }

    private double computeEnergy(IgmHessian H) {
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f e0 = new Vector3f();
        Vector3f e1 = new Vector3f();
        Vector3f uF = new Vector3f();
        Vector3f vF = new Vector3f();
        double E = 0;
        for (int f = 0; f < faceCount; f++) {
            mesh.vertexPosition(mesh.faceVertexAt(f, 0), p0);
            mesh.vertexPosition(mesh.faceVertexAt(f, 1), p1);
            mesh.vertexPosition(mesh.faceVertexAt(f, 2), p2);
            field.frameU(f, uF);
            field.frameV(f, vF);
            e0.set(p1).sub(p0);
            e1.set(p2).sub(p0);
            float q1u = e0.dot(uF), q1v = e0.dot(vF);
            float q2u = e1.dot(uF), q2v = e1.dot(vF);
            float sa = 0.5f * (q1u * q2v - q2u * q1v);
            if (Math.abs(sa) < 1e-20f) continue;
            double inv2A = 1.0 / (2.0 * sa);
            double b0 = (q1v - q2v) * inv2A, c0 = (q2u - q1u) * inv2A;
            double b1 = (q2v - 0)   * inv2A, c1 = (0   - q2u) * inv2A;
            double b2 = (0   - q1v) * inv2A, c2 = (q1u - 0)   * inv2A;
            double U0 = uCorner[f * 3],     U1 = uCorner[f * 3 + 1], U2 = uCorner[f * 3 + 2];
            double V0 = vCorner[f * 3],     V1 = vCorner[f * 3 + 1], V2 = vCorner[f * 3 + 2];
            double gux = U0 * b0 + U1 * b1 + U2 * b2;
            double guy = U0 * c0 + U1 * c1 + U2 * c2;
            double gvx = V0 * b0 + V1 * b1 + V2 * b2;
            double gvy = V0 * c0 + V1 * c1 + V2 * c2;
            double a = combed.combedAngle(f);
            double tx = Math.cos(a), ty = Math.sin(a);
            double ru1 = gux - tx;
            double ru2 = guy - ty;
            double rv1 = gvx + ty;  // v-target = (-sin, cos) = (-ty, tx)
            double rv2 = gvy - tx;
            double area = Math.abs(sa);
            E += (ru1 * ru1 + ru2 * ru2 + rv1 * rv1 + rv2 * rv2) * area;
        }
        return E;
    }
}
