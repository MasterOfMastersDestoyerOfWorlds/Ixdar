package unit.quadlayout.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.boundary.BoundaryCapper;
import ixdar.geometry.mesh.quadlayout.boundary.BoundaryCapper.CapResult;

public class BoundaryCapperTest {

    /**
     * Hexagon disk in the XY plane: 6 boundary vertices around (0,0,0), no
     * interior vertex (we want the cap to add the centre). The disk has 4 fan
     * triangles using vertex 0 as the anchor — so it has 1 boundary loop of
     * length 6 and the cap should add 1 vertex + 6 triangles.
     */
    private static ArrayMesh hexagonDisk() {
        int n = 6;
        float[] positions = new float[n * 3];
        for (int i = 0; i < n; i++) {
            double theta = 2.0 * Math.PI * i / n;
            positions[i * 3] = (float) Math.cos(theta);
            positions[i * 3 + 1] = (float) Math.sin(theta);
            positions[i * 3 + 2] = 0f;
        }
        // Fan-triangulate around vertex 0.
        int[] tris = new int[]{
                0, 1, 2,
                0, 2, 3,
                0, 3, 4,
                0, 4, 5,
        };
        return new ArrayMesh(positions, null, tris, 3);
    }

    @Test
    public void hexagonDiskOneBoundaryLoopOfSizeSix() {
        ArrayMesh disk = hexagonDisk();
        int boundaryEdges = countBoundaryEdges(disk);
        assertEquals(6, boundaryEdges);
    }

    @Test
    public void cappingHexagonAddsOneVertexAndSixTriangles() {
        ArrayMesh disk = hexagonDisk();
        CapResult result = BoundaryCapper.cap(disk);

        assertEquals(1, result.originalLoops().size());
        int[] loop = result.originalLoops().get(0);
        assertEquals(6, loop.length);
        assertEquals(1, result.capVertexIds().length);
        assertEquals(6, result.capFaceIds().length);

        ArrayMesh closed = result.closedMesh();
        assertEquals(disk.vertexCount() + 1, closed.vertexCount());
        assertEquals(disk.faceCount() + 6, closed.faceCount());

        // After capping, no boundary edges remain.
        assertEquals(0, countBoundaryEdges(closed));

        // Cap centroid should sit at origin for a unit hexagon.
        Vector3f centroid = closed.vertexPosition(result.capVertexIds()[0], new Vector3f());
        assertTrue(centroid.length() < 1e-5f, "centroid expected at origin, got " + centroid);
    }

    @Test
    public void capTrianglesPairAsTwinsOfDiskBoundary() {
        // Topology check: every original boundary half-edge must have a twin
        // in the cap, and vice versa. (For a degenerate flat disk the cap and
        // the disk are coplanar, so the cap's outward normal is opposite to
        // the disk's; the meaningful invariant for non-flat real meshes is
        // half-edge twin pairing, which is what this test verifies.)
        ArrayMesh disk = hexagonDisk();
        CapResult result = BoundaryCapper.cap(disk);
        ArrayMesh closed = result.closedMesh();

        for (int he = 0; he < closed.halfEdgeCount(); he++) {
            assertTrue(closed.halfEdgeTwin(he) >= 0,
                    "half-edge " + he + " is unpaired after capping");
        }
    }

    static int countBoundaryEdges(ArrayMesh mesh) {
        int count = 0;
        int edgeCount = mesh.edgeCount();
        for (int e = 0; e < edgeCount; e++) {
            if (mesh.isBoundaryEdge(e)) {
                count++;
            }
        }
        return count;
    }

}
