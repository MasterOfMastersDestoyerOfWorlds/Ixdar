package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.load.GltfMeshParser;
import ixdar.geometry.mesh.data.load.GltfMeshWriter;
import ixdar.geometry.mesh.data.load.GltfModel;
import ixdar.geometry.mesh.data.load.MeshExport;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.load.PlyMeshWriter;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.data.ExportMeshNode;

/**
 * {@link GltfMeshWriter} and {@link PlyMeshWriter} are the inverse of the loaders: a procedural
 * bundle written out and read back holds the same triangles, normals and per-corner UVs, and two
 * writes of one bundle are byte-identical.
 */
class MeshExportTest {

    private static final String TWO_TRIANGLES_GLTF_PATH = "test/resources/gltf/two_triangles.gltf";

    private static final String TWO_TRIANGLES_SEAM_GLTF_PATH ="test/resources/gltf/two_triangles_seam.gltf";

    private static final float EPSILON = 1e-6f;

    private static final int SQUARE_VERTICES = 4;

    private static final int SQUARE_TRIANGLES = 2;

    private static final int SEAM_SPLIT_VERTICES = 6;

    private static final int QUAD_CORNERS = 4;

    private static final float[] SQUARE_POSITIONS = {
        0f, 0f, 0f,
        1f, 0f, 0f,
        1f, 1f, 0f,
        0f, 1f, 0f,
    };

    private static final int[] SQUARE_INDICES = { 0, 1, 2, 0, 2, 3 };

    private static final float[] PINCHED_POSITIONS = {
        0f, 0f, 0f,
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 0f,
        -1f, 0f, 0f,
        0f, -1f, 0f,
    };

    private static final int PINCHED_TWIN_VERTEX = 3;

    private static final double[] SQUARE_CORNER_U = { 0.1, 0.2, 0.3, 0.1, 0.3, 0.4 };

    private static final double[] SQUARE_CORNER_V = { 0.5, 0.6, 0.7, 0.5, 0.7, 0.8 };

    private static final double[] SEAM_CORNER_U = { 0.1, 0.2, 0.3, 0.9, 0.8, 0.4 };

    private static final double[] SEAM_CORNER_V = { 0.5, 0.6, 0.7, 0.5, 0.7, 0.8 };

    @Test
    void glbRoundTripKeepsEveryCorner(@TempDir Path directory) throws IOException {
        GeometryBundle source = square(SQUARE_CORNER_U, SQUARE_CORNER_V);
        Path file = directory.resolve("square.glb");

        MeshExport written = GltfMeshWriter.write(source, file);
        GeometryBundle reloaded = MeshLoader.loadBundle(file.toString());

        assertEquals(SQUARE_VERTICES, written.vertexCount(), "no corner UV repeats a vertex");
        assertEquals(SQUARE_TRIANGLES, written.triangleCount(), "both triangles are written");
        assertEquals(SQUARE_VERTICES, reloaded.mesh().vertexCount(), "the weld finds four positions");
        assertEquals(SQUARE_TRIANGLES, reloaded.mesh().faceCount(), "both triangles come back");
        assertCornersMatch(source, reloaded);
    }

