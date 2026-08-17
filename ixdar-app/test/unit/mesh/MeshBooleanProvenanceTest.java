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
 */
public class MeshBooleanProvenanceTest {

    /** Faces on a cube, so a source face id must fall below this. */
    private static final int CUBE_FACE_COUNT = 6;

    /** Overlap of the two cubes is the corner octant, an eighth of a unit cube. */
    private static final double OVERLAP_VOLUME = 0.125;

    /** Volume tolerance, loose enough for float positions round-tripping through the kernel. */
    private static final double VOLUME_TOLERANCE = 1e-5;

    private static final float HALF = 0.5f;
    private static final int THREE = 3;
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
     * The union's faces must each be attributable, and some must be new: a boolean that only
     * relabelled whole input faces without splitting any at the intersection curve would leave no
     * new faces at all, which is exactly the failure the centroid-classifier approach has.
     */
    @Test
    public void unionFacesCarryTheirOriginatingCubeAndQuad() {
        QuadTriangulation cubeA = new QuadTriangulation(unitCube(0f)).build();
        QuadTriangulation cubeB = new QuadTriangulation(unitCube(HALF)).build();
        MeshBooleanResult union = boolean3d(cubeA, cubeB, BooleanOperation.UNION);

        assertEquals(union.mesh.faceCount(), union.faceOrigin.length,
                "one provenance entry per output face");
        assertEquals(union.mesh.faceCount(), union.faceSourceQuad.length,
                "one source-face entry per output face");

        int fromA = 0;
        int fromB = 0;
        int resolvedSourceQuads = 0;
        for (int face = 0; face < union.faceOrigin.length; face++) {
            int origin = union.faceOrigin[face];
            assertTrue(origin == MeshBooleanResult.ORIGIN_A || origin == MeshBooleanResult.ORIGIN_B
                    || origin == MeshBooleanResult.ORIGIN_NEW, "every face is attributable");
            if (origin == MeshBooleanResult.ORIGIN_A) {
                fromA++;
            } else if (origin == MeshBooleanResult.ORIGIN_B) {
                fromB++;
            }
            if (origin != MeshBooleanResult.ORIGIN_NEW && union.faceSourceQuad[face] >= 0) {
                assertTrue(union.faceSourceQuad[face] < CUBE_FACE_COUNT,
                        "a source face id must name one of the cube's quads");
                resolvedSourceQuads++;
            }
        }
        assertTrue(fromA > 0, "the union keeps faces from cube A");
        assertTrue(fromB > 0, "the union keeps faces from cube B");
        assertEquals(union.faceOrigin.length, resolvedSourceQuads,
                "every kept face traces back to a quad of the cube it came from; an all -1 map would"
                        + " mean the kernel's face ids never reached us");
        assertTrue(union.mesh.faceCount() > cubeA.triangleSourceFace.length
                + cubeB.triangleSourceFace.length,
                "the intersection curve split faces rather than merely relabelling them");
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
