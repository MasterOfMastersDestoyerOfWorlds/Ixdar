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

    private static final int COORDINATES_PER_POINT = 3;

    private static final float HALF_SIDE = 0.5f;

    private static final float RAY_START = 4f;

    private static final int TUBE_SIDES = 48;

    private static final int TUBE_RINGS = 40;

    private static final float TUBE_RADIUS = 0.25f;

    private static final float TUBE_LENGTH = 4f;

    private static final double AXIS_TOLERANCE_DEGREES = 2.0;

    private static final double WEIGHT_TOLERANCE = 1e-4;

    private static final int DIAMOND_SIDES = 16;

    private static final int DIAMOND_ROWS_ODD_FOR_A_CREASE_ROW = 51;

    private static final float DIAMOND_CREASE_RADIUS = 0.8f;

    private static final float DIAMOND_END_RADIUS = 0.2f;

    private static final int QUAD_CORNERS = 4;

    private static final String HIT_DRIFTED_IN_X = "the hit drifted in x";

    private static final String HIT_DRIFTED_IN_Z = "the hit drifted in z";

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
        assertEquals(origin[0], picker.pointX, WEIGHT_TOLERANCE, HIT_DRIFTED_IN_X);
        assertEquals(origin[2], picker.pointZ, WEIGHT_TOLERANCE, HIT_DRIFTED_IN_Z);
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
     * CRAW-40: a ray landing exactly on the diamond tube's crease ring, an edge two quads share
     * and fold across, is a hit on either quad, and the girdle through it is found even though
     * the crease is the tube's widest loop.
     */
    @Test
    void aRayOntoTheCreaseEdgeHitsBothQuadsAndGirdlesTheTube() {
        MeshTopology diamond = diamondTube();
        int creaseRow = DIAMOND_ROWS_ODD_FOR_A_CREASE_ROW / 2;
        assertRayOntoSegmentHits(diamond, diamondVertex(creaseRow, 0),
                diamondVertex(creaseRow, 1), 2);
    }

    /**
     * CRAW-40: a ray landing exactly on an edge two quads share along the cone is a hit on both,
     * and one landing on a quad's own fan diagonal is a hit on that quad.
     */
    @Test
    void aRayOntoASharedEdgeOrAFanDiagonalHitsEveryFaceHoldingIt() {
        MeshTopology diamond = diamondTube();
        int coneRow = DIAMOND_ROWS_ODD_FOR_A_CREASE_ROW / 2 + 2;
        assertRayOntoSegmentHits(diamond, diamondVertex(coneRow, 0),
                diamondVertex(coneRow + 1, 0), 2);
        assertRayOntoSegmentHits(diamond, diamondVertex(coneRow, 0),
                diamondVertex(coneRow + 1, 1), 1);
    }

    /**
     * Fire a ray radially inward at the midpoint of the segment between two vertices and require
     * a hit on that point whichever face holding both the id buffer hints, and a girdle there.
     *
     * @param mesh          surface to fire at
     * @param first         one end of the segment, packed xyz
     * @param second        the other end, packed xyz
     * @param expectedFaces faces holding both ends: two for a shared edge, one for a diagonal
     */
    private static void assertRayOntoSegmentHits(MeshTopology mesh, float[] first,
            float[] second, int expectedFaces) {
        float[] target = new float[COORDINATES_PER_POINT];
        for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
            target[axis] = 0.5f * (first[axis] + second[axis]);
        }
        float[] direction = { -target[0], 0f, -target[2] };
        float[] origin = {
            target[0] - RAY_START * direction[0], target[1], target[2] - RAY_START * direction[2] };
        SurfacePicker picker = new SurfacePicker();
        GirdlingPlane girdle = new GirdlingPlane();
        Vector3f corner = new Vector3f();
        int holdingFaces = 0;
        for (int index = 0; index < mesh.faceCount(); index++) {
            int faceId = mesh.faceIdAt(index);
            boolean holdsFirst = false;
            boolean holdsSecond = false;
            for (int slot = 0; slot < mesh.faceVertexCount(faceId); slot++) {
                mesh.vertexPosition(mesh.faceVertexAt(faceId, slot), corner);
                holdsFirst |= corner.equals(first[0], first[1], first[2]);
                holdsSecond |= corner.equals(second[0], second[1], second[2]);
            }
            if (!holdsFirst || !holdsSecond) {
                continue;
            }
            holdingFaces++;
            assertTrue(picker.pickNear(mesh, faceId, origin, direction),
                    "a ray exactly onto the shared segment, hinted at face " + faceId
                            + ", missed the surface");
            assertEquals(target[0], picker.pointX, WEIGHT_TOLERANCE, HIT_DRIFTED_IN_X);
            assertEquals(target[1], picker.pointY, WEIGHT_TOLERANCE, "the hit drifted in y");
            assertEquals(target[2], picker.pointZ, WEIGHT_TOLERANCE, HIT_DRIFTED_IN_Z);
            float[] hit = { picker.pointX, picker.pointY, picker.pointZ };
            assertTrue(girdle.find(mesh, picker.faceId, hit, new float[] { 0f, 1f, 0f }),
                    "no girdle was found through the hit on face " + picker.faceId);
        }
        assertTrue(picker.pickNearestFace(mesh, origin, direction),
                "a ray exactly onto the shared segment fell between the faces holding it");
        assertEquals(expectedFaces, holdingFaces, "the segment is not held by the faces expected");
    }

    /**
     * Position of one vertex of {@link #diamondTube()}.
     *
     * @param row  cross-section, 0 at the bottom tip
     * @param side index around the cross-section
     * @return the position, packed xyz
     */
    private static float[] diamondVertex(int row, int side) {
        float halfLength = 0.5f * TUBE_LENGTH;
        float height = -halfLength + TUBE_LENGTH * row / (DIAMOND_ROWS_ODD_FOR_A_CREASE_ROW - 1);
        float radius = DIAMOND_CREASE_RADIUS
                - (DIAMOND_CREASE_RADIUS - DIAMOND_END_RADIUS) * Math.abs(height) / halfLength;
        double angle = 2.0 * Math.PI * side / DIAMOND_SIDES;
        return new float[] {
            (float) (radius * Math.cos(angle)), height, (float) (radius * Math.sin(angle)) };
    }

    /**
     * The quad tube varying_tube_test.dsl sweeps: radius 0.2 at the ends and 0.8 at the middle,
     * a crease ring at the middle row, and that ring longer than twice the bounding radius.
     *
     * @return the open diamond tube as a quad half-edge mesh
     */
    private static HalfEdgeMesh diamondTube() {
        float[] positions = new float[COORDINATES_PER_POINT * DIAMOND_SIDES
                * DIAMOND_ROWS_ODD_FOR_A_CREASE_ROW];
        for (int row = 0; row < DIAMOND_ROWS_ODD_FOR_A_CREASE_ROW; row++) {
            for (int side = 0; side < DIAMOND_SIDES; side++) {
                System.arraycopy(diamondVertex(row, side), 0, positions,
                        COORDINATES_PER_POINT * (DIAMOND_SIDES * row + side),
                        COORDINATES_PER_POINT);
            }
        }
        int[] faces =
                new int[QUAD_CORNERS * DIAMOND_SIDES * (DIAMOND_ROWS_ODD_FOR_A_CREASE_ROW - 1)];
        int corner = 0;
        for (int row = 0; row + 1 < DIAMOND_ROWS_ODD_FOR_A_CREASE_ROW; row++) {
            for (int side = 0; side < DIAMOND_SIDES; side++) {
                int next = (side + 1) % DIAMOND_SIDES;
                faces[corner++] = DIAMOND_SIDES * row + side;
                faces[corner++] = DIAMOND_SIDES * (row + 1) + side;
                faces[corner++] = DIAMOND_SIDES * (row + 1) + next;
                faces[corner++] = DIAMOND_SIDES * row + next;
            }
        }
        HalfEdgeMesh diamond = HalfEdgeMeshEngine.bulkAllocate(positions, faces, QUAD_CORNERS);
        diamond.computeNormals();
        return diamond;
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
