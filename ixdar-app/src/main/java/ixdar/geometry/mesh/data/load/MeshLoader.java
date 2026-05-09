package ixdar.geometry.mesh.data.load;

import java.io.IOException;
import java.nio.file.Files;
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

    private MeshLoader() {
    }

    /**
     * Load a mesh from OBJ or PLY file. Auto-detects format by extension.
     *
     * @param path File path to OBJ or PLY file
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
        String content = Files.readString(Paths.get(path));
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
