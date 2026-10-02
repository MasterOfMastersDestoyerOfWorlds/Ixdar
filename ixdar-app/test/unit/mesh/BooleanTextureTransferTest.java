package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.csg.BooleanOperation;
import ixdar.geometry.mesh.csg.MeshBooleanResult;
import ixdar.geometry.mesh.csg.QuadTriangulation;
import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MaterialData;
import ixdar.geometry.mesh.data.MaterialSet;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.geometry.MeshBooleanNode;
import ixdar.platform.Platforms;

/**
 * Textures across a boolean: the kernel interpolates the operands' UVs at the vertices it creates,
 * and each output face keeps its source face's material. Both cubes are built from arrays with UVs
 * affine in position, so every UV is known analytically.
 */
public class BooleanTextureTransferTest {

    /** Half a unit cube's edge. */
    private static final float HALF = 0.5f;

    /** Shift from a coordinate to its UV, putting the origin cube's UVs in {@code [0, 1]}. */
    private static final double UV_ORIGIN = 0.5;

    /** Corners per triangle, and equally coordinates per vertex. */
    private static final int THREE = 3;

    /** Vertices in a quad. */
    private static final int QUAD_CORNERS = 4;

    /** Tolerance for recognising an input cube's corner after a float round trip. */
    private static final double POSITION_TOLERANCE = 1e-6;

    /** The ticket's bound on the UV interpolation error at an intersection vertex. */
    private static final double UV_TOLERANCE = 1e-4;

    /** Side of the test materials' base-colour images. */
    private static final int MATERIAL_SIZE = 2;

    private static final int SIX = 6;

    /** Label of the edge marks an operand carries into the boolean. */
    private static final String STALE_MARK_LABEL = "open_boundary";

    /**
     * Union of two textured cubes: every output corner's UV is the analytic value its position
     * asks for, at the vertices the intersection curve created as much as at the copied ones.
     */
    @Test
    public void unionInterpolatesUvsAtTheVerticesTheIntersectionCurveCreates() {
        GeometryBundle cubeA = texturedCube(0f, material());
        GeometryBundle cubeB = texturedCube(HALF, material());
        MeshBooleanResult union = boolean3d(cubeA, cubeB, BooleanOperation.UNION);

        assertNotNull(union.cornerU, "the operands' UVs came back through the kernel");
        assertEquals(union.mesh.faceCount() * THREE, union.cornerU.length,
                "one interpolated UV per output face corner");

        Vector3f position = new Vector3f();
        int cutCorners = 0;
        for (int activeFace = 0; activeFace < union.mesh.faceCount(); activeFace++) {
            int faceId = union.mesh.faceIdAt(activeFace);
            for (int corner = 0; corner < THREE; corner++) {
                union.mesh.vertexPosition(union.mesh.faceVertexAt(faceId, corner), position);
                int index = activeFace * THREE + corner;
                assertEquals(position.x + UV_ORIGIN, union.cornerU[index], UV_TOLERANCE,
                        "u interpolates to the analytic value at " + position);
                assertEquals(position.y + UV_ORIGIN, union.cornerV[index], UV_TOLERANCE,
                        "v interpolates to the analytic value at " + position);
                if (!isCubeCorner(position)) {
                    cutCorners++;
                }
            }
        }
        assertTrue(cutCorners > 0,
                "the union has corners at vertices neither cube contributed, which is the case"
                        + " the tolerance is about");
        assertTrue(isClosed(union.mesh),
                "splitting the operands' vertices by UV must not leave the result cracked");
    }

    /**
     * Box-projected cubes, whose every edge is a UV seam: the result welds each seam's property
     * vertices back into one mesh vertex and every corner keeps its own face's projection.
     */
    @Test
    public void uvSeamsWeldIntoAClosedMeshAndKeepEachFacesProjection() {
        MeshBooleanResult union = boolean3d(boxProjectedCube(0f), boxProjectedCube(HALF),
                BooleanOperation.UNION);

        assertTrue(isClosed(union.mesh), "a UV seam must not crack the result");
        Vector3f position = new Vector3f();
        for (int activeFace = 0; activeFace < union.mesh.faceCount(); activeFace++) {
            int faceId = union.mesh.faceIdAt(activeFace);
            int axis = union.mesh.faceDominantAxis(faceId);
            for (int corner = 0; corner < THREE; corner++) {
                union.mesh.vertexPosition(union.mesh.faceVertexAt(faceId, corner), position);
                int index = activeFace * THREE + corner;
                assertEquals(position.get((axis + 1) % THREE) + UV_ORIGIN, union.cornerU[index],
                        UV_TOLERANCE, "u is this face's projection at " + position);
                assertEquals(position.get((axis + 2) % THREE) + UV_ORIGIN, union.cornerV[index],
                        UV_TOLERANCE, "v is this face's projection at " + position);
            }
        }
    }

