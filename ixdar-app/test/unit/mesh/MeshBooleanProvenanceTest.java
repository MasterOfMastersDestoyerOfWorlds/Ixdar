package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.csg.BooleanOperation;
import ixdar.geometry.mesh.csg.MeshBooleanResult;
import ixdar.geometry.mesh.csg.QuadTriangulation;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.platform.Platforms;

/**
 * Booleans two quad cubes placed as QuadMixer's blending case demands — one cube's corner on the
 * other's centre — and checks both the geometry and the per-face provenance the later stages need.
 *
 * <p>The two-cube fixture is built from arrays, never loaded from a file.
 */
public class MeshBooleanProvenanceTest {

    /** Faces on a cube, so a source face id must fall below this. */
    private static final int CUBE_FACE_COUNT = 6;

    /** Overlap of the two cubes is the corner octant, an eighth of a unit cube. */
    private static final double OVERLAP_VOLUME = 0.125;

    /** Volume tolerance, loose enough for float positions round-tripping through the kernel. */
    private static final double VOLUME_TOLERANCE = 1e-5;

    /**
     * Untouched triangles per cube in the union: the three quads facing away from the other cube,
     * two triangles each.
     */
    private static final int UNTOUCHED_TRIANGLES_PER_CUBE = 6;

    /** Tolerance for a centroid lying on an axis-aligned quad's plane after float round trips. */
    private static final double PLANE_TOLERANCE = 1e-6;

    private static final float HALF = 0.5f;
    private static final int THREE = 3;
    private static final int QUAD_CORNERS = 4;
    private static final int SIX = 6;

    /**
     * Cube A spans [-0.5, 0.5]^3 and cube B spans [0, 1]^3, so B's corner sits exactly on A's
     * centre. All three operations must close up with the volumes exact set arithmetic predicts.
     */
    @Test
    public void cornerAtCentreCubesBooleanToClosedSolids() {
        QuadTriangulation cubeA = new QuadTriangulation(unitCube(0f)).build();
        QuadTriangulation cubeB = new QuadTriangulation(unitCube(HALF)).build();

        assertEquals(CUBE_FACE_COUNT * 2, cubeA.triangleSourceFace.length,
                "each of the cube's six quads splits into two triangles");

        MeshBooleanResult union = boolean3d(cubeA, cubeB, BooleanOperation.UNION);
        MeshBooleanResult difference = boolean3d(cubeA, cubeB, BooleanOperation.DIFFERENCE);
        MeshBooleanResult intersection = boolean3d(cubeA, cubeB, BooleanOperation.INTERSECTION);

        assertEquals(2 - OVERLAP_VOLUME, signedVolume(union.mesh), VOLUME_TOLERANCE,
                "union is both cubes less their shared octant");
        assertEquals(1 - OVERLAP_VOLUME, signedVolume(difference.mesh), VOLUME_TOLERANCE,
                "difference is cube A less the shared octant");
        assertEquals(OVERLAP_VOLUME, signedVolume(intersection.mesh), VOLUME_TOLERANCE,
                "intersection is exactly the shared octant");

        for (MeshBooleanResult result : new MeshBooleanResult[] {union, difference, intersection}) {
            assertTrue(isClosed(result.mesh), "a boolean of two solids is a closed surface");
        }
    }

    /**
     * Union: the three quads of each cube facing away from the other survive untouched, everything
     * else was cut by the intersection curve, and every face traces to the quad it lies on.
     */
    @Test
    public void unionFacesCarryTheirOriginatingCubeAndQuad() {
        ArrayMesh meshA = unitCube(0f);
        ArrayMesh meshB = unitCube(HALF);
        QuadTriangulation cubeA = new QuadTriangulation(meshA).build();
        QuadTriangulation cubeB = new QuadTriangulation(meshB).build();
        MeshBooleanResult union = boolean3d(cubeA, cubeB, BooleanOperation.UNION);

        assertProvenanceShape(union);
        assertEquals(UNTOUCHED_TRIANGLES_PER_CUBE, countOrigin(union, MeshBooleanResult.ORIGIN_A),
                "three quads of cube A face away from cube B and survive untouched");
        assertEquals(UNTOUCHED_TRIANGLES_PER_CUBE, countOrigin(union, MeshBooleanResult.ORIGIN_B),
                "three quads of cube B face away from cube A and survive untouched");
        assertEquals(union.mesh.faceCount() - 2 * UNTOUCHED_TRIANGLES_PER_CUBE,
                countOrigin(union, MeshBooleanResult.ORIGIN_NEW),
                "every other face is a piece the intersection curve cut");
        assertTrue(union.mesh.faceCount() > cubeA.triangleSourceFace.length
                + cubeB.triangleSourceFace.length,
                "the intersection curve split faces rather than merely relabelling them");
        assertFacesLieOnTheirSourceQuads(union, meshA, meshB);
    }

