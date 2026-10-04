package ixdar.geometry.mesh.data.load;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ixdar.geometry.mesh.data.GeometryBundle;

/**
 * Writes a bundle as binary glTF 2.0, the inverse of {@link GltfMeshParser} including its weld.
 * Sorted JSON keys and a fixed accessor order make the bytes depend on the geometry alone.
 */
public final class GltfMeshWriter {

    public static final String ASSET_GENERATOR = "ixdar GltfMeshWriter";

    public static final String ASSET_VERSION = "2.0";

    public static final int GLB_CONTAINER_VERSION = 2;

    public static final int GLB_CHUNK_ALIGNMENT_BYTES = 4;

    public static final int TARGET_ARRAY_BUFFER = 34962;

    public static final int TARGET_ELEMENT_ARRAY_BUFFER = 34963;

    public static final String JSON_MEMBER_SEPARATOR = ",";

    public static final String ACCESSOR_TYPE = "type";

    public static final String BYTE_LENGTH = "byteLength";

    public static final String ACCESSOR_TYPE_VEC3 = "VEC3";

    public static final String ACCESSOR_TYPE_VEC2 = "VEC2";

    public static final String ACCESSOR_TYPE_SCALAR = "SCALAR";

    private GltfMeshWriter() {
    }

    /**
     * Write {@code bundle} as a {@code .glb}, creating the parent directories.
     *
     * @param bundle geometry to write
     * @param file destination path
     * @return the flattened export that was written, for the caller's own report
     * @throws IOException if the bundle holds no triangles or the file cannot be written
     */
    public static MeshExport write(GeometryBundle bundle, Path file) throws IOException {
        MeshExport geometry = new MeshExport(bundle);
        geometry.build();
        byte[] bytes = glb(geometry);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, bytes);
        return geometry;
    }

    /**
     * Serialize a flattened export as a GLB container: header, JSON chunk, binary chunk.
     *
     * @param geometry a built {@link MeshExport}
     * @return the file's bytes
     * @throws IOException if the export holds no triangles or a position is not finite
     */
    public static byte[] glb(MeshExport geometry) throws IOException {
        if (geometry.triangleCount() == 0) {
            throw new IOException("glTF export needs at least one triangle");
        }
        // Binary chunk: the attribute and index streams in accessor order (positions, normals,
        // texture coordinates, weld keys, indices). Every stream is a whole number of four-byte
        // words, so each accessor lands on its component's alignment with no padding between views.
        int vectorFloats = geometry.vertexCount() * MeshExport.FLOATS_PER_VERTEX;
        int uvFloats = geometry.vertexUv == null ? 0
                : geometry.vertexCount() * MeshExport.UV_COMPONENTS;
        int keyFloats = geometry.weldKey == null ? 0 : geometry.vertexCount();
        int binaryBytes = (2 * vectorFloats + uvFloats + keyFloats + geometry.triangleIndices.length)
                * GltfMeshParser.BYTES_PER_WORD;
        ByteBuffer buffer = ByteBuffer.allocate(binaryBytes).order(ByteOrder.LITTLE_ENDIAN);
        FloatBuffer floats = buffer.asFloatBuffer();
        floats.put(geometry.positions, 0, vectorFloats);
        floats.put(geometry.normals, 0, vectorFloats);
        for (int vertex = 0; vertex < geometry.vertexCount() && geometry.vertexUv != null; vertex++) {
            int offset = vertex * MeshExport.UV_COMPONENTS;
            floats.put(geometry.vertexUv[offset]);
            floats.put((float) (1.0 - geometry.vertexUv[offset + 1]));
        }
        if (geometry.weldKey != null) {
            floats.put(geometry.weldKey, 0, keyFloats);
        }
        buffer.position(floats.position() * GltfMeshParser.BYTES_PER_WORD);
        buffer.asIntBuffer().put(geometry.triangleIndices, 0, geometry.triangleIndices.length);
        byte[] binary = buffer.array();

        // JSON chunk: one mesh of one primitive, every object's keys sorted so the text depends
        // on the geometry alone.
        int vectorBytes = vectorFloats * GltfMeshParser.BYTES_PER_WORD;
        int uvBytes = uvFloats * GltfMeshParser.BYTES_PER_WORD;
        int keyBytes = keyFloats * GltfMeshParser.BYTES_PER_WORD;
        int indexBytes = geometry.triangleIndices.length * GltfMeshParser.BYTES_PER_WORD;
        boolean hasUv = geometry.vertexUv != null;

        List<String> accessors = new ArrayList<>();
        List<String> views = new ArrayList<>();
        List<String> attributes = new ArrayList<>();
        accessors.add(object(GltfMeshParser.BUFFER_VIEW, number(0),
                GltfMeshParser.COMPONENT_TYPE, number(GltfMeshParser.COMPONENT_FLOAT),
                GltfMeshParser.COUNT, number(geometry.vertexCount()),
                "max", bound(geometry, true),
                "min", bound(geometry, false),
                ACCESSOR_TYPE, text(ACCESSOR_TYPE_VEC3)));
        accessors.add(object(GltfMeshParser.BUFFER_VIEW, number(1),
                GltfMeshParser.COMPONENT_TYPE, number(GltfMeshParser.COMPONENT_FLOAT),
                GltfMeshParser.COUNT, number(geometry.vertexCount()),
                ACCESSOR_TYPE, text(ACCESSOR_TYPE_VEC3)));
        views.add(view(0, vectorBytes, TARGET_ARRAY_BUFFER));
        views.add(view(vectorBytes, vectorBytes, TARGET_ARRAY_BUFFER));
        attributes.add(GltfMeshParser.POSITION);
        attributes.add(number(0));
        attributes.add(GltfMeshParser.NORMAL);
        attributes.add(number(1));
        int viewOffset = 2 * vectorBytes;
        if (hasUv) {
            attributes.add(GltfMeshParser.TEXCOORD);
            attributes.add(number(accessors.size()));
            accessors.add(object(GltfMeshParser.BUFFER_VIEW, number(accessors.size()),
                    GltfMeshParser.COMPONENT_TYPE, number(GltfMeshParser.COMPONENT_FLOAT),
                    GltfMeshParser.COUNT, number(geometry.vertexCount()),
                    ACCESSOR_TYPE, text(ACCESSOR_TYPE_VEC2)));
            views.add(view(viewOffset, uvBytes, TARGET_ARRAY_BUFFER));
            viewOffset += uvBytes;
        }
        if (geometry.weldKey != null) {
            attributes.add(GltfMeshParser.WELD_KEY);
            attributes.add(number(accessors.size()));
            accessors.add(object(GltfMeshParser.BUFFER_VIEW, number(accessors.size()),
                    GltfMeshParser.COMPONENT_TYPE, number(GltfMeshParser.COMPONENT_FLOAT),
                    GltfMeshParser.COUNT, number(geometry.vertexCount()),
                    ACCESSOR_TYPE, text(ACCESSOR_TYPE_SCALAR)));
            views.add(view(viewOffset, keyBytes, TARGET_ARRAY_BUFFER));
            viewOffset += keyBytes;
        }
        int indexAccessor = accessors.size();
        accessors.add(object(GltfMeshParser.BUFFER_VIEW, number(indexAccessor),
                GltfMeshParser.COMPONENT_TYPE, number(GltfMeshParser.COMPONENT_UNSIGNED_INT),
                GltfMeshParser.COUNT, number(geometry.triangleIndices.length),
                ACCESSOR_TYPE, text(ACCESSOR_TYPE_SCALAR)));
        views.add(view(viewOffset, indexBytes, TARGET_ELEMENT_ARRAY_BUFFER));

        String primitive = object(GltfMeshParser.ATTRIBUTES,
                object(attributes.toArray(new String[0])),
                GltfMeshParser.INDICES, number(indexAccessor),
                "mode", number(GltfMeshParser.MODE_TRIANGLES));
        String document = object(
                "accessors", array(accessors),
                "asset", object("generator", text(ASSET_GENERATOR), "version", text(ASSET_VERSION)),
                GltfMeshParser.BUFFER_VIEW + "s", array(views),
                "buffers", array(List.of(object(BYTE_LENGTH, number(binary.length)))),
                "materials", array(List.of()),
                "meshes", array(List.of(object(GltfMeshParser.PRIMITIVES,
                        array(List.of(primitive))))),
                GltfMeshParser.NODES, array(List.of(object(GltfMeshParser.MESH, number(0)))),
                "scene", number(0),
                "scenes", array(List.of(object(GltfMeshParser.NODES, array(List.of(number(0)))))));
        byte[] json = document.getBytes(StandardCharsets.UTF_8);

        // Container: header, then the JSON chunk space-padded to GLB's four-byte alignment, then
        // the binary chunk, already a whole number of words.
        int jsonPadding = (GLB_CHUNK_ALIGNMENT_BYTES - json.length % GLB_CHUNK_ALIGNMENT_BYTES)
                % GLB_CHUNK_ALIGNMENT_BYTES;
        int total = GltfMeshParser.GLB_HEADER_BYTES + 2 * GltfMeshParser.GLB_CHUNK_HEADER_BYTES
                + json.length + jsonPadding + binary.length;
        ByteBuffer container = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        container.putInt(GltfMeshParser.GLB_MAGIC);
        container.putInt(GLB_CONTAINER_VERSION);
        container.putInt(total);
        container.putInt(json.length + jsonPadding);
        container.putInt(GltfMeshParser.GLB_CHUNK_JSON);
        container.put(json);
        for (int pad = 0; pad < jsonPadding; pad++) {
            container.put((byte) ' ');
        }
        container.putInt(binary.length);
        container.putInt(GltfMeshParser.GLB_CHUNK_BIN);
        container.put(binary);
        return container.array();
    }

    /**
     * One buffer view over the single binary buffer.
     *
     * @param byteOffset where the view starts
     * @param byteLength how long it is
     * @param target the {@code target} hint a renderer binds it to
     * @return the view's JSON
     */
    private static String view(int byteOffset, int byteLength, int target) {
        return object("buffer", number(0),
                BYTE_LENGTH, number(byteLength),
                GltfMeshParser.BYTE_OFFSET, number(byteOffset),
                "target", number(target));
    }

    /**
     * The per-component extreme of the exported positions, which the POSITION accessor must carry.
     *
     * @param geometry a built {@link MeshExport}
     * @param maximum {@code true} for the maximum, {@code false} for the minimum
     * @return the bound as a JSON array
     * @throws IOException if a position is not finite
     */
    private static String bound(MeshExport geometry, boolean maximum) throws IOException {
        List<String> components = new ArrayList<>();
        for (int axis = 0; axis < MeshExport.FLOATS_PER_VERTEX; axis++) {
            float extreme = geometry.positions[axis];
            for (int vertex = 0; vertex < geometry.vertexCount(); vertex++) {
                float value = geometry.positions[vertex * MeshExport.FLOATS_PER_VERTEX + axis];
                if (!Float.isFinite(value)) {
                    throw new IOException("glTF cannot hold a non-finite position, at vertex "
                            + vertex);
                }
                extreme = maximum ? Math.max(extreme, value) : Math.min(extreme, value);
            }
            components.add(Float.toString(extreme));
        }
        return array(components);
    }

    /**
     * A JSON object whose members are sorted by key, which is what keeps the document byte-stable
     * whatever order the caller builds it in.
     *
     * @param keysAndValues alternating member name and already-rendered JSON value
     * @return the object's text
     */
    private static String object(String... keysAndValues) {
        String[] members = new String[keysAndValues.length / 2];
        for (int pair = 0; pair < members.length; pair++) {
            members[pair] = text(keysAndValues[pair * 2]) + ":" + keysAndValues[pair * 2 + 1];
        }
        Arrays.sort(members);
        return "{" + String.join(JSON_MEMBER_SEPARATOR, members) + "}";
    }

    /**
     * A JSON array of already-rendered values.
     *
     * @param items the values in order
     * @return the array's text
     */
    private static String array(List<String> items) {
        return "[" + String.join(JSON_MEMBER_SEPARATOR, items) + "]";
    }

    /**
     * A JSON string. The writer only ever quotes its own fixed names, so nothing needs escaping.
     *
     * @param value the text
     * @return the quoted text
     */
    private static String text(String value) {
        return "\"" + value + "\"";
    }

    /**
     * A JSON number.
     *
     * @param value the value
     * @return its text
     */
    private static String number(int value) {
        return Integer.toString(value);
    }
}