    /**
     * Every face of a textured union carries a UV and a material index, and both operands'
     * materials survive as a multi-material slot.
     */
    @Test
    public void texturedUnionGivesEveryFaceAUvAndAMaterial() {
        MaterialData materialA = material();
        MaterialData materialB = material();
        GeometryBundle output = union(texturedCube(0f, materialA), texturedCube(HALF, materialB));

        CornerUvField uv = (CornerUvField) output.slots().get(CornerUvField.SLOT);
        assertNotNull(uv, "the result carries a UV field");
        assertEquals(output.mesh().faceCount(), uv.faceCount(), "the UV field covers every face");

        MaterialSet materials = MaterialSet.of(output);
        assertNotNull(materials, "the result carries both operands' materials");
        assertEquals(2, materials.materials.length, "one material per operand, neither merged");
        IntField faceMaterial = (IntField) output.slots().get(MaterialSet.FACE_MATERIAL_SLOT);
        assertNotNull(faceMaterial, "the result names a material per face");
        assertEquals(output.mesh().faceCount(), faceMaterial.length(), "one index per face");

        IntField sourceOperand =
                (IntField) output.slots().get(MeshBooleanNode.FACE_SOURCE_OPERAND_SLOT);
        for (int face = 0; face < faceMaterial.length(); face++) {
            MaterialData expected =
                    sourceOperand.get(face) == MeshBooleanResult.ORIGIN_A ? materialA : materialB;
            assertEquals(expected, materials.get(faceMaterial.get(face)),
                    "face " + face + " keeps the material of the operand its surface lies on");
        }
    }

    /**
     * One textured operand and one plain one: the textured operand's faces name its material and
     * the plain operand's name none.
     */
    @Test
    public void untexturedOperandFacesCarryNoMaterial() {
        MaterialData materialA = material();
        GeometryBundle output = union(texturedCube(0f, materialA), plainCube(HALF));

        MaterialSet materials = MaterialSet.of(output);
        assertNotNull(materials, "the textured operand's material survives");
        assertEquals(1, materials.materials.length, "only one operand had a material");
        IntField faceMaterial = (IntField) output.slots().get(MaterialSet.FACE_MATERIAL_SLOT);
        IntField sourceOperand =
                (IntField) output.slots().get(MeshBooleanNode.FACE_SOURCE_OPERAND_SLOT);
        CornerUvField uv = (CornerUvField) output.slots().get(CornerUvField.SLOT);

        int untexturedFaces = 0;
        for (int face = 0; face < faceMaterial.length(); face++) {
            if (sourceOperand.get(face) == MeshBooleanResult.ORIGIN_A) {
                assertEquals(0, faceMaterial.get(face), "a face of the textured cube names it");
                continue;
            }
            assertEquals(MaterialSet.NO_MATERIAL, faceMaterial.get(face),
                    "a face of the plain cube names no material");
            assertTrue(Double.isNaN(uv.cornerU[face * THREE]),
                    "a face of the plain cube has no texture coordinates");
            untexturedFaces++;
        }
        assertTrue(untexturedFaces > 0, "the union keeps surface from the plain cube");
    }

    /**
     * An operand whose triangle has NaN UVs, as repair_mesh leaves on a hole fill, still goes
     * through the kernel, and only the faces taken from that triangle come back without UVs.
     */
    @Test
    public void missingUvsOnAnInputTriangleStayMissingOnItsOutputFaces() {
        GeometryBundle cubeA = texturedCube(0f, material());
        CornerUvField inputUv = (CornerUvField) cubeA.slots().get(CornerUvField.SLOT);
        int holeTriangle = triangleOnLowXFace(cubeA.mesh());
        double[] cornerU = inputUv.cornerU.clone();
        double[] cornerV = inputUv.cornerV.clone();
        for (int corner = 0; corner < THREE; corner++) {
            cornerU[holeTriangle * THREE + corner] = Double.NaN;
            cornerV[holeTriangle * THREE + corner] = Double.NaN;
        }
        GeometryBundle output = union(
                cubeA.withSlot(CornerUvField.SLOT, new CornerUvField(cornerU, cornerV)),
                texturedCube(HALF, material()));

        CornerUvField uv = (CornerUvField) output.slots().get(CornerUvField.SLOT);
        IntField sourceOperand =
                (IntField) output.slots().get(MeshBooleanNode.FACE_SOURCE_OPERAND_SLOT);
        IntField sourceFace = (IntField) output.slots().get(MeshBooleanNode.FACE_SOURCE_QUAD_SLOT);
        int missingFaces = 0;
        for (int face = 0; face < uv.faceCount(); face++) {
            boolean fromHole = sourceOperand.get(face) == MeshBooleanResult.ORIGIN_A
                    && sourceFace.get(face) == holeTriangle;
            for (int corner = 0; corner < THREE; corner++) {
                double cornerValue = uv.cornerU[face * THREE + corner];
                assertEquals(fromHole, Double.isNaN(cornerValue),
                        "face " + face + " has UVs exactly when its source triangle had them");
            }
            if (fromHole) {
                missingFaces++;
            }
        }
        assertTrue(missingFaces > 0, "the hole triangle lies outside the other cube and survives");
    }

