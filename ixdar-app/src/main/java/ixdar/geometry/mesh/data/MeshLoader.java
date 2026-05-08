package ixdar.geometry.mesh.data;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

/**
 * Loads OBJ and PLY mesh files into ArrayMesh format.
 * Supports vertex positions and optional normals.
 */
public final class MeshLoader {
    public static final String STR = "#";
    public static final String S = "\\s+";
    public static final String END_HEADER = "end_header";
    public static final String ELEMENT_VERTEX = "element vertex";
    public static final int NUM_4 = 4;
    public static final float NUM_0 = 0f;
    public static final float NUM_1e_20 = 1e-20f;
    public static final int NUM_6 = 6;
    public static final int NUM_5 = 5;

    private static final int FLOATS_PER_VERTEX = 3;

    private MeshLoader() {
    }

    /**
     * Load a mesh from OBJ or PLY file. Auto-detects format by extension.
     *
     * @param path File path to OBJ or PLY file
     * @throws IOException if file cannot be read or parsed
     * @throws IllegalArgumentException if {@code path} is null/empty or has an unsupported extension
     * @return ArrayMesh containing the loaded geometry
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
     *
     * @param path filesystem path to the OBJ
     * @throws IOException if reading the file fails
     * @return parsed mesh (empty if the file has no positions)
     */
    private static ArrayMesh loadObj(String path) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return parseObj(sb.toString());
        }
    }

    /**
     * Parse OBJ text content into ArrayMesh (no filesystem access).
     * Works in TeaVM/browser where FileReader is unavailable.
     *
     * @param content raw OBJ text
     * @throws RuntimeException if the embedded reader unexpectedly throws (should not happen for {@link StringReader})
     * @return parsed mesh; n-gon faces are fan-triangulated, missing vertex normals are synthesized from face normals
     */
    public static ArrayMesh parseObj(String content) {
        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(STR)) {
                    continue;
                }

                String[] parts = line.split(S);
                if (parts.length == 0) {
                    continue;
                }

                String type = parts[0];

                if ("v".equals(type) && parts.length >= NUM_4) {
                    float x = Float.parseFloat(parts[1]);
                    float y = Float.parseFloat(parts[2]);
                    float z = Float.parseFloat(parts[FLOATS_PER_VERTEX]);
                    positions.add(x);
                    positions.add(y);
                    positions.add(z);
                    normals.add(NUM_0);
                    normals.add(NUM_0);
                    normals.add(NUM_0);
                } else if ("vn".equals(type) && parts.length >= NUM_4) {
                    float x = Float.parseFloat(parts[1]);
                    float y = Float.parseFloat(parts[2]);
                    float z = Float.parseFloat(parts[FLOATS_PER_VERTEX]);
                    if (normals.size() / FLOATS_PER_VERTEX < positions.size() / FLOATS_PER_VERTEX) {
                        normals.add(x);
                        normals.add(y);
                        normals.add(z);
                    }
                } else if ("f".equals(type) && parts.length >= NUM_4) {
                    int[] face = new int[parts.length - 1];
                    for (int i = 1; i < parts.length; i++) {
                        String[] vertexParts = parts[i].split("/");
                        int vertexIdx = Integer.parseInt(vertexParts[0]) - 1;
                        face[i - 1] = vertexIdx;
                    }
                    faces.add(face);
                }
            }
        } catch (IOException e) {
            // StringReader.readLine() won't throw in practice
            throw new RuntimeException(e);
        }

        if (positions.isEmpty()) {
            return ArrayMeshEngine.emptyQuads();
        }

        // Convert face data to flat arrays — count triangulated indices
        int totalTriIndices = 0;
        for (int[] face : faces) {
            if (face.length >= FLOATS_PER_VERTEX) {
                totalTriIndices += (face.length - 2) * FLOATS_PER_VERTEX;
            }
        }

        float[] posArray = new float[positions.size()];
        float[] normArray = new float[positions.size()];
        for (int i = 0; i < positions.size(); i++) posArray[i] = positions.get(i);
        for (int i = 0; i < Math.min(normals.size(), normArray.length); i++) normArray[i] = normals.get(i);

        // Generate face normals if vertex normals are zero
        boolean hasVertexNormals = false;
        for (int i = 0; i < normals.size(); i++) {
            if (normals.get(i) != NUM_0) {
                hasVertexNormals = true;
                break;
            }
        }

        if (!hasVertexNormals) {
            // Compute face normals and assign to vertices
            int vpf = faces.size() > 0 ? faces.get(0).length : NUM_4;
            float[] faceNormals = new float[(faces.size() * vpf) / FLOATS_PER_VERTEX * FLOATS_PER_VERTEX];
            int fnIdx = 0;
            Vector3f e1 = new Vector3f();
            Vector3f e2 = new Vector3f();
            Vector3f fn = new Vector3f();

            for (int[] face : faces) {
                if (face.length < FLOATS_PER_VERTEX) {
                    continue;
                }
                // Triangulate n-gons
                for (int i = 1; i < face.length - 1; i++) {
                    int v0 = face[0];
                    int v1 = face[i];
                    int v2 = face[i + 1];
                    int p0o = v0 * FLOATS_PER_VERTEX;
                    int p1o = v1 * FLOATS_PER_VERTEX;
                    int p2o = v2 * FLOATS_PER_VERTEX;
                    e1.set(posArray[p1o], posArray[p1o + 1], posArray[p1o + 2])
                            .sub(posArray[p0o], posArray[p0o + 1], posArray[p0o + 2]);
                    e2.set(posArray[p2o], posArray[p2o + 1], posArray[p2o + 2])
                            .sub(posArray[p0o], posArray[p0o + 1], posArray[p0o + 2]);
                    e1.cross(e2, fn);
                    float len = fn.length();
                    if (len > NUM_1e_20) {
                        fn.mul(1.0f / len);
                    }
                    // Assign to all three vertices
                    for (int v : new int[]{v0, v1, v2}) {
                        int vo = v * FLOATS_PER_VERTEX;
                        normArray[vo] += fn.x;
                        normArray[vo + 1] += fn.y;
                        normArray[vo + 2] += fn.z;
                    }
                    fnIdx += FLOATS_PER_VERTEX;
                }
            }

            // Normalize vertex normals
            for (int i = 0; i < normArray.length; i += FLOATS_PER_VERTEX) {
                float nx = normArray[i];
                float ny = normArray[i + 1];
                float nz = normArray[i + 2];
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > NUM_1e_20) {
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
            if (face.length < FLOATS_PER_VERTEX) {
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
        int vertsPerFace = FLOATS_PER_VERTEX;

        return new ArrayMesh(posArray, normArray, faceIndices, vertsPerFace);
    }

    /**
     * Load a PLY file into ArrayMesh.
     * Supports ASCII PLY format with vertex positions and optional normals.
     *
     * @param path filesystem path to the PLY
     * @throws IOException if reading the file fails
     * @return parsed mesh (empty if the file has no positions); n-gon faces are fan-triangulated
     */
    private static ArrayMesh loadPly(String path) throws IOException {
        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            boolean readFaces = false;
            int vertexCount = 0;
            int faceVertexCount = FLOATS_PER_VERTEX; // default to triangles

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(STR)) {
                    continue;
                }

                if (line.equals(END_HEADER)) {
                    readFaces = true;
                    continue;
                }

                if (!readFaces) {
                    // Parse header
                    if (line.startsWith(ELEMENT_VERTEX)) {
                        String[] parts = line.split(S);
                        if (parts.length >= 2) {
                            vertexCount = Integer.parseInt(parts[1]);
                            // Pre-allocate normals
                            for (int i = 0; i < vertexCount * FLOATS_PER_VERTEX; i++) {
                                normals.add(NUM_0);
                            }
                        }
                    } else if (line.startsWith("element face")) {
                        String[] parts = line.split(S);
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
                    String[] parts = line.split(S);
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
                if (line.isEmpty() || line.startsWith(STR)) {
                    continue;
                }

                if (line.equals(END_HEADER)) {
                    readVertices = true;
                    continue;
                }

                if (!readVertices) {
                    if (line.startsWith(ELEMENT_VERTEX)) {
                        String[] parts = line.split(S);
                        if (parts.length >= 2) {
                            vertexCount = Integer.parseInt(parts[1]);
                        }
                    }
                } else if (!readFaces) {
                    // Parse vertex data
                    String[] parts = line.split(S);
                    if (parts.length >= FLOATS_PER_VERTEX) {
                        float x = Float.parseFloat(parts[0]);
                        float y = Float.parseFloat(parts[1]);
                        float z = Float.parseFloat(parts[2]);
                        positions.add(x);
                        positions.add(y);
                        positions.add(z);

                        // Check for normals
                        if (parts.length >= NUM_6) {
                            normals.set((positions.size() / FLOATS_PER_VERTEX - 1) * FLOATS_PER_VERTEX, Float.parseFloat(parts[FLOATS_PER_VERTEX]));
                            normals.set((positions.size() / FLOATS_PER_VERTEX - 1) * FLOATS_PER_VERTEX + 1, Float.parseFloat(parts[NUM_4]));
                            normals.set((positions.size() / FLOATS_PER_VERTEX - 1) * FLOATS_PER_VERTEX + 2, Float.parseFloat(parts[NUM_5]));
                        }
                    }
                } else {
                    // Parse face data
                    String[] parts = line.split(S);
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
        int vertsPerFace = FLOATS_PER_VERTEX; // default to triangles
        if (!faces.isEmpty()) {
            vertsPerFace = faces.get(0).length;
        }

        int[] faceIndices = new int[totalVertices];
        int fi = 0;
        for (int[] face : faces) {
            if (face.length < FLOATS_PER_VERTEX) {
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
