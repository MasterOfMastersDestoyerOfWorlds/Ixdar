package ixdar.geometry.mesh.data.paths.geodesic;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * A path traced onto a mesh surface: packed xyz positions plus, per point, the vertex it sits on,
 * the edge it crosses and where, or the face it passes inside.
 *
 * <p>
 * Exactly one of {@code vertexId[i]}, {@code edgeId[i]} and {@code faceId[i]} is non-negative.
 */
public final class TracedSurfacePath {

    public static final int COORDINATES_PER_POINT = 3;

    /** Every point, in travel order. */
    public PackedPoints positions;

    /**
     * Each point on the surface: {@link #positions} itself for a geodesic, the surface points
     * under an off-surface curve's samples otherwise.
     */
    public PackedPoints surfacePositions;

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

    /** Number of points, the size of {@link #positions}. */
    public int pointCount;

    /** Whether the last point joins back to the first. */
    public boolean closed;

    /** Euclidean length of the polyline, a closed path's closing span included. */
    public double length;

    /** Mean distance from {@link #centroid} to the points; set by {@link #measure}. */
    public double meanRadius;

    /** Length-weighted centroid, where a joint on the path would sit; set by {@link #measure}. */
    public final Vector3f centroid = new Vector3f();

    /**
     * Wraps the trace arrays of a path with no inside-face points, its positions on the surface,
     * without copying them.
     *
     * @param positions the points
     * @param vertexId  mesh vertex id per point, -1 at crossings
     * @param edgeId    crossed mesh edge id per point, -1 at vertices
     * @param fraction  crossing parameter per point, -1 at vertices
     * @param closed    whether the path is a closed loop
     */
    public TracedSurfacePath(PackedPoints positions, int[] vertexId, int[] edgeId, double[] fraction,
            boolean closed) {
        this.positions = positions;
        this.surfacePositions = positions;
        this.vertexId = vertexId;
        this.edgeId = edgeId;
        this.faceId = new int[vertexId.length];
        Arrays.fill(this.faceId, -1);
        this.fraction = fraction;
        this.pointCount = positions.size();
        this.closed = closed;
    }

    /**
     * Wraps the trace arrays without copying them.
     *
     * @param positions        the points
     * @param surfacePositions the same points on the surface
     * @param vertexId         mesh vertex id per point, -1 elsewhere
     * @param edgeId           crossed mesh edge id per point, -1 elsewhere
     * @param faceId           mesh face id per inside-face point, -1 elsewhere
     * @param fraction         crossing parameter per point, -1 off an edge
     * @param closed           whether the path is a closed loop
     */
    public TracedSurfacePath(PackedPoints positions, PackedPoints surfacePositions, int[] vertexId,
            int[] edgeId, int[] faceId, double[] fraction, boolean closed) {
        this.positions = positions;
        this.surfacePositions = surfacePositions;
        this.vertexId = vertexId;
        this.edgeId = edgeId;
        this.faceId = faceId;
        this.fraction = fraction;
        this.pointCount = positions.size();
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
        double[] weights = new double[pointCount];
        for (int here = 0; here < (closed ? pointCount : pointCount - 1); here++) {
            int there = (here + 1) % pointCount;
            double weight = Math.sqrt(positions.distanceSquared(there, here));
            weights[here] += weight * 0.5;
            weights[there] += weight * 0.5;
            length += weight;
        }
        centroid.set(positions.centroid(weights));
        for (int point = 0; point < pointCount; point++) {
            meanRadius += Math.sqrt(positions.distanceSquared(point, centroid)) / pointCount;
        }
        return this;
    }

    /**
     * The path as one curve, each point stored once and closed when the path is.
     *
     * @param points {@link #positions} or {@link #surfacePositions}
     * @return a fresh single-curve geometry over a copy of the points
     */
    public CurveGeometry curve(PackedPoints points) {
        return CurveGeometry.singlePolyline(points.copy(), closed);
    }
}
