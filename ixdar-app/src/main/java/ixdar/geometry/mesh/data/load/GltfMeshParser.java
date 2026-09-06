package ixdar.geometry.mesh.data.load;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.platform.Platforms;
import ixdar.platform.json.JsonValue;

/**
 * Reads glTF 2.0 ({@code .glb} / {@code .gltf}) into a triangle {@link ArrayMesh}, welding
 * bitwise-identical positions and carrying {@code TEXCOORD_0} as a per-corner {@link CornerUvField}.
 * Plain Java over the platform's JSON parser, so the browser build reads scans too.
 */
public final class GltfMeshParser {

    /** Floats per position, normal and UV element in the flattened arrays. */
    public static final int FLOATS_PER_VERTEX = 3;

    /** Indices per face; this parser accepts triangles only. */
    public static final int VERTICES_PER_TRIANGLE = 3;

    /** {@code glTF} as a little-endian magic word, the first four bytes of a {@code .glb}. */
    public static final int GLB_MAGIC = 0x46546C67;

    /** Bytes of a GLB header: magic, version, total length. */
    public static final int GLB_HEADER_BYTES = 12;

    /** Bytes of a GLB chunk header: chunk length, chunk type. */
    public static final int GLB_CHUNK_HEADER_BYTES = 8;

    /** GLB chunk type of the JSON document. */
    public static final int GLB_CHUNK_JSON = 0x4E4F534A;

    /** GLB chunk type of the binary buffer. */
    public static final int GLB_CHUNK_BIN = 0x004E4942;

    /** Accessor {@code componentType} of an unsigned byte. */
    public static final int COMPONENT_UNSIGNED_BYTE = 5121;

    /** Accessor {@code componentType} of an unsigned short. */
    public static final int COMPONENT_UNSIGNED_SHORT = 5123;

    /** Accessor {@code componentType} of an unsigned int. */
    public static final int COMPONENT_UNSIGNED_INT = 5125;

    /** Accessor {@code componentType} of a 32-bit float. */
    public static final int COMPONENT_FLOAT = 5126;

    /** Primitive {@code mode} of triangles, and the default when the file omits it. */
    public static final int MODE_TRIANGLES = 4;

    /** Bytes in a 32-bit float or int. */
    public static final int BYTES_PER_WORD = 4;

    /** Components of a UV pair. */
    public static final int UV_COMPONENTS = 2;

    /** Components stored per source vertex and per corner: u then v. */
    public static final int COMPONENTS_PER_CORNER = 2;

    /** Floats in a node's column-major {@code matrix}. */
    public static final int MATRIX_COMPONENTS = 16;

    /** Position of the {@code w} component in a glTF rotation quaternion, which is {@code xyzw}. */
    public static final int QUATERNION_W = 3;

    /** Mask taking the unsigned value of a byte. */
    public static final int UNSIGNED_BYTE_MASK = 0xFF;

    /** Mask taking the unsigned value of a short. */
    public static final int UNSIGNED_SHORT_MASK = 0xFFFF;

    /** Largest unsigned value an unsigned byte accessor can hold, for normalized UVs. */
    public static final float UNSIGNED_BYTE_MAX = 255f;

    /** Largest unsigned value an unsigned short accessor can hold, for normalized UVs. */
    public static final float UNSIGNED_SHORT_MAX = 65535f;

    /** Document member naming a node's or accessor's byte offset into its view. */
    public static final String BYTE_OFFSET = "byteOffset";

    /** Document member naming an accessor's buffer view. */
    public static final String BUFFER_VIEW = "bufferView";

    /** Document member naming an element count. */
    public static final String COUNT = "count";

    /** Document member naming a node's or material's name. */
    public static final String NAME = "name";

    /** Document member naming a buffer's or image's URI. */
    public static final String URI = "uri";

    /** Document member naming a texture reference's index. */
    public static final String INDEX = "index";

    /** Prefix of a URI that carries its payload inline. */
    public static final String DATA_URI_PREFIX = "data:";

    /** Marker inside a {@code data:} URI saying the payload is base64. */
    public static final String BASE64_MARKER = ";base64,";

    /** Document member naming the node array, and a scene's or node's child node list. */
    public static final String NODES = "nodes";

    /** Document member naming a node's mesh, and the top-level mesh array's singular member. */
    public static final String MESH = "mesh";

    /** Document member naming a mesh's primitive list. */
    public static final String PRIMITIVES = "primitives";

    /** Document member naming a primitive's attribute map. */
    public static final String ATTRIBUTES = "attributes";

    /** Attribute naming a primitive's vertex positions. */
    public static final String POSITION = "POSITION";

    /** Attribute naming a primitive's vertex normals. */
    public static final String NORMAL = "NORMAL";

    /** Attribute naming a primitive's first texture coordinate set. */
    public static final String TEXCOORD = "TEXCOORD_0";

    /** Document member naming a node's children. */
    public static final String CHILDREN = "children";

    /** Document member naming a primitive's index accessor. */
    public static final String INDICES = "indices";

    /** Document member naming an accessor's component type. */
    public static final String COMPONENT_TYPE = "componentType";

    /** Document member naming the buffer a view reads from. */
    public static final String BUFFER = "buffer";

    /** Slots per source vertex in the weld's open-addressed table, keeping it half empty. */
    public static final int WELD_LOAD_DIVISOR = 4;

    /** Odd multiplier mixing position bits into a weld hash. */
    public static final int HASH_MULTIPLIER = 31;

