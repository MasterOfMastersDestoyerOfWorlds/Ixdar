package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.GirdlingPlane;
import ixdar.geometry.mesh.data.paths.SurfacePicker;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

/**
 * CRAW-28: the two pieces the ring tool's hover rests on, on hand-authored surfaces — where the
 * ray lands, and which plane girdles the limb it landed on.
 */
public class SurfacePickerTest {

    /** Coordinates per point in every packed position here. */
    private static final int COORDINATES_PER_POINT = 3;

    /** Half the side of the unit cube the rays are fired at. */
    private static final float HALF_SIDE = 0.5f;

    /** How far outside the surface a test ray starts. */
    private static final float RAY_START = 4f;

    /** Sides the procedural tube is swept with. */
    private static final int TUBE_SIDES = 48;

    /** Cross-sections along the procedural tube. */
    private static final int TUBE_RINGS = 40;

    /** Radius of the procedural tube. */
    private static final float TUBE_RADIUS = 0.25f;

    /** Length of the procedural tube, along x. */
    private static final float TUBE_LENGTH = 4f;

    /** Angle the girdling plane's normal may miss the tube's axis by, in degrees. */
    private static final double AXIS_TOLERANCE_DEGREES = 2.0;

    /** Barycentric weights are compared to this many places. */
    private static final double WEIGHT_TOLERANCE = 1e-4;

    @Test
    void aRayDownTheCubeLandsOnTheTopFaceWithTheExpectedWeights() {
        MeshTopology cube = unitCube();
        SurfacePicker picker = new SurfacePicker();

        float[] origin = { 0.25f, RAY_START, 0.1f };
        float[] direction = { 0f, -1f, 0f };
        assertTrue(picker.pickNearestFace(cube, origin, direction),
                "a ray straight down the middle of the cube hit nothing");

        assertEquals(HALF_SIDE, picker.pointY, WEIGHT_TOLERANCE,
                "the hit is not on the cube's top face");
        assertEquals(origin[0], picker.pointX, WEIGHT_TOLERANCE, "the hit drifted in x");
        assertEquals(origin[2], picker.pointZ, WEIGHT_TOLERANCE, "the hit drifted in z");
        assertEquals(RAY_START - HALF_SIDE, picker.distanceAlongRay, WEIGHT_TOLERANCE,
                "the hit is not at the expected distance along the ray");

        Vector3f corner = new Vector3f();
        float[] interpolated = new float[COORDINATES_PER_POINT];
        float[] weights = {
            picker.weightAtFirstCorner, picker.weightAtSecondCorner, picker.weightAtThirdCorner };
        double weightSum = 0.0;
        for (int slot = 0; slot < weights.length; slot++) {
            cube.vertexPosition(cube.faceVertexAt(picker.faceId, slot), corner);
            interpolated[0] += weights[slot] * corner.x;
            interpolated[1] += weights[slot] * corner.y;
            interpolated[2] += weights[slot] * corner.z;
            weightSum += weights[slot];
        }
        assertEquals(1.0, weightSum, WEIGHT_TOLERANCE, "the barycentric weights do not sum to one");
        assertEquals(picker.pointX, interpolated[0], WEIGHT_TOLERANCE,
                "the weights do not interpolate the hit's x");
        assertEquals(picker.pointY, interpolated[1], WEIGHT_TOLERANCE,
                "the weights do not interpolate the hit's y");
        assertEquals(picker.pointZ, interpolated[2], WEIGHT_TOLERANCE,
                "the weights do not interpolate the hit's z");

        assertTrue(picker.hitFace(cube, picker.faceId, origin, direction),
                "the face the ray landed on does not take the same ray");
    }

    @Test
    void aRayThatMissesTheCubeReportsNoHit() {
        MeshTopology cube = unitCube();
        SurfacePicker picker = new SurfacePicker();

        assertFalse(picker.pickNearestFace(cube, new float[] { 2f, RAY_START, 0f },
                new float[] { 0f, -1f, 0f }), "a ray beside the cube claimed a hit");
        assertEquals(-1, picker.faceId, "a miss left a face id behind");
    }

