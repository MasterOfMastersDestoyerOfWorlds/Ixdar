package unit.procgen.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.procgen.dungeon.algo.DelaunayTriangulation2D;
import ixdar.procgen.dungeon.algo.PrimMinimumSpanningTree;
import ixdar.procgen.dungeon.algo.RoomPlacer;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue;
import ixdar.procgen.dungeon.values.RoomListValue.Room;

public class PrimMinimumSpanningTreeTest {

    private static RoomListValue roomsAt(float... xy) {
        List<Room> rs = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) {
            rs.add(new Room(i / 2, xy[i], xy[i + 1], 1, 1));
        }
        return new RoomListValue(rs);
    }

    private static boolean[] reachability(EdgeGraphValue g, int start) {
        boolean[] seen = new boolean[g.nodeCount()];
        int[] stack = new int[g.nodeCount()];
        int sp = 0;
        seen[start] = true;
        stack[sp++] = start;
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < g.nodeCount(); i++) adj.add(new ArrayList<>());
        for (int i = 0; i < g.edgeCount(); i++) {
            int[] e = g.edge(i);
            adj.get(e[0]).add(e[1]);
            adj.get(e[1]).add(e[0]);
        }
        while (sp > 0) {
            int v = stack[--sp];
            for (int n : adj.get(v)) {
                if (!seen[n]) {
                    seen[n] = true;
                    stack[sp++] = n;
                }
            }
        }
        return seen;
    }

    @Test
    public void mstWithoutExtrasHasExactlyNMinusOneEdges() {
        RoomListValue rooms = RoomPlacer.place(42L, 30, 30, 15, 3, 6, 1000);
        EdgeGraphValue tri = DelaunayTriangulation2D.triangulate(rooms);
        EdgeGraphValue mst = PrimMinimumSpanningTree.build(tri, rooms, 0.0, 0L);
        assertEquals(rooms.size() - 1, mst.edgeCount(),
                "MST with zero extra-edge probability should have n-1 edges");
    }

    @Test
    public void mstSpansAllRooms() {
        RoomListValue rooms = RoomPlacer.place(42L, 30, 30, 15, 3, 6, 1000);
        EdgeGraphValue tri = DelaunayTriangulation2D.triangulate(rooms);
        EdgeGraphValue mst = PrimMinimumSpanningTree.build(tri, rooms, 0.0, 0L);
        boolean[] reached = reachability(mst, 0);
        for (int i = 0; i < rooms.size(); i++) {
            assertTrue(reached[i], "room " + i + " unreachable from root in MST");
        }
    }

    @Test
    public void isSubsetOfDelaunayEdges() {
        RoomListValue rooms = RoomPlacer.place(42L, 30, 30, 15, 3, 6, 1000);
        EdgeGraphValue tri = DelaunayTriangulation2D.triangulate(rooms);
        EdgeGraphValue mst = PrimMinimumSpanningTree.build(tri, rooms, 0.3, 999L);
        Set<String> delaunaySet = new HashSet<>();
        for (int i = 0; i < tri.edgeCount(); i++) {
            int[] e = tri.edge(i);
            delaunaySet.add(key(e));
        }
        for (int i = 0; i < mst.edgeCount(); i++) {
            int[] e = mst.edge(i);
            assertTrue(delaunaySet.contains(key(e)),
                    "MST edge " + Arrays.toString(e) + " is not in Delaunay edge set");
        }
    }

    private static String key(int[] e) {
        int a = Math.min(e[0], e[1]);
        int b = Math.max(e[0], e[1]);
        return a + "-" + b;
    }

    @Test
    public void mstTotalWeightIsMinimal() {
        // Hand-constructed 5-room instance where the MST is uniquely determined.
        // Layout:
        //   A(0,0)  B(5,0)  C(10,0)
        //           D(5,5)  E(10,5)
        // Edges in Delaunay: A-B, B-C, B-D, D-E, C-E, and probably A-D and B-E as diagonals.
        // The unique MST uses A-B, B-C, B-D, D-E (total weight: 5 + 5 + 5 + 5 = 20).
        RoomListValue rooms = roomsAt(
                0, 0,
                5, 0,
                10, 0,
                5, 5,
                10, 5);
        EdgeGraphValue tri = DelaunayTriangulation2D.triangulate(rooms);
        EdgeGraphValue mst = PrimMinimumSpanningTree.build(tri, rooms, 0.0, 0L);
        double total = 0;
        for (int i = 0; i < mst.edgeCount(); i++) {
            int[] e = mst.edge(i);
            Room a = rooms.get(e[0]);
            Room b = rooms.get(e[1]);
            double dx = a.centerX() - b.centerX();
            double dy = a.centerY() - b.centerY();
            total += Math.sqrt(dx * dx + dy * dy);
        }
        // Optimal MST weight = 20.
        assertEquals(20.0, total, 1e-6, "hand-verified MST total weight should be 20");
    }

    @Test
    public void extraEdgeRngIsDeterministic() {
        RoomListValue rooms = RoomPlacer.place(42L, 30, 30, 15, 3, 6, 1000);
        EdgeGraphValue tri = DelaunayTriangulation2D.triangulate(rooms);
        EdgeGraphValue a = PrimMinimumSpanningTree.build(tri, rooms, 0.25, 123L);
        EdgeGraphValue b = PrimMinimumSpanningTree.build(tri, rooms, 0.25, 123L);
        assertEquals(a.edgeCount(), b.edgeCount());
        for (int i = 0; i < a.edgeCount(); i++) {
            int[] ea = a.edge(i);
            int[] eb = b.edge(i);
            assertTrue(ea[0] == eb[0] && ea[1] == eb[1],
                    "extra-edge pass must be deterministic for same seed");
        }
    }

    @Test
    public void extraEdgesIncreaseEdgeCountMonotonically() {
        RoomListValue rooms = RoomPlacer.place(42L, 30, 30, 15, 3, 6, 1000);
        EdgeGraphValue tri = DelaunayTriangulation2D.triangulate(rooms);
        EdgeGraphValue mstOnly = PrimMinimumSpanningTree.build(tri, rooms, 0.0, 0L);
        EdgeGraphValue mstAll = PrimMinimumSpanningTree.build(tri, rooms, 1.0, 0L);
        assertEquals(tri.edgeCount(), mstAll.edgeCount(),
                "extraEdgeProb=1.0 should keep every Delaunay edge");
        assertTrue(mstAll.edgeCount() >= mstOnly.edgeCount(),
                "more extras -> more edges");
    }

    @Test
    public void singleRoomHasZeroEdges() {
        RoomListValue rooms = roomsAt(5, 5);
        EdgeGraphValue tri = DelaunayTriangulation2D.triangulate(rooms);
        EdgeGraphValue mst = PrimMinimumSpanningTree.build(tri, rooms, 0.5, 0L);
        assertEquals(0, mst.edgeCount());
    }
}
