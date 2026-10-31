package ixdar.geometry.mesh.data.sculpt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * A sculpted cage stored between rounds as an OBJ, faces kept as quads, with each creased edge on
 * a {@code #crease a b weight} comment line so other OBJ readers still open it.
 */
public final class SculptCageFile {

    public static final String CREASE_PREFIX = "#crease";

    public static final String VERTEX_PREFIX = "v";

    public static final String FACE_PREFIX = "f";

    public static final String FIELD_SEPARATOR = "\\s+";

    public static final String INDEX_SEPARATOR = "/";

    public static final int CREASE_FIELDS = 4;

    private SculptCageFile() {
    }

    /**
     * Write a bundle's mesh and its float {@code "crease"} marks, vertices in active order.
     *
     * @param bundle sculpted geometry
     * @param file   destination, parent directories created
     * @throws IOException when the file cannot be written
     */
    public static void write(GeometryBundle bundle, Path file) throws IOException {
        MeshTopology mesh = bundle.mesh();
        StringBuilder text = new StringBuilder("# stroke_sculpt cage\n");
        int[] indexByVertexId = new int[mesh.vertexIdBound()];
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            indexByVertexId[vertexId] = activeVertex + 1;
            mesh.vertexPosition(vertexId, position);
            text.append(String.format(Locale.ROOT, "v %.6f %.6f %.6f%n", position.x, position.y,
                    position.z));
        }
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            text.append(FACE_PREFIX);
            for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                text.append(' ').append(indexByVertexId[mesh.faceVertexAt(faceId, corner)]);
            }
            text.append('\n');
        }
        float[] creases = EdgeMarks.floats(bundle, StrokeSculpt.CREASE_LABEL);
        for (int edgeId = 0; creases != null && edgeId < creases.length; edgeId++) {
            if (creases[edgeId] > 0f && mesh.hasEdge(edgeId)) {
                int halfEdge = mesh.edgeHalfEdge(edgeId);
                text.append(String.format(Locale.ROOT, "%s %d %d %.4f%n", CREASE_PREFIX,
                        indexByVertexId[mesh.halfEdgeVertex(halfEdge)],
                        indexByVertexId[mesh.halfEdgeEndVertex(halfEdge)], creases[edgeId]));
            }
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Read a cage {@link #write} stored, its creases restored as float {@code "crease"} marks.
     *
     * @param file stored cage
     * @return the cage geometry
     * @throws IOException when the file cannot be read
     */
    public static GeometryBundle read(Path file) throws IOException {
        String[] lines = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n");
        float[] positions = new float[lines.length * 3];
        int[] faceSizes = new int[lines.length];
        int[] faceCorners = new int[lines.length * 4];
        int vertexCount = 0;
        int faceCount = 0;
        int cornerCount = 0;
        for (String line : lines) {
            String[] fields = line.trim().split(FIELD_SEPARATOR);
            if (VERTEX_PREFIX.equals(fields[0])) {
                for (int axis = 0; axis < 3; axis++) {
                    positions[vertexCount * 3 + axis] = Float.parseFloat(fields[axis + 1]);
                }
                vertexCount++;
            } else if (FACE_PREFIX.equals(fields[0])) {
                if (cornerCount + fields.length > faceCorners.length) {
                    faceCorners = Arrays.copyOf(faceCorners, faceCorners.length * 2);
                }
                for (int corner = 1; corner < fields.length; corner++) {
                    faceCorners[cornerCount++] = Integer.parseInt(fields[corner].split(INDEX_SEPARATOR)[0]) - 1;
                }
                faceSizes[faceCount++] = fields.length - 1;
            }
        }
        HalfEdgeMesh mesh = HalfEdgeMeshOps.bulkAllocateMixed(
                Arrays.copyOf(positions, vertexCount * 3),
                Arrays.copyOf(faceSizes, faceCount),
                Arrays.copyOf(faceCorners, cornerCount));
        mesh.computeNormals();
        float[] creases = new float[mesh.edgeIdBound()];
        for (String line : lines) {
            String[] fields = line.trim().split(FIELD_SEPARATOR);
            if (fields.length == CREASE_FIELDS && CREASE_PREFIX.equals(fields[0])) {
                int edgeId = mesh.edgeBetween(mesh.vertexIdAt(Integer.parseInt(fields[1]) - 1),
                        mesh.vertexIdAt(Integer.parseInt(fields[2]) - 1));
                if (edgeId >= 0) {
                    creases[edgeId] = Float.parseFloat(fields[3]);
                }
            }
        }
        return EdgeMarks.with(GeometryBundle.ofMesh(mesh), StrokeSculpt.CREASE_LABEL, creases);
    }
}
