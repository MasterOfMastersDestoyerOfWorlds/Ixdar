package ixdar.geometry.mesh.data.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangulationRule;

public final class ObjMeshParser {
    /**
     * Load an OBJ file into a triangle mesh ({@link MeshLoader#indexedMesh}).
     * Supports vertex positions (v x y z) and normals (vn x y z); a file without normals gets the
     * mesh's own area-weighted ones.
     *
     * @param content raw OBJ content
     * @throws RuntimeException wraps any {@link IOException} from the underlying reader
     * @return parsed mesh (empty if the file has no positions)
     */
    public static MeshTopology load(String content) {

        PackedPoints positions = new PackedPoints();
        PackedPoints normals = new PackedPoints();
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
                    positions.append(x, y, z);
                    normals.append(0f, 0f, 0f);
                } else if ("vn".equals(type) && parts.length >= 4) {
                    float x = Float.parseFloat(parts[1]);
                    float y = Float.parseFloat(parts[2]);
                    float z = Float.parseFloat(parts[MeshLoader.FLOATS_PER_VERTEX]);
                    if (normals.size() < positions.size()) {
                        normals.append(x, y, z);
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

        if (positions.size() == 0) {
            return new HalfEdgeMesh();
        }

        int totalTriIndices = 0;
        for (int[] face : faces) {
            if (face.length >= MeshLoader.FLOATS_PER_VERTEX) {
                totalTriIndices += (face.length - 2) * MeshLoader.FLOATS_PER_VERTEX;
            }
        }

        normals.resize(positions.size());
        boolean hasVertexNormals = false;
        for (int vertex = 0; vertex < normals.size() && !hasVertexNormals; vertex++) {
            hasVertexNormals = normals.get(vertex, 0) != 0f || normals.get(vertex, 1) != 0f
                    || normals.get(vertex, 2) != 0f;
        }

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

        if (faceIndices.length == 0) {
            return new HalfEdgeMesh();
        }
        return MeshLoader.indexedMesh(positions, hasVertexNormals ? normals : null, faceIndices,
                MeshLoader.FLOATS_PER_VERTEX);
    }
}
