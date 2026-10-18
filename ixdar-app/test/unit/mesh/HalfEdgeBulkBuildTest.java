package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.InvalidMeshTopologyException;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

/**
 * The bulk builders ({@link HalfEdgeMeshEngine#buildFromIndexedMesh},
 * {@link HalfEdgeMeshEngine#bulkAllocate}, {@link HalfEdgeMeshEngine#bulkAllocateMixed}) must hand
 * back exactly the mesh that adding the same faces one at a time through
 * {@link HalfEdgeMesh#addFace} builds: the same vertex, edge, face and half-edge ids, the same
 * links and the same adjacency order.
 */
public class HalfEdgeBulkBuildTest {

    private static final int GRID_COLUMNS = 7;
    private static final int GRID_ROWS = 5;
    private static final int TORUS_MAJOR_SEGMENTS = 11;
    private static final int TORUS_MINOR_SEGMENTS = 6;
    private static final float TORUS_MAJOR_RADIUS = 1.0f;
    private static final float TORUS_MINOR_RADIUS = 0.3f;
    private static final double FULL_TURN = 2.0 * Math.PI;
    private static final int QUAD_CORNERS = 4;
    private static final int SCRAMBLE_STRIDE_COPRIME_TO_TRIANGLE_COUNT = 7;

    /** A closed triangulated torus: every edge has two faces. */
    @Test
    void closedTriangleMeshMatchesIncrementalBuild() {
        float[] positions = torusPositions();
        int[] triangles = torusFaces(true);
        HalfEdgeMesh reference = incrementalBuild(positions, triangles, 3);
        reference.computeNormals();
        assertNull(firstTopologyDifference(reference,
                HalfEdgeMeshEngine.buildFromIndexedMesh(positions, triangles)));
    }

    /**
     * An open grid with a vertex no face uses, its triangles reordered by a stride permutation and
     * each one's corners rotated: the boundary half-edges, the first-seen edge numbering and the
     * isolated vertex all have to agree.
     */
    @Test
    void openTriangleMeshMatchesIncrementalBuild() {
        float[] positions = gridPositions();
        int[] gridOrder = new int[(GRID_COLUMNS - 1) * (GRID_ROWS - 1) * 2 * 3];
        int cursor = 0;
        for (int row = 0; row < GRID_ROWS - 1; row++) {
            for (int column = 0; column < GRID_COLUMNS - 1; column++) {
                int corner = row * GRID_COLUMNS + column;
                int[] pair = {corner, corner + 1, corner + GRID_COLUMNS + 1,
                    corner, corner + GRID_COLUMNS + 1, corner + GRID_COLUMNS};
                System.arraycopy(pair, 0, gridOrder, cursor, pair.length);
                cursor += pair.length;
            }
        }
        int triangleCount = gridOrder.length / 3;
        int[] triangles = new int[gridOrder.length];
        for (int index = 0; index < triangleCount; index++) {
            int source = index * SCRAMBLE_STRIDE_COPRIME_TO_TRIANGLE_COUNT % triangleCount;
            int rotation = index % 3;
            for (int corner = 0; corner < 3; corner++) {
                triangles[index * 3 + corner] = gridOrder[source * 3 + (corner + rotation) % 3];
            }
        }
        HalfEdgeMesh reference = incrementalBuild(positions, triangles, 3);
        reference.computeNormals();
        assertNull(firstTopologyDifference(reference,
                HalfEdgeMeshEngine.buildFromIndexedMesh(positions, triangles)));
    }

    /** A quad torus through the uniform-arity bulk path. */
    @Test
    void quadMeshMatchesIncrementalBuild() {
        float[] positions = torusPositions();
        int[] quads = torusFaces(false);
        assertNull(firstTopologyDifference(incrementalBuild(positions, quads, QUAD_CORNERS),
                HalfEdgeMeshEngine.bulkAllocate(positions, quads, QUAD_CORNERS)));
    }

