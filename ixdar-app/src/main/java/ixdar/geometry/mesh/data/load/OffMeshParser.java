package ixdar.geometry.mesh.data.load;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;

public final class OffMeshParser {

    /**
     * Load an OFF (Object File Format) file into ArrayMesh.
     * Accepts the standard header variants (OFF, COFF, NOFF, ...); ignores any
     * trailing per-vertex color/normal columns and per-face color columns.
     *
     * @param content raw OFF content
     * @throws IllegalArgumentException if the first non-comment line is not an OFF header
     * @throws RuntimeException wraps any {@link IOException} from the underlying reader
     * @return parsed mesh (empty if the file has no positions); n-gon faces are
     *         fan-triangulated
     */
    public static ArrayMesh load(String content) {
        List<Float> positions = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();

        try (BufferedReader stringReader = new BufferedReader(new StringReader(content))) {
            boolean sawHeader = false;
            int nV = -1;
            int nF = -1;
            int vertsRead = 0;
            int facesRead = 0;
            String line;
            while ((line = stringReader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(MeshLoader.STR)) {
                    continue;
                }
                if (!sawHeader) {
                    // First non-empty/comment line must be the OFF header (OFF, COFF, NOFF, STOFF,
                    // ...).
                    if (!line.endsWith(MeshLoader.OFF_HEADER)) {
                        throw new IllegalArgumentException("Not an OFF file: missing OFF header");
                    }
                    sawHeader = true;
                    continue;
                }
                if (nV < 0) {
                    String[] parts = line.split(MeshLoader.S);
                    nV = Integer.parseInt(parts[0]);
                    nF = Integer.parseInt(parts[1]);
                    continue;
                }
                if (vertsRead < nV) {
                    String[] parts = line.split(MeshLoader.S);
                    positions.add(Float.parseFloat(parts[0]));
                    positions.add(Float.parseFloat(parts[1]));
                    positions.add(Float.parseFloat(parts[2]));
                    vertsRead++;
                } else if (facesRead < nF) {
                    String[] parts = line.split(MeshLoader.S);
                    int n = Integer.parseInt(parts[0]);
                    int[] face = new int[n];
                    for (int i = 0; i < n; i++) {
                        face[i] = Integer.parseInt(parts[i + 1]);
                    }
                    faces.add(face);
                    facesRead++;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (positions.isEmpty()) {
            return ArrayMeshEngine.emptyQuads();
        }

        float[] posArray = new float[positions.size()];
        for (int i = 0; i < positions.size(); i++)
            posArray[i] = positions.get(i);
        float[] normArray = new float[positions.size()];

        // Synthesize vertex normals by accumulating face normals (matches parseObj's
        // behavior).
        Vector3f e1 = new Vector3f();
        Vector3f e2 = new Vector3f();
        Vector3f fn = new Vector3f();
        for (int[] face : faces) {
            if (face.length < MeshLoader.FLOATS_PER_VERTEX) {
                continue;
            }
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
                for (int v : new int[] { v0, v1, v2 }) {
                    int vo = v * MeshLoader.FLOATS_PER_VERTEX;
                    normArray[vo] += fn.x;
                    normArray[vo + 1] += fn.y;
                    normArray[vo + 2] += fn.z;
                }
            }
        }
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

        // Determine vertsPerFace from the first face; fan-triangulate everything else.
        int vertsPerFace = MeshLoader.FLOATS_PER_VERTEX;
        boolean uniformQuads = !faces.isEmpty();
        for (int[] face : faces) {
            if (face.length != MeshLoader.NUM_4) {
                uniformQuads = false;
                break;
            }
        }
        if (uniformQuads) {
            vertsPerFace = MeshLoader.NUM_4;
            int[] faceIndices = new int[faces.size() * MeshLoader.NUM_4];
            int fi = 0;
            for (int[] face : faces) {
                faceIndices[fi++] = face[0];
                faceIndices[fi++] = face[1];
                faceIndices[fi++] = face[2];
                faceIndices[fi++] = face[MeshLoader.FLOATS_PER_VERTEX];
            }
            return new ArrayMesh(posArray, normArray, faceIndices, vertsPerFace);
        }

        int totalTriIndices = 0;
        for (int[] face : faces) {
            if (face.length >= MeshLoader.FLOATS_PER_VERTEX) {
                totalTriIndices += (face.length - 2) * MeshLoader.FLOATS_PER_VERTEX;
            }
        }
        int[] faceIndices = new int[totalTriIndices];
        int fi = 0;
        for (int[] face : faces) {
            if (face.length < MeshLoader.FLOATS_PER_VERTEX) {
                continue;
            }
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
