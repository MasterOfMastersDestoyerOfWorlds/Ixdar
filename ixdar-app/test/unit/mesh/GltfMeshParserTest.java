package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.CornerUvSplit;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.load.GltfMeshParser;
import ixdar.geometry.mesh.data.load.GltfModel;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.data.LoadMeshNode;

/**
 * {@link GltfMeshParser} turns a hand-written two-triangle glTF (a unit square in the XY plane with
 * corner UVs) into the triangle {@link ArrayMesh} shape the other parsers produce, welding
 * duplicate positions and carrying {@code TEXCOORD_0} as a per-corner {@link CornerUvField}.
 */
class GltfMeshParserTest {

    private static final String GLTF_PATH = "test/resources/gltf/two_triangles.gltf";

    /** Two triangles sharing two positions but disagreeing on their UVs: a texture seam. */
    private static final String SEAM_PATH = "test/resources/gltf/two_triangles_seam.gltf";

    private static final float EPSILON = 1e-6f;

    private static final int SQUARE_VERTICES = 4;

    private static final int SQUARE_TRIANGLES = 2;

    private static final float[] SQUARE_POSITIONS = {
        0f, 0f, 0f,
        1f, 0f, 0f,
        1f, 1f, 0f,
        0f, 1f, 0f,
    };

    private static final int[] SQUARE_INDICES = { 0, 1, 2, 0, 2, 3 };

    /** The fixture's {@code (u, v)} corners in glTF's top-left convention, in vertex order. */
    private static final float[][] GLTF_UVS = { { 0f, 0f }, { 1f, 0f }, { 1f, 1f }, { 0f, 1f } };

    /** Source vertices the seam fixture declares, before the weld. */
    private static final int SEAM_SOURCE_VERTICES = 6;

    /** Distinct positions the seam fixture holds, so the vertex count after the weld. */
    private static final int SEAM_WELDED_VERTICES = 4;

    @Test
    void positionsAndIndicesMatchTheFixture() throws IOException {
        ArrayMesh mesh = (ArrayMesh) GltfMeshParser.load(GLTF_PATH).mesh();

        assertEquals(SQUARE_VERTICES, mesh.vertexCount(), "four corners");
        assertEquals(SQUARE_TRIANGLES, mesh.faceCount(), "two triangles");
        assertEquals(GltfMeshParser.VERTICES_PER_TRIANGLE, mesh.getVertsPerFace(), "triangle mesh");
        assertArrayEquals(SQUARE_POSITIONS, mesh.copyPositions(), EPSILON, "positions in file order");
        assertArrayEquals(SQUARE_INDICES, mesh.copyFaceIndices(), "indices in file order");
    }

    @Test
    void normalsComeFromTheFile() throws IOException {
        ArrayMesh mesh = (ArrayMesh) GltfMeshParser.load(GLTF_PATH).mesh();

        float[] normals = mesh.copyNormals();
        for (int vertex = 0; vertex < SQUARE_VERTICES; vertex++) {
            int offset = vertex * GltfMeshParser.FLOATS_PER_VERTEX;
            assertEquals(0f, normals[offset], EPSILON, "normal x of vertex " + vertex);
            assertEquals(0f, normals[offset + 1], EPSILON, "normal y of vertex " + vertex);
            assertEquals(1f, normals[offset + 2], EPSILON, "normal z of vertex " + vertex);
        }
    }

