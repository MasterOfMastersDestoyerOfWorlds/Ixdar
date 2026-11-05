package ixdar.geometry.mesh.data.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.CornerUvField;
import ixdar.geometry.mesh.data.bundle.MaterialData;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.InvalidMeshTopologyException;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Loads OBJ, PLY, OFF and glTF mesh files into a {@link HalfEdgeMesh}, or an {@link ArrayMesh} when
 * the file's faces have no half-edge form.
 * Supports vertex positions and optional normals; glTF also carries texture coordinates.
 */
public final class MeshLoader {
    public static final String COMMENT_PREFIX = "#";
    public static final String S = "\\s+";
    public static final String END_HEADER = "end_header";
    public static final String ELEMENT_VERTEX = "element vertex";
    public static final String OFF_HEADER = "OFF";

    public static final int FLOATS_PER_VERTEX = 3;

    public static final String MODULE_DIRECTORY = "ixdar-app";

    public static final String RESOURCE_DIRECTORY = "src/main/resources";

    public static final String POM_FILE = "pom.xml";

    public static final String WORKING_DIRECTORY_PROPERTY = "user.dir";

    public static final String DATA_ROOT_VARIABLE = "IXDAR_DATA_ROOT";
    public static final String DATA_ROOT_PROPERTY = "ixdar.data.root";
    public static final String DATA_ROOT_PLACEHOLDER = "${" + DATA_ROOT_VARIABLE + "}";

    public static final String OBJ_EXTENSION = ".obj";
    public static final String PLY_EXTENSION = ".ply";
    public static final String OFF_EXTENSION = ".off";
    public static final String GLB_EXTENSION = ".glb";
    public static final String GLTF_EXTENSION = ".gltf";

    public static final List<String> MESH_EXTENSIONS = List.of(
            OBJ_EXTENSION, PLY_EXTENSION, OFF_EXTENSION, GLB_EXTENSION, GLTF_EXTENSION);

    private MeshLoader() {
    }

    /**
     * Load a mesh from an OBJ, PLY, OFF or glTF file. Auto-detects format by extension.
     *
     * @param path File path, absolute or relative to either the working directory or
     *             {@link #MODULE_DIRECTORY}
     * @throws IOException              if file cannot be read or parsed
     * @throws IllegalArgumentException if {@code path} is null/empty or has an
     *                                  unsupported extension
     * @return the loaded geometry, as {@link #indexedMesh} builds it
     */
    public static MeshTopology load(String path) throws IOException {
        String lower = path == null ? "" : path.toLowerCase();
        if (isGltf(lower)) {
            return loadGltfGeometry(resolve(path)).mesh();
        }
        return loadBundle(path).mesh();
    }

    /**
     * The mesh a file's flat arrays describe: a {@link HalfEdgeMesh} keeping the file's vertex and
     * face order, or an {@link ArrayMesh} over the arrays when the faces have no half-edge form.
     *
     * @param positions    packed xyz triples
     * @param normals      packed vertex normals matching {@code positions}, or {@code null} to compute
     *                     them from the faces
     * @param faceIndices  vertex indices grouped by {@code vertsPerFace}
     * @param vertsPerFace corners per face
     * @return the half-edge mesh, else the indexed mesh of a non-manifold or degenerate file
     */
    public static MeshTopology indexedMesh(float[] positions, float[] normals, int[] faceIndices,
            int vertsPerFace) {
        try {
            HalfEdgeMesh mesh = HalfEdgeMeshOps.bulkAllocate(positions, faceIndices, vertsPerFace);
            mesh.computeNormals();
            if (normals != null) {
                System.arraycopy(normals, 0, mesh.vertexNormals, 0, Math.min(normals.length,
                        mesh.vertexNormals.length));
            }
            return mesh;
        } catch (InvalidMeshTopologyException noHalfEdgeForm) {
            ArrayMesh mesh = new ArrayMesh(positions, normals, faceIndices, vertsPerFace);
            if (normals == null) {
                mesh.computeNormals();
            }
            return mesh;
        }
    }

