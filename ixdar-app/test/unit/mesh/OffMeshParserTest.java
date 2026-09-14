package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.load.OffMeshParser;
import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * {@link OffMeshParser} reads the same unit square, as two triangles, from an ASCII OFF, from a
 * big-endian binary OFF and from the little-endian binary variant the quad-layout figure sets
 * ship, ignoring the per-vertex normal and colour columns each header announces.
 */
class OffMeshParserTest {

    private static final float[] SQUARE_POSITIONS = {
        0f, 0f, 0f,
        1f, 0f, 0f,
        1f, 1f, 0f,
        0f, 1f, 0f,
    };

    private static final int[] SQUARE_INDICES = { 0, 1, 2, 0, 2, 3 };

    private static final int SQUARE_VERTICES = 4;

    private static final int SQUARE_EDGES = 5;

    private static final int[][] SQUARE_FACES = { { 0, 1, 2 }, { 0, 2, 3 } };

    private static final float EPSILON = 1e-6f;

    /** Per-vertex normal every binary fixture writes, so a wrong stride shows up as a position. */
    private static final float[] UP_NORMAL = { 0f, 0f, 1f };

    private static final float[] RGBA = { 0.25f, 0.5f, 0.75f, 1f };

    private static final float[] RGB = { 0.25f, 0.5f, 0.75f };

    private static final String BIG_ENDIAN_HEADER = "N C OFF BINARY";

    @Test
    void asciiSquareParses() {
        String text = "OFF\n4 2 5\n"
                + "0 0 0\n1 0 0\n1 1 0\n0 1 0\n"
                + "3 0 1 2\n3 0 2 3\n";
        ArrayMesh mesh = OffMeshParser.load(text.getBytes(StandardCharsets.UTF_8));

        assertEquals(3, mesh.getVertsPerFace());
        assertArrayEquals(SQUARE_POSITIONS, mesh.copyPositions(), EPSILON);
        assertArrayEquals(SQUARE_INDICES, mesh.copyFaceIndices());
    }

    @Test
    void asciiColorAndNormalColumnsAreIgnored() {
        String text = "# generated fixture\nC N OFF\n4 2 5\n"
                + "0 0 0 0 0 1 0.25 0.5 0.75 1\n"
                + "1 0 0 0 0 1 0.25 0.5 0.75 1\n"
                + "1 1 0 0 0 1 0.25 0.5 0.75 1\n"
                + "0 1 0 0 0 1 0.25 0.5 0.75 1\n"
                + "3 0 1 2 0.25 0.5 0.75 1\n"
                + "3 0 2 3 0.25 0.5 0.75 1\n";
        ArrayMesh mesh = OffMeshParser.load(text.getBytes(StandardCharsets.UTF_8));

        assertArrayEquals(SQUARE_POSITIONS, mesh.copyPositions(), EPSILON);
        assertArrayEquals(SQUARE_INDICES, mesh.copyFaceIndices());
    }

    @Test
    void binaryBigEndianSquareParses() {
        byte[] file = binarySquare(BIG_ENDIAN_HEADER, true, RGBA);
        ArrayMesh mesh = OffMeshParser.load(file);

        assertEquals(3, mesh.getVertsPerFace());
        assertArrayEquals(SQUARE_POSITIONS, mesh.copyPositions(), EPSILON);
        assertArrayEquals(SQUARE_INDICES, mesh.copyFaceIndices());
    }

    /** The layout {@code figure_8/block_in_tri.off} uses: little-endian words, RGB vertex colours. */
    @Test
    void binaryLittleEndianThreeComponentColorSquareParses() {
        byte[] file = binarySquare("C N OFF BINARY", false, RGB);
        ArrayMesh mesh = OffMeshParser.load(file);

        assertArrayEquals(SQUARE_POSITIONS, mesh.copyPositions(), EPSILON);
        assertArrayEquals(SQUARE_INDICES, mesh.copyFaceIndices());
    }

    @Test
    void headerWithoutOffKeywordIsRejected() {
        byte[] file = "ply\nformat ascii 1.0\n".getBytes(StandardCharsets.UTF_8);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OffMeshParser.load(file));
        assertTrue(failure.getMessage().contains("OFF header"), failure.getMessage());
    }

    @Test
    void truncatedBinaryBodyIsRejected() {
        byte[] file = binarySquare(BIG_ENDIAN_HEADER, true, RGBA);
        byte[] truncated = new byte[file.length - 1];
        System.arraycopy(file, 0, truncated, 0, truncated.length);

        assertThrows(IllegalArgumentException.class, () -> OffMeshParser.load(truncated));
    }

    /**
     * Write the two-triangle square as a Geomview binary OFF: the header line, the three counts,
     * one record per vertex carrying a normal then {@code vertexColor}, and one per face carrying
     * its corner indices then an RGBA colour.
     *
     * @param header      header line to write, which must name the flags the records carry
     * @param bigEndian   whether words are written most-significant byte first
     * @param vertexColor colour components appended to every vertex record
     * @return the complete file bytes
     */
    private static byte[] binarySquare(String header, boolean bigEndian, float[] vertexColor) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte headerByte : (header + "\n").getBytes(StandardCharsets.UTF_8)) {
            bytes.write(headerByte);
        }
        writeWord(bytes, SQUARE_VERTICES, bigEndian);
        writeWord(bytes, SQUARE_FACES.length, bigEndian);
        writeWord(bytes, SQUARE_EDGES, bigEndian);
        for (int vertex = 0; vertex < SQUARE_VERTICES; vertex++) {
            for (int axis = 0; axis < 3; axis++) {
                writeFloat(bytes, SQUARE_POSITIONS[vertex * 3 + axis], bigEndian);
            }
            for (float normal : UP_NORMAL) {
                writeFloat(bytes, normal, bigEndian);
            }
            for (float component : vertexColor) {
                writeFloat(bytes, component, bigEndian);
            }
        }
        for (int[] face : SQUARE_FACES) {
            writeWord(bytes, face.length, bigEndian);
            for (int corner : face) {
                writeWord(bytes, corner, bigEndian);
            }
            writeWord(bytes, RGBA.length, bigEndian);
            for (float component : RGBA) {
                writeFloat(bytes, component, bigEndian);
            }
        }
        return bytes.toByteArray();
    }

    /**
     * Append one 32-bit word.
     *
     * @param bytes     sink being filled
     * @param value     word to write
     * @param bigEndian whether the most-significant byte goes first
     */
    private static void writeWord(ByteArrayOutputStream bytes, int value, boolean bigEndian) {
        for (int index = 0; index < 4; index++) {
            int shift = bigEndian ? (3 - index) * Byte.SIZE : index * Byte.SIZE;
            bytes.write((value >>> shift) & 0xFF);
        }
    }

    /**
     * Append one 32-bit IEEE-754 float.
     *
     * @param bytes     sink being filled
     * @param value     float to write
     * @param bigEndian whether the most-significant byte goes first
     */
    private static void writeFloat(ByteArrayOutputStream bytes, float value, boolean bigEndian) {
        writeWord(bytes, Float.floatToIntBits(value), bigEndian);
    }
}
