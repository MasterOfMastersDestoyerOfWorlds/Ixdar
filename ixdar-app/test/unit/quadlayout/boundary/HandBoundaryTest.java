package unit.quadlayout.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.quadlayout.boundary.BoundaryCapper;
import ixdar.geometry.mesh.quadlayout.boundary.BoundaryCapper.CapResult;

public class HandBoundaryTest {

    private static final String HAND_OBJ = "/Users/acw28/Blends/Hand/Hand.obj";

    static boolean handObjAvailable() {
        return Files.exists(Path.of(HAND_OBJ));
    }

    /**
     * Loads the Hand mesh, reports boundary stats, and verifies the cap-pass
     * leaves zero boundary edges. The shipped {@code Hand.obj} from /Users/acw28/Blends
     * happens to be closed (sealed at the wrist), so the test asserts that
     * the loop count and post-cap boundary edge count are both zero in that
     * case. If a future open-mesh version replaces the file, the same
     * assertion (zero boundary edges after capping) still holds — the test
     * just becomes more interesting.
     */
    @Test
    @EnabledIf("handObjAvailable")
    public void handCappingClosesAllLoops() throws IOException {
        ArrayMesh hand = MeshLoader.load(HAND_OBJ);
        int boundaryEdgesBefore = countBoundaryEdges(hand);

        CapResult result = BoundaryCapper.cap(hand);
        int loopCount = result.originalLoops().size();

        int largestLoopSize = 0;
        for (int[] loop : result.originalLoops()) {
            if (loop.length > largestLoopSize) {
                largestLoopSize = loop.length;
            }
        }

        ArrayMesh closedMesh = result.closedMesh();
        int boundaryEdgesAfter = countBoundaryEdges(closedMesh);
        assertEquals(0, boundaryEdgesAfter,
                String.format("After cap on Hand.obj (loops=%d, largestLoop=%d, boundaryEdgesBefore=%d), expected 0 boundary edges",
                        loopCount, largestLoopSize, boundaryEdgesBefore));

        int expectedCapTris = 0;
        for (int[] loop : result.originalLoops()) {
            expectedCapTris += loop.length;
        }
        assertEquals(expectedCapTris, result.capFaceIds().length);

        System.out.printf(
                "Hand.obj boundary stats: vertexCount=%d, faceCount=%d, boundaryEdgesBefore=%d, loops=%d, largestLoopVertices=%d, capFaces=%d, boundaryEdgesAfter=%d%n",
                hand.vertexCount(), hand.faceCount(), boundaryEdgesBefore,
                loopCount, largestLoopSize, result.capFaceIds().length, boundaryEdgesAfter);
    }

    private static int countBoundaryEdges(ArrayMesh mesh) {
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