    /**
     * Difference keeps cube A's three far quads untouched and only cut pieces of cube B, while
     * the intersection, the shared octant, is bounded entirely by cut pieces.
     */
    @Test
    public void differenceAndIntersectionReportConsistentOrigins() {
        ArrayMesh meshA = unitCube(0f);
        ArrayMesh meshB = unitCube(HALF);
        QuadTriangulation cubeA = new QuadTriangulation(meshA).build();
        QuadTriangulation cubeB = new QuadTriangulation(meshB).build();

        MeshBooleanResult difference = boolean3d(cubeA, cubeB, BooleanOperation.DIFFERENCE);
        assertProvenanceShape(difference);
        assertEquals(UNTOUCHED_TRIANGLES_PER_CUBE,
                countOrigin(difference, MeshBooleanResult.ORIGIN_A),
                "cube A's three far quads survive the difference untouched");
        assertEquals(0, countOrigin(difference, MeshBooleanResult.ORIGIN_B),
                "no whole quad of cube B lies inside cube A");
        assertTrue(countSourceOperand(difference, MeshBooleanResult.ORIGIN_B) > 0,
                "the notch the difference cuts is bounded by pieces of cube B's quads");
        assertFacesLieOnTheirSourceQuads(difference, meshA, meshB);

        MeshBooleanResult intersection = boolean3d(cubeA, cubeB, BooleanOperation.INTERSECTION);
        assertProvenanceShape(intersection);
        assertEquals(intersection.mesh.faceCount(),
                countOrigin(intersection, MeshBooleanResult.ORIGIN_NEW),
                "the shared octant has no whole input quad on its boundary");
        assertTrue(countSourceOperand(intersection, MeshBooleanResult.ORIGIN_A) > 0,
                "three of the octant's sides are pieces of cube A");
        assertTrue(countSourceOperand(intersection, MeshBooleanResult.ORIGIN_B) > 0,
                "three of the octant's sides are pieces of cube B");
        assertFacesLieOnTheirSourceQuads(intersection, meshA, meshB);
    }

    /**
     * One entry per face in every provenance array, each holding a legal value.
     *
     * @param result boolean output to check
     */
    private static void assertProvenanceShape(MeshBooleanResult result) {
        int faceCount = result.mesh.faceCount();
        assertEquals(faceCount, result.faceOrigin.length, "one origin per output face");
        assertEquals(faceCount, result.faceSourceOperand.length, "one source operand per face");
        assertEquals(faceCount, result.faceSourceQuad.length, "one source face per output face");
        for (int face = 0; face < faceCount; face++) {
            int origin = result.faceOrigin[face];
            assertTrue(origin == MeshBooleanResult.ORIGIN_A || origin == MeshBooleanResult.ORIGIN_B
                    || origin == MeshBooleanResult.ORIGIN_NEW, "every face is attributable");
            int operand = result.faceSourceOperand[face];
            assertTrue(operand == MeshBooleanResult.ORIGIN_A
                    || operand == MeshBooleanResult.ORIGIN_B,
                    "every face lies on one operand's surface");
            if (origin != MeshBooleanResult.ORIGIN_NEW) {
                assertEquals(origin, operand, "an untouched face lies on the operand it copies");
            }
            assertTrue(result.faceSourceQuad[face] >= 0
                    && result.faceSourceQuad[face] < CUBE_FACE_COUNT,
                    "every face traces to one of its cube's six quads");
        }
    }