    /** Quads and triangles mixed on an open grid, through the mixed-arity bulk path. */
    @Test
    void mixedMeshMatchesIncrementalBuild() {
        float[] positions = gridPositions();
        int quadCount = (GRID_COLUMNS - 1) * (GRID_ROWS - 1);
        int[] counts = new int[quadCount + quadCount / 2];
        int[] flat = new int[quadCount * QUAD_CORNERS + quadCount / 2 * 2];
        int faceCursor = 0;
        int indexCursor = 0;
        for (int row = 0; row < GRID_ROWS - 1; row++) {
            for (int column = 0; column < GRID_COLUMNS - 1; column++) {
                int corner = row * GRID_COLUMNS + column;
                int[] quad = {corner, corner + 1, corner + GRID_COLUMNS + 1, corner + GRID_COLUMNS};
                if ((row + column) % 2 == 0) {
                    counts[faceCursor++] = QUAD_CORNERS;
                    System.arraycopy(quad, 0, flat, indexCursor, QUAD_CORNERS);
                    indexCursor += QUAD_CORNERS;
                } else {
                    counts[faceCursor++] = 3;
                    flat[indexCursor++] = quad[0];
                    flat[indexCursor++] = quad[1];
                    flat[indexCursor++] = quad[2];
                    counts[faceCursor++] = 3;
                    flat[indexCursor++] = quad[0];
                    flat[indexCursor++] = quad[2];
                    flat[indexCursor++] = quad[3];
                }
            }
        }
        int[] faceVertexCounts = Arrays.copyOf(counts, faceCursor);
        int[] faceIndices = Arrays.copyOf(flat, indexCursor);

        HalfEdgeMesh reference = new HalfEdgeMesh();
        for (int offset = 0; offset < positions.length; offset += 3) {
            reference.addVertex(positions[offset], positions[offset + 1], positions[offset + 2]);
        }
        int cursor = 0;
        for (int count : faceVertexCounts) {
            reference.addFace(Arrays.copyOfRange(faceIndices, cursor, cursor + count));
            cursor += count;
        }
        assertNull(firstTopologyDifference(reference,
                HalfEdgeMeshEngine.bulkAllocateMixed(positions, faceVertexCounts, faceIndices)));
    }

