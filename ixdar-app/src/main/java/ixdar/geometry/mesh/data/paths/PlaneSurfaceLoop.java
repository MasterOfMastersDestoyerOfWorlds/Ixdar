package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * The loop a plane cuts out of a surface, walked face by face from one crossing edge.
 *
 * <p>
 * Crossings are computed only on the faces the walk touches, so the cost is the loop's length
 * rather than the mesh's edge count.
 */
public final class PlaneSurfaceLoop {

    /** Coordinates per point in {@link #polyline} and in a plane's point and normal. */
    public static final int COORDINATES_PER_POINT = 3;

    /** Steps a walk may take before it is abandoned as not closing. */
    public static final int MAXIMUM_STEPS = 200000;

    /** Crossings the buffers start out holding, doubled as a walk outgrows them. */
    public static final int INITIAL_CROSSINGS = 256;

    /**
     * Mesh edge ids the walk crossed, in travel order. It is a buffer the walks share and grow, so
     * only its first {@link #stepCount} entries belong to the last walk.
     */
    public int[] edgeId = new int[0];

    /** Where on each crossed edge the plane cut it, along {@code edgeHalfEdge}'s tail-to-head. */
    public double[] crossingFraction = new double[0];

    /**
     * Crossing points in travel order, packed xyz, left open at the seam. Shared and grown like
     * {@link #edgeId}, so it holds {@link #stepCount} points however long the array is.
     */
    public float[] polyline = new float[0];

    /** Crossings the last walk recorded. */
    public int stepCount;

    /** Whether the walk returned to the edge it started from. */
    public boolean closed;

    /** Euclidean length of the closed polyline, the seam span included. */
    public double length;

    /**
     * Length a walk may reach before it is abandoned. A search over cutting planes sets this to
     * the best loop it has so far, which stops the planes that cut along a limb rather than
     * around it from being traced to the end.
     */
    public double maximumLength = Double.POSITIVE_INFINITY;

    private final Vector3f tailPosition = new Vector3f();
    private final Vector3f headPosition = new Vector3f();
    private float[] planePoint = new float[COORDINATES_PER_POINT];
    private float[] planeNormal = new float[COORDINATES_PER_POINT];

    /**
     * Walks the plane's intersection with {@code mesh} from {@code startEdgeId}.
     *
     * @param mesh        surface to cut
     * @param point       a point the plane passes through, packed xyz
     * @param normal      the plane's unit normal, packed xyz
     * @param startEdgeId an edge whose endpoints the plane separates
     * @return true when the walk closed back onto {@code startEdgeId}
     */
    public boolean walk(MeshTopology mesh, float[] point, float[] normal, int startEdgeId) {
        planePoint = point;
        planeNormal = normal;
        stepCount = 0;
        closed = false;
        length = 0.0;
        if (mesh == null || startEdgeId < 0 || !mesh.hasEdge(startEdgeId)
                || crossingOn(mesh, startEdgeId) < 0.0) {
            return false;
        }
        int capacity = Math.min(MAXIMUM_STEPS, Math.max(mesh.edgeCount(), 1));
        int currentEdgeId = startEdgeId;
        int currentFaceId = mesh.halfEdgeFace(mesh.edgeHalfEdge(startEdgeId));
        double previousX = 0.0;
        double previousY = 0.0;
        double previousZ = 0.0;
        for (int step = 0; step < capacity; step++) {
            if (currentFaceId < 0) {
                return false;
            }
            double along = crossingOn(mesh, currentEdgeId);
            double pointX = tailPosition.x + along * (headPosition.x - tailPosition.x);
            double pointY = tailPosition.y + along * (headPosition.y - tailPosition.y);
            double pointZ = tailPosition.z + along * (headPosition.z - tailPosition.z);
            if (stepCount > 0) {
                length += Math.sqrt((pointX - previousX) * (pointX - previousX)
                        + (pointY - previousY) * (pointY - previousY)
                        + (pointZ - previousZ) * (pointZ - previousZ));
                if (length > maximumLength) {
                    return false;
                }
            }
            previousX = pointX;
            previousY = pointY;
            previousZ = pointZ;
            if (stepCount == edgeId.length) {
                int grown = Math.min(capacity, Math.max(INITIAL_CROSSINGS, 2 * edgeId.length));
                edgeId = Arrays.copyOf(edgeId, grown);
                crossingFraction = Arrays.copyOf(crossingFraction, grown);
            }
            edgeId[stepCount] = currentEdgeId;
            crossingFraction[stepCount] = along;
            stepCount++;
            int nextEdgeId = -1;
            for (int side = 0; side < mesh.faceEdgeCount(currentFaceId); side++) {
                int candidate = mesh.faceEdgeAt(currentFaceId, side);
                if (candidate != currentEdgeId && crossingOn(mesh, candidate) >= 0.0) {
                    nextEdgeId = candidate;
                    break;
                }
            }
            if (nextEdgeId < 0) {
                return false;
            }
            currentFaceId = faceAcross(mesh, nextEdgeId, currentFaceId);
            currentEdgeId = nextEdgeId;
            if (currentEdgeId == startEdgeId) {
                closed = true;
                measure(mesh);
                return length <= maximumLength;
            }
        }
        return false;
    }