    /** Right shift folding the high bits of a weld hash into the low ones. */
    public static final int HASH_SHIFT = 16;

    /** Component difference above which a welded group counts as a normal conflict. */
    public static final float NORMAL_CONFLICT_EPSILON = 1e-3f;

    /**
     * Whether {@link #parse} also resolves the file's images to bytes. {@link #read} sets it;
     * {@link #load} leaves it off so a 40 MB scan's textures are not copied for a geometry-only
     * load.
     */
    public boolean includeImages;

    private final Matrix4f worldTransform = new Matrix4f();

    private final Matrix3f normalTransform = new Matrix3f();

    private final Vector3f scratch = new Vector3f();

    private final Map<Integer, byte[]> resolvedBuffers = new HashMap<>();

    private final Map<Integer, Integer> resolvedBufferBases = new HashMap<>();

    private final List<Integer> primitiveFaceStart = new ArrayList<>();

    private final List<Integer> primitiveFaceCount = new ArrayList<>();

    private final List<Integer> primitiveMaterial = new ArrayList<>();

    private JsonValue document = JsonValue.ofNull();

    private JsonValue nodes = JsonValue.ofNull();

    private JsonValue meshes = JsonValue.ofNull();

    private JsonValue accessors = JsonValue.ofNull();

    private JsonValue bufferViews = JsonValue.ofNull();

    private Path baseDirectory;

    private byte[] containerBytes;

    private int binaryChunkOffset = -1;

    private int binaryChunkLength;

    private String source = "";

    private int totalVertexCount;

    private int totalTriangleCount;

    private float[] positions;

    private float[] normals;

    private float[] sourceUv;

    private float[] weldedPositions;

    private float[] weldedNormals;

    private int weldedVertexCount;

    private int normalConflicts;

    private int[] triangleIndices;

    private int vertexCursor;

    private int indexCursor;

    private int faceCursor;

    private boolean anyNormals;

    private boolean anyTextureCoordinates;

    private GltfMeshParser() {
    }

    /**
     * Read the geometry of a glTF file, skipping its images.
     *
     * @param path filesystem path of a {@code .glb} or {@code .gltf} file
     * @return bundle whose mesh is a welded triangle {@link ArrayMesh}; {@link CornerUvField#SLOT} holds
     *     one {@code (u, v)} per face corner and is absent when the file carried no UVs
     * @throws IOException if the file cannot be read or holds no triangles
     */
    public static GeometryBundle load(String path) throws IOException {
        return new GltfMeshParser().parse(path).bundle;
    }

    /**
     * Read a glTF file whole: geometry, primitive ranges, materials and image bytes.
     *
     * @param path filesystem path of a {@code .glb} or {@code .gltf} file
     * @return the parsed model
     * @throws IOException if the file cannot be read or holds no triangles
     */
    public static GltfModel read(String path) throws IOException {
        GltfMeshParser parser = new GltfMeshParser();
        parser.includeImages = true;
        return parser.parse(path);
    }

    /**
     * Read the container, size the flat arrays from the accessors, then flatten every mesh instance
     * in the scene through its node transform.
     *
     * @param path filesystem path of the glTF file
     * @return the parsed model
     * @throws IOException if the file cannot be read or holds no triangles
     */
    private GltfModel parse(String path) throws IOException {
        source = path;
        Path file = Path.of(path);
        baseDirectory = file.toAbsolutePath().getParent();
        readContainer(Files.readAllBytes(file));

        nodes = document.get(NODES);
        meshes = document.get("meshes");
        accessors = document.get("accessors");
        bufferViews = document.get("bufferViews");

        for (int rootNode : sceneRoots()) {
            countNode(rootNode);
        }
        if (totalVertexCount == 0 || totalTriangleCount == 0) {
            throw new IOException("glTF file has no triangles: " + source);
        }
        positions = new float[totalVertexCount * FLOATS_PER_VERTEX];
        normals = new float[totalVertexCount * FLOATS_PER_VERTEX];
        if (anyTextureCoordinates) {
            sourceUv = new float[totalVertexCount * COMPONENTS_PER_CORNER];
        }
        triangleIndices = new int[totalTriangleCount * VERTICES_PER_TRIANGLE];
        for (int rootNode : sceneRoots()) {
            copyNode(rootNode, worldTransform.identity());
        }

        CornerUvField cornerUv = buildCornerUv();
        weldByPosition();

        ArrayMesh mesh = new ArrayMesh(weldedPositions, weldedNormals, triangleIndices,
                VERTICES_PER_TRIANGLE);
        if (!anyNormals) {
            mesh.computeNormals();
        }
        GltfModel model = new GltfModel();
        model.bundle = cornerUv == null
                ? GeometryBundle.ofMesh(mesh)
                : new GeometryBundle(mesh, Map.of(CornerUvField.SLOT, cornerUv));
        model.sourceVertexCount = totalVertexCount;
        model.weldedVertexCount = weldedVertexCount;
        model.normalConflicts = normalConflicts;
        model.primitiveFaceStart = toIntArray(primitiveFaceStart);
        model.primitiveFaceCount = toIntArray(primitiveFaceCount);
        model.primitiveMaterial = toIntArray(primitiveMaterial);
        readImages(model);
        readMaterials(model);
        model.textureImage = readTextureImages();
        return model;
    }

    /**
     * Copy a growing index list into the flat array a {@link GltfModel} holds.
     *
     * @param values collected values in order
     * @return the same values as a primitive array
     */
    private static int[] toIntArray(List<Integer> values) {
        int[] flat = new int[values.size()];
        for (int slot = 0; slot < flat.length; slot++) {
            flat[slot] = values.get(slot);
        }
        return flat;
    }

