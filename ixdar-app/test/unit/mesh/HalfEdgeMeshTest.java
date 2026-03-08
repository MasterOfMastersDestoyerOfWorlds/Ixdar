package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.common.exceptions.InvalidMeshTopologyException;
import ixdar.geometry.mesh.HalfEdgeMesh;
import ixdar.geometry.mesh.MeshTopology;
import ixdar.graphics.render.model.HalfEdgeCompiledMeshData;

public class HalfEdgeMeshTest {

    @Test
    public void cubeBuildCreatesClosedTopology() {
        HalfEdgeMesh mesh = HalfEdgeMesh.buildFromIndexedMesh(cubePositions(), cubeTriangles());

        assertEquals(8, mesh.vertexCount());
        assertEquals(18, mesh.edgeCount());
        assertEquals(12, mesh.faceCount());
        assertEquals(36, mesh.halfEdgeCount());

        for (int i = 0; i < mesh.faceCount(); i++) {
            int faceId = mesh.faceIdAt(i);
            assertEquals(3, mesh.faceHalfEdgeCount(faceId));
            assertEquals(3, mesh.faceVertexCount(faceId));
            assertEquals(3, mesh.faceEdgeCount(faceId));
            assertTrue(mesh.faceNormal(faceId, new Vector3f()).length() > 0f);
            for (int j = 0; j < mesh.faceHalfEdgeCount(faceId); j++) {
                int halfEdgeId = mesh.faceHalfEdgeAt(faceId, j);
                assertTrue(mesh.hasHalfEdge(mesh.halfEdgeTwin(halfEdgeId)));
                assertTrue(mesh.hasHalfEdge(mesh.halfEdgeNext(halfEdgeId)));
                assertTrue(mesh.hasHalfEdge(mesh.halfEdgePrev(halfEdgeId)));
                assertEquals(faceId, mesh.halfEdgeFace(halfEdgeId));
                assertEquals(halfEdgeId, mesh.halfEdgePrev(mesh.halfEdgeNext(halfEdgeId)));
                assertEquals(halfEdgeId, mesh.halfEdgeNext(mesh.halfEdgePrev(halfEdgeId)));
            }
        }

        for (int i = 0; i < mesh.vertexCount(); i++) {
            int vertexId = mesh.vertexIdAt(i);
            assertTrue(mesh.vertexOutgoingHalfEdgeCount(vertexId) > 0);
            assertTrue(mesh.vertexFaceCount(vertexId) > 0);
            assertTrue(mesh.vertexEdgeCount(vertexId) > 0);
            assertFalse(mesh.isBoundaryVertex(vertexId));
            assertTrue(mesh.vertexNormal(vertexId, new Vector3f()).length() > 0f);
        }

        for (int i = 0; i < mesh.edgeCount(); i++) {
            int edgeId = mesh.edgeIdAt(i);
            assertFalse(mesh.isBoundaryEdge(edgeId));
            assertTrue(mesh.hasHalfEdge(mesh.edgeHalfEdge(edgeId)));
            assertTrue(mesh.hasHalfEdge(mesh.halfEdgeTwin(mesh.edgeHalfEdge(edgeId))));
        }
    }

    @Test
    public void icosahedronSatisfiesEulerCharacteristic() {
        HalfEdgeMesh mesh = HalfEdgeMesh.buildFromIndexedMesh(icosahedronPositions(), icosahedronTriangles());

        assertEquals(12, mesh.vertexCount());
        assertEquals(30, mesh.edgeCount());
        assertEquals(20, mesh.faceCount());
        assertEquals(2, mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount());
    }

    @Test
    public void removeFacePromotesSharedEdgesToBoundary() {
        HalfEdgeMesh mesh = HalfEdgeMesh.buildFromIndexedMesh(planePositions(), planeTriangles());
        int removedFaceId = mesh.faceIdAt(0);
        int[] removedVertexIds = faceVertexIds(mesh, removedFaceId);

        mesh.removeFace(removedFaceId);

        assertEquals(1, mesh.faceCount());
        assertEquals(3, mesh.edgeCount());
        for (int vertexId : removedVertexIds) {
            assertFalse(vertexHasFace(mesh, vertexId, removedFaceId));
        }
        assertEquals(3L, boundaryEdgeCount(mesh));
    }

