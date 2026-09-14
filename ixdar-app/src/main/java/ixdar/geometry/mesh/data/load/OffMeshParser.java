package ixdar.geometry.mesh.data.load;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;

/**
 * Reads Geomview OFF meshes, ASCII and binary, into {@link ArrayMesh}. The header prefixes
 * {@code ST}, {@code C} and {@code N} announce per-vertex texture coordinates, colours and
 * normals; only positions and connectivity are kept.
 */
public final class OffMeshParser {

    public static final String BINARY_KEYWORD = "BINARY";

    public static final String TEXTURE_COORDINATE_FLAG = "ST";

    public static final char COLOR_FLAG = 'C';

    public static final char NORMAL_FLAG = 'N';

    public static final int BYTES_PER_WORD = 4;

    public static final int BYTE_MASK = 0xFF;

    public static final int HEADER_COUNT_WORDS = 3;

    public static final int TEXTURE_COORDINATE_COMPONENTS = 2;

    public static final int RGBA_COMPONENTS = 4;

    /**
     * Load an OFF file. The first non-comment line is the header; a {@code BINARY} keyword on it
     * switches the rest of the file to 32-bit words, otherwise the body is parsed as ASCII.
     *
     * @param content raw bytes of the file
     * @throws IllegalArgumentException if the header is not an OFF header, names a variant this
     *                                  parser does not read, or the binary body does not match it
     * @return parsed mesh (empty if the file has no positions); n-gon faces are fan-triangulated
     */
    public static ArrayMesh load(byte[] content) {
        int lineStart = 0;
        int bodyStart = content.length;
        String header = "";
        while (lineStart < content.length) {
            int lineEnd = lineStart;
            while (lineEnd < content.length && content[lineEnd] != '\n') {
                lineEnd++;
            }
            String line = new String(content, lineStart, lineEnd - lineStart,
                    StandardCharsets.UTF_8).trim();
            lineStart = lineEnd + 1;
            if (!line.isEmpty() && !line.startsWith(MeshLoader.STR)) {
                header = line;
                bodyStart = Math.min(lineStart, content.length);
                break;
            }
        }

        boolean binary = false;
        boolean sawOffKeyword = false;
        boolean hasNormals = false;
        boolean hasColors = false;
        boolean hasTextureCoordinates = false;
        for (String token : header.split(MeshLoader.S)) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.equals(BINARY_KEYWORD)) {
                binary = true;
                continue;
            }
            String flags = token;
            if (token.endsWith(MeshLoader.OFF_HEADER)) {
                sawOffKeyword = true;
                flags = token.substring(0, token.length() - MeshLoader.OFF_HEADER.length());
            }
            int at = 0;
            while (at < flags.length()) {
                if (flags.startsWith(TEXTURE_COORDINATE_FLAG, at)) {
                    hasTextureCoordinates = true;
                    at += TEXTURE_COORDINATE_FLAG.length();
                } else if (flags.charAt(at) == COLOR_FLAG) {
                    hasColors = true;
                    at++;
                } else if (flags.charAt(at) == NORMAL_FLAG) {
                    hasNormals = true;
                    at++;
                } else {
                    throw new IllegalArgumentException("Unsupported OFF header: " + header);
                }
            }
        }
        if (!sawOffKeyword) {
            throw new IllegalArgumentException("Not an OFF file: missing OFF header");
        }

        if (!binary) {
            return loadAscii(new String(content, bodyStart, content.length - bodyStart,
                    StandardCharsets.UTF_8));
        }
        return loadBinary(content, bodyStart, header, hasNormals, hasColors, hasTextureCoordinates);
    }

    /**
     * Parse the ASCII body that follows the header: the count line, then one vertex per line and
     * one face per line, with any trailing normal, colour or texture columns ignored.
     *
     * @param body file text after the header line
     * @throws RuntimeException wraps any {@link IOException} from the underlying reader
     * @return parsed mesh
     */
    private static ArrayMesh loadAscii(String body) {
        List<Float> positions = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(body))) {
            int vertexCount = -1;
            int faceCount = -1;
            int verticesRead = 0;
            int facesRead = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(MeshLoader.STR)) {
                    continue;
                }
                String[] parts = line.split(MeshLoader.S);
                if (vertexCount < 0) {
                    vertexCount = Integer.parseInt(parts[0]);
                    faceCount = Integer.parseInt(parts[1]);
                    continue;
                }
                if (verticesRead < vertexCount) {
                    positions.add(Float.parseFloat(parts[0]));
                    positions.add(Float.parseFloat(parts[1]));
                    positions.add(Float.parseFloat(parts[2]));
                    verticesRead++;
                } else if (facesRead < faceCount) {
                    int cornerCount = Integer.parseInt(parts[0]);
                    int[] corners = new int[cornerCount];
                    for (int corner = 0; corner < cornerCount; corner++) {
                        corners[corner] = Integer.parseInt(parts[corner + 1]);
                    }
                    faces.add(corners);
                    facesRead++;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return buildMesh(positions, faces);
    }

    /**
     * Parse the binary body. The word order and the number of per-vertex colour components are
     * not fixed by the header, so each candidate layout is tried and the one that consumes the
     * file exactly, with every index in range, wins.
     *
     * @param content                raw bytes of the file
     * @param bodyStart              offset of the first byte after the header line
     * @param header                 header line, quoted in the failure message
     * @param hasNormals             whether the header's {@code N} flag is set
     * @param hasColors              whether the header's {@code C} flag is set
     * @param hasTextureCoordinates  whether the header's {@code ST} flag is set
     * @throws IllegalArgumentException if no candidate layout reads the body
     * @return parsed mesh
     */
    private static ArrayMesh loadBinary(byte[] content, int bodyStart, String header,
            boolean hasNormals, boolean hasColors, boolean hasTextureCoordinates) {
        int fixedFloatsPerVertex = MeshLoader.FLOATS_PER_VERTEX
                + (hasNormals ? MeshLoader.FLOATS_PER_VERTEX : 0)
                + (hasTextureCoordinates ? TEXTURE_COORDINATE_COMPONENTS : 0);
        int[] colorComponentCandidates = hasColors
                ? new int[] { RGBA_COMPONENTS, MeshLoader.FLOATS_PER_VERTEX, 1 }
                : new int[] { 0 };
        List<Float> positions = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();
        for (boolean bigEndian : new boolean[] { true, false }) {
            for (int colorComponents : colorComponentCandidates) {
                positions.clear();
                faces.clear();
                if (readBinaryBody(content, bodyStart, bigEndian,
                        fixedFloatsPerVertex + colorComponents, positions, faces)) {
                    return buildMesh(positions, faces);
                }
            }
        }
        throw new IllegalArgumentException(
                "Binary OFF body does not match its header '" + header + "'");
    }

    /**
     * Read one candidate layout of the binary body into {@code positions} and {@code faces},
     * rejecting it on the first word that cannot belong to a mesh of the declared counts.
     *
     * @param content             raw bytes of the file
     * @param bodyStart           offset of the first byte after the header line
     * @param bigEndian           whether words are most-significant byte first
     * @param floatsPerVertex     stride of a vertex record, positions first
     * @param positions           filled with three floats per vertex when the read succeeds
     * @param faces               filled with one vertex-index array per face when it succeeds
     * @return whether the layout read the whole body consistently
     */
    private static boolean readBinaryBody(byte[] content, int bodyStart, boolean bigEndian,
            int floatsPerVertex, List<Float> positions, List<int[]> faces) {
        int wordsAvailable = (content.length - bodyStart) / BYTES_PER_WORD;
        if (wordsAvailable < HEADER_COUNT_WORDS) {
            return false;
        }
        int vertexCount = readWord(content, bodyStart, bigEndian);
        int faceCount = readWord(content, bodyStart + BYTES_PER_WORD, bigEndian);
        if (vertexCount < 0 || faceCount < 0 || HEADER_COUNT_WORDS
                + (long) vertexCount * floatsPerVertex + faceCount > wordsAvailable) {
            return false;
        }

        int at = bodyStart + HEADER_COUNT_WORDS * BYTES_PER_WORD;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            positions.add(readFloat(content, at, bigEndian));
            positions.add(readFloat(content, at + BYTES_PER_WORD, bigEndian));
            positions.add(readFloat(content, at + 2 * BYTES_PER_WORD, bigEndian));
            at += floatsPerVertex * BYTES_PER_WORD;
        }

        for (int face = 0; face < faceCount; face++) {
            if (at + BYTES_PER_WORD > content.length) {
                return false;
            }
            int cornerCount = readWord(content, at, bigEndian);
            at += BYTES_PER_WORD;
            if (cornerCount < 1
                    || at + ((long) cornerCount + 1) * BYTES_PER_WORD > content.length) {
                return false;
            }
            int[] corners = new int[cornerCount];
            for (int corner = 0; corner < cornerCount; corner++) {
                corners[corner] = readWord(content, at, bigEndian);
                at += BYTES_PER_WORD;
                if (corners[corner] < 0 || corners[corner] >= vertexCount) {
                    return false;
                }
            }
            int faceColorComponents = readWord(content, at, bigEndian);
            at += BYTES_PER_WORD;
            if (faceColorComponents < 0 || faceColorComponents > RGBA_COMPONENTS
                    || at + (long) faceColorComponents * BYTES_PER_WORD > content.length) {
                return false;
            }
            at += faceColorComponents * BYTES_PER_WORD;
            faces.add(corners);
        }
        return content.length - at < BYTES_PER_WORD;
    }

    /**
     * Read one 32-bit word.
     *
     * @param content   raw bytes of the file
     * @param offset    index of the word's first byte
     * @param bigEndian whether the word is most-significant byte first
     * @return the word as a signed int
     */
    private static int readWord(byte[] content, int offset, boolean bigEndian) {
        int value = 0;
        for (int index = 0; index < BYTES_PER_WORD; index++) {
            int byteIndex = bigEndian ? offset + index : offset + BYTES_PER_WORD - 1 - index;
            value = (value << Byte.SIZE) | (content[byteIndex] & BYTE_MASK);
        }
        return value;
    }

    /**
     * Read one 32-bit IEEE-754 float.
     *
     * @param content   raw bytes of the file
     * @param offset    index of the float's first byte
     * @param bigEndian whether the word is most-significant byte first
     * @return the decoded float
     */
    private static float readFloat(byte[] content, int offset, boolean bigEndian) {
        return Float.intBitsToFloat(readWord(content, offset, bigEndian));
    }

    /**
     * Assemble the mesh both bodies produce: positions, synthesized vertex normals, and either
     * the quads as authored or a fan triangulation of every face.
     *
     * @param positions three floats per vertex, in file order
     * @param faces     vertex indices of each face, in file order
     * @return the assembled mesh, empty when there are no positions or no faces
     */
    private static ArrayMesh buildMesh(List<Float> positions, List<int[]> faces) {
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