    /** Two operands with no texture slots produce a result with none, and no exception. */
    @Test
    public void plainOperandsProduceNoTextureSlots() {
        GeometryBundle output = union(plainCube(0f), plainCube(HALF));

        assertNull(output.slots().get(CornerUvField.SLOT), "no UV field appears from nowhere");
        assertFalse(output.slots().containsKey(MaterialData.SLOT), "no material slot");
        assertFalse(output.slots().containsKey(MaterialSet.SLOT), "no material list");
        assertFalse(output.slots().containsKey(MaterialSet.FACE_MATERIAL_SLOT),
                "no per-face material index");
        assertNotNull(output.slots().get(MeshBooleanNode.FACE_ORIGIN_SLOT),
                "provenance is still recorded");
    }

    /** A textured operand's own slots do not survive onto a result they no longer describe. */
    @Test
    public void staleInputSlotsAreReplacedNotCarried() {
        GeometryBundle cubeA = texturedCube(0f, material());
        cubeA = cubeA.withSlot(EdgeMarks.SLOT, Map.of(STALE_MARK_LABEL,
                new boolean[cubeA.mesh().edgeCount()]));
        GeometryBundle output = union(cubeA, plainCube(HALF));

        CornerUvField inputUv = (CornerUvField) cubeA.slots().get(CornerUvField.SLOT);
        CornerUvField outputUv = (CornerUvField) output.slots().get(CornerUvField.SLOT);
        assertTrue(outputUv.faceCount() > inputUv.faceCount(),
                "the boolean cut faces, so the result's UV field is the kernel's, not the input's");
        assertFalse(output.slots().containsKey(MaterialData.SLOT),
                "the single-material slot gives way to the multi-material one");
        assertFalse(output.slots().containsKey(EdgeMarks.SLOT),
                "edge marks index the input's edges, which the result no longer has");
    }

    /**
     * Run {@code mesh_boolean} over two bundles.
     *
     * @param cubeA first operand
     * @param cubeB second operand
     * @return the node's output bundle
     */
    private static GeometryBundle union(GeometryBundle cubeA, GeometryBundle cubeB) {
        return new MapNodeContext(new MeshBooleanNode())
                .with(MeshBooleanNode.MESH_A, cubeA)
                .with(MeshBooleanNode.MESH_B, cubeB)
                .with(MeshBooleanNode.OPERATION, MeshBooleanNode.UNION)
                .eval()
                .output(MeshBooleanNode.GEOMETRY, GeometryBundle.class);
    }

    /**
     * Run one boolean through the platform's backend, carrying whatever UVs the bundles hold.
     *
     * @param cubeA first operand
     * @param cubeB second operand
     * @param operation which boolean to compute
     * @return the result, its provenance and its interpolated UVs
     */
    private static MeshBooleanResult boolean3d(GeometryBundle cubeA, GeometryBundle cubeB,
            BooleanOperation operation) {
        return Platforms.get().meshBooleanBackend().compute(
                new QuadTriangulation(cubeA.mesh()).build(CornerUvField.of(cubeA)),
                new QuadTriangulation(cubeB.mesh()).build(CornerUvField.of(cubeB)),
                operation);
    }

    /**
     * A triangulated unit cube whose UVs are {@code (x, y)} shifted into the unit square, so the
     * UV anywhere on it is an affine function of position and a linear interpolation is exact.
     *
     * @param offset amount added to each coordinate
     * @param material material the cube's faces name
     * @return the bundle with its UV and material slots
     */
    private static GeometryBundle texturedCube(float offset, MaterialData material) {
        QuadTriangulation split = new QuadTriangulation(plainCube(offset).mesh()).build();
        double[] cornerU = new double[split.triangles.length];
        double[] cornerV = new double[split.triangles.length];
        for (int corner = 0; corner < split.triangles.length; corner++) {
            int vertex = split.triangles[corner] * THREE;
            cornerU[corner] = split.positions[vertex] + UV_ORIGIN;
            cornerV[corner] = split.positions[vertex + 1] + UV_ORIGIN;
        }
        ArrayMesh mesh = new ArrayMesh(split.positions, new float[split.positions.length],
                split.triangles, THREE);
        mesh.computeNormals();
        return GeometryBundle.ofMesh(mesh)
                .withSlot(CornerUvField.SLOT, new CornerUvField(cornerU, cornerV))
                .withSlot(MaterialData.SLOT, material);
    }

