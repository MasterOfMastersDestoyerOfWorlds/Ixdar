package ixdar.geometry.mesh.data;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

/**
 * Loads OBJ and PLY mesh files into ArrayMesh format.
 * Supports vertex positions and optional normals.
 */
public final class MeshLoader {

    private static final int FLOATS_PER_VERTEX = 3;

    private MeshLoader() {
    }

    /**
     * Load a mesh from OBJ or PLY file. Auto-detects format by extension.
     *
     * @param path File path to OBJ or PLY file
     * @return ArrayMesh containing the loaded geometry
     * @throws IOException if file cannot be read or parsed
     */
    public static ArrayMesh load(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("File path cannot be empty");
        }
        String lower = path.toLowerCase();
        if (lower.endsWith(".obj")) {
            return loadObj(path);
        } else if (lower.endsWith(".ply")) {
            return loadPly(path);
        } else {
            throw new IllegalArgumentException("Unsupported file format: " + path + ". Only .obj and .ply are supported");
        }
    }

    /**
     * Load an OBJ file into ArrayMesh.
     * Supports vertex positions (v x y z) and normals (vn x y z).
     * Generates face normals if not provided.
     */
    private static ArrayMesh loadObj(String path) throws IOException {
        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\s+");
                if (parts.length == 0) {
                    continue;
                }

                String type = parts[0];

                if ("v".equals(type) && parts.length >= 4) {
                    float x = Float.parseFloat(parts[1]);
                    float y = Float.parseFloat(parts[2]);
                    float z = Float.parseFloat(parts[3]);
                    positions.add(x);
                    positions.add(y);
                    positions.add(z);
                    normals.add(0f);
                    normals.add(0f);
                    normals.add(0f);
                } else if ("vn".equals(type) && parts.length >= 4) {
                    float x = Float.parseFloat(parts[1]);
                    float y = Float.parseFloat(parts[2]);
                    float z = Float.parseFloat(parts[3]);
                    // Store normals in a separate list for later assignment
                    // We'll map them to faces after parsing
                    if (normals.size() / 3 < positions.size() / 3) {
                        normals.add(x);
                        normals.add(y);
                        normals.add(z);
                    }
                } else if ("f".equals(type) && parts.length >= 4) {
                    int[] face = new int[parts.length - 1];
                    for (int i = 1; i < parts.length; i++) {
                        String[] vertexParts = parts[i].split("/");
                        int vertexIdx = Integer.parseInt(vertexParts[0]) - 1;
                        face[i - 1] = vertexIdx;
                    }
                    faces.add(face);
                }
            }
        }

        if (positions.isEmpty()) {
            return ArrayMeshEngine.emptyQuads();
        }

        // Convert face data to flat arrays — count triangulated indices
        int totalTriIndices = 0;
        for (int[] face : faces) {
            if (face.length >= 3) {
                totalTriIndices += (face.length - 2) * 3;
            }
        }

        float[] posArray = new float[positions.size()];
        float[] normArray = new float[positions.size()];
        for (int i = 0; i < positions.size(); i++) posArray[i] = positions.get(i);
        for (int i = 0; i < Math.min(normals.size(), normArray.length); i++) normArray[i] = normals.get(i);

        // Generate face normals if vertex normals are zero
        boolean hasVertexNormals = false;
        for (int i = 0; i < normals.size(); i++) {
            if (normals.get(i) != 0f) {
                hasVertexNormals = true;
                break;
            }
        }

        if (!hasVertexNormals) {
            // Compute face normals and assign to vertices
            int vpf = faces.size() > 0 ? faces.get(0).length : 4;
            float[] faceNormals = new float[(faces.size() * vpf) / 3 * 3];
            int fnIdx = 0;
            Vector3f e1 = new Vector3f();
            Vector3f e2 = new Vector3f();
            Vector3f fn = new Vector3f();

            for (int[] face : faces) {
                if (face.length < 3) {
                    continue;
                }
                // Triangulate n-gons
                for (int i = 1; i < face.length - 1; i++) {
                    int v0 = face[0];
                    int v1 = face[i];
                    int v2 = face[i + 1];
                    int p0o = v0 * 3;
                    int p1o = v1 * 3;
                    int p2o = v2 * 3;
                    e1.set(posArray[p1o], posArray[p1o + 1], posArray[p1o + 2])
                            .sub(posArray[p0o], posArray[p0o + 1], posArray[p0o + 2]);
                    e2.set(posArray[p2o], posArray[p2o + 1], posArray[p2o + 2])
                            .sub(posArray[p0o], posArray[p0o + 1], posArray[p0o + 2]);
                    e1.cross(e2, fn);
                    float len = fn.length();
                    if (len > 1e-20f) {
                        fn.mul(1.0f / len);
                    }
                    // Assign to all three vertices
                    for (int v : new int[]{v0, v1, v2}) {
                        int vo = v * 3;
                        normArray[vo] += fn.x;
                        normArray[vo + 1] += fn.y;
                        normArray[vo + 2] += fn.z;
                    }
                    fnIdx += 3;
                }
            }

            // Normalize vertex normals
            for (int i = 0; i < normArray.length; i += 3) {
                float nx = normArray[i];
                float ny = normArray[i + 1];
                float nz = normArray[i + 2];
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1e-20f) {
                    normArray[i] = nx / len;
                    normArray[i + 1] = ny / len;
                    normArray[i + 2] = nz / len;
                }
            }
        }

        // Flatten faces to triangles (fan triangulation for n-gons)
        int[] faceIndices = new int[totalTriIndices];
        int fi = 0;
        for (int[] face : faces) {
            if (face.length < 3) {
                continue;
            }
            // Triangulate n-gons using fan
            for (int i = 1; i < face.length - 1; i++) {
                faceIndices[fi++] = face[0];
                faceIndices[fi++] = face[i];
                faceIndices[fi++] = face[i + 1];
            }
        }

        if (faceIndices.length == 0) {
            return ArrayMeshEngine.emptyQuads();
        }

        // Determine vertsPerFace (assume triangles)
        int vertsPerFace = 3;

        return new ArrayMesh(posArray, normArray, faceIndices, vertsPerFace);
    }

    /**
     * Load a PLY file into ArrayMesh.
     * Supports ASCII PLY format with vertex positions and optional normals.
     */
    private static ArrayMesh loadPly(String path) throws IOException {
        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            boolean readFaces = false;
            int vertexCount = 0;
            int faceVertexCount = 3; // default to triangles

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.equals("end_header")) {
                    readFaces = true;
                    continue;
                }

                if (!readFaces) {
                    // Parse header
                    if (line.startsWith("element vertex")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            vertexCount = Integer.parseInt(parts[1]);
                            // Pre-allocate normals
                            for (int i = 0; i < vertexCount * 3; i++) {
                                normals.add(0f);
                            }
                        }
                    } else if (line.startsWith("element face")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            int faceCount = Integer.parseInt(parts[1]);
                            // Pre-allocate faces list
                            for (int i = 0; i < faceCount; i++) {
                                faces.add(new int[0]);
                            }
                        }
                    } else if (line.startsWith("property float normal")) {
                        // Mark that we have normals
                        normals.clear();
                    }
                } else {
                    // Parse face data
                    String[] parts = line.split("\\s+");
                    if (parts.length > 0) {
                        int nVerts = Integer.parseInt(parts[0]);
                        int[] face = new int[nVerts];
                        for (int i = 0; i < nVerts; i++) {
                            face[i] = Integer.parseInt(parts[i + 1]);
                        }
                        faces.set(faces.size() - 1, face);
                    }
                }
            }
        }

        // Now read the file again to get vertex data
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            boolean readVertices = false;
            boolean readFaces = false;
            int vertexCount = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.equals("end_header")) {
                    readVertices = true;
                    continue;
                }

                if (!readVertices) {
                    if (line.startsWith("element vertex")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            vertexCount = Integer.parseInt(parts[1]);
                        }
                    }
                } else if (!readFaces) {
                    // Parse vertex data
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        float x = Float.parseFloat(parts[0]);
                        float y = Float.parseFloat(parts[1]);
                        float z = Float.parseFloat(parts[2]);
                        positions.add(x);
                        positions.add(y);
                        positions.add(z);

                        // Check for normals
                        if (parts.length >= 6) {
                            normals.set((positions.size() / 3 - 1) * 3, Float.parseFloat(parts[3]));
                            normals.set((positions.size() / 3 - 1) * 3 + 1, Float.parseFloat(parts[4]));
                            normals.set((positions.size() / 3 - 1) * 3 + 2, Float.parseFloat(parts[5]));
                        }
                    }
                } else {
                    // Parse face data
                    String[] parts = line.split("\\s+");
                    if (parts.length > 0) {
                        int nVerts = Integer.parseInt(parts[0]);
                        int[] face = new int[nVerts];
                        for (int i = 0; i < nVerts; i++) {
                            face[i] = Integer.parseInt(parts[i + 1]);
                        }
                        faces.add(face);
                    }
                }
            }
        }

        if (positions.isEmpty()) {
            return ArrayMeshEngine.emptyQuads();
        }

        // Flatten face data to triangles
        int totalVertices = 0;
        for (int[] face : faces) {
            totalVertices += face.length;
        }

        float[] posArray = new float[positions.size()];
        float[] normArray = new float[positions.size()];
        for (int i = 0; i < positions.size(); i++) posArray[i] = positions.get(i);
        for (int i = 0; i < Math.min(normals.size(), normArray.length); i++) normArray[i] = normals.get(i);

        // Determine face vertex count
        int vertsPerFace = 3; // default to triangles
        if (!faces.isEmpty()) {
            vertsPerFace = faces.get(0).length;
        }

        int[] faceIndices = new int[totalVertices];
        int fi = 0;
        for (int[] face : faces) {
            if (face.length < 3) {
                continue;
            }
            // Triangulate n-gons using fan
            for (int i = 1; i < face.length - 1; i++) {
                faceIndices[fi++] = face[0];
                faceIndices[fi++] = face[i];
                faceIndices[fi++] = face[i + 1];
            }
        }

        if (faceIndices.length == 0) {
            return ArrayMeshEngine.emptyQuads();
        }

        return new ArrayMesh(posArray, normArray, faceIndices, vertsPerFace);
    }
}
