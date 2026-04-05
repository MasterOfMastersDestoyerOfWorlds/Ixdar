package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.ArrayMeshEngine;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.HalfEdgeMeshEngine;

public class ArrayMeshTest {

    private static float[] cubePositions() {
        float c = 0.5f;
        return new float[] {
                -c, -c, -c, c, -c, -c, c, c, -c, -c, c, -c,
                -c, -c, c, c, -c, c, c, c, c, -c, c, c,
        };
    }

    private static int[] cubeQuads() {
        return new int[] {
                0, 1, 2, 3,
                4, 7, 6, 5,
                0, 4, 5, 1,
                3, 2, 6, 7,
                1, 5, 6, 2,
                0, 3, 7, 4,
        };
    }

    @Test
    public void subdivideQuadsOnceCubeVertexAndFaceCounts() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        ArrayMesh am = ArrayMesh.fromQuads(positions, quads);
        ArrayMesh once = ArrayMeshEngine.subdivideQuadsOnce(am);
        assertEquals(26, once.vertexCount());
        assertEquals(24, once.faceCount());
        assertEquals(4, once.getVertsPerFace());
    }

    @Test
    public void subdivideQuadsTwiceCubeVertexAndFaceCounts() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        ArrayMesh am = ArrayMesh.fromQuads(positions, quads);
        ArrayMesh once = ArrayMeshEngine.subdivideQuadsOnce(am);
        ArrayMesh twice = ArrayMeshEngine.subdivideQuadsOnce(once);
        // Level 1: 8 + 12 + 6 = 26 verts, 24 faces
        // Level 2: 26 + 48 + 24 = 98 verts, 96 faces
        assertEquals(98, twice.vertexCount());
        assertEquals(96, twice.faceCount());
        assertEquals(4, twice.getVertsPerFace());
    }

    @Test
    public void subdivideQuadsLevel6CubeVertexAndFaceCountsMatchHalfEdge() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        
        // Build ArrayMesh and subdivide 6 levels
        ArrayMesh am = ArrayMesh.fromQuads(positions, quads);
        for (int i = 0; i < 6; i++) {
            am = ArrayMeshEngine.subdivideQuadsOnce(am);
        }
        
        // Build HalfEdgeMesh and subdivide 6 levels
        HalfEdgeMesh hem = HalfEdgeMesh.bulkAllocate(positions.clone(), quads.clone(), 4);
        for (int i = 0; i < 6; i++) {
            hem = HalfEdgeMeshEngine.subdivideQuadsOnce(hem);
        }
        
        // Verify vertex and face counts match
        assertEquals(hem.vertexCount(), am.vertexCount(), 
                "Vertex count mismatch at level 6: ArrayMesh=" + am.vertexCount() + " HalfEdgeMesh=" + hem.vertexCount());
        assertEquals(hem.faceCount(), am.faceCount(),
                "Face count mismatch at level 6: ArrayMesh=" + am.faceCount() + " HalfEdgeMesh=" + hem.faceCount());
        
        // Expected counts for a cube at level 6:
        // Vertices: 8 + 12 * (4^6 - 1) / 3 + 6 * (4^6 - 1) / 3 = 8 + 8 * (4096 - 1) = 32768
        // Faces: 6 * 4^6 = 24576
        assertEquals(32768, am.vertexCount());
        assertEquals(24576, am.faceCount());
    }

    @Test
    public void deleteVerticesRemovesSelectedAndRemapsIndices() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        ArrayMesh am = ArrayMesh.fromQuads(positions, quads);
        
        // Delete 2 opposite vertices (0 and 7)
        boolean[] del = new boolean[8];
        del[0] = true;
        del[7] = true;
        
        ArrayMesh result = ArrayMeshEngine.deleteVertices(am, del);
        
        // Original 8 vertices, delete 2, should have 6 left
        assertEquals(6, result.vertexCount());
        
        // Faces that contained deleted vertices are removed
        // Original 6 faces, faces 0,1,2 contain vertex 0; faces 3,4,5 contain vertex 7
        // All 6 faces are affected, but some may still be valid if they share other vertices
        // Actually with 2 opposite vertices deleted, all faces are affected
        assertTrue(result.faceCount() < 6);
        
        // Verify no face index references deleted vertices
        for (int fi = 0; fi < result.faceCount(); fi++) {
            for (int k = 0; k < 4; k++) {
                int vi = result.faceVertexAt(fi, k);
                assertTrue(vi >= 0 && vi < result.vertexCount(),
                        "Invalid vertex index " + vi + " in face " + fi);
            }
        }
    }

    @Test
    public void deleteVerticesEmptyArrayReturnsEmptyMesh() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        ArrayMesh am = ArrayMesh.fromQuads(positions, quads);
        
        boolean[] del = new boolean[8];
        Arrays.fill(del, true);
        
        ArrayMesh result = ArrayMeshEngine.deleteVertices(am, del);
        
        assertEquals(0, result.vertexCount());
        assertEquals(0, result.faceCount());
    }

    @Test
    public void deleteVerticesNoSelectionReturnsCopy() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        ArrayMesh am = ArrayMesh.fromQuads(positions, quads);
        
        boolean[] del = new boolean[8];
        Arrays.fill(del, false);
        
        ArrayMesh result = ArrayMeshEngine.deleteVertices(am, del);
        
        assertEquals(am.vertexCount(), result.vertexCount());
        assertEquals(am.faceCount(), result.faceCount());
    }

    @Test
    public void deleteEdgesRemovesSelectedAndRebuildsMesh() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        ArrayMesh am = ArrayMesh.fromQuads(positions, quads);
        
        // Delete 1 edge (edge 0)
        boolean[] delEdge = new boolean[am.edgeCount()];
        delEdge[0] = true;
        
        ArrayMesh result = ArrayMeshEngine.deleteEdges(am, delEdge);
        
        // After deleting an edge, the mesh topology changes
        // Some faces may be removed, vertices may be merged
        assertEquals(4, result.getVertsPerFace());
        
        // Verify face indices are valid
        for (int fi = 0; fi < result.faceCount(); fi++) {
            for (int k = 0; k < 4; k++) {
                int vi = result.faceVertexAt(fi, k);
                assertTrue(vi >= 0 && vi < result.vertexCount(),
                        "Invalid vertex index " + vi + " in face " + fi);
            }
        }
    }

    @Test
    public void deleteEdgesNoSelectionReturnsCopy() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        ArrayMesh am = ArrayMesh.fromQuads(positions, quads);
        
        boolean[] delEdge = new boolean[am.edgeCount()];
        Arrays.fill(delEdge, false);
        
        ArrayMesh result = ArrayMeshEngine.deleteEdges(am, delEdge);
        
        assertEquals(am.vertexCount(), result.vertexCount());
        assertEquals(am.faceCount(), result.faceCount());
    }

    @Test
    public void mergeByDistanceWeldsCloseVertices() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        ArrayMesh am = ArrayMesh.fromQuads(positions, quads);
        
        // Merge with large distance to weld all vertices at same position
        // This should reduce vertex count significantly
        ArrayMesh result = ArrayMeshEngine.mergeByDistance(am, 0.1f);
        
        // All 8 original vertices should be preserved (no duplicates at same position)
        // But the operation should still produce valid mesh
        assertEquals(4, result.getVertsPerFace());
        assertTrue(result.vertexCount() > 0);
        
        // Verify face indices are valid
        for (int fi = 0; fi < result.faceCount(); fi++) {
            for (int k = 0; k < 4; k++) {
                int vi = result.faceVertexAt(fi, k);
                assertTrue(vi >= 0 && vi < result.vertexCount(),
                        "Invalid vertex index " + vi + " in face " + fi);
            }
        }
    }

    @Test
    public void mergeByDistanceZeroDistanceReturnsCopy() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        ArrayMesh am = ArrayMesh.fromQuads(positions, quads);
        
        ArrayMesh result = ArrayMeshEngine.mergeByDistance(am, 0f);
        
        assertEquals(am.vertexCount(), result.vertexCount());
        assertEquals(am.faceCount(), result.faceCount());
    }

    @Test
    public void mergeByDistanceNegativeDistanceReturnsCopy() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        ArrayMesh am = ArrayMesh.fromQuads(positions, quads);
        
        ArrayMesh result = ArrayMeshEngine.mergeByDistance(am, -0.1f);
        
        assertEquals(am.vertexCount(), result.vertexCount());
        assertEquals(am.faceCount(), result.faceCount());
    }

    @Test
    public void joinTwoCubesDoublesVertices() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        ArrayMesh a = ArrayMesh.fromQuads(positions, quads);
        ArrayMesh b = ArrayMesh.fromQuads(positions.clone(), quads.clone());
        ArrayMesh j = ArrayMeshEngine.join(a, b);
        assertEquals(16, j.vertexCount());
        assertEquals(12, j.faceCount());
    }

    @Test
    public void joinWithNullFirstReturnsSecond() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        ArrayMesh a = null;
        ArrayMesh b = ArrayMesh.fromQuads(positions, quads);
        ArrayMesh j = ArrayMeshEngine.join(a, b);
        assertEquals(b.vertexCount(), j.vertexCount());
        assertEquals(b.faceCount(), j.faceCount());
    }

    @Test
    public void joinWithNullSecondReturnsFirst() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        ArrayMesh a = ArrayMesh.fromQuads(positions, quads);
        ArrayMesh b = null;
        ArrayMesh j = ArrayMeshEngine.join(a, b);
        assertEquals(a.vertexCount(), j.vertexCount());
        assertEquals(a.faceCount(), j.faceCount());
    }

    @Test
    public void joinEmptyMeshesReturnsEmpty() {
        ArrayMesh a = ArrayMeshEngine.emptyQuads();
        ArrayMesh b = ArrayMeshEngine.emptyQuads();
        ArrayMesh j = ArrayMeshEngine.join(a, b);
        assertEquals(0, j.vertexCount());
        assertEquals(0, j.faceCount());
    }

    @Test
    public void subdivideQuadsStaticFactoryFromArrays() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        
        // Use the static factory method on ArrayMesh
        ArrayMesh result = ArrayMesh.subdivideQuads(positions, quads);
        
        assertEquals(26, result.vertexCount());
        assertEquals(24, result.faceCount());
        assertEquals(4, result.getVertsPerFace());
    }

    @Test
    public void topologyConsistencyAfterOperations() {
        float[] positions = cubePositions();
        int[] quads = cubeQuads();
        ArrayMesh am = ArrayMesh.fromQuads(positions, quads);
        
        // Subdivide once
        am = ArrayMeshEngine.subdivideQuadsOnce(am);
        verifyTopology(am);
        
        // Join with self
        am = ArrayMeshEngine.join(am, am);
        verifyTopology(am);
        
        // Delete some vertices
        boolean[] del = new boolean[am.vertexCount()];
        Arrays.fill(del, false);
        del[0] = true;
        del[1] = true;
        am = ArrayMeshEngine.deleteVertices(am, del);
        verifyTopology(am);
    }

    private void verifyTopology(ArrayMesh mesh) {
        // Verify all face vertex indices are valid
        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            for (int k = 0; k < mesh.getVertsPerFace(); k++) {
                int vi = mesh.faceVertexAt(fi, k);
                assertTrue(vi >= 0 && vi < mesh.vertexCount(),
                        "Invalid vertex index " + vi + " in face " + fi);
            }
        }
    }
}
