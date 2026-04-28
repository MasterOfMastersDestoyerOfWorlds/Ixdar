package unit.quadlayout.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.boundary.BoundaryCapper;
import ixdar.geometry.mesh.quadlayout.boundary.BoundaryCapper.CapResult;
import ixdar.geometry.mesh.quadlayout.boundary.BoundaryUncapper;

public class BoundaryUncapperTest {

    /**
     * Build a mesh that mimics the QGP output topology for a capped hexagon:
     * the original disk is replaced by 6 triangular cap "quads" (degenerate
     * quads where one vertex is the centroid) sharing a centroid vertex. The
     * uncap step should remove those, leaving only edges with the original
     * 6-vertex boundary loop.
     */
    @Test
    public void cappedThenUncappedHexagonRestoresBoundary() {
        // Synthetic quad mesh: 6 quads forming a fan around a centroid.
        // Vertices 0..5 form a hexagon, vertex 6 is the centroid.
        int n = 6;
        float[] positions = new float[(n + 1) * 3];
        for (int i = 0; i < n; i++) {
            double theta = 2.0 * Math.PI * i / n;
            positions[i * 3] = (float) Math.cos(theta);
            positions[i * 3 + 1] = (float) Math.sin(theta);
            positions[i * 3 + 2] = 0f;
        }
        positions[n * 3] = 0f;
        positions[n * 3 + 1] = 0f;
        positions[n * 3 + 2] = 0f;
        // Build 6 cap quads: (centroid, a, mid_ab_ish, b). For test purposes
        // we collapse to (centroid, a, a, b) — only positions matter for the
        // uncap classifier.
        int[] quads = new int[6 * 4];
        int qc = n;
        for (int i = 0; i < n; i++) {
            int a = i;
            int b = (i + 1) % n;
            quads[i * 4] = qc;
            quads[i * 4 + 1] = a;
            quads[i * 4 + 2] = a;
            quads[i * 4 + 3] = b;
        }
        ArrayMesh quadMesh = new ArrayMesh(positions, null, quads, 4);

        // Synthesize the CapResult directly (we are exercising the uncap
        // classifier; the disk → cap path is exercised by BoundaryCapperTest).
        ArrayMesh disk = makeHexagonDisk();
        CapResult capResult = BoundaryCapper.cap(disk);

        ArrayMesh uncapped = BoundaryUncapper.uncap(quadMesh, capResult);
        assertEquals(0, uncapped.faceCount(), "all quads should be classified as cap and removed");
    }

    @Test
    public void uncapPreservesQuadsAwayFromCap() {
        // Two regions: cap-region quads near origin and a far-away unrelated
        // quad. Only cap-region quads should be removed.
        ArrayMesh disk = makeHexagonDisk();
        CapResult capResult = BoundaryCapper.cap(disk);

        // Cap-region quads (3 of them, all corners near origin) plus one
        // far-away quad whose corners are at z=100 — clearly outside any cap.
        float[] positions = new float[]{
                // Cap quad corners (0..3)
                0.1f, 0.1f, 0f,
                -0.1f, 0.1f, 0f,
                -0.1f, -0.1f, 0f,
                0.1f, -0.1f, 0f,
                // Far quad corners (4..7)
                10f, 10f, 100f,
                11f, 10f, 100f,
                11f, 11f, 100f,
                10f, 11f, 100f,
        };
        int[] quads = new int[]{0, 1, 2, 3, 4, 5, 6, 7};
        ArrayMesh mesh = new ArrayMesh(positions, null, quads, 4);

        ArrayMesh uncapped = BoundaryUncapper.uncap(mesh, capResult);
        assertEquals(1, uncapped.faceCount());
        Set<Integer> kept = new HashSet<>();
        for (int c = 0; c < 4; c++) {
            kept.add(uncapped.faceVertexAt(0, c));
        }
        assertTrue(kept.contains(4) && kept.contains(5) && kept.contains(6) && kept.contains(7));
    }

    static ArrayMesh makeHexagonDisk() {
        int n = 6;
        float[] positions = new float[n * 3];
        for (int i = 0; i < n; i++) {
            double theta = 2.0 * Math.PI * i / n;
            positions[i * 3] = (float) Math.cos(theta);
            positions[i * 3 + 1] = (float) Math.sin(theta);
            positions[i * 3 + 2] = 0f;
        }
        int[] tris = new int[]{
                0, 1, 2,
                0, 2, 3,
                0, 3, 4,
                0, 4, 5,
        };
        return new ArrayMesh(positions, null, tris, 3);
    }

    static Vector3f origin() {
        return new Vector3f();
    }
}
