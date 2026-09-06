package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.load.GltfMeshParser;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.api.Vector3Field;
import ixdar.geometry.mesh.nodes.data.LoadMeshNode;

/**
 * {@link GltfMeshParser} turns a hand-written two-triangle glTF (a unit square in the XY plane with
 * corner UVs) into the triangle {@link ArrayMesh} shape the other parsers produce, carrying
 * {@code TEXCOORD_0} as the {@link MeshLoader#UV_SLOT} bundle slot.
 */
class GltfMeshParserTest {

    private static final String GLTF_PATH = "test/resources/gltf/two_triangles.gltf";

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
     * Assimp hands glTF texture coordinates over with {@code v} flipped to the OpenGL bottom-left
     * origin, so the slot holds {@code (u, 1 - v, 0)} per vertex.
     *
     * @throws IOException if the fixture cannot be read
     */
    @Test
    void textureCoordinatesRideTheUvSlot() throws IOException {
        GeometryBundle bundle = GltfMeshParser.load(GLTF_PATH);

        Object slot = bundle.slots().get(MeshLoader.UV_SLOT);
        Vector3Field uv = assertInstanceOf(Vector3Field.class, slot, "the UV slot is a Vector3Field");
        assertEquals(bundle.mesh().vertexCount(), uv.length(), "one UV per vertex");
        for (int vertex = 0; vertex < SQUARE_VERTICES; vertex++) {
            assertEquals(GLTF_UVS[vertex][0], uv.getX(vertex), EPSILON, "u of vertex " + vertex);
            assertEquals(1f - GLTF_UVS[vertex][1], uv.getY(vertex), EPSILON, "flipped v of vertex " + vertex);
            assertEquals(0f, uv.getZ(vertex), EPSILON, "z padding of vertex " + vertex);
        }
    }

    @Test
    void meshLoaderDispatchesGltfAndKeepsTheSlot() throws IOException {
        GeometryBundle bundle = MeshLoader.loadBundle(GLTF_PATH);

        assertEquals(SQUARE_VERTICES, bundle.mesh().vertexCount(), "loadBundle reaches the glTF parser");
        assertTrue(bundle.slots().containsKey(MeshLoader.UV_SLOT), "loadBundle keeps the UV slot");
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
        Vector3Field uv = assertInstanceOf(Vector3Field.class, bundle.slots().get(MeshLoader.UV_SLOT));
        assertEquals(SQUARE_VERTICES, uv.length(), "load_mesh keeps one UV per vertex");
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
