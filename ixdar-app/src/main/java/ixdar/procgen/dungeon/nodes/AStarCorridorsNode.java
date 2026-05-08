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
    public static final String ROOMS_2 = "rooms";
    public static final String EDGES_2 = "edges";
    public static final String GRID_W_2 = "grid_w";
    public static final String GRID_H_2 = "grid_h";
    public static final String REUSE_COST_2 = "reuse_cost";
    public static final String EMPTY_COST_2 = "empty_cost";
    public static final String ROOM_COST_2 = "room_cost";
    public static final String TILES_2 = "tiles";
    public static final int NUM_30 = 30;

    private static final CostWeights D = AStarCorridorPathfinder2D.DEFAULT_WEIGHTS;

    private static final InputPort ROOMS = new InputPort(ROOMS_2, PortType.ROOM_LIST, null);
    private static final InputPort EDGES = new InputPort(EDGES_2, PortType.EDGE_GRAPH, null);
    private static final InputPort GRID_W = new InputPort(GRID_W_2, PortType.INT, 30, 1f, 1000f);
    private static final InputPort GRID_H = new InputPort(GRID_H_2, PortType.INT, 30, 1f, 1000f);
    private static final InputPort REUSE_COST = new InputPort(
            REUSE_COST_2, PortType.FLOAT, (float) D.hallwayReuseCost(), 0.001f, 100_000f);
    private static final InputPort EMPTY_COST = new InputPort(
            EMPTY_COST_2, PortType.FLOAT, (float) D.emptyCellCost(), 0.001f, 100_000f);
    private static final InputPort ROOM_COST = new InputPort(
            ROOM_COST_2, PortType.FLOAT, (float) D.throughRoomCost(), 0.001f, 1_000_000f);
    private static final OutputPort TILES = new OutputPort(TILES_2, PortType.TILE_GRID);

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
                ROOMS_2, "Room list used to paint ROOM cells in the grid and anchor corridor endpoints.",
                EDGES_2, "MST (+ extras) edges to carve as corridors.",
                GRID_W_2, "Grid width in cells.",
                GRID_H_2, "Grid height in cells.",
                REUSE_COST_2, "Per-cell cost to step through an existing HALLWAY (cheap).",
                EMPTY_COST_2, "Per-cell cost to cut a new corridor through EMPTY space (moderate).",
                ROOM_COST_2, "Per-cell cost to path through a ROOM interior (steep to force go-around).",
                TILES_2, "Fully-populated grid of CellType (EMPTY/ROOM/HALLWAY).");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        RoomListValue rooms = ctx.getInput(ROOMS_2, RoomListValue.class);
        EdgeGraphValue edges = ctx.getInput(EDGES_2, EdgeGraphValue.class);
        Number gw = ctx.getInput(GRID_W_2, Number.class);
        Number gh = ctx.getInput(GRID_H_2, Number.class);
        Number rc = ctx.getInput(REUSE_COST_2, Number.class);
        Number ec = ctx.getInput(EMPTY_COST_2, Number.class);
        Number roomC = ctx.getInput(ROOM_COST_2, Number.class);
        if (rooms == null) throw new IllegalArgumentException("astar_corridors: missing 'rooms'");
        if (edges == null) throw new IllegalArgumentException("astar_corridors: missing 'edges'");
        int gridW = gw == null ? NUM_30 : gw.intValue();
        int gridH = gh == null ? NUM_30 : gh.intValue();
        CostWeights weights = new CostWeights(
                rc == null ? D.hallwayReuseCost() : rc.doubleValue(),
                ec == null ? D.emptyCellCost() : ec.doubleValue(),
                roomC == null ? D.throughRoomCost() : roomC.doubleValue());
        TileGridValue tiles = AStarCorridorPathfinder2D.carve(gridW, gridH, rooms, edges, weights);
        ctx.setOutput(TILES_2, tiles);
    }
}