    /**
     * Load a mesh file together with the attributes the format carries: glTF texture coordinates
     * ride {@link CornerUvField#SLOT} per face corner and the PBR material rides
     * {@link MaterialData#SLOT}; the text formats contribute no slots.
     *
     * @param path File path, absolute or relative to either the working directory or
     *             {@link #MODULE_DIRECTORY}
     * @throws IOException              if file cannot be read or parsed
     * @throws IllegalArgumentException if {@code path} is null/empty or has an
     *                                  unsupported extension
     * @return bundle wrapping the loaded mesh
     */
    public static GeometryBundle loadBundle(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("File path cannot be empty");
        }
        if (!isSupported(path)) {
            throw new IllegalArgumentException(
                    "Unsupported file format: " + path + ". Only " + String.join(", ", MESH_EXTENSIONS)
                            + " are supported");
        }
        String lower = path.toLowerCase();
        Path file = resolve(path);
        if (isGltf(lower)) {
            return loadGltf(file);
        }
        byte[] bytes = Files.readAllBytes(file);
        if (lower.endsWith(OBJ_EXTENSION)) {
            return GeometryBundle.ofMesh(
                    ObjMeshParser.load(new String(bytes, StandardCharsets.UTF_8)));
        } else if (lower.endsWith(PLY_EXTENSION)) {
            return GeometryBundle.ofMesh(
                    PlyMeshParser.load(new String(bytes, StandardCharsets.UTF_8)));
        }
        return GeometryBundle.ofMesh(OffMeshParser.load(bytes));
    }

    /**
     * Resolve a path as every file the program reads or writes does: against the checkout, then
     * under {@link #MODULE_DIRECTORY}, then under its {@link #RESOURCE_DIRECTORY}, so
     * {@code dsl/skull/base.ply} works; a {@link #DATA_ROOT_PLACEHOLDER} path resolves below
     * {@link #dataRoot()}. A file that exists nowhere resolves in the checkout, so there is one
     * {@code tmp/}.
     *
     * @param path file name or path as the caller wrote it
     * @return the data-root path, else the first candidate that exists, else the checkout one, absolute
     */
    public static Path resolve(String path) {
        if (path.startsWith(DATA_ROOT_PLACEHOLDER)) {
            String below = path.substring(DATA_ROOT_PLACEHOLDER.length()).replaceFirst("^[/\\\\]+", "");
            return dataRoot().resolve(below);
        }
        Path checkout = checkoutRoot();
        Path module = checkout.resolve(MODULE_DIRECTORY);
        Path file = checkout.resolve(path);
        for (Path candidate : new Path[] { file, module.resolve(path), module.resolve(RESOURCE_DIRECTORY).resolve(path) }) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return file;
    }

    /**
     * The checkout the program runs in: the nearest ancestor of the working directory (the module,
     * when launched as F5 and the CLI launch it) holding a {@value #POM_FILE} whose
     * {@link #MODULE_DIRECTORY} holds its own, else the working directory.
     *
     * @return absolute path to the checkout root
     */
    public static Path checkoutRoot() {
        Path workingDirectory = Paths.get(System.getProperty(WORKING_DIRECTORY_PROPERTY, ".")).toAbsolutePath()
                .normalize();
        for (Path candidate = workingDirectory; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve(POM_FILE))
                    && Files.isRegularFile(candidate.resolve(MODULE_DIRECTORY).resolve(POM_FILE))) {
                return candidate;
            }
        }
        return workingDirectory;
    }

    /**
     * This machine's data root, whose folders hold data kept out of the repositories (the crawfish
     * scans live in {@code crawfish/}): the {@link #DATA_ROOT_PROPERTY} property, else the
     * {@link #DATA_ROOT_VARIABLE} variable, else the user's home directory.
     *
     * @return the data root, which need not exist
     */
    public static Path dataRoot() {
        String property = System.getProperty(DATA_ROOT_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Path.of(property);
        }
        String variable = System.getenv(DATA_ROOT_VARIABLE);
        if (variable != null && !variable.isBlank()) {
            return Path.of(variable);
        }
        return Path.of(System.getProperty("user.home"));
    }

    /**
     * Whether {@code path} names a file {@link #load} can read, judged by extension alone.
     *
     * @param path file name or path, any case
     * @return {@code true} when the extension is one of {@link #MESH_EXTENSIONS}
     */
    public static boolean isSupported(String path) {
        String lower = path.toLowerCase();
        for (String extension : MESH_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code lowerCasePath} names a glTF container, binary or JSON.
     *
     * @param lowerCasePath already lower-cased file name or path
     * @return {@code true} for {@code .glb} and {@code .gltf}
     */
    public static boolean isGltf(String lowerCasePath) {
        return lowerCasePath.endsWith(GLB_EXTENSION) || lowerCasePath.endsWith(GLTF_EXTENSION);
    }

    /**
     * Read a glTF container through {@link GltfMeshParser}, adding the material slot. The parser is
     * plain Java over the platform's JSON reader, so this path works in the browser build too.
     *
     * @param file resolved path of the glTF file
     * @throws IOException if the file is missing or the parser rejects it
     * @return the parsed bundle carrying the file's UV and material slots
     */
    private static GeometryBundle loadGltf(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("No such glTF file: " + file);
        }
        return GltfMaterialReader.loadBundle(file.toString());
    }

    /**
     * Read a glTF container's geometry alone, leaving its images unread. {@link #load} takes this
     * path so a scan's textures are not decoded for a caller that only wants the mesh.
     *
     * @param file resolved path of the glTF file
     * @throws IOException if the file is missing or the parser rejects it
     * @return the parser's geometry-only bundle
     */
    private static GeometryBundle loadGltfGeometry(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("No such glTF file: " + file);
        }
        return GltfMeshParser.load(file.toString());
    }
}
