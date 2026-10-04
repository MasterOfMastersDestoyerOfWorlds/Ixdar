package ixdar.geometry.mesh.data.paths;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Where a ray meets a surface: the face it lands on, its barycentric weights there, and the
 * world point; a ray onto an edge two faces share lands on at least one of them.
 */
public final class SurfacePicker {

    public static final int COORDINATES_PER_POINT = 3;

    public static final int TRIANGLE_CORNERS = 3;

    public static final float MINIMUM_DISTANCE = 1e-6f;

    /** Face the last hit landed on, or {@code -1} when nothing was hit. */
    public int faceId = -1;

    /** Weight of the face's first corner in the last hit. */
    public float weightAtFirstCorner;

    /** Weight of the hit fan triangle's second corner, face corner {@link #fanCorner}. */
    public float weightAtSecondCorner;

    /** Weight of the hit fan triangle's third corner, face corner {@link #fanCorner} + 1. */
    public float weightAtThirdCorner;

    /** Face corner the hit fan triangle's second corner is; 1 on a triangle. */
    public int fanCorner;

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
     * The ray's hit on {@code hintFace} or, when it passes outside it, the nearest hit on a face
     * sharing a corner with it: the id buffer and the ray may give an edge to different sides.
     *
     * @param mesh         surface the face belongs to
     * @param hintFace     face id the GPU id buffer reports under the ray
     * @param rayOrigin    ray origin, packed xyz
     * @param rayDirection ray direction, packed xyz, need not be normalised
     * @return true when the ray crosses the hint face or one of its neighbours
     */
    public boolean pickNear(MeshTopology mesh, int hintFace, float[] rayOrigin,
            float[] rayDirection) {
        if (hitFace(mesh, hintFace, rayOrigin, rayDirection)) {
            return true;
        }
        if (mesh == null || hintFace < 0 || !mesh.hasFace(hintFace)) {
            return false;
        }
        int nearestFace = -1;
        float nearestDistance = Float.POSITIVE_INFINITY;
        for (int corner = 0; corner < mesh.faceVertexCount(hintFace); corner++) {
            int vertexId = mesh.faceVertexAt(hintFace, corner);
            for (int slot = 0; slot < mesh.vertexFaceCount(vertexId); slot++) {
                int neighbour = mesh.vertexFaceAt(vertexId, slot);
                if (neighbour != hintFace && neighbour != nearestFace
                        && hitFace(mesh, neighbour, rayOrigin, rayDirection)
                        && distanceAlongRay < nearestDistance) {
                    nearestDistance = distanceAlongRay;
                    nearestFace = neighbour;
                }
            }
        }
        return nearestFace >= 0 && hitFace(mesh, nearestFace, rayOrigin, rayDirection);
    }

    /**
     * Intersects the ray with one face, a polygon as the fan from its first corner, and records
     * the hit; triangles sharing an edge agree which side the ray passes, so none falls between.
     *
     * <p>
     * Woop, Benthin and Wald 2013, "Watertight Ray/Triangle Intersection", JCGT 2(1).
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
        if (mesh == null || candidateFace < 0 || !mesh.hasFace(candidateFace)) {
            return false;
        }
        // Shear the ray onto +z of a frame whose z is the direction's dominant axis; the swap
        // keeps the frame right-handed so every edge's 2D function has one sign convention.
        int axisZ = 0;
        for (int axis = 1; axis < COORDINATES_PER_POINT; axis++) {
            if (Math.abs(rayDirection[axis]) > Math.abs(rayDirection[axisZ])) {
                axisZ = axis;
            }
        }
        if (rayDirection[axisZ] == 0f) {
            return false;
        }
        int axisX = (axisZ + 1) % COORDINATES_PER_POINT;
        int axisY = (axisX + 1) % COORDINATES_PER_POINT;
        if (rayDirection[axisZ] < 0f) {
            int swapped = axisX;
            axisX = axisY;
            axisY = swapped;
        }
        double shearX = rayDirection[axisX] / (double) rayDirection[axisZ];
        double shearY = rayDirection[axisY] / (double) rayDirection[axisZ];
        double scaleZ = 1.0 / rayDirection[axisZ];
        for (int fan = 1; fan + 1 < mesh.faceVertexCount(candidateFace); fan++) {
            mesh.vertexPosition(mesh.faceVertexAt(candidateFace, 0), cornerA);
            mesh.vertexPosition(mesh.faceVertexAt(candidateFace, fan), cornerB);
            mesh.vertexPosition(mesh.faceVertexAt(candidateFace, fan + 1), cornerC);
            double aDepth = cornerA.get(axisZ) - (double) rayOrigin[axisZ];
            double bDepth = cornerB.get(axisZ) - (double) rayOrigin[axisZ];
            double cDepth = cornerC.get(axisZ) - (double) rayOrigin[axisZ];
            double aX = cornerA.get(axisX) - (double) rayOrigin[axisX] - shearX * aDepth;
            double aY = cornerA.get(axisY) - (double) rayOrigin[axisY] - shearY * aDepth;
            double bX = cornerB.get(axisX) - (double) rayOrigin[axisX] - shearX * bDepth;
            double bY = cornerB.get(axisY) - (double) rayOrigin[axisY] - shearY * bDepth;
            double cX = cornerC.get(axisX) - (double) rayOrigin[axisX] - shearX * cDepth;
            double cY = cornerC.get(axisY) - (double) rayOrigin[axisY] - shearY * cDepth;
            double oppositeA = cX * bY - cY * bX;
            double oppositeB = aX * cY - aY * cX;
            double oppositeC = bX * aY - bY * aX;
            if ((oppositeA < 0.0 || oppositeB < 0.0 || oppositeC < 0.0)
                    && (oppositeA > 0.0 || oppositeB > 0.0 || oppositeC > 0.0)) {
                continue;
            }
            double determinant = oppositeA + oppositeB + oppositeC;
            if (determinant == 0.0) {
                continue;
            }
            double parameter = scaleZ * (oppositeA * aDepth + oppositeB * bDepth
                    + oppositeC * cDepth) / determinant;
            if (parameter < MINIMUM_DISTANCE) {
                continue;
            }
            faceId = candidateFace;
            fanCorner = fan;
            weightAtFirstCorner = (float) (oppositeA / determinant);
            weightAtSecondCorner = (float) (oppositeB / determinant);
            weightAtThirdCorner = (float) (oppositeC / determinant);
            distanceAlongRay = (float) parameter;
            pointX = (float) (rayOrigin[0] + parameter * rayDirection[0]);
            pointY = (float) (rayOrigin[1] + parameter * rayDirection[1]);
            pointZ = (float) (rayOrigin[2] + parameter * rayDirection[2]);
            return true;
        }
        return false;
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
