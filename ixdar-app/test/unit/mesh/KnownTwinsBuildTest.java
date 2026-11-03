package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * A half-edge build handed every corner's twin, as repair_mesh hands it from its edge table, makes
 * the same mesh id for id as one that finds the pairs itself (CRAW-93).
 */
public final class KnownTwinsBuildTest {

    private static final int GRID = 7;

    @Test
    void anOpenGridBuildsTheSameWithKnownTwins() {
        float[] positions = new float[GRID * GRID * 3];
        for (int vertex = 0; vertex < GRID * GRID; vertex++) {
            positions[vertex * 3] = vertex % GRID;
            positions[vertex * 3 + 1] = vertex / GRID;
            positions[vertex * 3 + 2] = (vertex * 7 % 5) * 0.1f;
        }
        int[] faces = new int[(GRID - 1) * (GRID - 1) * 6];
        int next = 0;
        for (int row = 0; row + 1 < GRID; row++) {
            for (int column = 0; column + 1 < GRID; column++) {
                int corner = row * GRID + column;
                // Alternate diagonals, so vertices have uneven fans.
                if ((row + column) % 2 == 0) {
                    faces[next++] = corner;
                    faces[next++] = corner + 1;
                    faces[next++] = corner + GRID;
                    faces[next++] = corner + 1;
                    faces[next++] = corner + GRID + 1;
                    faces[next++] = corner + GRID;
                } else {
                    faces[next++] = corner;
                    faces[next++] = corner + GRID + 1;
                    faces[next++] = corner + GRID;
                    faces[next++] = corner;
                    faces[next++] = corner + 1;
                    faces[next++] = corner + GRID + 1;
                }
            }
        }
        Map<Long, Integer> cornerOfSide = new HashMap<>();
        for (int corner = 0; corner < faces.length; corner++) {
            cornerOfSide.put(side(faces, corner), corner);
        }
        int[] twins = new int[faces.length];
        for (int corner = 0; corner < faces.length; corner++) {
            long reversed = side(faces, corner);
            reversed = (reversed << 32) | (reversed >>> 32);
            twins[corner] = cornerOfSide.getOrDefault(reversed, MeshTopology.NONE);
        }

        HalfEdgeMesh found = HalfEdgeMeshOps.buildFromIndexedMesh(positions, faces);
        HalfEdgeMesh given = HalfEdgeMeshOps.buildFromIndexedMesh(positions, faces, twins);

        assertArrayEquals(found.halfEdgeTwin, given.halfEdgeTwin, "twins");
        assertArrayEquals(found.halfEdgeNext, given.halfEdgeNext, "next");
        assertArrayEquals(found.halfEdgePrev, given.halfEdgePrev, "prev");
        assertArrayEquals(found.halfEdgeVertex, given.halfEdgeVertex, "vertices");
        assertArrayEquals(found.halfEdgeFace, given.halfEdgeFace, "faces");
        assertArrayEquals(found.halfEdgeEdge, given.halfEdgeEdge, "edges");
        assertArrayEquals(found.faceHalfEdge, given.faceHalfEdge, "face half-edges");
        assertArrayEquals(found.edgeHalfEdge, given.edgeHalfEdge, "edge half-edges");
        assertArrayEquals(found.vertexOutgoing, given.vertexOutgoing, "outgoing");
    }

    private static long side(int[] faces, int corner) {
        int first = corner - corner % 3;
        int to = faces[first + (corner % 3 + 1) % 3];
        return ((long) faces[corner] << 32) | to;
    }
}
