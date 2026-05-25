package ixdar.geometry.mesh.quadlayout.motorcycle;

/**
 * Parametric axis of a motorcycle trace: constant-u (V axis motion) or
 * constant-v (U axis motion).
 */
public enum TraceAxis {
    /** Trace moves along increasing/decreasing u; v is constant. */
    U,
    /** Trace moves along increasing/decreasing v; u is constant. */
    V;

    /**
     * Unit direction vector in chart space for this axis and sign.
     *
     * @param sign +1 or -1 along the axis
     * @return {@code [dx, dy]} with unit length
     */
    public double[] direction(int sign) {
        if (this == U) {
            return new double[] { sign, 0.0 };
        }
        return new double[] { 0.0, sign };
    }

    /**
     * Constant coordinate name for iso-line rendering.
     *
     * @return {@code true} when the trace holds u constant (V-axis motion)
     */
    public boolean holdsUConstant() {
        return this == V;
    }

    /**
     * Parse axis from a direction vector aligned to the parametric grid.
     *
     * @param dx x component of direction
     * @param dy y component of direction
     * @return axis and sign packed as {@code axis.ordinal()} and sign in caller
     */
    public static TraceAxis fromDirection(double dx, double dy) {
        if (Math.abs(dx) >= Math.abs(dy)) {
            return U;
        }
        return V;
    }

    /**
     * Sign along the axis for a direction vector.
     *
     * @param axis parametric axis
     * @param dx x component of direction
     * @param dy y component of direction
     * @return +1 or -1
     */
    public static int signFor(TraceAxis axis, double dx, double dy) {
        if (axis == U) {
            return dx >= 0.0 ? 1 : -1;
        }
        return dy >= 0.0 ? 1 : -1;
    }
}