    /**
     * Lift the per-source-vertex texture coordinates onto face corners, which must happen before
     * {@link #weldByPosition} rewrites the indices: a corner's UV is the one its own source vertex
     * carried, and that is the information welding discards.
     *
     * @return the corner field, or {@code null} when the file carried no texture coordinates
     */
    private CornerUvField buildCornerUv() {
        if (sourceUv == null) {
            return null;
        }
        double[] cornerU = new double[triangleIndices.length];
        double[] cornerV = new double[triangleIndices.length];
        for (int corner = 0; corner < triangleIndices.length; corner++) {
            int sourceVertex = triangleIndices[corner];
            cornerU[corner] = sourceUv[sourceVertex * COMPONENTS_PER_CORNER];
            cornerV[corner] = sourceUv[sourceVertex * COMPONENTS_PER_CORNER + 1];
        }
        return new CornerUvField(cornerU, cornerV);
    }

    /**
     * Merge vertices whose positions are bitwise identical, keeping first-occurrence order and the
     * first vertex's normal, and rewrite the indices onto the survivors. glTF duplicates a position
     * only to give it another UV, which the corner field now holds instead.
     */
    private void weldByPosition() {
        int capacity = Integer.highestOneBit(Math.max(totalVertexCount, 1)) * WELD_LOAD_DIVISOR;
        int[] table = new int[capacity];
        Arrays.fill(table, -1);
        int mask = capacity - 1;
        int[] weldedOf = new int[totalVertexCount];
        int[] representative = new int[totalVertexCount];
        weldedVertexCount = 0;
        for (int sourceVertex = 0; sourceVertex < totalVertexCount; sourceVertex++) {
            int slot = positionHash(sourceVertex) & mask;
            while (table[slot] != -1 && !samePosition(table[slot], sourceVertex)) {
                slot = (slot + 1) & mask;
            }
            if (table[slot] == -1) {
                table[slot] = sourceVertex;
                representative[weldedVertexCount] = sourceVertex;
                weldedOf[sourceVertex] = weldedVertexCount++;
            } else {
                weldedOf[sourceVertex] = weldedOf[table[slot]];
                normalConflicts += sameNormal(table[slot], sourceVertex) ? 0 : 1;
            }
        }
        weldedPositions = new float[weldedVertexCount * FLOATS_PER_VERTEX];
        weldedNormals = new float[weldedVertexCount * FLOATS_PER_VERTEX];
        for (int welded = 0; welded < weldedVertexCount; welded++) {
            System.arraycopy(positions, representative[welded] * FLOATS_PER_VERTEX, weldedPositions,
                    welded * FLOATS_PER_VERTEX, FLOATS_PER_VERTEX);
            System.arraycopy(normals, representative[welded] * FLOATS_PER_VERTEX, weldedNormals,
                    welded * FLOATS_PER_VERTEX, FLOATS_PER_VERTEX);
        }
        for (int corner = 0; corner < triangleIndices.length; corner++) {
            triangleIndices[corner] = weldedOf[triangleIndices[corner]];
        }
    }

    /**
     * Hash of a source vertex's position bits, mixing the three components so the open-addressed
     * weld table spreads.
     *
     * @param sourceVertex index into the source vertex arrays
     * @return a non-negative hash
     */
    private int positionHash(int sourceVertex) {
        int offset = sourceVertex * FLOATS_PER_VERTEX;
        int hash = Float.floatToRawIntBits(positions[offset]);
        hash = hash * HASH_MULTIPLIER + Float.floatToRawIntBits(positions[offset + 1]);
        hash = hash * HASH_MULTIPLIER + Float.floatToRawIntBits(positions[offset + 2]);
        return (hash ^ (hash >>> HASH_SHIFT)) & Integer.MAX_VALUE;
    }

