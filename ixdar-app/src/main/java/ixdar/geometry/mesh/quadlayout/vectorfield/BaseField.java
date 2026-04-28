package ixdar.geometry.mesh.quadlayout.vectorfield;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;

/**
 * Per-face tangent angle (theta) representation of a face-based direction field
 * over a triangulated 2-manifold. The field stores theta_i in radians, expressed
 * in each face's local 2D reference frame. The local frame for face i is
 * (e0_i, n_i x e0_i, n_i) where e0_i is the unit vector along the face's first
 * edge (vertex 0 -> vertex 1) and n_i is the face normal.
 *
 * <p>4-RoSy interpretation: theta and theta + k * pi/2 represent the same cross
 * field. Subclasses (e.g. {@link FaceRosyField}) interpret the angle as a cross
 * by emitting four orthogonal directions {theta, theta+pi/2, theta+pi, theta+3pi/2}.
 */
public abstract class BaseField {

    protected final ArrayMesh mesh;
    protected final double[] theta;
    protected final float[] frameU;
    protected final float[] frameV;
    protected final float[] frameN;

    protected BaseField(ArrayMesh mesh) {
        this.mesh = mesh;
        int f = mesh.faceCount();
        this.theta = new double[f];
        this.frameU = new float[f * 3];
        this.frameV = new float[f * 3];
        this.frameN = new float[f * 3];
        buildLocalFrames();
    }

    public ArrayMesh mesh() { return mesh; }

    public int faceCount() { return mesh.faceCount(); }

    public double theta(int faceId) { return theta[faceId]; }

    public double[] copyTheta() { return Arrays.copyOf(theta, theta.length); }

    public void setTheta(int faceId, double value) { theta[faceId] = value; }

    /** Project the per-face cross direction (theta) to a 3D unit vector. */
    public Vector3f directionAt(int faceId, int branchIndex, Vector3f dest) {
        double t = theta[faceId] + (branchIndex & 3) * (Math.PI * 0.5);
        float c = (float) Math.cos(t);
        float s = (float) Math.sin(t);
        int o = faceId * 3;
        dest.set(frameU[o] * c + frameV[o] * s,
                 frameU[o + 1] * c + frameV[o + 1] * s,
                 frameU[o + 2] * c + frameV[o + 2] * s);
        return dest;
    }

    public Vector3f frameU(int faceId, Vector3f dest) {
        int o = faceId * 3;
        return dest.set(frameU[o], frameU[o + 1], frameU[o + 2]);
    }

    public Vector3f frameV(int faceId, Vector3f dest) {
        int o = faceId * 3;
        return dest.set(frameV[o], frameV[o + 1], frameV[o + 2]);
    }

    public Vector3f frameN(int faceId, Vector3f dest) {
        int o = faceId * 3;
        return dest.set(frameN[o], frameN[o + 1], frameN[o + 2]);
    }

    private void buildLocalFrames() {
        int f = mesh.faceCount();
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f e0 = new Vector3f();
        Vector3f e1 = new Vector3f();
        Vector3f n = new Vector3f();
        Vector3f v = new Vector3f();
        for (int fi = 0; fi < f; fi++) {
            mesh.vertexPosition(mesh.faceVertexAt(fi, 0), p0);
            mesh.vertexPosition(mesh.faceVertexAt(fi, 1), p1);
            mesh.vertexPosition(mesh.faceVertexAt(fi, 2), p2);
            e0.set(p1).sub(p0);
            e1.set(p2).sub(p0);
            e0.cross(e1, n);
            float nl = n.length();
            if (nl > 1e-30f) n.mul(1f / nl); else n.set(0, 0, 1);
            float el = e0.length();
            if (el > 1e-30f) e0.mul(1f / el); else e0.set(1, 0, 0);
            // v = n x e0 (the 90-degree CCW rotation of e0 in the tangent plane).
            n.cross(e0, v);
            int o = fi * 3;
            frameU[o] = e0.x; frameU[o + 1] = e0.y; frameU[o + 2] = e0.z;
            frameV[o] = v.x;  frameV[o + 1] = v.y;  frameV[o + 2] = v.z;
            frameN[o] = n.x;  frameN[o + 1] = n.y;  frameN[o + 2] = n.z;
        }
    }
}
