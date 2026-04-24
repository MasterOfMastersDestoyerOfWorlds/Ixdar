package unit.procgen.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.documentation.MeshNodeCatalog;
import ixdar.procgen.dungeon.nodes.AStarCorridorsNode;
import ixdar.procgen.dungeon.nodes.DelaunayGraphNode;
import ixdar.procgen.dungeon.nodes.DungeonGridToMeshNode;
import ixdar.procgen.dungeon.nodes.MinimumSpanningTreeNode;
import ixdar.procgen.dungeon.nodes.RandomRoomsNode;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue;
import ixdar.procgen.dungeon.values.TileGridValue;

public class DungeonNodeWrappersTest {

    @Test
    public void randomRoomsNodeProducesNonEmptyRoomList() {
        RandomRoomsNode node = new RandomRoomsNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("seed", 42);
        ctx.setInput("grid_w", 30);
        ctx.setInput("grid_h", 30);
        ctx.setInput("count", 10);
        ctx.setInput("min_size", 3);
        ctx.setInput("max_size", 6);
        ctx.setInput("max_attempts", 2000);
        node.evaluate(ctx);
        RoomListValue rooms = ctx.getOutput("rooms", RoomListValue.class);
        assertNotNull(rooms);
        assertTrue(rooms.size() > 0 && rooms.size() <= 10);
    }

    @Test
    public void delaunayGraphNodeProducesEdges() {
        RoomListValue rooms = placeRooms(42L);
        DelaunayGraphNode node = new DelaunayGraphNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("rooms", rooms);
        node.evaluate(ctx);
        EdgeGraphValue edges = ctx.getOutput("edges", EdgeGraphValue.class);
        assertNotNull(edges);
        assertTrue(edges.edgeCount() >= rooms.size() - 1,
                "Delaunay should produce at least a spanning set of edges");
    }

    @Test
    public void minimumSpanningTreeNodeProducesSpanningTree() {
        RoomListValue rooms = placeRooms(42L);
        DelaunayGraphNode dn = new DelaunayGraphNode();
        MapNodeContext dctx = new MapNodeContext(dn);
        dctx.setInput("rooms", rooms);
        dn.evaluate(dctx);
        EdgeGraphValue delaunay = dctx.getOutput("edges", EdgeGraphValue.class);

        MinimumSpanningTreeNode node = new MinimumSpanningTreeNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("edges", delaunay);
        ctx.setInput("rooms", rooms);
        ctx.setInput("extra_edge_prob", 0.0f);
        ctx.setInput("seed", 42);
        node.evaluate(ctx);
        EdgeGraphValue mst = ctx.getOutput("edges", EdgeGraphValue.class);
        assertEquals(rooms.size() - 1, mst.edgeCount(),
                "MST with zero extras should have n-1 edges");
    }

    @Test
    public void aStarCorridorsNodeProducesTileGrid() {
        RoomListValue rooms = placeRooms(42L);
        EdgeGraphValue mst = mstOf(rooms);
        AStarCorridorsNode node = new AStarCorridorsNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("rooms", rooms);
        ctx.setInput("edges", mst);
        ctx.setInput("grid_w", 30);
        ctx.setInput("grid_h", 30);
        ctx.setInput("reuse_cost", 1.0f);
        ctx.setInput("empty_cost", 5.0f);
        ctx.setInput("room_cost", 50.0f);
        node.evaluate(ctx);
        TileGridValue tiles = ctx.getOutput("tiles", TileGridValue.class);
        assertNotNull(tiles);
        assertEquals(30, tiles.width());
        assertEquals(30, tiles.height());
    }

    @Test
    public void dungeonGridToMeshNodeProducesArrayMesh() {
        RoomListValue rooms = placeRooms(42L);
        EdgeGraphValue mst = mstOf(rooms);
        AStarCorridorsNode a = new AStarCorridorsNode();
        MapNodeContext ac = new MapNodeContext(a);
        ac.setInput("rooms", rooms);
        ac.setInput("edges", mst);
        ac.setInput("grid_w", 30);
        ac.setInput("grid_h", 30);
        ac.setInput("reuse_cost", 1.0f);
        ac.setInput("empty_cost", 5.0f);
        ac.setInput("room_cost", 50.0f);
        a.evaluate(ac);
        TileGridValue tiles = ac.getOutput("tiles", TileGridValue.class);

        DungeonGridToMeshNode node = new DungeonGridToMeshNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("tiles", tiles);
        ctx.setInput("cell_size", 1.0f);
        node.evaluate(ctx);
        ArrayMesh mesh = ctx.getOutput("mesh", ArrayMesh.class);
        assertNotNull(mesh);
        assertTrue(mesh.vertexCount() > 0, "mesh should have vertices");
        assertTrue(mesh.faceCount() > 0, "mesh should have faces");
    }

    // --- Scope isolation — the load-bearing PROCGEN-1 correctness check -----

    @Test
    public void dungeonNodesAreHiddenFromMeshScope() {
        String meshJson = MeshNodeCatalog.toJsonFromAnnotationRegistry("mesh");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>)
                ((Map<String, Object>) new Gson().fromJson(meshJson, Map.class)).get("nodes");
        List<String> ids = nodes.stream().map(n -> (String) n.get("id")).toList();
        for (String id : List.of(
                "random_rooms", "delaunay_graph", "minimum_spanning_tree",
                "astar_corridors", "dungeon_grid_to_mesh")) {
            assertFalse(ids.contains(id),
                    "dungeon-only node '" + id + "' must NOT appear in mesh-scope catalog");
        }
    }

    @Test
    public void dungeonNodesAppearInDungeonScope() {
        String dungeonJson = MeshNodeCatalog.toJsonFromAnnotationRegistry("dungeon");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>)
                ((Map<String, Object>) new Gson().fromJson(dungeonJson, Map.class)).get("nodes");
        List<String> ids = nodes.stream().map(n -> (String) n.get("id")).toList();
        for (String id : List.of(
                "random_rooms", "delaunay_graph", "minimum_spanning_tree",
                "astar_corridors", "dungeon_grid_to_mesh")) {
            assertTrue(ids.contains(id),
                    "dungeon-scope catalog must include '" + id + "'");
        }
    }

    // --- Helpers --------------------------------------------------------

    private static RoomListValue placeRooms(long seed) {
        RandomRoomsNode n = new RandomRoomsNode();
        MapNodeContext ctx = new MapNodeContext(n);
        ctx.setInput("seed", (int) seed);
        ctx.setInput("grid_w", 30);
        ctx.setInput("grid_h", 30);
        ctx.setInput("count", 12);
        ctx.setInput("min_size", 3);
        ctx.setInput("max_size", 6);
        ctx.setInput("max_attempts", 2000);
        n.evaluate(ctx);
        return ctx.getOutput("rooms", RoomListValue.class);
    }

    private static EdgeGraphValue mstOf(RoomListValue rooms) {
        DelaunayGraphNode d = new DelaunayGraphNode();
        MapNodeContext dctx = new MapNodeContext(d);
        dctx.setInput("rooms", rooms);
        d.evaluate(dctx);
        EdgeGraphValue delaunay = dctx.getOutput("edges", EdgeGraphValue.class);
        MinimumSpanningTreeNode m = new MinimumSpanningTreeNode();
        MapNodeContext mctx = new MapNodeContext(m);
        mctx.setInput("edges", delaunay);
        mctx.setInput("rooms", rooms);
        mctx.setInput("extra_edge_prob", 0.125f);
        mctx.setInput("seed", 42);
        m.evaluate(mctx);
        return mctx.getOutput("edges", EdgeGraphValue.class);
    }
}
