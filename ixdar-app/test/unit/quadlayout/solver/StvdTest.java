package unit.quadlayout.solver;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.solver.Stvd;

public class StvdTest {

    /**
     * Flat triangulated grid in z=0 plane. STVD distance from a corner to the
     * far corner should match the Euclidean diagonal within 5%.
     */
    @Test
    void flatGridRecoversDiagonal() {
        int N = 11;
        float step = 1.0f / (N - 1);
        float[] positions = new float[N * N * 3];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int idx = (j * N + i) * 3;
                positions[idx] = i * step;
                positions[idx + 1] = j * step;
                positions[idx + 2] = 0f;
            }
        }
        // Triangulated quads.
        int[] tris = new int[(N - 1) * (N - 1) * 6];
        int t = 0;
        for (int j = 0; j < N - 1; j++) {
            for (int i = 0; i < N - 1; i++) {
                int v00 = j * N + i;
                int v10 = j * N + i + 1;
                int v01 = (j + 1) * N + i;
                int v11 = (j + 1) * N + i + 1;
                tris[t++] = v00; tris[t++] = v10; tris[t++] = v11;
                tris[t++] = v00; tris[t++] = v11; tris[t++] = v01;
            }
        }
        ArrayMesh mesh = new ArrayMesh(positions, null, tris, 3);
        // Edge weight = Euclidean edge length (isotropic case).
        Stvd.EdgeWeight w = (u, v) -> {
            double dx = positions[3 * u] - positions[3 * v];
            double dy = positions[3 * u + 1] - positions[3 * v + 1];
            double dz = positions[3 * u + 2] - positions[3 * v + 2];
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        };
        Stvd.Result res = Stvd.computeK4(mesh, new int[]{ 0 }, w);
        // Far corner: (N-1, N-1, 0). True Euclidean distance = sqrt(2).
        int far = N * N - 1;
        double dist = res.distance[far];
        double expected = Math.sqrt(2.0);
        double err = Math.abs(dist - expected) / expected;
        assertTrue(err < 0.05, "flat grid k=4 STVD distance " + dist + " vs " + expected
                + " err=" + err);
    }

    /**
     * Cube mesh: distance from one corner to the antipodal corner along the
     * surface. The unfolded geodesic is sqrt(5) per side ≈ 2.236; classical
     * Dijkstra on cube edges gives 3.0 (much worse). STVD with k=4 should be
     * substantially better than k=1.
     */
    @Test
    void cubeMeshSTVDBetterThanDijkstra() {
        // Subdivide a unit cube into a triangulated mesh. We use a small grid
        // per face for adequate resolution.
        int subdivision = 6;
        CubeBuilder cb =
                new CubeBuilder(subdivision);
        ArrayMesh mesh = cb.build();
        float[] pos = cb.positions;
        Stvd.EdgeWeight w = (u, v) -> {
            double dx = pos[3 * u] - pos[3 * v];
            double dy = pos[3 * u + 1] - pos[3 * v + 1];
            double dz = pos[3 * u + 2] - pos[3 * v + 2];
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        };
        int start = cb.cornerVertex(false, false, false);
        int end = cb.cornerVertex(true, true, true);
        Stvd.Result r1 = new Stvd(mesh, 1, w).compute(new int[]{ start });
        Stvd.Result r4 = new Stvd(mesh, 4, w).compute(new int[]{ start });
        double d1 = r1.distance[end];
        double d4 = r4.distance[end];
        // True surface geodesic for a unit cube corner-to-corner is sqrt(5)
        // (unfold across one face): the 2-face shortest-path. Our cube is
        // unit-sized; within 5% target for k=4.
        double trueGeo = Math.sqrt(5.0);
        // Dijkstra (k=1) is forced to follow grid edges and overestimates.
        // k=4 should be closer to truth than k=1.
        double err1 = Math.abs(d1 - trueGeo) / trueGeo;
        double err4 = Math.abs(d4 - trueGeo) / trueGeo;
        assertTrue(d4 <= d1 + 1e-9, "k=4 should not be worse than k=1: " + d4 + " vs " + d1);
        assertTrue(err4 < err1 || err4 < 0.05,
                "k=4 err=" + err4 + " not better than k=1 err=" + err1);
        assertTrue(err4 < 0.10, "k=4 STVD on cube should be within 10% of geodesic, got " + err4);
    }
}