    /**
     * Four triangles on edge 0-1: the second use of 0->1 and the third use of the edge both
     * collide, and the build names both rather than stopping at the first.
     */
    @Test
    void everyCollidingHalfEdgeIsReported() {
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1};
        int[] triangles = {0, 1, 2, 1, 0, 3, 0, 1, 4, 1, 0, 5};
        InvalidMeshTopologyException failure = assertThrows(InvalidMeshTopologyException.class,
                () -> HalfEdgeMeshEngine.buildFromIndexedMesh(positions, triangles));
        assertTrue(failure.getMessage().startsWith("2 non-manifold"), failure.getMessage());
        assertTrue(failure.getMessage().endsWith(" 0->1 1->0"), failure.getMessage());
    }

    /**
     * The first way two half-edge meshes differ in ids, links, adjacency order, positions or
     * normals, compared bit for bit.
     *
     * @param expected mesh built the reference way
     * @param actual   mesh under test
     * @return a description of the first difference, or null when the two are identical
     */
    public static String firstTopologyDifference(HalfEdgeMesh expected, HalfEdgeMesh actual) {
        if (expected.vertexCount() != actual.vertexCount()
                || expected.edgeCount() != actual.edgeCount()
                || expected.faceCount() != actual.faceCount()
                || expected.halfEdgeCount() != actual.halfEdgeCount()) {
            return "element counts differ";
        }
        Vector3f expectedVector = new Vector3f();
        Vector3f actualVector = new Vector3f();
        for (int index = 0; index < expected.vertexCount(); index++) {
            int vertexId = expected.vertexIdAt(index);
            if (actual.vertexIdAt(index) != vertexId
                    || !expected.vertexPosition(vertexId, expectedVector)
                            .equals(actual.vertexPosition(vertexId, actualVector))
                    || !expected.vertexNormal(vertexId, expectedVector)
                            .equals(actual.vertexNormal(vertexId, actualVector))
                    || expected.vertexOutgoingHalfEdge(vertexId)
                            != actual.vertexOutgoingHalfEdge(vertexId)) {
                return "vertex " + vertexId + " differs";
            }
            int outgoing = expected.vertexOutgoingHalfEdgeCount(vertexId);
            int edges = expected.vertexEdgeCount(vertexId);
            int faces = expected.vertexFaceCount(vertexId);
            if (outgoing != actual.vertexOutgoingHalfEdgeCount(vertexId)
                    || edges != actual.vertexEdgeCount(vertexId)
                    || faces != actual.vertexFaceCount(vertexId)) {
                return "vertex " + vertexId + " adjacency sizes differ";
            }
            for (int slot = 0; slot < outgoing; slot++) {
                if (expected.vertexOutgoingHalfEdgeAt(vertexId, slot)
                        != actual.vertexOutgoingHalfEdgeAt(vertexId, slot)) {
                    return "vertex " + vertexId + " outgoing half-edge " + slot + " differs";
                }
            }
            for (int slot = 0; slot < edges; slot++) {
                if (expected.vertexEdgeAt(vertexId, slot) != actual.vertexEdgeAt(vertexId, slot)) {
                    return "vertex " + vertexId + " edge " + slot + " differs";
                }
            }
            for (int slot = 0; slot < faces; slot++) {
                if (expected.vertexFaceAt(vertexId, slot) != actual.vertexFaceAt(vertexId, slot)) {
                    return "vertex " + vertexId + " face " + slot + " differs";
                }
            }
        }
        for (int index = 0; index < expected.edgeCount(); index++) {
            int edgeId = expected.edgeIdAt(index);
            if (actual.edgeIdAt(index) != edgeId
                    || expected.edgeHalfEdge(edgeId) != actual.edgeHalfEdge(edgeId)) {
                return "edge " + edgeId + " differs";
            }
        }
        for (int index = 0; index < expected.faceCount(); index++) {
            int faceId = expected.faceIdAt(index);
            if (actual.faceIdAt(index) != faceId
                    || expected.faceHalfEdge(faceId) != actual.faceHalfEdge(faceId)
                    || !expected.faceNormal(faceId, expectedVector)
                            .equals(actual.faceNormal(faceId, actualVector))) {
                return "face " + faceId + " differs";
            }
            int corners = expected.faceVertexCount(faceId);
            if (corners != actual.faceVertexCount(faceId)
                    || expected.faceHalfEdgeCount(faceId) != actual.faceHalfEdgeCount(faceId)
                    || expected.faceEdgeCount(faceId) != actual.faceEdgeCount(faceId)) {
                return "face " + faceId + " adjacency sizes differ";
            }
            for (int slot = 0; slot < corners; slot++) {
                if (expected.faceVertexAt(faceId, slot) != actual.faceVertexAt(faceId, slot)
                        || expected.faceHalfEdgeAt(faceId, slot)
                                != actual.faceHalfEdgeAt(faceId, slot)
                        || expected.faceEdgeAt(faceId, slot) != actual.faceEdgeAt(faceId, slot)) {
                    return "face " + faceId + " corner " + slot + " differs";
                }
            }
        }
        for (int index = 0; index < expected.halfEdgeCount(); index++) {
            int halfEdgeId = expected.halfEdgeIdAt(index);
            if (actual.halfEdgeIdAt(index) != halfEdgeId
                    || expected.halfEdgeTwin(halfEdgeId) != actual.halfEdgeTwin(halfEdgeId)
                    || expected.halfEdgeNext(halfEdgeId) != actual.halfEdgeNext(halfEdgeId)
                    || expected.halfEdgePrev(halfEdgeId) != actual.halfEdgePrev(halfEdgeId)
                    || expected.halfEdgeVertex(halfEdgeId) != actual.halfEdgeVertex(halfEdgeId)
                    || expected.halfEdgeFace(halfEdgeId) != actual.halfEdgeFace(halfEdgeId)
                    || expected.halfEdgeEdge(halfEdgeId) != actual.halfEdgeEdge(halfEdgeId)) {
                return "half-edge " + halfEdgeId + " differs";
            }
        }
        return null;
    }

    /**
     * The reference build: every vertex, then every face through {@link HalfEdgeMesh#addFace}.
     *
     * @param positions    packed xyz triples
     * @param faceIndices  face corners grouped by {@code cornerCount}
     * @param cornerCount  corners per face
     * @return the incrementally built mesh, without normals
     */
    public static HalfEdgeMesh incrementalBuild(float[] positions, int[] faceIndices,
            int cornerCount) {
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        for (int offset = 0; offset < positions.length; offset += 3) {
            mesh.addVertex(positions[offset], positions[offset + 1], positions[offset + 2]);
        }
        for (int offset = 0; offset < faceIndices.length; offset += cornerCount) {
            mesh.addFace(Arrays.copyOfRange(faceIndices, offset, offset + cornerCount));
        }
        return mesh;
    }

    private static float[] torusPositions() {
        float[] positions = new float[TORUS_MAJOR_SEGMENTS * TORUS_MINOR_SEGMENTS * 3];
        int cursor = 0;
        for (int major = 0; major < TORUS_MAJOR_SEGMENTS; major++) {
            double ringAngle = FULL_TURN * major / TORUS_MAJOR_SEGMENTS;
            for (int minor = 0; minor < TORUS_MINOR_SEGMENTS; minor++) {
                double tubeAngle = FULL_TURN * minor / TORUS_MINOR_SEGMENTS;
                double distanceFromAxis = TORUS_MAJOR_RADIUS
                        + TORUS_MINOR_RADIUS * Math.cos(tubeAngle);
                positions[cursor++] = (float) (distanceFromAxis * Math.cos(ringAngle));
                positions[cursor++] = (float) (TORUS_MINOR_RADIUS * Math.sin(tubeAngle));
                positions[cursor++] = (float) (distanceFromAxis * Math.sin(ringAngle));
            }
        }
        return positions;
    }

    private static int[] torusFaces(boolean triangulate) {
        int quadCount = TORUS_MAJOR_SEGMENTS * TORUS_MINOR_SEGMENTS;
        int[] faces = new int[triangulate ? quadCount * 2 * 3 : quadCount * QUAD_CORNERS];
        int cursor = 0;
        for (int major = 0; major < TORUS_MAJOR_SEGMENTS; major++) {
            int nextMajor = (major + 1) % TORUS_MAJOR_SEGMENTS;
            for (int minor = 0; minor < TORUS_MINOR_SEGMENTS; minor++) {
                int nextMinor = (minor + 1) % TORUS_MINOR_SEGMENTS;
                int corner0 = major * TORUS_MINOR_SEGMENTS + minor;
                int corner1 = major * TORUS_MINOR_SEGMENTS + nextMinor;
                int corner2 = nextMajor * TORUS_MINOR_SEGMENTS + nextMinor;
                int corner3 = nextMajor * TORUS_MINOR_SEGMENTS + minor;
                if (triangulate) {
                    int[] pair = {corner0, corner1, corner2, corner0, corner2, corner3};
                    System.arraycopy(pair, 0, faces, cursor, pair.length);
                    cursor += pair.length;
                } else {
                    int[] quad = {corner0, corner1, corner2, corner3};
                    System.arraycopy(quad, 0, faces, cursor, QUAD_CORNERS);
                    cursor += QUAD_CORNERS;
                }
            }
        }
        return faces;
    }

    /** Grid positions plus one trailing vertex that no face uses. */
    private static float[] gridPositions() {
        float[] positions = new float[(GRID_COLUMNS * GRID_ROWS + 1) * 3];
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int column = 0; column < GRID_COLUMNS; column++) {
                int offset = (row * GRID_COLUMNS + column) * 3;
                positions[offset] = column;
                positions[offset + 1] = row;
                positions[offset + 2] = (column * row) % 3 * 0.25f;
            }
        }
        positions[positions.length - 1] = -1f;
        return positions;
    }
}
