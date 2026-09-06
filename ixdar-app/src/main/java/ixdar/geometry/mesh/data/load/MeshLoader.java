package ixdar.geometry.mesh.data.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.MaterialData;
import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * Loads OBJ, PLY, OFF and glTF mesh files into ArrayMesh format.
 * Supports vertex positions and optional normals; glTF also carries texture coordinates.
 */
public final class MeshLoader {
    public static final String STR = "#";
    public static final String S = "\\s+";
    public static final String END_HEADER = "end_header";
    public static final String ELEMENT_VERTEX = "element vertex";
    public static final String OFF_HEADER = "OFF";
    public static final int NUM_4 = 4;
    public static final float NUM_0 = 0f;
    public static final float NUM_1e_20 = 1e-20f;
    public static final int NUM_6 = 6;
    public static final int NUM_5 = 5;

    public static final int FLOATS_PER_VERTEX = 3;

    /**
     * Module directory a relative path is retried against. Scene resource constants are written
     * relative to it because every {@code launch.json} entry runs with it as the working directory,
     * so a launcher that starts at the repository root instead would otherwise miss every default.
     */
    public static final String MODULE_DIRECTORY = "ixdar-app";

    public static final String OBJ_EXTENSION = ".obj";
    public static final String PLY_EXTENSION = ".ply";
    public static final String OFF_EXTENSION = ".off";
    public static final String GLB_EXTENSION = ".glb";
    public static final String GLTF_EXTENSION = ".gltf";

    /** Every extension {@link #load} accepts, lower case and dot-prefixed. */
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
     * @return ArrayMesh containing the loaded geometry
     */
    public static ArrayMesh load(String path) throws IOException {
        String lower = path == null ? "" : path.toLowerCase();
        if (isGltf(lower)) {
            return (ArrayMesh) loadGltfGeometry(resolve(path)).mesh();
        }
        return (ArrayMesh) loadBundle(path).mesh();
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
     * @return bundle wrapping the loaded {@link ArrayMesh}
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
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (lower.endsWith(OBJ_EXTENSION)) {
            return GeometryBundle.ofMesh(ObjMeshParser.load(content));
        } else if (lower.endsWith(PLY_EXTENSION)) {
            return GeometryBundle.ofMesh(PlyMeshParser.load(content));
        }
        return GeometryBundle.ofMesh(OffMeshParser.load(content));
    }

    /**
     * Resolve a path against the working directory, retrying under {@link #MODULE_DIRECTORY} so a
     * launcher started at the repository root still finds a scene's relative default.
     *
     * @param path file name or path as the caller wrote it
     * @return the path that exists, or the working-directory one when neither does
     */
    public static Path resolve(String path) {
        Path file = Paths.get(path);
        if (!Files.exists(file) && Files.exists(Paths.get(MODULE_DIRECTORY, path))) {
            return Paths.get(MODULE_DIRECTORY, path);
        }
        return file;
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
