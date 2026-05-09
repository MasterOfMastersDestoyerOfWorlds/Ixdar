package ixdar.geometry.mesh.data.load;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;

public final class PlyMeshParser {

    /**
     * Load a PLY file into ArrayMesh.
     * Supports ASCII PLY format with vertex positions and optional normals.
     *
     * @param content raw PLY content
     * @return parsed mesh (empty if the file has no positions); n-gon faces are
     *         fan-triangulated
     */ 
    public static ArrayMesh load(String content) {
        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();

        try (BufferedReader stringReader = new BufferedReader(new StringReader(content))) {
            String line;
            boolean readFaces = false;
            int vertexCount = 0;

            while ((line = stringReader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(MeshLoader.STR)) {
                    continue;
                }

                if (line.equals(MeshLoader.END_HEADER)) {
                    readFaces = true;
                    continue;
                }

                if (!readFaces) {
                    // Parse header
                    if (line.startsWith(MeshLoader.ELEMENT_VERTEX)) {
                        String[] parts = line.split(MeshLoader.S);
                        if (parts.length >= 2) {
                            vertexCount = Integer.parseInt(parts[1]);
                            // Pre-allocate normals
                            for (int i = 0; i < vertexCount * MeshLoader.FLOATS_PER_VERTEX; i++) {
                                normals.add(MeshLoader.NUM_0);
                            }
                        }
                    } else if (line.startsWith("element face")) {
                        String[] parts = line.split(MeshLoader.S);
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
                    String[] parts = line.split(MeshLoader.S);
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Now read the file again to get vertex data
        try (BufferedReader stringReader = new BufferedReader(new StringReader(content))) {
            String line;
            boolean readVertices = false;
            boolean readFaces = false;
            int vertexCount = 0;

            while ((line = stringReader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(MeshLoader.STR)) {
                    continue;
                }

                if (line.equals(MeshLoader.END_HEADER)) {
                    readVertices = true;
                    continue;
                }

                if (!readVertices) {
                    if (line.startsWith(MeshLoader.ELEMENT_VERTEX)) {
                        String[] parts = line.split(MeshLoader.S);
                        if (parts.length >= 2) {
                            vertexCount = Integer.parseInt(parts[1]);
                        }
                    }
                } else if (!readFaces) {
                    // Parse vertex data
                    String[] parts = line.split(MeshLoader.S);
                    if (parts.length >= MeshLoader.FLOATS_PER_VERTEX) {
                        float x = Float.parseFloat(parts[0]);
                        float y = Float.parseFloat(parts[1]);
                        float z = Float.parseFloat(parts[2]);
                        positions.add(x);
                        positions.add(y);
                        positions.add(z);

                        // Check for normals
                        if (parts.length >= MeshLoader.NUM_6) {
                            normals.set(
                                    (positions.size() / MeshLoader.FLOATS_PER_VERTEX - 1)
                                            * MeshLoader.FLOATS_PER_VERTEX,
                                    Float.parseFloat(parts[MeshLoader.FLOATS_PER_VERTEX]));
                            normals.set(
                                    (positions.size() / MeshLoader.FLOATS_PER_VERTEX - 1) * MeshLoader.FLOATS_PER_VERTEX
                                            + 1,
                                    Float.parseFloat(parts[MeshLoader.NUM_4]));
                            normals.set(
                                    (positions.size() / MeshLoader.FLOATS_PER_VERTEX - 1) * MeshLoader.FLOATS_PER_VERTEX
                                            + 2,
                                    Float.parseFloat(parts[MeshLoader.NUM_5]));
                        }
                    }
                } else {
                    // Parse face data
                    String[] parts = line.split(MeshLoader.S);
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
        } catch (IOException e) {
            throw new RuntimeException(e);
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
        for (int i = 0; i < positions.size(); i++)
            posArray[i] = positions.get(i);
        for (int i = 0; i < Math.min(normals.size(), normArray.length); i++)
            normArray[i] = normals.get(i);

        // Determine face vertex count
        int vertsPerFace = MeshLoader.FLOATS_PER_VERTEX; // default to triangles
        if (!faces.isEmpty()) {
            vertsPerFace = faces.get(0).length;
        }

        int[] faceIndices = new int[totalVertices];
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

        return new ArrayMesh(posArray, normArray, faceIndices, vertsPerFace);
    }
}