    @Test
    public void detectsBoundaryEdgesOnOpenMesh() {
        HalfEdgeMesh mesh = HalfEdgeMesh.buildFromIndexedMesh(planePositions(), planeTriangles());

        assertEquals(4L, boundaryEdgeCount(mesh));
        assertTrue(anyBoundaryVertex(mesh));
    }

    @Test
    public void computesExpectedTriangleNormals() {
        HalfEdgeMesh mesh = HalfEdgeMesh.buildFromIndexedMesh(
                new float[] {
                        0f, 0f, 0f,
                        1f, 0f, 0f,
                        0f, 1f, 0f
                },
                new int[] { 0, 1, 2 });

        Vector3f expected = new Vector3f(0f, 0f, 1f);
        assertVectorClose(expected, mesh.faceNormal(mesh.faceIdAt(0), new Vector3f()));
        for (int i = 0; i < mesh.vertexCount(); i++) {
            assertVectorClose(expected, mesh.vertexNormal(mesh.vertexIdAt(i), new Vector3f()));
        }
    }

    @Test
    public void rejectsNonManifoldInsertions() {
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        mesh.addVertex(0f, 0f, 0f);
        mesh.addVertex(1f, 0f, 0f);
        mesh.addVertex(0f, 1f, 0f);
        mesh.addVertex(0f, 0f, 1f);

        mesh.addFace(0, 1, 2);
        mesh.addFace(1, 0, 3);

        assertThrows(InvalidMeshTopologyException.class, () -> mesh.addFace(0, 1, 3));
    }

    @Test
    public void supportsAddingAndRemovingIsolatedEdges() {
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        int firstVertexId = mesh.addVertex(0f, 0f, 0f);
        int secondVertexId = mesh.addVertex(1f, 0f, 0f);

        int edgeId = mesh.addEdge(firstVertexId, secondVertexId);

        assertEquals(1, mesh.edgeCount());
        assertTrue(mesh.isBoundaryEdge(edgeId));
        mesh.removeEdge(edgeId);
        assertEquals(0, mesh.edgeCount());
        assertEquals(0, mesh.halfEdgeCount());
        assertEquals(0, mesh.vertexOutgoingHalfEdgeCount(firstVertexId));
        assertEquals(0, mesh.vertexOutgoingHalfEdgeCount(secondVertexId));
    }

    @Test
    public void compilesPackedSurfaceDataForRuntimeBoundary() {
        HalfEdgeMesh mesh = HalfEdgeMesh.buildFromIndexedMesh(cubePositions(), cubeTriangles());

        HalfEdgeCompiledMeshData data = mesh.compileSurfaceData();

        assertEquals(mesh.vertexCount(), data.vertexCount);
        assertEquals(mesh.faceCount(), data.faceCount);
        assertEquals(mesh.vertexCount() * 8, data.vertices.length);
        assertEquals(36, data.indices.length);
        assertTrue(data.radius > 0f);
        assertTrue(data.maxBounds.x >= data.minBounds.x);
    }

    @Test
    public void packedMeshHandlesLargeProceduralGrid() {
        GridMeshData grid = buildGrid(224, 224);

        long startNanos = System.nanoTime();
        HalfEdgeMesh mesh = HalfEdgeMesh.buildFromIndexedMesh(grid.positions, grid.indices);
        HalfEdgeCompiledMeshData data = mesh.compileSurfaceData();
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertEquals(225 * 225, mesh.vertexCount());
        assertEquals(224 * 224 * 2, mesh.faceCount());
        assertEquals(mesh.vertexCount(), data.vertexCount);
        assertTrue(elapsedMillis < 15000L, "Packed build was unexpectedly slow: " + elapsedMillis + "ms");
    }

