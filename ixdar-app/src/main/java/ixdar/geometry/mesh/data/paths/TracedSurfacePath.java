package ixdar.geometry.mesh.data.paths;

/**
 * A path traced onto a mesh surface: packed xyz positions plus, per point, the vertex it sits on
 * or the edge it crosses and where.
 *
 * <p>
 * Exactly one of {@code vertexId[i]} and {@code edgeId[i]} is non-negative. A crossing's fraction
 * runs from the tail of the edge's canonical half-edge to its head.
 */
public final class TracedSurfacePath {

    /** Packed xyz of every point, in travel order. */
    public double[] positions;

    /** Mesh vertex id per point, or -1 where the point is an edge crossing. */
    public int[] vertexId;

    /** Crossed mesh edge id per point, or -1 where the point sits on a vertex. */
    public int[] edgeId;

    /** Position along the crossed edge in {@code [0, 1]}, or -1 at a vertex point. */
    public double[] fraction;

    /** Number of points stored in the arrays. */
    public int pointCount;

    /** Whether the last point joins back to the first. */
    public boolean closed;

    /**
     * Wraps already-packed trace arrays without copying them.
     *
     * @param positions  packed xyz, at least {@code 3 * pointCount} long
     * @param vertexId   mesh vertex id per point, -1 at crossings
     * @param edgeId     crossed mesh edge id per point, -1 at vertices
     * @param fraction   crossing parameter per point, -1 at vertices
     * @param pointCount number of valid points
     * @param closed     whether the path is a closed loop
     */
    public TracedSurfacePath(double[] positions, int[] vertexId, int[] edgeId, double[] fraction,
            int pointCount, boolean closed) {
        this.positions = positions;
        this.vertexId = vertexId;
        this.edgeId = edgeId;
        this.fraction = fraction;
        this.pointCount = pointCount;
        this.closed = closed;
    }

    /**
     * Summed straight-line length of the polyline, closing the loop when the path is closed.
     *
     * @return the polyline's Euclidean length
     */
    public double polylineLength() {
        double total = 0.0;
        int spans = closed ? pointCount : pointCount - 1;
        for (int index = 0; index < spans; index++) {
            int here = 3 * index;
            int there = 3 * ((index + 1) % pointCount);
            double dx = positions[there] - positions[here];
            double dy = positions[there + 1] - positions[here + 1];
            double dz = positions[there + 2] - positions[here + 2];
            total += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return total;
    }

    /**
     * The polyline as packed single-precision xyz, the form curve geometry consumes.
     *
     * @return a fresh {@code float[]} of {@code 3 * pointCount} coordinates
     */
    public float[] packedFloatPositions() {
        float[] packed = new float[3 * pointCount];
        for (int index = 0; index < packed.length; index++) {
            packed[index] = (float) positions[index];
        }
        return packed;
    }
}
