package ixdar.geometry.mesh.data.paths.query;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * The loop a plane cuts out of a surface, walked face by face from one crossing edge.
 *
 * <p>
 * Crossings are computed only on the faces the walk touches, so the cost is the loop's length
 * rather than the mesh's edge count.
 */
public final class PlaneSurfaceLoop {

    public static final int COORDINATES_PER_POINT = 3;

    public static final int MAXIMUM_STEPS = 200000;

    public static final int INITIAL_CROSSINGS = 256;

    /**
     * The loop the last walk closed, measured: one point per crossed mesh edge in travel order,
     * its fraction along {@code edgeHalfEdge}'s tail-to-head. Null when the last walk did not
     * return to the edge it started from.
     */
    public TracedSurfacePath path;

    /**
     * Length a walk may reach before it is abandoned. A search over cutting planes sets this to
     * the best loop it has so far, which stops the planes that cut along a limb rather than
     * around it from being traced to the end.
     */
    public double maximumLength = Double.POSITIVE_INFINITY;

    private int[] crossedEdgeId = new int[0];
    private double[] crossedFraction = new double[0];
    private double[] crossedXyz = new double[0];
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
     * @return true when the walk closed back onto {@code startEdgeId} within
     *         {@link #maximumLength}
     */
    public boolean walk(MeshTopology mesh, float[] point, float[] normal, int startEdgeId) {
        planePoint = point;
        planeNormal = normal;
        path = null;
        if (mesh == null || startEdgeId < 0 || !mesh.hasEdge(startEdgeId)
                || crossingOn(mesh, startEdgeId) < 0.0) {
            return false;
        }
        int capacity = Math.min(MAXIMUM_STEPS, Math.max(mesh.edgeCount(), 1));
        int currentEdgeId = startEdgeId;
        int currentFaceId = mesh.halfEdgeFace(mesh.edgeHalfEdge(startEdgeId));
        int stepCount = 0;
        double walked = 0.0;
        for (int step = 0; step < capacity; step++) {
            if (currentFaceId < 0) {
                return false;
            }
            double along = crossingOn(mesh, currentEdgeId);
            if (stepCount == crossedEdgeId.length) {
                int grown = Math.min(capacity,
                        Math.max(INITIAL_CROSSINGS, 2 * crossedEdgeId.length));
                crossedEdgeId = Arrays.copyOf(crossedEdgeId, grown);
                crossedFraction = Arrays.copyOf(crossedFraction, grown);
                crossedXyz = Arrays.copyOf(crossedXyz, COORDINATES_PER_POINT * grown);
            }
            int base = COORDINATES_PER_POINT * stepCount;
            crossedXyz[base] = tailPosition.x + along * (headPosition.x - tailPosition.x);
            crossedXyz[base + 1] = tailPosition.y + along * (headPosition.y - tailPosition.y);
            crossedXyz[base + 2] = tailPosition.z + along * (headPosition.z - tailPosition.z);
            if (stepCount > 0) {
                double dx = crossedXyz[base] - crossedXyz[base - COORDINATES_PER_POINT];
                double dy = crossedXyz[base + 1] - crossedXyz[base - COORDINATES_PER_POINT + 1];
                double dz = crossedXyz[base + 2] - crossedXyz[base - COORDINATES_PER_POINT + 2];
                walked += Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (walked > maximumLength) {
                    return false;
                }
            }
            crossedEdgeId[stepCount] = currentEdgeId;
            crossedFraction[stepCount] = along;
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
                int[] noVertex = new int[stepCount];
                Arrays.fill(noVertex, -1);
                path = new TracedSurfacePath(
                        Arrays.copyOf(crossedXyz, COORDINATES_PER_POINT * stepCount), noVertex,
                        Arrays.copyOf(crossedEdgeId, stepCount),
                        Arrays.copyOf(crossedFraction, stepCount), stepCount, true).measure();
                return path.length <= maximumLength;
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
