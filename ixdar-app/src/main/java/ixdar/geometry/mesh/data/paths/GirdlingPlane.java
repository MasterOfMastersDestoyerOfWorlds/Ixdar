package ixdar.geometry.mesh.data.paths;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * The plane through a surface point that cuts across the limb there rather than along it.
 *
 * <p>
 * Its normal lies in the surface's tangent plane, so the search is over one angle, and the
 * shortest closed cut wins.
 */
public final class GirdlingPlane {

    /** Coordinates per point in every packed position here. */
    public static final int COORDINATES_PER_POINT = 3;

    /** Directions the half-circle of candidate normals is sampled at. */
    public static final int DEFAULT_SCAN_DIRECTIONS = 12;

    /** Bisection rounds that narrow the scan around its best direction. */
    public static final int DEFAULT_REFINE_LEVELS = 3;

    /** Model radii a cut may reach before it is abandoned as running along the limb. */
    public static final double DEFAULT_LONGEST_GIRDLE_IN_RADII = 2.0;

    /** One half, as the bisection step and the tangent-basis axis choice. */
    public static final float HALF = 0.5f;

    /** Directions the scan samples across the half-circle of candidate normals. */
    public int scanDirections = DEFAULT_SCAN_DIRECTIONS;

    /** Bisection rounds run after the scan. */
    public int refineLevels = DEFAULT_REFINE_LEVELS;

    /** Model radii a cut may reach before the search abandons it. */
    public double longestGirdleInRadii = DEFAULT_LONGEST_GIRDLE_IN_RADII;

    /** Extra rotation of the found normal about {@link #viewDirection}, in radians. */
    public float tiltAboutView;

    /** Extra rotation about the tangent perpendicular to the view, in radians. */
    public float tiltAboutTangent;

    /** Direction the viewer looks along, the axis the first tilt turns about. */
    public final float[] viewDirection = new float[COORDINATES_PER_POINT];

    /** Normal of the plane the last search settled on, packed xyz. */
    public final float[] normal = new float[COORDINATES_PER_POINT];

    /** The cut itself, packed xyz, closed by its last-to-first span. */
    public float[] polyline = new float[0];

    /** Mesh edges the cut crosses. */
    public int edgeCount;

    /** Euclidean length of the cut. */
    public double length;

    /** The walk the last search settled on, whose crossings carry the edges the cut runs over. */
    public final PlaneSurfaceLoop cut = new PlaneSurfaceLoop();

    /**
     * Surface {@link #modelRadius} was measured on. A hover searches the same surface every frame,
     * and measuring it walks every vertex, so it is measured once per surface instance.
     */
    public MeshTopology measuredSurface;

    /** Bounding-sphere radius of {@link #measuredSurface}, the unit the length budget is in. */
    public double modelRadius;

    private final Vector3f surfaceNormal = new Vector3f();
    private final Vector3f firstTangent = new Vector3f();
    private final Vector3f secondTangent = new Vector3f();
    private final Vector3f candidate = new Vector3f();
    private final Vector3f viewVector = new Vector3f();
    private final Vector3f otherTangent = new Vector3f();

    /**
     * Search for the girdling plane through {@code point} on {@code faceId} and keep its cut.
     *
     * @param mesh     surface to cut
     * @param faceId   face the point lies on, which the walk starts from
     * @param point    the point the plane passes through, packed xyz
     * @param seedAxis a first guess at the limb axis the scan starts from, or null for none
     * @return true when a closed cut was found
     */
    public boolean find(MeshTopology mesh, int faceId, float[] point, float[] seedAxis) {
        polyline = new float[0];
        edgeCount = 0;
        length = 0.0;
        if (mesh == null || faceId < 0 || !mesh.hasFace(faceId)) {
            return false;
        }
        mesh.faceNormal(faceId, surfaceNormal);
        if (surfaceNormal.lengthSquared() <= 0f) {
            return false;
        }
        surfaceNormal.normalize();
        surfaceNormal.cross(Math.abs(surfaceNormal.x) < HALF ? new Vector3f(1f, 0f, 0f)
                : new Vector3f(0f, 1f, 0f), firstTangent);
        firstTangent.normalize();
        surfaceNormal.cross(firstTangent, secondTangent).normalize();

        double seedAngle = seedAxis == null ? 0.0
                : Math.atan2(seedAxis[0] * secondTangent.x + seedAxis[1] * secondTangent.y
                        + seedAxis[2] * secondTangent.z,
                        seedAxis[0] * firstTangent.x + seedAxis[1] * firstTangent.y
                                + seedAxis[2] * firstTangent.z);

        if (mesh != measuredSurface) {
            measuredSurface = mesh;
            modelRadius = mesh.radius();
        }
        double step = Math.PI / scanDirections;
        double bestAngle = seedAngle;
        double bestLength = longestGirdleInRadii * modelRadius;
        boolean found = false;
        for (int direction = 0; direction < scanDirections; direction++) {
            double angle = seedAngle + direction * step;
            double cutLength = walkAt(mesh, faceId, point, angle, false, bestLength);
            if (cutLength < bestLength) {
                bestLength = cutLength;
                bestAngle = angle;
                found = true;
            }
        }
        for (int level = 0; level < refineLevels; level++) {
            step *= HALF;
            for (int side = -1; side <= 1; side += 2) {
                double angle = bestAngle + side * step;
                double cutLength = walkAt(mesh, faceId, point, angle, false, bestLength);
                if (cutLength < bestLength) {
                    bestLength = cutLength;
                    bestAngle = angle;
                    found = true;
                }
            }
        }
        if (!found || walkAt(mesh, faceId, point, bestAngle, true, Double.POSITIVE_INFINITY)
                == Double.POSITIVE_INFINITY) {
            return false;
        }
        polyline = cut.polyline;
        edgeCount = cut.stepCount;
        length = cut.length;
        return true;
    }

    /**
     * Cut with the plane whose normal sits at {@code angle} in the surface's tangent plane and
     * report the loop's length; a walk that passes {@code budget} is abandoned unfinished.
     */
    private double walkAt(MeshTopology mesh, int faceId, float[] point, double angle,
            boolean applyTilt, double budget) {
        candidate.set(firstTangent).mul((float) Math.cos(angle))
                .fma((float) Math.sin(angle), secondTangent);
        if (applyTilt && (tiltAboutView != 0f || tiltAboutTangent != 0f)) {
            viewVector.set(viewDirection[0], viewDirection[1], viewDirection[2]);
            if (viewVector.lengthSquared() > 0f) {
                viewVector.normalize();
                candidate.rotateAxis(tiltAboutView, viewVector.x, viewVector.y, viewVector.z);
                candidate.cross(viewVector, otherTangent);
                if (otherTangent.lengthSquared() > 0f) {
                    otherTangent.normalize();
                    candidate.rotateAxis(tiltAboutTangent, otherTangent.x, otherTangent.y,
                            otherTangent.z);
                }
            }
        }
        if (candidate.lengthSquared() <= 0f) {
            return Double.POSITIVE_INFINITY;
        }
        candidate.normalize();
        normal[0] = candidate.x;
        normal[1] = candidate.y;
        normal[2] = candidate.z;
        int startEdgeId = cut.crossingEdgeOn(mesh, point, normal, faceId);
        cut.maximumLength = budget;
        if (startEdgeId < 0 || !cut.walk(mesh, point, normal, startEdgeId)) {
            return Double.POSITIVE_INFINITY;
        }
        return cut.length;
    }
}
