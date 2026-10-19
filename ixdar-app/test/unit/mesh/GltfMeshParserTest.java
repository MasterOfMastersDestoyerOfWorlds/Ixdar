package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.CornerUvField;
import ixdar.geometry.mesh.data.bundle.CornerUvSplit;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.GltfMeshParser;
import ixdar.geometry.mesh.data.io.GltfModel;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.data.LoadMeshNode;

/**
 * {@link GltfMeshParser} turns a hand-written two-triangle glTF (a unit square in the XY plane with
 * corner UVs) into the triangle mesh the other parsers produce, welding duplicate positions and
 * carrying {@code TEXCOORD_0} as a per-corner {@link CornerUvField}.
 */
class GltfMeshParserTest {

    private static final String GLTF_PATH = "test/resources/gltf/two_triangles.gltf";

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

    private static final float[][] GLTF_UVS = { { 0f, 0f }, { 1f, 0f }, { 1f, 1f }, { 0f, 1f } };

    private static final int SEAM_SOURCE_VERTICES = 6;

    private static final int SEAM_WELDED_VERTICES = 4;

    @Test
    void positionsAndIndicesMatchTheFixture() throws IOException {
        HalfEdgeMesh mesh = assertInstanceOf(HalfEdgeMesh.class, GltfMeshParser.load(GLTF_PATH).mesh(),
                "a manifold file loads as a half-edge mesh");

        assertEquals(SQUARE_VERTICES, mesh.vertexCount(), "four corners");
        assertEquals(SQUARE_TRIANGLES, mesh.faceCount(), "two triangles");
        assertEquals(GltfMeshParser.VERTICES_PER_TRIANGLE, mesh.faceVertexCount(mesh.faceIdAt(0)),
                "triangle mesh");
        assertArrayEquals(SQUARE_POSITIONS, Arrays.copyOf(mesh.vertexPositions, SQUARE_POSITIONS.length),
                EPSILON, "positions in file order");
        assertArrayEquals(SQUARE_INDICES, cornersOf(mesh), "indices in file order");
    }

    @Test
    void normalsComeFromTheFile() throws IOException {
        MeshTopology mesh = GltfMeshParser.load(GLTF_PATH).mesh();

        Vector3f normal = new Vector3f();
        for (int vertex = 0; vertex < SQUARE_VERTICES; vertex++) {
            mesh.vertexNormal(mesh.vertexIdAt(vertex), normal);
            assertEquals(0f, normal.x, EPSILON, "normal x of vertex " + vertex);
            assertEquals(0f, normal.y, EPSILON, "normal y of vertex " + vertex);
            assertEquals(1f, normal.z, EPSILON, "normal z of vertex " + vertex);
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
        int[] faceIndices = cornersOf(bundle.mesh());
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

        MeshTopology mesh = model.bundle.mesh();
        CornerUvField uv = assertInstanceOf(CornerUvField.class,
                model.bundle.slots().get(CornerUvField.SLOT));
        int[] weldedCorners = cornersOf(mesh);
        float[] splitUv = new float[CornerUvSplit.maxSplitUvLength(mesh.faceCount())];
        int[] splitIndices = new int[weldedCorners.length];
        int splitVertexCount = CornerUvSplit.split(weldedCorners, uv, null, splitUv, splitIndices,
                new int[weldedCorners.length]);

        assertEquals(SEAM_SOURCE_VERTICES, splitVertexCount,
                "splitting on disagreeing corner UVs gives the source vertices back");
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
        MeshTopology mesh = model.bundle.mesh();
        CornerUvField uv = assertInstanceOf(CornerUvField.class,
                model.bundle.slots().get(CornerUvField.SLOT));

        int[] weldedCorners = cornersOf(mesh);
        int splitVertexCount = CornerUvSplit.split(weldedCorners, uv, null,
                new float[CornerUvSplit.maxSplitUvLength(mesh.faceCount())],
                new int[weldedCorners.length], new int[weldedCorners.length]);

        assertEquals(SQUARE_VERTICES, model.weldedVertexCount, "nothing to weld");
        assertEquals(SQUARE_VERTICES, splitVertexCount, "and so nothing to split");
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

    /**
     * Every face's corner vertex ids end to end, in active-face order.
     *
     * @param mesh parsed mesh
     * @return the corners of every face
     */
    private static int[] cornersOf(MeshTopology mesh) {
        int[] corners = new int[mesh.halfEdgeCount()];
        int cursor = 0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                corners[cursor++] = mesh.faceVertexAt(faceId, corner);
            }
        }
        return Arrays.copyOf(corners, cursor);
    }
}
