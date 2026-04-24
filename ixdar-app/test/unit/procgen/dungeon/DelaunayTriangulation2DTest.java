package unit.procgen.dungeon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.procgen.dungeon.algo.DelaunayTriangulation2D;
import ixdar.procgen.dungeon.algo.RoomPlacer;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue;
import ixdar.procgen.dungeon.values.RoomListValue.Room;

public class DelaunayTriangulation2DTest {

    private static RoomListValue roomsAt(float... xy) {
        List<Room> rs = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) {
            rs.add(new Room(i / 2, xy[i], xy[i + 1], 1, 1));
        }
        return new RoomListValue(rs);
    }

    @Test
    public void emptyInput() {
        EdgeGraphValue g = DelaunayTriangulation2D.triangulate(roomsAt());
        assertEquals(0, g.nodeCount());
        assertEquals(0, g.edgeCount());
    }

    @Test
    public void singleRoom() {
        EdgeGraphValue g = DelaunayTriangulation2D.triangulate(roomsAt(5, 5));
        assertEquals(1, g.nodeCount());
        assertEquals(0, g.edgeCount());
    }

    @Test
    public void twoRoomsHaveOneEdge() {
        EdgeGraphValue g = DelaunayTriangulation2D.triangulate(roomsAt(0, 0, 10, 0));
        assertEquals(2, g.nodeCount());
        assertEquals(1, g.edgeCount());
        assertArrayEquals(new int[] { 0, 1 }, g.edge(0));
    }

    @Test
    public void triangleHasThreeEdges() {
        EdgeGraphValue g = DelaunayTriangulation2D.triangulate(roomsAt(0, 0, 10, 0, 5, 10));
        assertEquals(3, g.edgeCount());
    }

    @Test
    public void fourPointsInSquareYieldFiveEdges() {
        // Square corners: Delaunay adds one diagonal (either one depending on configuration).
        EdgeGraphValue g = DelaunayTriangulation2D.triangulate(roomsAt(
                0, 0,
                10, 0,
                10, 10,
                0, 10));
        assertEquals(5, g.edgeCount(), "four coplanar corners -> 4 sides + 1 diagonal");
    }

    @Test
    public void emptyCircumcirclePropertyHolds() {
        // For every triangle implied by an edge triple that forms a closed face in the output,
        // no other room center lies strictly inside its circumscribed circle.
        RoomListValue rooms = RoomPlacer.place(42L, 30, 30, 12, 3, 6, 1000);
        EdgeGraphValue edges = DelaunayTriangulation2D.triangulate(rooms);
        // Reconstruct triangles by looking for 3-cycles in the edge graph.
        List<int[]> triangles = findTriangles(edges);
        assertTrue(triangles.size() > 0, "expected at least one triangle for n>=3");
        for (int[] tri : triangles) {
            for (int k = 0; k < rooms.size(); k++) {
                if (k == tri[0] || k == tri[1] || k == tri[2]) continue;
                Room ra = rooms.get(tri[0]);
                Room rb = rooms.get(tri[1]);
                Room rc = rooms.get(tri[2]);
                Room rd = rooms.get(k);
                assertTrue(!strictlyInCircumcircle(ra, rb, rc, rd),
                        "point " + k + " is inside circumcircle of triangle ("
                                + tri[0] + "," + tri[1] + "," + tri[2] + ")");
            }
        }
    }

    @Test
    public void isDeterministicForFixedInput() {
        RoomListValue rooms = RoomPlacer.place(7L, 30, 30, 15, 3, 8, 1000);
        EdgeGraphValue a = DelaunayTriangulation2D.triangulate(rooms);
        EdgeGraphValue b = DelaunayTriangulation2D.triangulate(rooms);
        assertEquals(a.edgeCount(), b.edgeCount());
        for (int i = 0; i < a.edgeCount(); i++) {
            assertArrayEquals(a.edge(i), b.edge(i), "edge " + i + " must match across runs");
        }
    }

    // ------------------------------------------------------------------------
    // Helpers

    /** Brute-force: find every 3-cycle in the (undirected, deduped) edge graph. */
    private static List<int[]> findTriangles(EdgeGraphValue edges) {
        int n = edges.nodeCount();
        boolean[][] adj = new boolean[n][n];
        for (int i = 0; i < edges.edgeCount(); i++) {
            int[] e = edges.edge(i);
            adj[e[0]][e[1]] = true;
            adj[e[1]][e[0]] = true;
        }
        List<int[]> tris = new ArrayList<>();
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                if (!adj[a][b]) continue;
                for (int c = b + 1; c < n; c++) {
                    if (adj[a][c] && adj[b][c]) {
                        tris.add(new int[] { a, b, c });
                    }
                }
            }
        }
        return tris;
    }

    /** Strict in-circumcircle predicate (false for points exactly on the circle). */
    private static boolean strictlyInCircumcircle(Room a, Room b, Room c, Room d) {
        double ax = a.centerX(), ay = a.centerY();
        double bx = b.centerX(), by = b.centerY();
        double cx = c.centerX(), cy = c.centerY();
        double dx = d.centerX(), dy = d.centerY();
        // Ensure CCW order for the determinant sign.
        double signed = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
        if (signed < 0) {
            double tx = bx, ty = by;
            bx = cx; by = cy;
            cx = tx; cy = ty;
        }
        double adx = ax - dx, ady = ay - dy;
        double bdx = bx - dx, bdy = by - dy;
        double cdx = cx - dx, cdy = cy - dy;
        double alift = adx * adx + ady * ady;
        double blift = bdx * bdx + bdy * bdy;
        double clift = cdx * cdx + cdy * cdy;
        double det = alift * (bdx * cdy - cdx * bdy)
                + blift * (cdx * ady - adx * cdy)
                + clift * (adx * bdy - bdx * ady);
        // A small epsilon slack so that points landing *exactly* on the circle (a legal Delaunay
        // ambiguity) don't trip the test.
        return det > 1e-9;
    }
}