    /**
     * Each output face's centroid lies in the plane of the quad it claims as its source, for cut
     * pieces as much as untouched copies.
     *
     * @param result boolean output to check
     * @param meshA first operand's quad mesh
     * @param meshB second operand's quad mesh
     */
    private static void assertFacesLieOnTheirSourceQuads(MeshBooleanResult result, ArrayMesh meshA,
            ArrayMesh meshB) {
        Vector3f corner = new Vector3f();
        Vector3f centroid = new Vector3f();
        Vector3f quadCorner = new Vector3f();
        Vector3f quadOther = new Vector3f();
        for (int activeFace = 0; activeFace < result.mesh.faceCount(); activeFace++) {
            int faceId = result.mesh.faceIdAt(activeFace);
            centroid.zero();
            for (int index = 0; index < THREE; index++) {
                result.mesh.vertexPosition(result.mesh.faceVertexAt(faceId, index), corner);
                centroid.add(corner);
            }
            centroid.div(THREE);

            ArrayMesh source = result.faceSourceOperand[activeFace] == MeshBooleanResult.ORIGIN_A
                    ? meshA : meshB;
            int quadId = source.faceIdAt(result.faceSourceQuad[activeFace]);
            source.vertexPosition(source.faceVertexAt(quadId, 0), quadCorner);
            for (int axis = 0; axis < THREE; axis++) {
                boolean constantAxis = true;
                for (int index = 1; index < QUAD_CORNERS; index++) {
                    source.vertexPosition(source.faceVertexAt(quadId, index), quadOther);
                    constantAxis &= quadOther.get(axis) == quadCorner.get(axis);
                }
                if (constantAxis) {
                    assertEquals(quadCorner.get(axis), centroid.get(axis), PLANE_TOLERANCE,
                            "face " + activeFace + " lies on its source quad's plane");
                }
            }
        }
    }

    /**
     * Faces whose origin is a given value.
     *
     * @param result boolean output to count over
     * @param origin origin value to count
     * @return number of faces with that origin
     */
    private static int countOrigin(MeshBooleanResult result, int origin) {
        int count = 0;
        for (int value : result.faceOrigin) {
            if (value == origin) {
                count++;
            }
        }
        return count;
    }

    /**
     * Faces lying on a given operand's surface, untouched or cut.
     *
     * @param result boolean output to count over
     * @param operand operand value to count
     * @return number of faces on that operand
     */
    private static int countSourceOperand(MeshBooleanResult result, int operand) {
        int count = 0;
        for (int value : result.faceSourceOperand) {
            if (value == operand) {
                count++;
            }
        }
        return count;
    }

    /**
     * Run one boolean through the platform's backend.
     *
     * @param operandA first triangulated solid
     * @param operandB second triangulated solid
     * @param operation which boolean to compute
     * @return the result and its provenance
     */
    private static MeshBooleanResult boolean3d(QuadTriangulation operandA, QuadTriangulation operandB,
            BooleanOperation operation) {
        return Platforms.get().meshBooleanBackend().compute(operandA, operandB, operation);
    }

    /**
     * A unit quad cube, matching the {@code cube} mesh-node primitive, offset along every axis.
     *
     * @param offset amount added to each coordinate, so {@code 0.5} puts a corner on the origin cube's centre
     * @return the cube as a six-quad mesh
     */
    private static ArrayMesh unitCube(float offset) {
        float low = -HALF + offset;
        float high = HALF + offset;
        float[] positions = {
            low, low, low, high, low, low, high, high, low, low, high, low,
            low, low, high, high, low, high, high, high, high, low, high, high,
        };
        int[] quads = {
            0, THREE, 2, 1,
            4, 5, SIX, 7,
            0, 1, 5, 4,
            THREE, 7, SIX, 2,
            1, 2, SIX, 5,
            0, 4, 7, THREE,
        };
        ArrayMesh cube = ArrayMesh.fromQuads(positions, quads);
        cube.computeNormals();
        return cube;
    }

    /**
     * Volume enclosed by a closed triangle mesh, as the signed tetrahedra its faces span with the
     * origin. Also catches inconsistent winding, which flips the sign.
     *
     * @param mesh closed triangle mesh
     * @return the enclosed volume
     */
    private static double signedVolume(HalfEdgeMesh mesh) {
        Vector3f cornerA = new Vector3f();
        Vector3f cornerB = new Vector3f();
        Vector3f cornerC = new Vector3f();
        double total = 0.0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), cornerA);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 1), cornerB);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 2), cornerC);
            total += (double) cornerA.dot(new Vector3f(cornerB).cross(cornerC)) / SIX;
        }
        return Math.abs(total);
    }

    /**
     * Whether every edge of the mesh is shared by two faces.
     *
     * @param mesh mesh to check
     * @return true when the surface has no boundary
     */
    private static boolean isClosed(HalfEdgeMesh mesh) {
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            if (mesh.isBoundaryEdge(mesh.edgeIdAt(activeEdge))) {
                return false;
            }
        }
        return true;
    }
}
