package ixdar.geometry.mesh.data.paths.geodesic;

import java.util.Arrays;

/**
 * A path traced onto a mesh surface: packed xyz positions plus, per point, the vertex it sits on,
 * the edge it crosses and where, or the face it passes inside.
 *
 * <p>
 * Exactly one of {@code vertexId[i]}, {@code edgeId[i]} and {@code faceId[i]} is non-negative.
 */
public final class TracedSurfacePath {

    public static final int COORDINATES_PER_POINT = 3;

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

    /** Euclidean length of the polyline, a closed path's closing span included. */
    public double length;

    /** Mean distance from {@link #centroid} to the points; set by {@link #measure}. */
    public double meanRadius;

    /** Length-weighted centroid xyz, where a joint on the path would sit; set by {@link #measure}. */
    public final double[] centroid = new double[COORDINATES_PER_POINT];

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
     * Sets {@link #length}, {@link #centroid} and {@link #meanRadius} from {@link #positions},
     * spans weighting the centroid by their length; a path of no length keeps the origin.
     *
     * @return this path, measured
     */
    public TracedSurfacePath measure() {
        length = 0.0;
        meanRadius = 0.0;
        double[] weighted = new double[COORDINATES_PER_POINT];
        int spans = closed ? pointCount : pointCount - 1;
        for (int span = 0; span < spans; span++) {
            int here = COORDINATES_PER_POINT * span;
            int there = COORDINATES_PER_POINT * ((span + 1) % pointCount);
            double dx = positions[there] - positions[here];
            double dy = positions[there + 1] - positions[here + 1];
            double dz = positions[there + 2] - positions[here + 2];
            double weight = Math.sqrt(dx * dx + dy * dy + dz * dz);
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                weighted[axis] += weight * 0.5 * (positions[here + axis] + positions[there + axis]);
            }
            length += weight;
        }
        for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
            centroid[axis] = length <= 0.0 ? 0.0 : weighted[axis] / length;
        }
        for (int point = 0; point < pointCount; point++) {
            int base = COORDINATES_PER_POINT * point;
            double dx = positions[base] - centroid[0];
            double dy = positions[base + 1] - centroid[1];
            double dz = positions[base + 2] - centroid[2];
            meanRadius += Math.sqrt(dx * dx + dy * dy + dz * dz) / pointCount;
        }
        return this;
    }

    /**
     * Packed single-precision xyz of the points, a closed path's first point repeated at the end,
     * the form curve geometry and the GL overlays draw.
     *
     * @param packedXyz {@link #positions} or {@link #surfacePositions}
     * @return a fresh {@code float[]}
     */
    public float[] drawnPolyline(double[] packedXyz) {
        int drawn = closed && pointCount > 0 ? pointCount + 1 : pointCount;
        float[] packed = new float[COORDINATES_PER_POINT * drawn];
        for (int point = 0; point < drawn; point++) {
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                packed[COORDINATES_PER_POINT * point + axis] =
                        (float) packedXyz[COORDINATES_PER_POINT * (point % pointCount) + axis];
            }
        }
        return packed;
    }
}
