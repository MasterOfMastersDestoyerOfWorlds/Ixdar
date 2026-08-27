package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D.CostWeights;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue;
import ixdar.procgen.dungeon.values.TileGridValue;

@MeshNodeAnnotation(id = "astar_corridors", scopes = { "dungeon" })
public class AStarCorridorsNode implements MeshNode {
    public static final int NUM_30 = 30;

    public static final InputPort ROOMS = new InputPort("rooms", PortType.ROOM_LIST, null);
    public static final InputPort EDGES = new InputPort("edges", PortType.EDGE_GRAPH, null);
    public static final InputPort GRID_W = new InputPort("grid_w", PortType.INT, 30, 1f, 1000f);
    public static final InputPort GRID_H = new InputPort("grid_h", PortType.INT, 30, 1f, 1000f);
    public static final InputPort REUSE_COST = new InputPort("reuse_cost", PortType.FLOAT,
            (float) AStarCorridorPathfinder2D.DEFAULT_WEIGHTS.hallwayReuseCost(), 0.001f, 100_000f);
    public static final InputPort EMPTY_COST = new InputPort("empty_cost", PortType.FLOAT,
            (float) AStarCorridorPathfinder2D.DEFAULT_WEIGHTS.emptyCellCost(), 0.001f, 100_000f);
    public static final InputPort ROOM_COST = new InputPort("room_cost", PortType.FLOAT,
            (float) AStarCorridorPathfinder2D.DEFAULT_WEIGHTS.throughRoomCost(), 0.001f, 1_000_000f);
    public static final OutputPort TILES = new OutputPort("tiles", PortType.TILE_GRID);

    private static final CostWeights D = AStarCorridorPathfinder2D.DEFAULT_WEIGHTS;

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
                ROOMS.name, "Room list used to paint ROOM cells in the grid and anchor corridor endpoints.",
                EDGES.name, "MST (+ extras) edges to carve as corridors.",
                GRID_W.name, "Grid width in cells.",
                GRID_H.name, "Grid height in cells.",
                REUSE_COST.name, "Per-cell cost to step through an existing HALLWAY (cheap).",
                EMPTY_COST.name, "Per-cell cost to cut a new corridor through EMPTY space (moderate).",
                ROOM_COST.name, "Per-cell cost to path through a ROOM interior (steep to force go-around).",
                TILES.name, "Fully-populated grid of CellType (EMPTY/ROOM/HALLWAY).");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        RoomListValue rooms = ctx.getInput(ROOMS.name, RoomListValue.class);
        EdgeGraphValue edges = ctx.getInput(EDGES.name, EdgeGraphValue.class);
        Number gw = ctx.getInput(GRID_W.name, Number.class);
        Number gh = ctx.getInput(GRID_H.name, Number.class);
        Number rc = ctx.getInput(REUSE_COST.name, Number.class);
        Number ec = ctx.getInput(EMPTY_COST.name, Number.class);
        Number roomC = ctx.getInput(ROOM_COST.name, Number.class);
        if (rooms == null) throw new IllegalArgumentException("astar_corridors: missing 'rooms'");
        if (edges == null) throw new IllegalArgumentException("astar_corridors: missing 'edges'");
        int gridW = gw == null ? NUM_30 : gw.intValue();
        int gridH = gh == null ? NUM_30 : gh.intValue();
        CostWeights weights = new CostWeights(
                rc == null ? D.hallwayReuseCost() : rc.doubleValue(),
                ec == null ? D.emptyCellCost() : ec.doubleValue(),
                roomC == null ? D.throughRoomCost() : roomC.doubleValue());
        TileGridValue tiles = AStarCorridorPathfinder2D.carve(gridW, gridH, rooms, edges, weights);
        ctx.setOutput(TILES.name, tiles);
    }
}
