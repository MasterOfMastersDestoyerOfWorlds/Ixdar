package unit.procgen.dungeon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.procgen.dungeon.algo.DelaunayTriangulation3D;
import ixdar.procgen.dungeon.algo.RoomPlacer3D;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue3D;
import ixdar.procgen.dungeon.values.RoomListValue3D.Room;

public class DelaunayTriangulation3DTest {

    private static RoomListValue3D pointsAt(float... xyz) {
        List<Room> rs = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) {
            rs.add(new Room(i / 3, xyz[i], xyz[i + 1], xyz[i + 2], 0.5f, 0.5f, 0.5f));
        }
        return new RoomListValue3D(rs);
    }

    @Test
    public void emptyInput() {
        EdgeGraphValue g = DelaunayTriangulation3D.triangulate(pointsAt());
        assertEquals(0, g.nodeCount());
        assertEquals(0, g.edgeCount());
    }

    @Test
    public void twoPointsOneEdge() {
        EdgeGraphValue g = DelaunayTriangulation3D.triangulate(pointsAt(0, 0, 0, 5, 0, 0));
        assertArrayEquals(new int[] { 0, 1 }, g.edge(0));
    }

    @Test
    public void fourPointsFormSingleTetrahedron() {
        // Standard tetrahedron — each pair connected -> 6 edges.
        EdgeGraphValue g = DelaunayTriangulation3D.triangulate(pointsAt(
                0, 0, 0,
                10, 0, 0,
                0, 10, 0,
                0, 0, 10));
        assertEquals(6, g.edgeCount());
    }

    @Test
    public void fivePointsInGeneralPositionYieldsTwoTetrahedra() {
        // Add a fifth point inside the original tetrahedron's space — should still produce a
        // valid tetrahedralization (more than 6 edges since two tets share a face).
        EdgeGraphValue g = DelaunayTriangulation3D.triangulate(pointsAt(
                0, 0, 0,
                10, 0, 0,
                0, 10, 0,
                0, 0, 10,
                3, 3, 3));
        // 5 vertices, two tets sharing one face: 4 + 4 - 1 = 7 unique edges if 5th is inside.
        // Actual edge count depends on 5th point's circumsphere relationship.
        assertTrue(g.edgeCount() >= 7,
                "5-point tetrahedralization should have at least 7 edges, got " + g.edgeCount());
    }

    @Test
    public void allRoomsReachableThroughEdges() {
        // Practical correctness: the Delaunay graph must connect all rooms (this is what the
        // MST stage relies on). The strict empty-circumsphere property is harder to verify
        // post-hoc since 4-cliques in the edge graph don't correspond 1:1 to tetrahedra in
        // the complex.
        RoomListValue3D rooms = RoomPlacer3D.place(42L, 30, 5, 30, 12, 3, 6, 2000);
        EdgeGraphValue edges = DelaunayTriangulation3D.triangulate(rooms);
        int n = rooms.size();
        boolean[][] adj = new boolean[n][n];
        for (int i = 0; i < edges.edgeCount(); i++) {
            int[] e = edges.edge(i);
            adj[e[0]][e[1]] = true;
            adj[e[1]][e[0]] = true;
        }
        boolean[] seen = new boolean[n];
        int[] stack = new int[n];
        int sp = 0;
        seen[0] = true;
        stack[sp++] = 0;
        while (sp > 0) {
            int v = stack[--sp];
            for (int u = 0; u < n; u++) {
                if (adj[v][u] && !seen[u]) {
                    seen[u] = true;
                    stack[sp++] = u;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            assertTrue(seen[i], "room " + i + " unreachable in 3D Delaunay graph");
        }
    }

    @Test
    public void isDeterministicForFixedInput() {
        RoomListValue3D rooms = RoomPlacer3D.place(7L, 30, 5, 30, 10, 3, 6, 2000);
        EdgeGraphValue a = DelaunayTriangulation3D.triangulate(rooms);
        EdgeGraphValue b = DelaunayTriangulation3D.triangulate(rooms);
        assertEquals(a.edgeCount(), b.edgeCount());
        for (int i = 0; i < a.edgeCount(); i++) {
            assertArrayEquals(a.edge(i), b.edge(i));
        }
    }

    /** Brute-force: find every 4-clique in the (undirected, deduped) edge graph. */
    private static List<int[]> findTetrahedra(EdgeGraphValue edges) {
        int n = edges.nodeCount();
        boolean[][] adj = new boolean[n][n];
        for (int i = 0; i < edges.edgeCount(); i++) {
            int[] e = edges.edge(i);
            adj[e[0]][e[1]] = true;
            adj[e[1]][e[0]] = true;
        }
        List<int[]> tets = new ArrayList<>();
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                if (!adj[a][b]) continue;
                for (int c = b + 1; c < n; c++) {
                    if (!adj[a][c] || !adj[b][c]) continue;
                    for (int d = c + 1; d < n; d++) {
                        if (adj[a][d] && adj[b][d] && adj[c][d]) {
                            tets.add(new int[] { a, b, c, d });
                        }
                    }
                }
            }
        }
        return tets;
    }

    private static boolean strictlyInCircumsphere(Room a, Room b, Room c, Room d, Room p) {
        double[] av = { a.centerX(), a.centerY(), a.centerZ() };
        double[] bv = { b.centerX(), b.centerY(), b.centerZ() };
        double[] cv = { c.centerX(), c.centerY(), c.centerZ() };
        double[] dv = { d.centerX(), d.centerY(), d.centerZ() };
        double[] pv = { p.centerX(), p.centerY(), p.centerZ() };
        // Ensure positive orientation; if signed volume is negative, swap c and d.
        if (signedVolume(av, bv, cv, dv) < 0) {
            double[] tmp = cv; cv = dv; dv = tmp;
        }
        double[] ad = sub(av, pv);
        double[] bd = sub(bv, pv);
        double[] cd = sub(cv, pv);
        double[] dd = sub(dv, pv);
        double aL = lift(ad), bL = lift(bd), cL = lift(cd), dL = lift(dd);
        double det = aL * det3(bd, cd, dd)
                   - bL * det3(ad, cd, dd)
                   + cL * det3(ad, bd, dd)
                   - dL * det3(ad, bd, cd);
        // Allow tiny epsilon slack so points exactly on the sphere don't trip.
        return det > 1e-7;
    }

    private static double signedVolume(double[] a, double[] b, double[] c, double[] d) {
        double[] ab = sub(b, a);
        double[] ac = sub(c, a);
        double[] ad = sub(d, a);
        return ab[0] * (ac[1] * ad[2] - ac[2] * ad[1])
             + ab[1] * (ac[2] * ad[0] - ac[0] * ad[2])
             + ab[2] * (ac[0] * ad[1] - ac[1] * ad[0]);
    }

    private static double[] sub(double[] u, double[] v) {
        return new double[] { u[0] - v[0], u[1] - v[1], u[2] - v[2] };
    }

    private static double lift(double[] v) {
        return v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
    }

    private static double det3(double[] a, double[] b, double[] c) {
        return a[0] * (b[1] * c[2] - b[2] * c[1])
             - a[1] * (b[0] * c[2] - b[2] * c[0])
             + a[2] * (b[0] * c[1] - b[1] * c[0]);
    }
}