    /**
     * A triangulated unit cube whose faces each project position onto the two axes they span, so
     * every cube edge is a UV seam and the UV within a face is affine in position.
     *
     * @param offset amount added to each coordinate
     * @return the bundle with its UV slot
     */
    private static GeometryBundle boxProjectedCube(float offset) {
        GeometryBundle cube = texturedCube(offset, material());
        MeshTopology mesh = cube.mesh();
        double[] cornerU = new double[mesh.faceCount() * THREE];
        double[] cornerV = new double[cornerU.length];
        Vector3f position = new Vector3f();
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int axis = mesh.faceDominantAxis(faceId);
            for (int corner = 0; corner < THREE; corner++) {
                mesh.vertexPosition(mesh.faceVertexAt(faceId, corner), position);
                cornerU[activeFace * THREE + corner] = position.get((axis + 1) % THREE) + UV_ORIGIN;
                cornerV[activeFace * THREE + corner] = position.get((axis + 2) % THREE) + UV_ORIGIN;
            }
        }
        return cube.withSlot(CornerUvField.SLOT, new CornerUvField(cornerU, cornerV));
    }

    /**
     * A unit quad cube with no slots, matching the {@code cube} primitive, offset along every axis.
     *
     * @param offset amount added to each coordinate
     * @return the cube as a six-quad bundle
     */
    private static GeometryBundle plainCube(float offset) {
        float low = -HALF + offset;
        float high = HALF + offset;
        float[] positions = {
            low, low, low, high, low, low, high, high, low, low, high, low,
            low, low, high, high, low, high, high, high, high, low, high, high,
        };
        int[] quads = {
            0, THREE, 2, 1,
            QUAD_CORNERS, 5, SIX, 7,
            0, 1, 5, QUAD_CORNERS,
            THREE, 7, SIX, 2,
            1, 2, SIX, 5,
            0, QUAD_CORNERS, 7, THREE,
        };
        ArrayMesh cube = ArrayMesh.fromQuads(positions, quads);
        cube.computeNormals();
        return GeometryBundle.ofMesh(cube);
    }

    /**
     * A distinct two-by-two material, so two operands' materials never compare equal by identity.
     *
     * @return the material
     */
    private static MaterialData material() {
        byte[] pixels = new byte[MATERIAL_SIZE * MATERIAL_SIZE * QUAD_CORNERS];
        return new MaterialData(pixels, MATERIAL_SIZE, MATERIAL_SIZE, null, 0, 0,
                new float[] {1f, 1f, 1f, 1f}, 0f, 1f);
    }

    /**
     * A triangle lying on the cube's {@code x = -1/2} face, which the offset cube never reaches.
     *
     * @param mesh triangulated cube at the origin
     * @return that triangle's face index
     */
    private static int triangleOnLowXFace(MeshTopology mesh) {
        Vector3f position = new Vector3f();
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            boolean onFace = true;
            for (int corner = 0; corner < THREE; corner++) {
                mesh.vertexPosition(mesh.faceVertexAt(faceId, corner), position);
                onFace &= Math.abs(position.x + HALF) < POSITION_TOLERANCE;
            }
            if (onFace) {
                return activeFace;
            }
        }
        throw new AssertionError("a unit cube has two triangles on each face");
    }

    /**
     * Whether a position is a corner of either input cube, every other output vertex being one the
     * intersection curve created.
     *
     * @param position the position to classify
     * @return true when it is a corner of the cube at the origin or of the offset one
     */
    private static boolean isCubeCorner(Vector3f position) {
        return isCornerOf(position, 0f) || isCornerOf(position, HALF);
    }

    /**
     * Whether a position is a corner of one unit cube.
     *
     * @param position the position to classify
     * @param offset the cube's offset along every axis
     * @return true when every coordinate is one of that cube's two extents
     */
    private static boolean isCornerOf(Vector3f position, float offset) {
        for (int axis = 0; axis < THREE; axis++) {
            double coordinate = position.get(axis);
            if (Math.abs(coordinate - (offset - HALF)) > POSITION_TOLERANCE
                    && Math.abs(coordinate - (offset + HALF)) > POSITION_TOLERANCE) {
                return false;
            }
        }
        return true;
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