    @Test
    void twoWritesOfOneBundleAreByteIdentical(@TempDir Path directory) throws IOException {
        GeometryBundle source = square(SQUARE_CORNER_U, SQUARE_CORNER_V);
        Path first = directory.resolve("first.glb");
        Path second = directory.resolve("second.glb");

        GltfMeshWriter.write(source, first);
        GltfMeshWriter.write(source, second);

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second),
                "the same bundle writes the same bytes");
    }

    @Test
    void seamCornersAreSplitBackIntoTheirOwnVertices(@TempDir Path directory) throws IOException {
        GeometryBundle source = square(SEAM_CORNER_U, SEAM_CORNER_V);
        Path file = directory.resolve("seam.glb");

        MeshExport written = GltfMeshWriter.write(source, file);
        GltfModel model = GltfMeshParser.read(file.toString());

        assertEquals(SEAM_SPLIT_VERTICES, written.vertexCount(),
                "the two seam corners each take a vertex of their own");
        assertEquals(SEAM_SPLIT_VERTICES, model.sourceVertexCount, "the file declares the split");
        assertEquals(SQUARE_VERTICES, model.weldedVertexCount, "and the loader welds it back");
        assertCornersMatch(source, model.bundle);
    }

    @Test
    void plyRoundTripsThroughThePlyParser(@TempDir Path directory) throws IOException {
        GeometryBundle source = square(SEAM_CORNER_U, SEAM_CORNER_V);
        Path file = directory.resolve("square.ply");

        PlyMeshWriter.write(source, file);
        GeometryBundle reloaded = MeshLoader.loadBundle(file.toString());

        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .contains("property list uchar float texcoord"), "the UVs ride on the faces");
        assertEquals(SQUARE_VERTICES, reloaded.mesh().vertexCount(),
                "so a seam splits no vertex and four come back");
        assertEquals(SQUARE_TRIANGLES, reloaded.mesh().faceCount(), "two triangles come back");
        assertCornerPositionsMatch(source, reloaded);
        assertCornerNormalsMatch(source, reloaded);
    }

    @Test
    void distinctVerticesAtOnePositionStayDistinct(@TempDir Path directory) throws IOException {
        float[] normals = new float[PINCHED_POSITIONS.length];
        for (int offset = 2; offset < normals.length; offset += MeshExport.FLOATS_PER_VERTEX) {
            normals[offset] = 1f;
        }
        ArrayMesh pinched = new ArrayMesh(PINCHED_POSITIONS.clone(), normals,
                new int[] { 0, 1, 2, PINCHED_TWIN_VERTEX, PINCHED_TWIN_VERTEX + 1, PINCHED_TWIN_VERTEX + 2 },
                MeshExport.CORNERS_PER_TRIANGLE);
        GeometryBundle source = new GeometryBundle(pinched, Map.of(CornerUvField.SLOT,
                new CornerUvField(SEAM_CORNER_U.clone(), SEAM_CORNER_V.clone())));
        Path file = directory.resolve("pinched.glb");

        MeshExport written = GltfMeshWriter.write(source, file);
        GltfModel model = GltfMeshParser.read(file.toString());

        assertEquals(1f, written.weldKey[PINCHED_TWIN_VERTEX], "the second vertex at the origin is key 1");
        assertEquals(pinched.vertexCount(), model.weldedVertexCount,
                "the weld key keeps the pinch apart where a position weld would fuse it");
        assertCornersMatch(source, model.bundle);
    }

    @Test
    void bothGltfFixturesRoundTrip(@TempDir Path directory) throws IOException {
        for (String fixture : new String[] { TWO_TRIANGLES_GLTF_PATH, TWO_TRIANGLES_SEAM_GLTF_PATH }) {
            GeometryBundle source = MeshLoader.loadBundle(fixture);
            Path file = directory.resolve(Path.of(fixture).getFileName() + ".glb");

            GltfMeshWriter.write(source, file);
            GeometryBundle reloaded = MeshLoader.loadBundle(file.toString());

            assertEquals(source.mesh().vertexCount(), reloaded.mesh().vertexCount(),
                    "vertex count of " + fixture);
            assertEquals(source.mesh().faceCount(), reloaded.mesh().faceCount(),
                    "face count of " + fixture);
            assertCornersMatch(source, reloaded);
        }
    }

    @Test
    void aQuadWithoutUvsIsFannedIntoTrianglesWithComputedNormals(@TempDir Path directory)
            throws IOException {
        ArrayMesh quad = new ArrayMesh(SQUARE_POSITIONS.clone(), null, new int[] { 0, 1, 2, 3 },
                QUAD_CORNERS);
        Path file = directory.resolve("quad.glb");

        MeshExport written = GltfMeshWriter.write(GeometryBundle.ofMesh(quad), file);
        GeometryBundle reloaded = MeshLoader.loadBundle(file.toString());

        assertEquals(SQUARE_TRIANGLES, written.triangleCount(), "one quad fans into two triangles");
        assertEquals(SQUARE_TRIANGLES, reloaded.mesh().faceCount(), "and reloads as two");
        Vector3f normal = reloaded.mesh().vertexNormal(reloaded.mesh().vertexIdAt(0), new Vector3f());
        assertEquals(1f, normal.length(), EPSILON, "a mesh without normals gets computed ones");
    }

    @Test
    void perCornerUvsOnAQuadAreRefused(@TempDir Path directory) {
        ArrayMesh quad = new ArrayMesh(SQUARE_POSITIONS.clone(), null, new int[] { 0, 1, 2, 3 },
                QUAD_CORNERS);
        GeometryBundle bundle = new GeometryBundle(quad,
                Map.of(CornerUvField.SLOT, new CornerUvField(new double[] { 0, 0, 0 },
                        new double[] { 0, 0, 0 })));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> GltfMeshWriter.write(bundle, directory.resolve("quad_uv.glb")));

        assertTrue(refused.getMessage().contains("triangle-only"), refused.getMessage());
    }

    @Test
    void aNonFiniteCornerUvIsWrittenAsZero(@TempDir Path directory) throws IOException {
        double[] cornerU = SQUARE_CORNER_U.clone();
        cornerU[0] = Double.NaN;
        GeometryBundle source = square(cornerU, SQUARE_CORNER_V);

        MeshExport written = GltfMeshWriter.write(source, directory.resolve("nan.glb"));

        assertEquals(1, written.nonFiniteUvCount, "the NaN corner is counted");
        assertEquals(0f, written.vertexUv[0], EPSILON, "and written as zero");
    }

    @Test
    void exportMeshNodeWritesTheFileAndPassesTheBundleThrough(@TempDir Path directory)
            throws IOException {
        GeometryBundle source = square(SQUARE_CORNER_U, SQUARE_CORNER_V);
        Path file = directory.resolve("nested/graph.glb");
        ExportMeshNode node = new ExportMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(ExportMeshNode.GEOMETRY.name, source);
        context.setInput(ExportMeshNode.PATH.name, file.toString());

        node.evaluate(context);

        assertSame(source, context.getOutput(ExportMeshNode.GEOMETRY.name, GeometryBundle.class),
                "export_mesh passes its input through untouched");
        assertTrue(Files.exists(file), "the node creates the parent directory and writes the file");
        assertEquals(SQUARE_TRIANGLES, MeshLoader.load(file.toString()).faceCount(),
                "and the file reloads");
    }

    @Test
    void exportMeshNodeTakesThePlyFormatFromTheExtension(@TempDir Path directory)
            throws IOException {
        GeometryBundle source = square(SQUARE_CORNER_U, SQUARE_CORNER_V);
        Path file = directory.resolve("graph.ply");
        ExportMeshNode node = new ExportMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(ExportMeshNode.GEOMETRY.name, source);
        context.setInput(ExportMeshNode.PATH.name, file.toString());

        node.evaluate(context);

        assertTrue(Files.readString(file).startsWith("ply"), "the extension chose ASCII PLY");
        assertEquals(SQUARE_VERTICES, MeshLoader.load(file.toString()).vertexCount(),
                "and the PLY reloads");
    }

    /**
     * A unit square of two triangles in the XY plane with the given per-corner UVs.
     *
     * @param cornerU {@code u} per corner, six long
     * @param cornerV {@code v} per corner, six long
     * @return the bundle
     */
    private static GeometryBundle square(double[] cornerU, double[] cornerV) {
        float[] normals = new float[SQUARE_POSITIONS.length];
        for (int vertex = 0; vertex < SQUARE_VERTICES; vertex++) {
            normals[vertex * MeshExport.FLOATS_PER_VERTEX + 2] = 1f;
        }
        ArrayMesh mesh = new ArrayMesh(SQUARE_POSITIONS.clone(), normals, SQUARE_INDICES.clone(),
                MeshExport.CORNERS_PER_TRIANGLE);
        return new GeometryBundle(mesh, Map.of(CornerUvField.SLOT,
                new CornerUvField(cornerU.clone(), cornerV.clone())));
    }

    /**
     * Assert that a reloaded bundle carries the same position, normal and UV at every face corner,
     * which is what survives a round trip through a per-vertex file format.
     *
     * @param source bundle that was written
     * @param reloaded bundle read back from the file
     */
    private static void assertCornersMatch(GeometryBundle source, GeometryBundle reloaded) {
        assertCornerPositionsMatch(source, reloaded);
        assertCornerNormalsMatch(source, reloaded);
        CornerUvField before = assertInstanceOf(CornerUvField.class,
                source.slots().get(CornerUvField.SLOT), "the source carries corner UVs");
        CornerUvField after = assertInstanceOf(CornerUvField.class,
                reloaded.slots().get(CornerUvField.SLOT), "so does the reloaded bundle");
        assertEquals(before.faceCount(), after.faceCount(), "one UV triple per face");
        for (int face = 0; face < before.faceCount(); face++) {
            for (int corner = 0; corner < CornerUvField.CORNERS_PER_FACE; corner++) {
                assertEquals(before.u(face, corner), after.u(face, corner), EPSILON,
                        "u of face " + face + " corner " + corner);
                assertEquals(before.v(face, corner), after.v(face, corner), EPSILON,
                        "v of face " + face + " corner " + corner);
            }
        }
    }

    /**
     * Assert that every face corner sits at the position it was written with.
     *
     * @param source bundle that was written
     * @param reloaded bundle read back from the file
     */
    private static void assertCornerPositionsMatch(GeometryBundle source,
            GeometryBundle reloaded) {
        MeshTopology before = source.mesh();
        MeshTopology after = reloaded.mesh();
        assertEquals(before.faceCount(), after.faceCount(), "face count");
        Vector3f left = new Vector3f();
        Vector3f right = new Vector3f();
        for (int face = 0; face < before.faceCount(); face++) {
            int beforeFace = before.faceIdAt(face);
            int afterFace = after.faceIdAt(face);
            assertEquals(before.faceVertexCount(beforeFace), after.faceVertexCount(afterFace),
                    "corners of face " + face);
            for (int corner = 0; corner < before.faceVertexCount(beforeFace); corner++) {
                before.vertexPosition(before.faceVertexAt(beforeFace, corner), left);
                after.vertexPosition(after.faceVertexAt(afterFace, corner), right);
                assertEquals(left.x, right.x, EPSILON, "x of face " + face + " corner " + corner);
                assertEquals(left.y, right.y, EPSILON, "y of face " + face + " corner " + corner);
                assertEquals(left.z, right.z, EPSILON, "z of face " + face + " corner " + corner);
            }
        }
    }

    /**
     * Assert that every face corner kept the normal it was written with.
     *
     * @param source bundle that was written
     * @param reloaded bundle read back from the file
     */
    private static void assertCornerNormalsMatch(GeometryBundle source, GeometryBundle reloaded) {
        MeshTopology before = source.mesh();
        MeshTopology after = reloaded.mesh();
        Vector3f left = new Vector3f();
        Vector3f right = new Vector3f();
        for (int face = 0; face < before.faceCount(); face++) {
            int beforeFace = before.faceIdAt(face);
            int afterFace = after.faceIdAt(face);
            for (int corner = 0; corner < before.faceVertexCount(beforeFace); corner++) {
                before.vertexNormal(before.faceVertexAt(beforeFace, corner), left);
                after.vertexNormal(after.faceVertexAt(afterFace, corner), right);
                assertEquals(left.x, right.x, EPSILON, "nx of face " + face + " corner " + corner);
                assertEquals(left.y, right.y, EPSILON, "ny of face " + face + " corner " + corner);
                assertEquals(left.z, right.z, EPSILON, "nz of face " + face + " corner " + corner);
            }
        }
    }
}
