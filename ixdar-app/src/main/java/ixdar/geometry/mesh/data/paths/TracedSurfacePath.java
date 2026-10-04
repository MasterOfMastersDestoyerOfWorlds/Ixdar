package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;

/**
 * A path traced onto a mesh surface: packed xyz positions plus, per point, the vertex it sits on,
 * the edge it crosses and where, or the face it passes inside.
 *
 * <p>
 * Exactly one of {@code vertexId[i]}, {@code edgeId[i]} and {@code faceId[i]} is non-negative.
 */
public final class TracedSurfacePath {

    /** Packed xyz of every point, in travel order. */
    public double[] positions;

    /**
     * Packed xyz of each point on the surface: {@link #positions} itself for a geodesic, the
     * surface points under an off-surface curve's samples otherwise.
     */
    public double[] surfacePositions;

    /** Mesh vertex id per point, or -1 where the point is not on a vertex. */
    public int[] vertexId;

    /** Crossed mesh edge id per point, or -1 where the point crosses no edge. */
    public int[] edgeId;

    /**
     * Mesh face id per point that lies strictly inside a face, where a trace crossed an intrinsic
     * edge splitting a quad or larger polygon, or -1.
     */
    public int[] faceId;

    /** Position along the crossed edge in {@code [0, 1]}, or -1 at a vertex or inside a face. */
    public double[] fraction;

    /** Number of points stored in the arrays. */
    public int pointCount;

    /** Whether the last point joins back to the first. */
    public boolean closed;

    /**
     * Wraps already-packed trace arrays of a path with no inside-face points, its positions on the
     * surface, without copying them.
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
        this.surfacePositions = positions;
        this.vertexId = vertexId;
        this.edgeId = edgeId;
        this.faceId = new int[vertexId.length];
        Arrays.fill(this.faceId, -1);
        this.fraction = fraction;
        this.pointCount = pointCount;
        this.closed = closed;
    }

    /**
     * Wraps already-packed trace arrays without copying them.
     *
     * @param positions        packed xyz, at least {@code 3 * pointCount} long
     * @param surfacePositions packed xyz of the same points on the surface
     * @param vertexId         mesh vertex id per point, -1 elsewhere
     * @param edgeId           crossed mesh edge id per point, -1 elsewhere
     * @param faceId           mesh face id per inside-face point, -1 elsewhere
     * @param fraction         crossing parameter per point, -1 off an edge
     * @param pointCount       number of valid points
     * @param closed           whether the path is a closed loop
     */
    public TracedSurfacePath(double[] positions, double[] surfacePositions, int[] vertexId,
            int[] edgeId, int[] faceId, double[] fraction, int pointCount, boolean closed) {
        this.positions = positions;
        this.surfacePositions = surfacePositions;
        this.vertexId = vertexId;
        this.edgeId = edgeId;
        this.faceId = faceId;
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
