package ixdar.geometry.mesh.data.load;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;

public final class ObjMeshParser {
    /**
     * Load an OBJ file into ArrayMesh.
     * Supports vertex positions (v x y z) and normals (vn x y z).
     * Generates face normals if not provided.
     *
     * @param content raw OBJ content
     * @throws RuntimeException wraps any {@link IOException} from the underlying reader
     * @return parsed mesh (empty if the file has no positions)
     */
    public static ArrayMesh load(String content) {

        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();

        try (BufferedReader stringReader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = stringReader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(MeshLoader.STR)) {
                    continue;
                }

                String[] parts = line.split(MeshLoader.S);
                if (parts.length == 0) {
                    continue;
                }

                String type = parts[0];

                if ("v".equals(type) && parts.length >= MeshLoader.NUM_4) {
                    float x = Float.parseFloat(parts[1]);
                    float y = Float.parseFloat(parts[2]);
                    float z = Float.parseFloat(parts[MeshLoader.FLOATS_PER_VERTEX]);
                    positions.add(x);
                    positions.add(y);
                    positions.add(z);
                    normals.add(MeshLoader.NUM_0);
                    normals.add(MeshLoader.NUM_0);
                    normals.add(MeshLoader.NUM_0);
                } else if ("vn".equals(type) && parts.length >= MeshLoader.NUM_4) {
                    float x = Float.parseFloat(parts[1]);
                    float y = Float.parseFloat(parts[2]);
                    float z = Float.parseFloat(parts[MeshLoader.FLOATS_PER_VERTEX]);
                    if (normals.size() / MeshLoader.FLOATS_PER_VERTEX < positions.size()
                            / MeshLoader.FLOATS_PER_VERTEX) {
                        normals.add(x);
                        normals.add(y);
                        normals.add(z);
                    }
                } else if ("f".equals(type) && parts.length >= MeshLoader.NUM_4) {
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
            throw new RuntimeException(e);
        }

        if (positions.isEmpty()) {
            return ArrayMeshEngine.emptyQuads();
        }

        // Convert face data to flat arrays — count triangulated indices
        int totalTriIndices = 0;
        for (int[] face : faces) {
            if (face.length >= MeshLoader.FLOATS_PER_VERTEX) {
                totalTriIndices += (face.length - 2) * MeshLoader.FLOATS_PER_VERTEX;
            }
        }

        float[] posArray = new float[positions.size()];
        float[] normArray = new float[positions.size()];
        for (int i = 0; i < positions.size(); i++)
            posArray[i] = positions.get(i);
        for (int i = 0; i < Math.min(normals.size(), normArray.length); i++)
            normArray[i] = normals.get(i);

        // Generate face normals if vertex normals are zero
        boolean hasVertexNormals = false;
        for (int i = 0; i < normals.size(); i++) {
            if (normals.get(i) != MeshLoader.NUM_0) {
                hasVertexNormals = true;
                break;
            }
        }

        if (!hasVertexNormals) {
            // Compute face normals and assign to vertices
            int vpf = faces.size() > 0 ? faces.get(0).length : MeshLoader.NUM_4;
            Vector3f e1 = new Vector3f();
            Vector3f e2 = new Vector3f();
            Vector3f fn = new Vector3f();

            for (int[] face : faces) {
                if (face.length < MeshLoader.FLOATS_PER_VERTEX) {
                    continue;
                }
                // Triangulate n-gons
                for (int i = 1; i < face.length - 1; i++) {
                    int v0 = face[0];
                    int v1 = face[i];
                    int v2 = face[i + 1];
                    int p0o = v0 * MeshLoader.FLOATS_PER_VERTEX;
                    int p1o = v1 * MeshLoader.FLOATS_PER_VERTEX;
                    int p2o = v2 * MeshLoader.FLOATS_PER_VERTEX;
                    e1.set(posArray[p1o], posArray[p1o + 1], posArray[p1o + 2])
                            .sub(posArray[p0o], posArray[p0o + 1], posArray[p0o + 2]);
                    e2.set(posArray[p2o], posArray[p2o + 1], posArray[p2o + 2])
                            .sub(posArray[p0o], posArray[p0o + 1], posArray[p0o + 2]);
                    e1.cross(e2, fn);
                    float len = fn.length();
                    if (len > MeshLoader.NUM_1e_20) {
                        fn.mul(1.0f / len);
                    }
                    // Assign to all three vertices
                    for (int v : new int[] { v0, v1, v2 }) {
                        int vo = v * MeshLoader.FLOATS_PER_VERTEX;
                        normArray[vo] += fn.x;
                        normArray[vo + 1] += fn.y;
                        normArray[vo + 2] += fn.z;
                    }
                }
            }

            // Normalize vertex normals
            for (int i = 0; i < normArray.length; i += MeshLoader.FLOATS_PER_VERTEX) {
                float nx = normArray[i];
                float ny = normArray[i + 1];
                float nz = normArray[i + 2];
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > MeshLoader.NUM_1e_20) {
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
            if (face.length < MeshLoader.FLOATS_PER_VERTEX) {
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
        int vertsPerFace = MeshLoader.FLOATS_PER_VERTEX;

        return new ArrayMesh(posArray, normArray, faceIndices, vertsPerFace);
    }
}