    private static void assertVectorClose(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 0.0001f);
        assertEquals(expected.y, actual.y, 0.0001f);
        assertEquals(expected.z, actual.z, 0.0001f);
    }

    private static long boundaryEdgeCount(MeshTopology mesh) {
        long count = 0L;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            if (mesh.isBoundaryEdge(mesh.edgeIdAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static boolean anyBoundaryVertex(MeshTopology mesh) {
        for (int i = 0; i < mesh.vertexCount(); i++) {
            if (mesh.isBoundaryVertex(mesh.vertexIdAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean vertexHasFace(MeshTopology mesh, int vertexId, int targetFaceId) {
        for (int i = 0; i < mesh.vertexFaceCount(vertexId); i++) {
            if (mesh.vertexFaceAt(vertexId, i) == targetFaceId) {
                return true;
            }
        }
        return false;
    }

    private static int[] faceVertexIds(MeshTopology mesh, int faceId) {
        int[] ids = new int[mesh.faceVertexCount(faceId)];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = mesh.faceVertexAt(faceId, i);
        }
        return ids;
    }

    private static GridMeshData buildGrid(int width, int height) {
        float[] positions = new float[(width + 1) * (height + 1) * 3];
        int[] indices = new int[width * height * 6];
        int vertexCursor = 0;
        for (int y = 0; y <= height; y++) {
            for (int x = 0; x <= width; x++) {
                positions[vertexCursor++] = x;
                positions[vertexCursor++] = y;
                positions[vertexCursor++] = 0f;
            }
        }

        int indexCursor = 0;
        int stride = width + 1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v0 = y * stride + x;
                int v1 = v0 + 1;
                int v2 = v0 + stride;
                int v3 = v2 + 1;
                indices[indexCursor++] = v0;
                indices[indexCursor++] = v1;
                indices[indexCursor++] = v3;
                indices[indexCursor++] = v0;
                indices[indexCursor++] = v3;
                indices[indexCursor++] = v2;
            }
        }
        return new GridMeshData(positions, indices);
    }

    private record GridMeshData(float[] positions, int[] indices) {
    }

    private static float[] cubePositions() {
        return new float[] {
                -0.5f, -0.5f, -0.5f,
                0.5f, -0.5f, -0.5f,
                0.5f, 0.5f, -0.5f,
                -0.5f, 0.5f, -0.5f,
                -0.5f, -0.5f, 0.5f,
                0.5f, -0.5f, 0.5f,
                0.5f, 0.5f, 0.5f,
                -0.5f, 0.5f, 0.5f,
        };
    }

    private static int[] cubeTriangles() {
        return new int[] {
                0, 1, 2, 2, 3, 0,
                4, 7, 6, 6, 5, 4,
                0, 4, 5, 5, 1, 0,
                3, 2, 6, 6, 7, 3,
                1, 5, 6, 6, 2, 1,
                0, 3, 7, 7, 4, 0,
        };
    }

    private static float[] planePositions() {
        return new float[] {
                0f, 0f, 0f,
                1f, 0f, 0f,
                1f, 1f, 0f,
                0f, 1f, 0f,
        };
    }

    private static int[] planeTriangles() {
        return new int[] {
                0, 1, 2,
                0, 2, 3,
        };
    }

    private static float[] icosahedronPositions() {
        float phi = (1f + (float) Math.sqrt(5f)) * 0.5f;
        return new float[] {
                -1f, phi, 0f,
                1f, phi, 0f,
                -1f, -phi, 0f,
                1f, -phi, 0f,
                0f, -1f, phi,
                0f, 1f, phi,
                0f, -1f, -phi,
                0f, 1f, -phi,
                phi, 0f, -1f,
                phi, 0f, 1f,
                -phi, 0f, -1f,
                -phi, 0f, 1f,
        };
    }

    private static int[] icosahedronTriangles() {
        return new int[] {
                0, 11, 5,
                0, 5, 1,
                0, 1, 7,
                0, 7, 10,
                0, 10, 11,
                1, 5, 9,
                5, 11, 4,
                11, 10, 2,
                10, 7, 6,
                7, 1, 8,
                3, 9, 4,
                3, 4, 2,
                3, 2, 6,
                3, 6, 8,
                3, 8, 9,
                4, 9, 5,
                2, 4, 11,
                6, 2, 10,
                8, 6, 7,
                9, 8, 1,
        };
    }
}
