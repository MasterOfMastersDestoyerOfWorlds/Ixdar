package ixdar.geometry.mesh.data.paths;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Where a ray meets a surface: the face it lands on, the barycentric weights inside that face,
 * and the world point they interpolate.
 *
 * <p>
 * Moller and Trumbore 1997. The interactive tool narrows the ray to one face with the GPU id
 * buffer and calls {@link #hitFace}; only tests scan every face.
 */
public final class SurfacePicker {

    /** Coordinates per point in a ray or a hit position. */
    public static final int COORDINATES_PER_POINT = 3;

    /** Corners a triangle the picker tests must have. */
    public static final int TRIANGLE_CORNERS = 3;

    /** Ray parameters below this count as behind the eye. */
    public static final float MINIMUM_DISTANCE = 1e-6f;

    /** Determinants below this mean the ray runs along the triangle's plane. */
    public static final float PARALLEL_EPSILON = 1e-12f;

    /** Face the last hit landed on, or {@code -1} when nothing was hit. */
    public int faceId = -1;

    /** Weight of the face's first corner in the last hit. */
    public float weightAtFirstCorner;

    /** Weight of the face's second corner in the last hit. */
    public float weightAtSecondCorner;

    /** Weight of the face's third corner in the last hit. */
    public float weightAtThirdCorner;

    /** World x of the last hit. */
    public float pointX;

    /** World y of the last hit. */
    public float pointY;

    /** World z of the last hit. */
    public float pointZ;

    /** Ray parameter of the last hit, in the units of the direction given. */
    public float distanceAlongRay;

    private final Vector3f cornerA = new Vector3f();
    private final Vector3f cornerB = new Vector3f();
    private final Vector3f cornerC = new Vector3f();

    /**
     * Intersects the ray with one triangle of the mesh and records the hit.
     *
     * @param mesh          surface the face belongs to
     * @param candidateFace face id to test
     * @param rayOrigin     ray origin, packed xyz
     * @param rayDirection  ray direction, packed xyz, need not be normalised
     * @return true when the ray crosses the face in front of its origin
     */
    public boolean hitFace(MeshTopology mesh, int candidateFace, float[] rayOrigin,
            float[] rayDirection) {
        faceId = -1;
        if (mesh == null || candidateFace < 0 || !mesh.hasFace(candidateFace)
                || mesh.faceVertexCount(candidateFace) < TRIANGLE_CORNERS) {
            return false;
        }
        mesh.vertexPosition(mesh.faceVertexAt(candidateFace, 0), cornerA);
        mesh.vertexPosition(mesh.faceVertexAt(candidateFace, 1), cornerB);
        mesh.vertexPosition(mesh.faceVertexAt(candidateFace, 2), cornerC);

        float edgeOneX = cornerB.x - cornerA.x;
        float edgeOneY = cornerB.y - cornerA.y;
        float edgeOneZ = cornerB.z - cornerA.z;
        float edgeTwoX = cornerC.x - cornerA.x;
        float edgeTwoY = cornerC.y - cornerA.y;
        float edgeTwoZ = cornerC.z - cornerA.z;
        float pivotX = rayDirection[1] * edgeTwoZ - rayDirection[2] * edgeTwoY;
        float pivotY = rayDirection[2] * edgeTwoX - rayDirection[0] * edgeTwoZ;
        float pivotZ = rayDirection[0] * edgeTwoY - rayDirection[1] * edgeTwoX;
        float determinant = edgeOneX * pivotX + edgeOneY * pivotY + edgeOneZ * pivotZ;
        if (Math.abs(determinant) < PARALLEL_EPSILON) {
            return false;
        }
        float inverseDeterminant = 1f / determinant;
        float toOriginX = rayOrigin[0] - cornerA.x;
        float toOriginY = rayOrigin[1] - cornerA.y;
        float toOriginZ = rayOrigin[2] - cornerA.z;
        float alongEdgeTwo = inverseDeterminant
                * (toOriginX * pivotX + toOriginY * pivotY + toOriginZ * pivotZ);
        if (alongEdgeTwo < 0f || alongEdgeTwo > 1f) {
            return false;
        }
        float crossX = toOriginY * edgeOneZ - toOriginZ * edgeOneY;
        float crossY = toOriginZ * edgeOneX - toOriginX * edgeOneZ;
        float crossZ = toOriginX * edgeOneY - toOriginY * edgeOneX;
        float alongEdgeOne = inverseDeterminant
                * (rayDirection[0] * crossX + rayDirection[1] * crossY + rayDirection[2] * crossZ);
        if (alongEdgeOne < 0f || alongEdgeTwo + alongEdgeOne > 1f) {
            return false;
        }
        float parameter = inverseDeterminant
                * (edgeTwoX * crossX + edgeTwoY * crossY + edgeTwoZ * crossZ);
        if (parameter < MINIMUM_DISTANCE) {
            return false;
        }
        faceId = candidateFace;
        weightAtSecondCorner = alongEdgeTwo;
        weightAtThirdCorner = alongEdgeOne;
        weightAtFirstCorner = 1f - alongEdgeTwo - alongEdgeOne;
        distanceAlongRay = parameter;
        pointX = rayOrigin[0] + parameter * rayDirection[0];
        pointY = rayOrigin[1] + parameter * rayDirection[1];
        pointZ = rayOrigin[2] + parameter * rayDirection[2];
        return true;
    }

    /**
     * The nearest face the ray crosses, found by testing every face. Only small procedural meshes
     * and unit tests use this; the interactive tool reads the face from the GPU id buffer.
     *
     * @param mesh         surface to test
     * @param rayOrigin    ray origin, packed xyz
     * @param rayDirection ray direction, packed xyz, need not be normalised
     * @return true when the ray crosses the surface in front of its origin
     */
    public boolean pickNearestFace(MeshTopology mesh, float[] rayOrigin, float[] rayDirection) {
        int nearestFace = -1;
        float nearestDistance = Float.POSITIVE_INFINITY;
        for (int index = 0; index < mesh.faceCount(); index++) {
            int candidate = mesh.faceIdAt(index);
            if (hitFace(mesh, candidate, rayOrigin, rayDirection)
                    && distanceAlongRay < nearestDistance) {
                nearestDistance = distanceAlongRay;
                nearestFace = candidate;
            }
        }
        return nearestFace >= 0 && hitFace(mesh, nearestFace, rayOrigin, rayDirection);
    }
}
