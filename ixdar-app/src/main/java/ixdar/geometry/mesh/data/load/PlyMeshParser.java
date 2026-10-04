package ixdar.geometry.mesh.data.load;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;

/**
 * Reads ASCII PLY into an {@link ArrayMesh}: vertex positions, optional {@code nx/ny/nz} normals,
 * and a face list. Faces keep their arity when every face agrees on three or four corners, and are
 * fanned into triangles otherwise.
 */
public final class PlyMeshParser {

    public static final String ELEMENT = "element";

    public static final String PROPERTY = "property";

    public static final String FORMAT = "format";

    public static final String ASCII = "ascii";

    public static final String VERTEX_ELEMENT = "vertex";

    public static final String FACE_ELEMENT = "face";

    public static final int QUAD_CORNERS = 4;

    public static final int TRIANGLE_CORNERS = 3;

    private static final List<String> POSITION_PROPERTIES = List.of("x", "y", "z");

    private static final List<String> NORMAL_PROPERTIES = List.of("nx", "ny", "nz");

    private PlyMeshParser() {
    }

    /**
     * Parse a PLY document.
     *
     * @param content the file's text
     * @return the mesh, or an empty one when the document declares no vertices
     * @throws IOException if the document is binary PLY, which this parser does not read, or a
     *     body line is malformed
     */
    public static ArrayMesh load(String content) throws IOException {
        String[] lines = content.split("\n");
        // Header: element counts, and the vertex element's property names in column order.
        List<String> vertexProperties = new ArrayList<>();
        int vertexCount = 0;
        int faceCount = 0;
        String element = "";
        int bodyStart = 0;
        while (bodyStart < lines.length) {
            String line = lines[bodyStart++].trim();
            if (line.isEmpty() || line.startsWith(MeshLoader.COMMENT_PREFIX)) {
                continue;
            }
            if (line.equals(MeshLoader.END_HEADER)) {
                break;
            }
            String[] parts = line.split(MeshLoader.S);
            if (parts[0].equals(FORMAT) && (parts.length < 2 || !parts[1].equals(ASCII))) {
                throw new IOException("Only ASCII PLY is supported, not: " + line);
            }
            if (parts[0].equals(ELEMENT) && parts.length >= TRIANGLE_CORNERS) {
                element = parts[1];
                if (element.equals(VERTEX_ELEMENT)) {
                    vertexCount = Integer.parseInt(parts[2]);
                } else if (element.equals(FACE_ELEMENT)) {
                    faceCount = Integer.parseInt(parts[2]);
                }
            } else if (parts[0].equals(PROPERTY) && element.equals(VERTEX_ELEMENT)) {
                vertexProperties.add(parts[parts.length - 1]);
            }
        }
        if (vertexCount == 0) {
            return ArrayMeshEngine.emptyQuads();
        }

        float[] positions = new float[vertexCount * MeshLoader.FLOATS_PER_VERTEX];
        float[] normals = new float[vertexCount * MeshLoader.FLOATS_PER_VERTEX];
        int[] positionColumns = columns(vertexProperties, POSITION_PROPERTIES);
        int[] normalColumns = columns(vertexProperties, NORMAL_PROPERTIES);
        int cursor = bodyStart;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            String[] parts = bodyLine(lines, cursor, vertex, VERTEX_ELEMENT);
            cursor = nextLine(lines, cursor);
            int offset = vertex * MeshLoader.FLOATS_PER_VERTEX;
            for (int axis = 0; axis < MeshLoader.FLOATS_PER_VERTEX; axis++) {
                positions[offset + axis] = component(parts, positionColumns[axis]);
                normals[offset + axis] = component(parts, normalColumns[axis]);
            }
        }

        int[] faceSizes = new int[faceCount];
        int[] faceCorners = new int[faceCount * TRIANGLE_CORNERS];
        int corners = 0;
        for (int face = 0; face < faceCount; face++) {
            String[] parts = bodyLine(lines, cursor, face, FACE_ELEMENT);
            cursor = nextLine(lines, cursor);
            int size = Integer.parseInt(parts[0]);
            faceSizes[face] = size;
            if (corners + size > faceCorners.length) {
                faceCorners = Arrays.copyOf(faceCorners, Math.max(corners + size,
                        faceCorners.length * 2));
            }
            for (int corner = 0; corner < size; corner++) {
                faceCorners[corners + corner] = Integer.parseInt(parts[corner + 1]);
            }
            corners += size;
        }

        // Keep the faces' arity when they all agree on triangles or quads; fan them otherwise.
        int uniform = faceSizes.length == 0 ? 0 : faceSizes[0];
        for (int size : faceSizes) {
            if (size != uniform) {
                uniform = 0;
            }
        }
        if (uniform == TRIANGLE_CORNERS || uniform == QUAD_CORNERS) {
            return new ArrayMesh(positions, normals, Arrays.copyOf(faceCorners, corners), uniform);
        }
        int triangleCorners = 0;
        for (int size : faceSizes) {
            triangleCorners += size < TRIANGLE_CORNERS ? 0 : (size - 2) * TRIANGLE_CORNERS;
        }
        if (triangleCorners == 0) {
            return ArrayMeshEngine.emptyQuads();
        }
        int[] triangles = new int[triangleCorners];
        int read = 0;
        int write = 0;
        for (int size : faceSizes) {
            for (int fan = 1; fan + 1 < size; fan++) {
                triangles[write++] = faceCorners[read];
                triangles[write++] = faceCorners[read + fan];
                triangles[write++] = faceCorners[read + fan + 1];
            }
            read += size;
        }
        return new ArrayMesh(positions, normals, triangles, TRIANGLE_CORNERS);
    }

    /**
     * Split the next non-blank body line into its fields.
     *
     * @param lines the whole document
     * @param cursor line to read from
     * @param index which element of its kind is being read, for the diagnostic
     * @param element element name, for the diagnostic
     * @return the line's fields
     * @throws IOException if the body ends before the header's counts are satisfied
     */
    private static String[] bodyLine(String[] lines, int cursor, int index, String element)
            throws IOException {
        int at = cursor;
        while (at < lines.length && lines[at].trim().isEmpty()) {
            at++;
        }
        if (at >= lines.length) {
            throw new IOException("PLY body ends before " + element + " " + index);
        }
        return lines[at].trim().split(MeshLoader.S);
    }

    /**
     * Where the line after the one {@link #bodyLine} read begins.
     *
     * @param lines the whole document
     * @param cursor line {@link #bodyLine} was called with
     * @return index of the following line
     */
    private static int nextLine(String[] lines, int cursor) {
        int at = cursor;
        while (at < lines.length && lines[at].trim().isEmpty()) {
            at++;
        }
        return at + 1;
    }

    /**
     * Column of each named property, in the order the names are given.
     *
     * @param vertexProperties property names in column order
     * @param names properties to locate
     * @return the column of each name, {@code -1} where the document declares none
     */
    private static int[] columns(List<String> vertexProperties, List<String> names) {
        int[] found = new int[names.size()];
        for (int slot = 0; slot < found.length; slot++) {
            found[slot] = vertexProperties.indexOf(names.get(slot));
        }
        return found;
    }

    /**
     * One numeric field of a vertex line.
     *
     * @param parts the line's fields
     * @param column column to read, or {@code -1} for a property the document omits
     * @return the value, zero when the column is absent
     */
    private static float component(String[] parts, int column) {
        if (column < 0 || column >= parts.length) {
            return 0f;
        }
        return Float.parseFloat(parts[column]);
    }
}
