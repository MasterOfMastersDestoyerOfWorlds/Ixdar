package ixdar.geometry.mesh.data.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * Loads OBJ and PLY mesh files into ArrayMesh format.
 * Supports vertex positions and optional normals.
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

    private MeshLoader() {
    }

    /**
     * Load a mesh from OBJ, PLY or OFF file. Auto-detects format by extension.
     *
     * @param path File path, absolute or relative to either the working directory or
     *             {@link #MODULE_DIRECTORY}
     * @throws IOException              if file cannot be read or parsed
     * @throws IllegalArgumentException if {@code path} is null/empty or has an
     *                                  unsupported extension
     * @return ArrayMesh containing the loaded geometry
     */
    public static ArrayMesh load(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("File path cannot be empty");
        }
        String lower = path.toLowerCase();
        Path file = Paths.get(path);
        if (!Files.exists(file) && Files.exists(Paths.get(MODULE_DIRECTORY, path))) {
            file = Paths.get(MODULE_DIRECTORY, path);
        }
        String content = Files.readString(file);
        if (lower.endsWith(".obj")) {
            return ObjMeshParser.load(content);
        } else if (lower.endsWith(".ply")) {
            return PlyMeshParser.load(content);
        } else if (lower.endsWith(".off")) {
            return OffMeshParser.load(content);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported file format: " + path + ". Only .obj, .ply and .off are supported");
        }
    }
}