    /**
     * Whether two source vertices sit at bitwise-identical positions. No epsilon: a real seam
     * duplicate is a byte-for-byte copy of its twin, and a tolerance would merge distinct geometry.
     *
     * @param left index into the source vertex arrays
     * @param right index into the source vertex arrays
     * @return {@code true} when all three components match bit for bit
     */
    private boolean samePosition(int left, int right) {
        int leftOffset = left * FLOATS_PER_VERTEX;
        int rightOffset = right * FLOATS_PER_VERTEX;
        for (int component = 0; component < FLOATS_PER_VERTEX; component++) {
            if (Float.floatToRawIntBits(positions[leftOffset + component])
                    != Float.floatToRawIntBits(positions[rightOffset + component])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether two source vertices agree on a normal within {@link #NORMAL_CONFLICT_EPSILON}, which
     * decides whether a welded group is reported as a normal conflict.
     *
     * @param left index into the source vertex arrays
     * @param right index into the source vertex arrays
     * @return {@code true} when every component agrees within the tolerance
     */
    private boolean sameNormal(int left, int right) {
        int leftOffset = left * FLOATS_PER_VERTEX;
        int rightOffset = right * FLOATS_PER_VERTEX;
        for (int component = 0; component < FLOATS_PER_VERTEX; component++) {
            if (Math.abs(normals[leftOffset + component] - normals[rightOffset + component])
                    > NORMAL_CONFLICT_EPSILON) {
                return false;
            }
        }
        return true;
    }

    /**
     * Split a {@code .glb} into its JSON and BIN chunks, or take the whole file as a {@code .gltf}
     * JSON document.
     *
     * @param bytes the file's contents
     * @throws IOException if the container is truncated or holds no JSON chunk
     */
    private void readContainer(byte[] bytes) throws IOException {
        if (bytes.length >= BYTES_PER_WORD
                && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == GLB_MAGIC) {
            readBinaryContainer(bytes);
            return;
        }
        document = Platforms.get().parseJson(new String(bytes, StandardCharsets.UTF_8));
        requireDocument();
    }

    /**
     * Walk a GLB's chunk list, keeping the JSON document and the binary buffer.
     *
     * @param bytes the {@code .glb} file's contents
     * @throws IOException if a chunk runs past the end of the file or the JSON chunk is missing
     */
    private void readBinaryContainer(byte[] bytes) throws IOException {
        containerBytes = bytes;
        ByteBuffer container = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int cursor = GLB_HEADER_BYTES;
        while (cursor + GLB_CHUNK_HEADER_BYTES <= bytes.length) {
            int chunkLength = container.getInt(cursor);
            int chunkType = container.getInt(cursor + BYTES_PER_WORD);
            int chunkStart = cursor + GLB_CHUNK_HEADER_BYTES;
            if (chunkLength < 0 || chunkStart + chunkLength > bytes.length) {
                throw new IOException("glTF chunk runs past the end of " + source);
            }
            if (chunkType == GLB_CHUNK_JSON) {
                document = Platforms.get().parseJson(
                        new String(bytes, chunkStart, chunkLength, StandardCharsets.UTF_8));
            } else if (chunkType == GLB_CHUNK_BIN) {
                binaryChunkOffset = chunkStart;
                binaryChunkLength = chunkLength;
            }
            cursor = chunkStart + chunkLength;
        }
        requireDocument();
    }

    /**
     * Reject a container whose JSON document did not parse into an object.
     *
     * @throws IOException when the document is missing or malformed
     */
    private void requireDocument() throws IOException {
        if (!document.isObject()) {
            throw new IOException("glTF file has no readable JSON document: " + source);
        }
    }

    /**
     * Root nodes of the default scene, or every node when the file declares no scenes.
     *
     * @return node indices to walk, in file order
     */
    private int[] sceneRoots() {
        JsonValue scenes = document.get("scenes");
        JsonValue scene = scenes.item(document.getInt("scene", 0));
        JsonValue roots = scene.get(NODES);
        if (roots.isArray() && roots.size() > 0) {
            int[] indices = new int[roots.size()];
            for (int slot = 0; slot < indices.length; slot++) {
                indices[slot] = (int) roots.item(slot).numberValue;
            }
            return indices;
        }
        int[] everyNode = new int[nodes.size()];
        for (int slot = 0; slot < everyNode.length; slot++) {
            everyNode[slot] = slot;
        }
        return everyNode;
    }

    /**
     * Add one node's mesh instances to the totals, then recurse into its children.
     *
     * @param nodeIndex index into the document's node array
     */
    private void countNode(int nodeIndex) {
        JsonValue node = nodes.item(nodeIndex);
        JsonValue mesh = meshes.item(node.getInt(MESH, -1));
        JsonValue primitives = mesh.get(PRIMITIVES);
        for (int slot = 0; slot < primitives.size(); slot++) {
            JsonValue primitive = primitives.item(slot);
            JsonValue attributes = primitive.get(ATTRIBUTES);
            int vertexCount = accessors.item(attributes.getInt(POSITION, -1)).getInt(COUNT, 0);
            if (vertexCount == 0) {
                continue;
            }
            totalVertexCount += vertexCount;
            totalTriangleCount += primitiveTriangleCount(primitive, vertexCount);
            anyNormals |= attributes.has(NORMAL);
            anyTextureCoordinates |= attributes.has(TEXCOORD);
        }
        JsonValue children = node.get(CHILDREN);
        for (int slot = 0; slot < children.size(); slot++) {
            countNode((int) children.item(slot).numberValue);
        }
    }

    /**
     * Triangles one primitive contributes: its index count, or its vertex count when it is
     * non-indexed.
     *
     * @param primitive the primitive's JSON
     * @param vertexCount the primitive's POSITION count
     * @return triangle count
     */
    private int primitiveTriangleCount(JsonValue primitive, int vertexCount) {
        int indicesAccessor = primitive.getInt(INDICES, -1);
        int indexCount = indicesAccessor < 0
                ? vertexCount
                : accessors.item(indicesAccessor).getInt(COUNT, 0);
        return indexCount / VERTICES_PER_TRIANGLE;
    }

    /**
     * Copy one node's mesh instances through the accumulated transform, then recurse.
     *
     * @param nodeIndex index into the document's node array
     * @param parentTransform transform accumulated from the ancestors
     * @throws IOException if an accessor is unreadable or a primitive is not triangles
     */
    private void copyNode(int nodeIndex, Matrix4fc parentTransform) throws IOException {
        JsonValue node = nodes.item(nodeIndex);
        Matrix4f world = new Matrix4f(parentTransform).mul(localTransform(node));
        JsonValue mesh = meshes.item(node.getInt(MESH, -1));
        JsonValue primitives = mesh.get(PRIMITIVES);
        for (int slot = 0; slot < primitives.size(); slot++) {
            copyPrimitive(primitives.item(slot), world);
        }
        JsonValue children = node.get(CHILDREN);
        for (int slot = 0; slot < children.size(); slot++) {
            copyNode((int) children.item(slot).numberValue, world);
        }
    }

    /**
     * A node's own transform: its 16-float column-major {@code matrix}, else its TRS triple.
     *
     * @param node the node's JSON
     * @return a fresh matrix
     */
    private Matrix4f localTransform(JsonValue node) {
        JsonValue matrix = node.get("matrix");
        if (matrix.isArray() && matrix.size() == MATRIX_COMPONENTS) {
            float[] columnMajor = new float[matrix.size()];
            for (int slot = 0; slot < columnMajor.length; slot++) {
                columnMajor[slot] = (float) matrix.item(slot).numberValue;
            }
            return new Matrix4f().set(columnMajor);
        }
        JsonValue translation = node.get("translation");
        JsonValue rotation = node.get("rotation");
        JsonValue scale = node.get("scale");
        return new Matrix4f().translationRotateScale(
                component(translation, 0, 0f), component(translation, 1, 0f),
                component(translation, 2, 0f),
                component(rotation, 0, 0f), component(rotation, 1, 0f),
                component(rotation, 2, 0f), component(rotation, QUATERNION_W, 1f),
                component(scale, 0, 1f), component(scale, 1, 1f), component(scale, 2, 1f));
    }

    /**
     * One component of a numeric array member.
     *
     * @param array the array's JSON
     * @param slot component position
     * @param fallback value when the array or the component is absent
     * @return the component
     */
    private static float component(JsonValue array, int slot, float fallback) {
        JsonValue value = array.item(slot);
        return value.kind == JsonValue.KIND_NUMBER ? (float) value.numberValue : fallback;
    }

    /**
     * Append one primitive's vertices and triangles, baking {@code world} into the positions and
     * normals when it is not the identity.
     *
     * @param primitive the primitive's JSON
     * @param world accumulated node transform of this instance
     * @throws IOException if the primitive is not triangles or an accessor is unreadable
     */
    private void copyPrimitive(JsonValue primitive, Matrix4f world) throws IOException {
        int mode = primitive.getInt("mode", MODE_TRIANGLES);
        if (mode != MODE_TRIANGLES) {
            throw new IOException("glTF primitive mode " + mode + " is not triangles (mode "
                    + MODE_TRIANGLES + "), in " + source);
        }
        JsonValue attributes = primitive.get(ATTRIBUTES);
        int positionAccessor = attributes.getInt(POSITION, -1);
        int vertexCount = accessors.item(positionAccessor).getInt(COUNT, 0);
        if (vertexCount == 0) {
            return;
        }
        int floatOffset = vertexCursor * FLOATS_PER_VERTEX;
        readVectors(accessors.item(positionAccessor), positions, floatOffset, vertexCount);

        int normalAccessor = attributes.getInt(NORMAL, -1);
        boolean hasNormals = normalAccessor >= 0;
        if (hasNormals) {
            readVectors(accessors.item(normalAccessor), normals, floatOffset, vertexCount);
        }
        int uvAccessor = attributes.getInt(TEXCOORD, -1);
        if (uvAccessor >= 0 && sourceUv != null) {
            readTextureCoordinates(accessors.item(uvAccessor),
                    vertexCursor * COMPONENTS_PER_CORNER, vertexCount);
        }
        bakeTransform(world, floatOffset, vertexCount, hasNormals);

        int indicesAccessor = primitive.getInt(INDICES, -1);
        int triangleCount = primitiveTriangleCount(primitive, vertexCount);
        if (indicesAccessor < 0) {
            for (int slot = 0; slot < triangleCount * VERTICES_PER_TRIANGLE; slot++) {
                triangleIndices[indexCursor++] = vertexCursor + slot;
            }
        } else {
            readIndices(accessors.item(indicesAccessor), indexCursor,
                    triangleCount * VERTICES_PER_TRIANGLE, vertexCursor);
            indexCursor += triangleCount * VERTICES_PER_TRIANGLE;
        }
        primitiveFaceStart.add(faceCursor);
        primitiveFaceCount.add(triangleCount);
        primitiveMaterial.add(primitive.getInt("material", GltfModel.NO_TEXTURE));
        faceCursor += triangleCount;
        vertexCursor += vertexCount;
    }

    /**
     * Transform a just-copied vertex range into world space, leaving it untouched when the node
     * chain is the identity — which is the common case for a single-node scan.
     *
     * @param world accumulated node transform
     * @param floatOffset first float of the range in the flat arrays
     * @param vertexCount vertices in the range
     * @param hasNormals whether the range's normals were read from the file
     */
    private void bakeTransform(Matrix4f world, int floatOffset, int vertexCount, boolean hasNormals) {
        world.determineProperties();
        if ((world.properties() & Matrix4fc.PROPERTY_IDENTITY) != 0) {
            return;
        }
        world.normal(normalTransform);
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int offset = floatOffset + vertex * FLOATS_PER_VERTEX;
            scratch.set(positions[offset], positions[offset + 1], positions[offset + 2]);
            world.transformPosition(scratch);
            positions[offset] = scratch.x;
            positions[offset + 1] = scratch.y;
            positions[offset + 2] = scratch.z;
            if (hasNormals) {
                scratch.set(normals[offset], normals[offset + 1], normals[offset + 2]);
                normalTransform.transform(scratch).normalize();
                normals[offset] = scratch.x;
                normals[offset + 1] = scratch.y;
                normals[offset + 2] = scratch.z;
            }
        }
    }

    /**
     * Read a float {@code VEC3} accessor into the flat arrays, bulk-copying when the buffer view is
     * tightly packed and stepping the stride otherwise.
     *
     * @param accessor the accessor's JSON
     * @param destination flat array to fill
     * @param floatOffset first float to write
     * @param vertexCount vertices to read
     * @throws IOException if the accessor is not tightly typed floats
     */
    private void readVectors(JsonValue accessor, float[] destination, int floatOffset,
            int vertexCount) throws IOException {
        int componentType = accessor.getInt(COMPONENT_TYPE, COMPONENT_FLOAT);
        if (componentType != COMPONENT_FLOAT) {
            throw new IOException("glTF accessor componentType " + componentType
                    + " is not float, in " + source);
        }
        byte[] bytes = accessorBytes(accessor);
        if (bytes == null) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int base = accessorByteOffset(accessor);
        int stride = accessorStride(accessor, FLOATS_PER_VERTEX * BYTES_PER_WORD);
        if (stride == FLOATS_PER_VERTEX * BYTES_PER_WORD) {
            buffer.position(base);
            buffer.asFloatBuffer().get(destination, floatOffset, vertexCount * FLOATS_PER_VERTEX);
            return;
        }
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int element = base + vertex * stride;
            int offset = floatOffset + vertex * FLOATS_PER_VERTEX;
            destination[offset] = buffer.getFloat(element);
            destination[offset + 1] = buffer.getFloat(element + BYTES_PER_WORD);
            destination[offset + 2] = buffer.getFloat(element + 2 * BYTES_PER_WORD);
        }
    }

    /**
     * Read a {@code TEXCOORD_0} accessor as {@code (u, 1 - v)}: glTF puts the UV origin at the top
     * left, and the {@link CornerUvField} contract is OpenGL's bottom left.
     *
     * @param accessor the accessor's JSON
     * @param floatOffset first float of {@link #sourceUv} to write
     * @param vertexCount vertices to read
     * @throws IOException if the accessor's component type is not one glTF allows for UVs
     */
    private void readTextureCoordinates(JsonValue accessor, int floatOffset, int vertexCount)
            throws IOException {
        byte[] bytes = accessorBytes(accessor);
        if (bytes == null) {
            return;
        }
        int componentType = accessor.getInt(COMPONENT_TYPE, COMPONENT_FLOAT);
        int componentBytes = uvComponentBytes(componentType);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int base = accessorByteOffset(accessor);
        int stride = accessorStride(accessor, UV_COMPONENTS * componentBytes);
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int element = base + vertex * stride;
            int offset = floatOffset + vertex * COMPONENTS_PER_CORNER;
            sourceUv[offset] = uvComponent(buffer, element, componentType);
            sourceUv[offset + 1] = 1f - uvComponent(buffer, element + componentBytes, componentType);
        }
    }

    /**
     * Bytes one UV component occupies.
     *
     * @param componentType accessor component type
     * @return component size in bytes
     * @throws IOException if glTF does not allow that component type for UVs
     */
    private int uvComponentBytes(int componentType) throws IOException {
        if (componentType == COMPONENT_FLOAT) {
            return BYTES_PER_WORD;
        }
        if (componentType == COMPONENT_UNSIGNED_BYTE) {
            return 1;
        }
        if (componentType == COMPONENT_UNSIGNED_SHORT) {
            return 2;
        }
        throw new IOException("glTF TEXCOORD_0 componentType " + componentType
                + " is not float or a normalized integer, in " + source);
    }

    /**
     * One UV component, normalizing the integer encodings to {@code [0, 1]}.
     *
     * @param buffer little-endian view of the accessor's buffer
     * @param byteIndex absolute byte position of the component
     * @param componentType accessor component type
     * @return the component's value
     */
    private static float uvComponent(ByteBuffer buffer, int byteIndex, int componentType) {
        if (componentType == COMPONENT_UNSIGNED_BYTE) {
            return (buffer.get(byteIndex) & UNSIGNED_BYTE_MASK) / UNSIGNED_BYTE_MAX;
        }
        if (componentType == COMPONENT_UNSIGNED_SHORT) {
            return (buffer.getShort(byteIndex) & UNSIGNED_SHORT_MASK) / UNSIGNED_SHORT_MAX;
        }
        return buffer.getFloat(byteIndex);
    }

    /**
     * Read an index accessor into the flat index array, shifted by this instance's vertex base.
     *
     * @param accessor the accessor's JSON
     * @param intOffset first index to write
     * @param indexCount indices to read
     * @param vertexBase value added to every index
     * @throws IOException if the accessor's component type is not one glTF allows for indices
     */
    private void readIndices(JsonValue accessor, int intOffset, int indexCount, int vertexBase)
            throws IOException {
        byte[] bytes = accessorBytes(accessor);
        if (bytes == null) {
            return;
        }
        int componentType = accessor.getInt(COMPONENT_TYPE, COMPONENT_UNSIGNED_INT);
        int componentBytes = indexComponentBytes(componentType);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int base = accessorByteOffset(accessor);
        int stride = accessorStride(accessor, componentBytes);
        if (componentType == COMPONENT_UNSIGNED_INT && stride == BYTES_PER_WORD) {
            buffer.position(base);
            buffer.asIntBuffer().get(triangleIndices, intOffset, indexCount);
            for (int slot = 0; slot < indexCount; slot++) {
                triangleIndices[intOffset + slot] += vertexBase;
            }
            return;
        }
        for (int slot = 0; slot < indexCount; slot++) {
            int element = base + slot * stride;
            int index;
            if (componentType == COMPONENT_UNSIGNED_BYTE) {
                index = buffer.get(element) & UNSIGNED_BYTE_MASK;
            } else if (componentType == COMPONENT_UNSIGNED_SHORT) {
                index = buffer.getShort(element) & UNSIGNED_SHORT_MASK;
            } else {
                index = buffer.getInt(element);
            }
            triangleIndices[intOffset + slot] = vertexBase + index;
        }
    }

    /**
     * Bytes one index component occupies.
     *
     * @param componentType accessor component type
     * @return component size in bytes
     * @throws IOException if glTF does not allow that component type for indices
     */
    private int indexComponentBytes(int componentType) throws IOException {
        if (componentType == COMPONENT_UNSIGNED_INT) {
            return BYTES_PER_WORD;
        }
        if (componentType == COMPONENT_UNSIGNED_SHORT) {
            return 2;
        }
        if (componentType == COMPONENT_UNSIGNED_BYTE) {
            return 1;
        }
        throw new IOException("glTF index componentType " + componentType
                + " is not an unsigned byte, short or int, in " + source);
    }

    /**
     * The buffer bytes an accessor reads from.
     *
     * @param accessor the accessor's JSON
     * @return the buffer's bytes, or {@code null} when the accessor has no buffer view and so reads
     *     as zeros
     * @throws IOException if the buffer cannot be resolved
     */
    private byte[] accessorBytes(JsonValue accessor) throws IOException {
        int viewIndex = accessor.getInt(BUFFER_VIEW, -1);
        if (viewIndex < 0) {
            return null;
        }
        return buffer(bufferViews.item(viewIndex).getInt(BUFFER, 0));
    }

    /**
     * Absolute byte offset of an accessor's first element: its buffer view's offset plus its own.
     *
     * @param accessor the accessor's JSON
     * @return byte offset into the array {@link #buffer} returns
     * @throws IOException if the buffer cannot be resolved
     */
    private int accessorByteOffset(JsonValue accessor) throws IOException {
        JsonValue view = bufferViews.item(accessor.getInt(BUFFER_VIEW, -1));
        return bufferBase(view.getInt(BUFFER, 0)) + view.getInt(BYTE_OFFSET, 0)
                + accessor.getInt(BYTE_OFFSET, 0);
    }

    /**
     * Bytes between consecutive elements: the buffer view's {@code byteStride} when it declares
     * one, otherwise the element's own size.
     *
     * @param accessor the accessor's JSON
     * @param elementBytes size of one element
     * @return the stride in bytes
     */
    private int accessorStride(JsonValue accessor, int elementBytes) {
        JsonValue view = bufferViews.item(accessor.getInt(BUFFER_VIEW, -1));
        int declared = view.getInt("byteStride", 0);
        return declared > 0 ? declared : elementBytes;
    }

    /**
     * Resolve one of the document's buffers: the GLB binary chunk, a {@code data:} URI, or a file
     * beside the {@code .gltf}.
     *
     * @param bufferIndex index into the document's buffer array
     * @return the buffer's bytes
     * @throws IOException if an external buffer cannot be read
     */
    private byte[] buffer(int bufferIndex) throws IOException {
        byte[] cached = resolvedBuffers.get(bufferIndex);
        if (cached != null) {
            return cached;
        }
        JsonValue descriptor = document.get("buffers").item(bufferIndex);
        String uri = descriptor.getString(URI, "");
        byte[] bytes;
        int base = 0;
        if (uri.isEmpty()) {
            if (binaryChunkOffset < 0) {
                throw new IOException("glTF buffer " + bufferIndex + " has no URI and the file has "
                        + "no binary chunk: " + source);
            }
            bytes = containerBytes;
            base = binaryChunkOffset;
        } else if (uri.startsWith(DATA_URI_PREFIX)) {
            bytes = dataUriBytes(uri);
        } else {
            Path external = baseDirectory == null ? Path.of(uri) : baseDirectory.resolve(uri);
            bytes = Files.readAllBytes(external);
        }
        resolvedBuffers.put(bufferIndex, bytes);
        resolvedBufferBases.put(bufferIndex, base);
        return bytes;
    }

    /**
     * Where a buffer's own bytes start inside the array {@link #buffer} returns. Only a GLB's
     * binary chunk is offset: it is read in place instead of copied out of the file's bytes.
     *
     * @param bufferIndex index into the document's buffer array
     * @return byte offset of the buffer's first byte
     * @throws IOException if the buffer cannot be resolved
     */
    private int bufferBase(int bufferIndex) throws IOException {
        buffer(bufferIndex);
        Integer base = resolvedBufferBases.get(bufferIndex);
        return base == null ? 0 : base;
    }

    /**
     * Decode the payload of a base64 {@code data:} URI.
     *
     * @param uri the URI
     * @return the decoded bytes
     * @throws IOException if the URI is not base64-encoded or does not decode
     */
    private byte[] dataUriBytes(String uri) throws IOException {
        int marker = uri.indexOf(BASE64_MARKER);
        if (marker < 0) {
            throw new IOException("glTF data URI is not base64-encoded, in " + source);
        }
        try {
            return Base64.getDecoder().decode(uri.substring(marker + BASE64_MARKER.length()));
        } catch (IllegalArgumentException malformed) {
            throw new IOException("glTF data URI does not decode, in " + source, malformed);
        }
    }

    /**
     * Fill the model's image arrays, leaving them empty unless {@link #includeImages} is set.
     *
     * @param model model being assembled
     * @throws IOException if an image's buffer or file cannot be read
     */
    private void readImages(GltfModel model) throws IOException {
        if (!includeImages) {
            return;
        }
        JsonValue declared = document.get("images");
        int count = declared.size();
        model.imageBytes = new byte[count][];
        model.imageMimeType = new String[count];
        model.imageName = new String[count];
        for (int slot = 0; slot < count; slot++) {
            JsonValue image = declared.item(slot);
            String uri = image.getString(URI, "");
            byte[] bytes;
            String mimeType = image.getString("mimeType", "");
            if (!uri.isEmpty() && uri.startsWith(DATA_URI_PREFIX)) {
                bytes = dataUriBytes(uri);
                mimeType = mimeType.isEmpty() ? dataUriMimeType(uri) : mimeType;
            } else if (!uri.isEmpty()) {
                bytes = Files.readAllBytes(baseDirectory == null ? Path.of(uri) : baseDirectory.resolve(uri));
            } else {
                bytes = bufferViewBytes(image.getInt(BUFFER_VIEW, -1));
            }
            model.imageBytes[slot] = bytes;
            model.imageMimeType[slot] = mimeType;
            model.imageName[slot] = image.getString(NAME, "");
        }
    }

    /**
     * The media type a {@code data:} URI declares.
     *
     * @param uri the URI
     * @return the media type, empty when the URI declares none
     */
    private static String dataUriMimeType(String uri) {
        int marker = uri.indexOf(BASE64_MARKER);
        if (marker < 0) {
            return "";
        }
        return uri.substring(DATA_URI_PREFIX.length(), marker);
    }

    /**
     * Copy the bytes one buffer view spans.
     *
     * @param viewIndex index into the document's buffer-view array
     * @return a fresh array holding the view's bytes, empty when the index is out of range
     * @throws IOException if the underlying buffer cannot be resolved
     */
    private byte[] bufferViewBytes(int viewIndex) throws IOException {
        if (viewIndex < 0) {
            return new byte[0];
        }
        JsonValue view = bufferViews.item(viewIndex);
        byte[] bytes = buffer(view.getInt(BUFFER, 0));
        int offset = bufferBase(view.getInt(BUFFER, 0)) + view.getInt(BYTE_OFFSET, 0);
        int length = view.getInt("byteLength", 0);
        byte[] slice = new byte[length];
        System.arraycopy(bytes, offset, slice, 0, length);
        return slice;
    }

    /**
     * Fill the model's material arrays with the metallic-roughness factors and texture references.
     *
     * @param model model being assembled
     */
    private void readMaterials(GltfModel model) {
        JsonValue declared = document.get("materials");
        int count = declared.size();
        model.materialName = new String[count];
        model.baseColorFactor = new float[count * GltfModel.COLOR_COMPONENTS];
        model.emissiveFactor = new float[count * GltfModel.EMISSIVE_COMPONENTS];
        model.metallicFactor = new double[count];
        model.roughnessFactor = new double[count];
        model.baseColorTexture = new int[count];
        model.metallicRoughnessTexture = new int[count];
        model.normalTexture = new int[count];
        model.occlusionTexture = new int[count];
        model.emissiveTexture = new int[count];
        for (int slot = 0; slot < count; slot++) {
            JsonValue material = declared.item(slot);
            JsonValue pbr = material.get("pbrMetallicRoughness");
            JsonValue baseColorFactor = pbr.get("baseColorFactor");
            for (int channel = 0; channel < GltfModel.COLOR_COMPONENTS; channel++) {
                model.baseColorFactor[slot * GltfModel.COLOR_COMPONENTS + channel] =
                        component(baseColorFactor, channel, 1f);
            }
            JsonValue emissiveFactor = material.get("emissiveFactor");
            for (int channel = 0; channel < GltfModel.EMISSIVE_COMPONENTS; channel++) {
                model.emissiveFactor[slot * GltfModel.EMISSIVE_COMPONENTS + channel] =
                        component(emissiveFactor, channel, 0f);
            }
            model.materialName[slot] = material.getString(NAME, "");
            model.metallicFactor[slot] = pbr.getDouble("metallicFactor", 1.0);
            model.roughnessFactor[slot] = pbr.getDouble("roughnessFactor", 1.0);
            model.baseColorTexture[slot] = textureReference(pbr.get("baseColorTexture"));
            model.metallicRoughnessTexture[slot] =
                    textureReference(pbr.get("metallicRoughnessTexture"));
            model.normalTexture[slot] = textureReference(material.get("normalTexture"));
            model.occlusionTexture[slot] = textureReference(material.get("occlusionTexture"));
            model.emissiveTexture[slot] = textureReference(material.get("emissiveTexture"));
        }
    }

    /**
     * The texture index a material's texture reference points at.
     *
     * @param reference the reference's JSON, possibly absent
     * @return the texture index, or {@link GltfModel#NO_TEXTURE}
     */
    private static int textureReference(JsonValue reference) {
        return reference.getInt(INDEX, GltfModel.NO_TEXTURE);
    }

    /**
     * The image each texture samples.
     *
     * @return image index per texture, {@code -1} where the texture names no source
     */
    private int[] readTextureImages() {
        JsonValue declared = document.get("textures");
        int[] sources = new int[declared.size()];
        for (int slot = 0; slot < sources.length; slot++) {
            sources[slot] = declared.item(slot).getInt("source", -1);
        }
        return sources;
    }
}
