package ixdar.geometry.mesh.quadlayout.motorcycle;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Lifts chart-space (u, v) points on a triangle back to 3D surface positions
 * by barycentric interpolation against the face's corner UVs. One walker is
 * kept for the lifetime of the lift so repeated calls don't re-allocate chart
 * lookups.
 */
public final class ParametricLift {

    /** Barycentric denominator below which the chart triangle is degenerate. */
    public static final double DEGENERATE_CHART_DENOMINATOR = 1.0e-12;

    public final SeamlessParameterization seamless;
    public final ChartWalker walker;

    /**
     * Prepares a lift over one parametrization.
     *
     * @param seamless built seamless parametrization supplying chart UVs and
     *                 vertex positions
     */
    public ParametricLift(SeamlessParameterization seamless) {
        this.seamless = seamless;
        this.walker = new ChartWalker(seamless);
    }

    /**
     * Lift a chart-space point on one triangle to its 3D surface position.
     *
     * @param activeFace dense active face index of the containing triangle
     * @param u          chart u coordinate
     * @param v          chart v coordinate
     * @return surface position; the face's first corner position when the
     *         chart triangle is degenerate
     */
    public Vector3f liftToPosition(int activeFace, double u, double v) {
        double[] cornerUv = new double[ChartWalker.CORNER_UV_FLOATS];
        walker.faceCornerUv(activeFace, cornerUv);
        int faceId = seamless.mesh.faceIdAt(activeFace);
        Vector3f position0 = new Vector3f();
        Vector3f position1 = new Vector3f();
        Vector3f position2 = new Vector3f();
        seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, 0), position0);
        seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, 1), position1);
        seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, 2), position2);
        double u0 = cornerUv[0];
        double v0 = cornerUv[1];
        double u1 = cornerUv[2];
        double v1 = cornerUv[3];
        double u2 = cornerUv[4];
        double v2 = cornerUv[5];
        double denominator = (v1 - v2) * (u0 - u2) + (u2 - u1) * (v0 - v2);
        if (Math.abs(denominator) < DEGENERATE_CHART_DENOMINATOR) {
            return new Vector3f(position0);
        }
        double w0 = ((v1 - v2) * (u - u2) + (u2 - u1) * (v - v2)) / denominator;
        double w1 = ((v2 - v0) * (u - u2) + (u0 - u2) * (v - v2)) / denominator;
        double w2 = 1.0 - w0 - w1;
        return new Vector3f(
                (float) (w0 * position0.x + w1 * position1.x + w2 * position2.x),
                (float) (w0 * position0.y + w1 * position1.y + w2 * position2.y),
                (float) (w0 * position0.z + w1 * position1.z + w2 * position2.z));
    }
}