    /**
     * An edge of {@code faceId} the plane separates the endpoints of, the seed a walk starts from.
     *
     * @param mesh   surface to cut
     * @param point  a point the plane passes through, packed xyz
     * @param normal the plane's unit normal, packed xyz
     * @param faceId face to look on
     * @return one crossing edge id, or {@code -1} when the plane misses the face
     */
    public int crossingEdgeOn(MeshTopology mesh, float[] point, float[] normal, int faceId) {
        planePoint = point;
        planeNormal = normal;
        if (mesh == null || faceId < 0 || !mesh.hasFace(faceId)) {
            return -1;
        }
        for (int side = 0; side < mesh.faceEdgeCount(faceId); side++) {
            int candidate = mesh.faceEdgeAt(faceId, side);
            if (crossingOn(mesh, candidate) >= 0.0) {
                return candidate;
            }
        }
        return -1;
    }

    /**
     * The crossing edge nearest {@code point}, found by scanning every edge, for callers that
     * have no face to start from.
     *
     * @param mesh   surface to cut
     * @param point  a point the plane passes through, packed xyz
     * @param normal the plane's unit normal, packed xyz
     * @return the nearest crossing edge id, or {@code -1} when the plane misses the surface
     */
    public int nearestCrossingEdge(MeshTopology mesh, float[] point, float[] normal) {
        planePoint = point;
        planeNormal = normal;
        int nearest = -1;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < mesh.edgeCount(); index++) {
            int candidate = mesh.edgeIdAt(index);
            double fraction = crossingOn(mesh, candidate);
            if (fraction < 0.0) {
                continue;
            }
            double x = tailPosition.x + fraction * (headPosition.x - tailPosition.x) - point[0];
            double y = tailPosition.y + fraction * (headPosition.y - tailPosition.y) - point[1];
            double z = tailPosition.z + fraction * (headPosition.z - tailPosition.z) - point[2];
            double distance = x * x + y * y + z * z;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    /**
     * The crossings of the last walk as a traced surface path, the shape the conforming snap and
     * the ring measurements consume.
     *
     * @param mesh surface the walk ran on
     * @return the closed path, or null when the last walk did not close
     */
    public TracedSurfacePath asTracedPath(MeshTopology mesh) {
        if (!closed) {
            return null;
        }
        double[] positions = new double[COORDINATES_PER_POINT * stepCount];
        int[] vertexId = new int[stepCount];
        int[] pathEdgeId = new int[stepCount];
        double[] fraction = new double[stepCount];
        for (int step = 0; step < stepCount; step++) {
            int halfEdge = mesh.edgeHalfEdge(edgeId[step]);
            mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), tailPosition);
            mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), headPosition);
            double along = crossingFraction[step];
            positions[COORDINATES_PER_POINT * step] =
                    tailPosition.x + along * (headPosition.x - tailPosition.x);
            positions[COORDINATES_PER_POINT * step + 1] =
                    tailPosition.y + along * (headPosition.y - tailPosition.y);
            positions[COORDINATES_PER_POINT * step + 2] =
                    tailPosition.z + along * (headPosition.z - tailPosition.z);
            vertexId[step] = -1;
            pathEdgeId[step] = edgeId[step];
            fraction[step] = along;
        }
        return new TracedSurfacePath(positions, vertexId, pathEdgeId, fraction, stepCount, true);
    }

    private void measure(MeshTopology mesh) {
        if (polyline.length < COORDINATES_PER_POINT * stepCount) {
            polyline = new float[COORDINATES_PER_POINT * stepCount];
        }
        for (int step = 0; step < stepCount; step++) {
            int halfEdge = mesh.edgeHalfEdge(edgeId[step]);
            mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), tailPosition);
            mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), headPosition);
            double along = crossingFraction[step];
            polyline[COORDINATES_PER_POINT * step] =
                    (float) (tailPosition.x + along * (headPosition.x - tailPosition.x));
            polyline[COORDINATES_PER_POINT * step + 1] =
                    (float) (tailPosition.y + along * (headPosition.y - tailPosition.y));
            polyline[COORDINATES_PER_POINT * step + 2] =
                    (float) (tailPosition.z + along * (headPosition.z - tailPosition.z));
        }
        double seamX = polyline[0] - polyline[COORDINATES_PER_POINT * (stepCount - 1)];
        double seamY = polyline[1] - polyline[COORDINATES_PER_POINT * (stepCount - 1) + 1];
        double seamZ = polyline[2] - polyline[COORDINATES_PER_POINT * (stepCount - 1) + 2];
        length += Math.sqrt(seamX * seamX + seamY * seamY + seamZ * seamZ);
    }

    /**
     * Where the plane cuts one edge, as a fraction from its half-edge's tail to its head, or
     * {@code -1} when both endpoints lie on the same side. Leaves the endpoint positions in the
     * scratch vectors the callers read.
     */
    private double crossingOn(MeshTopology mesh, int candidateEdgeId) {
        int halfEdge = mesh.edgeHalfEdge(candidateEdgeId);
        mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), tailPosition);
        mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), headPosition);
        double tailSide = signedDistance(tailPosition);
        double headSide = signedDistance(headPosition);
        if (tailSide >= 0.0 == headSide >= 0.0) {
            return -1.0;
        }
        return tailSide / (tailSide - headSide);
    }

    private double signedDistance(Vector3f position) {
        return (position.x - planePoint[0]) * planeNormal[0]
                + (position.y - planePoint[1]) * planeNormal[1]
                + (position.z - planePoint[2]) * planeNormal[2];
    }

    private static int faceAcross(MeshTopology mesh, int edgeId, int faceId) {
        int halfEdge = mesh.edgeHalfEdge(edgeId);
        int here = mesh.halfEdgeFace(halfEdge);
        int twin = mesh.halfEdgeTwin(halfEdge);
        int there = twin < 0 ? -1 : mesh.halfEdgeFace(twin);
        return here == faceId ? there : here;
    }
}