    /**
     * The parser flips {@code v} to the OpenGL bottom-left origin and hands the coordinates over per
     * face corner, so every corner reads back its own vertex's UV.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    void textureCoordinatesRideTheCornerUvSlot() throws IOException {
        GeometryBundle bundle = GltfMeshParser.load(GLTF_PATH);

        Object slot = bundle.slots().get(CornerUvField.SLOT);
        CornerUvField uv = assertInstanceOf(CornerUvField.class, slot, "the _uv slot is a UvField");
        assertEquals(bundle.mesh().faceCount(), uv.faceCount(), "one UV triple per face");
        int[] faceIndices = ((ArrayMesh) bundle.mesh()).copyFaceIndices();
        for (int face = 0; face < SQUARE_TRIANGLES; face++) {
            for (int corner = 0; corner < CornerUvField.CORNERS_PER_FACE; corner++) {
                int vertex = faceIndices[face * CornerUvField.CORNERS_PER_FACE + corner];
                assertEquals(GLTF_UVS[vertex][0], uv.u(face, corner), EPSILON,
                        "u of face " + face + " corner " + corner);
                assertEquals(1f - GLTF_UVS[vertex][1], uv.v(face, corner), EPSILON,
                        "flipped v of face " + face + " corner " + corner);
            }
        }
    }

    @Test
    void aSeamWeldsToDistinctPositionsAndSplitsBackToTheSourceVertices() throws IOException {
        GltfModel model = GltfMeshParser.read(SEAM_PATH);

        assertEquals(SEAM_SOURCE_VERTICES, model.sourceVertexCount, "the file declares six vertices");
        assertEquals(SEAM_WELDED_VERTICES, model.weldedVertexCount,
                "two positions appear twice, so the weld keeps four");
        assertEquals(SEAM_WELDED_VERTICES, model.bundle.mesh().vertexCount(),
                "the mesh carries the welded vertices");
        assertEquals(SQUARE_TRIANGLES, model.bundle.mesh().faceCount(), "both triangles survive");
        assertEquals(0, model.normalConflicts, "every welded group agreed on its normal");

        ArrayMesh mesh = (ArrayMesh) model.bundle.mesh();
        CornerUvField uv = assertInstanceOf(CornerUvField.class,
                model.bundle.slots().get(CornerUvField.SLOT));
        float[] splitUv = new float[CornerUvSplit.maxSplitUvLength(mesh)];
        ArrayMesh split = CornerUvSplit.split(mesh, uv, splitUv);

        assertEquals(SEAM_SOURCE_VERTICES, split.vertexCount(),
                "splitting on disagreeing corner UVs gives the source vertices back");
        int[] splitIndices = split.copyFaceIndices();
        assertEquals(mesh.faceCount() * CornerUvField.CORNERS_PER_FACE, splitIndices.length,
                "the split keeps every corner");
        for (int face = 0; face < mesh.faceCount(); face++) {
            for (int corner = 0; corner < CornerUvField.CORNERS_PER_FACE; corner++) {
                int splitVertex = splitIndices[face * CornerUvField.CORNERS_PER_FACE + corner];
                assertEquals(uv.u(face, corner),
                        splitUv[splitVertex * CornerUvSplit.COMPONENTS_PER_VERTEX],
                        EPSILON, "split u of face " + face + " corner " + corner);
                assertEquals(uv.v(face, corner),
                        splitUv[splitVertex * CornerUvSplit.COMPONENTS_PER_VERTEX + 1],
                        EPSILON, "split v of face " + face + " corner " + corner);
            }
        }
    }

    @Test
    void aFixtureWithoutASeamSplitsBackToItsOwnVertices() throws IOException {
        GltfModel model = GltfMeshParser.read(GLTF_PATH);
        ArrayMesh mesh = (ArrayMesh) model.bundle.mesh();
        CornerUvField uv = assertInstanceOf(CornerUvField.class,
                model.bundle.slots().get(CornerUvField.SLOT));

        ArrayMesh split = CornerUvSplit.split(mesh, uv,
                new float[CornerUvSplit.maxSplitUvLength(mesh)]);

        assertEquals(SQUARE_VERTICES, model.weldedVertexCount, "nothing to weld");
        assertEquals(SQUARE_VERTICES, split.vertexCount(), "and so nothing to split");
    }

    @Test
    void meshLoaderDispatchesGltfAndKeepsTheSlot() throws IOException {
        GeometryBundle bundle = MeshLoader.loadBundle(GLTF_PATH);

        assertEquals(SQUARE_VERTICES, bundle.mesh().vertexCount(), "loadBundle reaches the glTF parser");
        assertTrue(bundle.slots().containsKey(CornerUvField.SLOT), "loadBundle keeps the _uv slot");
        assertEquals(SQUARE_TRIANGLES, MeshLoader.load(GLTF_PATH).faceCount(), "load returns the mesh alone");
    }

    @Test
    void loadMeshNodeCarriesTheUvSlotIntoTheGraph() {
        LoadMeshNode node = new LoadMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(LoadMeshNode.PATH.name, GLTF_PATH);
        node.evaluate(context);
        GeometryBundle bundle = context.getOutput(LoadMeshNode.GEOMETRY.name, GeometryBundle.class);

        assertEquals(SQUARE_VERTICES, bundle.mesh().vertexCount(), "load_mesh loads the glTF");
        CornerUvField uv = assertInstanceOf(CornerUvField.class,
                bundle.slots().get(CornerUvField.SLOT));
        assertEquals(SQUARE_TRIANGLES, uv.faceCount(), "load_mesh keeps one UV triple per face");
    }

    @Test
    void unknownExtensionsAreStillRejected() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> MeshLoader.load("test/resources/gltf/two_triangles.stl"));

        assertTrue(rejected.getMessage().startsWith("Unsupported file format: "), rejected.getMessage());
    }

    @Test
    void missingGltfFileIsAnIoException() {
        assertThrows(IOException.class, () -> MeshLoader.load("test/resources/gltf/no_such_file.glb"));
    }
}