    @Test
    void theGirdlingPlaneOnATubeIsPerpendicularToItsAxis() {
        MeshTopology tube = tube();
        SurfacePicker picker = new SurfacePicker();
        float[] origin = { 0.37f, RAY_START, 0f };
        float[] direction = { 0f, -1f, 0f };
        assertTrue(picker.pickNearestFace(tube, origin, direction), "the ray missed the tube");

        GirdlingPlane girdle = new GirdlingPlane();
        float[] hit = { picker.pointX, picker.pointY, picker.pointZ };
        assertTrue(girdle.find(tube, picker.faceId, hit, null),
                "no girdling plane was found on the tube");

        double alongAxis = Math.abs(girdle.normal[0]);
        double missDegrees = Math.toDegrees(Math.acos(Math.min(1.0, alongAxis)));
        assertTrue(missDegrees < AXIS_TOLERANCE_DEGREES,
                "the girdling plane's normal misses the tube's axis by " + missDegrees
                        + " degrees, more than " + AXIS_TOLERANCE_DEGREES);
        double circumference = 2.0 * Math.PI * TUBE_RADIUS;
        assertTrue(Math.abs(girdle.length - circumference) < 0.05 * circumference,
                "the cut is " + girdle.length + ", not the tube's circumference " + circumference);
    }

    /**
     * The unit cube as twelve triangles, so a ray's face and weights can be checked by hand.
     *
     * @return the cube as a half-edge mesh
     */
    private static HalfEdgeMesh unitCube() {
        float[] positions = {
            -HALF_SIDE, -HALF_SIDE, -HALF_SIDE, HALF_SIDE, -HALF_SIDE, -HALF_SIDE,
            HALF_SIDE, HALF_SIDE, -HALF_SIDE, -HALF_SIDE, HALF_SIDE, -HALF_SIDE,
            -HALF_SIDE, -HALF_SIDE, HALF_SIDE, HALF_SIDE, -HALF_SIDE, HALF_SIDE,
            HALF_SIDE, HALF_SIDE, HALF_SIDE, -HALF_SIDE, HALF_SIDE, HALF_SIDE };
        int[] faces = {
            0, 2, 1, 0, 3, 2,
            4, 5, 6, 4, 6, 7,
            0, 1, 5, 0, 5, 4,
            3, 7, 6, 3, 6, 2,
            0, 4, 7, 0, 7, 3,
            1, 2, 6, 1, 6, 5 };
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faces);
    }

    /**
     * An open tube along x, the shape a limb is, so the plane that girdles it is known exactly.
     *
     * @return the tube as a half-edge mesh
     */
    private static HalfEdgeMesh tube() {
        float[] positions = new float[COORDINATES_PER_POINT * TUBE_SIDES * TUBE_RINGS];
        for (int ring = 0; ring < TUBE_RINGS; ring++) {
            float x = -HALF_SIDE * TUBE_LENGTH + TUBE_LENGTH * ring / (TUBE_RINGS - 1);
            for (int side = 0; side < TUBE_SIDES; side++) {
                double angle = 2.0 * Math.PI * side / TUBE_SIDES;
                int base = COORDINATES_PER_POINT * (TUBE_SIDES * ring + side);
                positions[base] = x;
                positions[base + 1] = (float) (TUBE_RADIUS * Math.cos(angle));
                positions[base + 2] = (float) (TUBE_RADIUS * Math.sin(angle));
            }
        }
        int[] faces = new int[2 * COORDINATES_PER_POINT * TUBE_SIDES * (TUBE_RINGS - 1)];
        int corner = 0;
        for (int ring = 0; ring + 1 < TUBE_RINGS; ring++) {
            for (int side = 0; side < TUBE_SIDES; side++) {
                int next = (side + 1) % TUBE_SIDES;
                int here = TUBE_SIDES * ring + side;
                int ahead = TUBE_SIDES * ring + next;
                int over = TUBE_SIDES * (ring + 1) + side;
                int overAhead = TUBE_SIDES * (ring + 1) + next;
                faces[corner++] = here;
                faces[corner++] = over;
                faces[corner++] = overAhead;
                faces[corner++] = here;
                faces[corner++] = overAhead;
                faces[corner++] = ahead;
            }
        }
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faces);
    }
}
