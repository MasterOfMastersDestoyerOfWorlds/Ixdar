package ixdar.geometry.mesh.data.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangulationRule;

public final class ObjMeshParser {
    /**
     * Load an OBJ file into a triangle mesh ({@link MeshLoader#indexedMesh}).
     * Supports vertex positions (v x y z) and normals (vn x y z).
     * Generates face normals if not provided.
     *
     * @param content raw OBJ content
     * @throws RuntimeException wraps any {@link IOException} from the underlying reader
     * @return parsed mesh (empty if the file has no positions)
     */
    public static MeshTopology load(String content) {

        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();

        try (BufferedReader stringReader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = stringReader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(MeshLoader.COMMENT_PREFIX)) {
                    continue;
                }

                String[] parts = line.split(MeshLoader.S);
                if (parts.length == 0) {
                    continue;
                }

                String type = parts[0];

                if ("v".equals(type) && parts.length >= 4) {
                    float x = Float.parseFloat(parts[1]);
                    float y = Float.parseFloat(parts[2]);
                    float z = Float.parseFloat(parts[MeshLoader.FLOATS_PER_VERTEX]);
                    positions.add(x);
                    positions.add(y);
                    positions.add(z);
                    normals.add(0f);
                    normals.add(0f);
                    normals.add(0f);
                } else if ("vn".equals(type) && parts.length >= 4) {
                    float x = Float.parseFloat(parts[1]);
                    float y = Float.parseFloat(parts[2]);
                    float z = Float.parseFloat(parts[MeshLoader.FLOATS_PER_VERTEX]);
                    if (normals.size() / MeshLoader.FLOATS_PER_VERTEX < positions.size()
                            / MeshLoader.FLOATS_PER_VERTEX) {
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (positions.isEmpty()) {
            return new HalfEdgeMesh();
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
            if (normals.get(i) != 0f) {
                hasVertexNormals = true;
                break;
            }
        }

        // Fan every face into triangles.
        int[] faceIndices = new int[totalTriIndices];
        int[] fan = new int[0];
        int fi = 0;
        for (int[] face : faces) {
            if (fan.length < MeshLoader.FLOATS_PER_VERTEX * face.length) {
                fan = new int[MeshLoader.FLOATS_PER_VERTEX * face.length];
            }
            int triangles = MeshTopology.triangleCorners(TriangulationRule.FAN, face.length, true,
                    fan);
            for (int slot = 0; slot < MeshLoader.FLOATS_PER_VERTEX * triangles; slot++) {
                faceIndices[fi++] = face[fan[slot]];
            }
        }

        if (!hasVertexNormals) {
            // Compute face normals and assign to vertices
            Vector3f e1 = new Vector3f();
            Vector3f e2 = new Vector3f();
            Vector3f fn = new Vector3f();

            for (int corner = 0; corner < faceIndices.length;
                    corner += MeshLoader.FLOATS_PER_VERTEX) {
                int v0 = faceIndices[corner];
                int v1 = faceIndices[corner + 1];
                int v2 = faceIndices[corner + 2];
                int p0o = v0 * MeshLoader.FLOATS_PER_VERTEX;
                int p1o = v1 * MeshLoader.FLOATS_PER_VERTEX;
                int p2o = v2 * MeshLoader.FLOATS_PER_VERTEX;
                e1.set(posArray[p1o], posArray[p1o + 1], posArray[p1o + 2])
                        .sub(posArray[p0o], posArray[p0o + 1], posArray[p0o + 2]);
                e2.set(posArray[p2o], posArray[p2o + 1], posArray[p2o + 2])
                        .sub(posArray[p0o], posArray[p0o + 1], posArray[p0o + 2]);
                e1.cross(e2, fn);
                float len = fn.length();
                if (len > MeshLoader.NORMAL_LENGTH_EPSILON) {
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

            // Normalize vertex normals
            for (int i = 0; i < normArray.length; i += MeshLoader.FLOATS_PER_VERTEX) {
                float nx = normArray[i];
                float ny = normArray[i + 1];
                float nz = normArray[i + 2];
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > MeshLoader.NORMAL_LENGTH_EPSILON) {
                    normArray[i] = nx / len;
                    normArray[i + 1] = ny / len;
                    normArray[i + 2] = nz / len;
                }
            }
        }

        if (faceIndices.length == 0) {
            return new HalfEdgeMesh();
        }
        return MeshLoader.indexedMesh(posArray, normArray, faceIndices, MeshLoader.FLOATS_PER_VERTEX);
    }
}
