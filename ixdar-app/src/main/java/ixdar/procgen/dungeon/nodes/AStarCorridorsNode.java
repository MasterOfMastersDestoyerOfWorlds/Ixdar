package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D.CostWeights;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue;
import ixdar.procgen.dungeon.values.TileGridValue;

@MeshNodeAnnotation(id = "astar_corridors", scopes = { "dungeon" })
public class AStarCorridorsNode implements MeshNode {

    private static final CostWeights D = AStarCorridorPathfinder2D.DEFAULT_WEIGHTS;

    private static final InputPort ROOMS = new InputPort("rooms", PortType.ROOM_LIST, null);
    private static final InputPort EDGES = new InputPort("edges", PortType.EDGE_GRAPH, null);
    private static final InputPort GRID_W = new InputPort("grid_w", PortType.INT, 30, 1f, 1000f);
    private static final InputPort GRID_H = new InputPort("grid_h", PortType.INT, 30, 1f, 1000f);
    private static final InputPort REUSE_COST = new InputPort(
            "reuse_cost", PortType.FLOAT, (float) D.hallwayReuseCost(), 0.001f, 100_000f);
    private static final InputPort EMPTY_COST = new InputPort(
            "empty_cost", PortType.FLOAT, (float) D.emptyCellCost(), 0.001f, 100_000f);
    private static final InputPort ROOM_COST = new InputPort(
            "room_cost", PortType.FLOAT, (float) D.throughRoomCost(), 0.001f, 1_000_000f);
    private static final OutputPort TILES = new OutputPort("tiles", PortType.TILE_GRID);

    @Override
    public List<InputPort> inputs() {
        return List.of(ROOMS, EDGES, GRID_W, GRID_H, REUSE_COST, EMPTY_COST, ROOM_COST);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(TILES);
    }

    @Override
    public String description() {
        return "Carves HALLWAY cells between room pairs via grid A*. Cost weights favor reusing "
                + "existing hallway cells over empty over passing through rooms. Stage 4 of the "
                + "dungeon pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                "rooms", "Room list used to paint ROOM cells in the grid and anchor corridor endpoints.",
                "edges", "MST (+ extras) edges to carve as corridors.",
                "grid_w", "Grid width in cells.",
                "grid_h", "Grid height in cells.",
                "reuse_cost", "Per-cell cost to step through an existing HALLWAY (cheap).",
                "empty_cost", "Per-cell cost to cut a new corridor through EMPTY space (moderate).",
                "room_cost", "Per-cell cost to path through a ROOM interior (steep to force go-around).",
                "tiles", "Fully-populated grid of CellType (EMPTY/ROOM/HALLWAY).");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        RoomListValue rooms = ctx.getInput("rooms", RoomListValue.class);
        EdgeGraphValue edges = ctx.getInput("edges", EdgeGraphValue.class);
        Number gw = ctx.getInput("grid_w", Number.class);
        Number gh = ctx.getInput("grid_h", Number.class);
        Number rc = ctx.getInput("reuse_cost", Number.class);
        Number ec = ctx.getInput("empty_cost", Number.class);
        Number roomC = ctx.getInput("room_cost", Number.class);
        if (rooms == null) throw new IllegalArgumentException("astar_corridors: missing 'rooms'");
        if (edges == null) throw new IllegalArgumentException("astar_corridors: missing 'edges'");
        int gridW = gw == null ? 30 : gw.intValue();
        int gridH = gh == null ? 30 : gh.intValue();
        CostWeights weights = new CostWeights(
                rc == null ? D.hallwayReuseCost() : rc.doubleValue(),
                ec == null ? D.emptyCellCost() : ec.doubleValue(),
                roomC == null ? D.throughRoomCost() : roomC.doubleValue());
        TileGridValue tiles = AStarCorridorPathfinder2D.carve(gridW, gridH, rooms, edges, weights);
        ctx.setOutput("tiles", tiles);
    }
}
