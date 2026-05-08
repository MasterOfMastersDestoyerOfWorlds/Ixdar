package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D.CostWeights;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder3D;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue3D;
import ixdar.procgen.dungeon.values.TileGridValue3D;

@MeshNodeAnnotation(id = "astar_corridors_3d", scopes = { "dungeon" })
public class AStarCorridors3DNode implements MeshNode {
    public static final String ROOMS_2 = "rooms";
    public static final String EDGES_2 = "edges";
    public static final String GRID_W_2 = "grid_w";
    public static final String GRID_H_2 = "grid_h";
    public static final String GRID_D_2 = "grid_d";
    public static final String REUSE_COST_2 = "reuse_cost";
    public static final String EMPTY_COST_2 = "empty_cost";
    public static final String ROOM_COST_2 = "room_cost";
    public static final String TILES_2 = "tiles";
    public static final int NUM_30 = 30;
    public static final int NUM_5 = 5;

    private static final CostWeights D = AStarCorridorPathfinder2D.DEFAULT_WEIGHTS;

    private static final InputPort ROOMS = new InputPort(ROOMS_2, PortType.ROOM_LIST_3D, null);
    private static final InputPort EDGES = new InputPort(EDGES_2, PortType.EDGE_GRAPH, null);
    private static final InputPort GRID_W = new InputPort(GRID_W_2, PortType.INT, 30, 1f, 1000f);
    private static final InputPort GRID_H = new InputPort(GRID_H_2, PortType.INT, 5, 1f, 100f);
    private static final InputPort GRID_D = new InputPort(GRID_D_2, PortType.INT, 30, 1f, 1000f);
    private static final InputPort REUSE_COST = new InputPort(
            REUSE_COST_2, PortType.FLOAT, (float) D.hallwayReuseCost(), 0.001f, 100_000f);
    private static final InputPort EMPTY_COST = new InputPort(
            EMPTY_COST_2, PortType.FLOAT, (float) D.emptyCellCost(), 0.001f, 100_000f);
    private static final InputPort ROOM_COST = new InputPort(
            ROOM_COST_2, PortType.FLOAT, (float) D.throughRoomCost(), 0.001f, 1_000_000f);
    private static final OutputPort TILES = new OutputPort(TILES_2, PortType.TILE_GRID_3D);

    @Override
    public List<InputPort> inputs() {
        return List.of(ROOMS, EDGES, GRID_W, GRID_H, GRID_D, REUSE_COST, EMPTY_COST, ROOM_COST);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(TILES);
    }

    @Override
    public String description() {
        return "3D corridor carving via A* with stair transitions between floors. Stage 4 of the 3D pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                ROOMS_2, "3D rooms.",
                EDGES_2, "MST + extras edges to carve.",
                GRID_W_2, "Grid width (X).",
                GRID_H_2, "Grid height (Y) in floors.",
                GRID_D_2, "Grid depth (Z).",
                REUSE_COST_2, "Cost to step through an existing HALLWAY/STAIR cell.",
                EMPTY_COST_2, "Cost to cut through EMPTY space.",
                ROOM_COST_2, "Cost to path through a ROOM interior.",
                TILES_2, "Populated 3D grid of CellType.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        RoomListValue3D rooms = ctx.getInput(ROOMS_2, RoomListValue3D.class);
        EdgeGraphValue edges = ctx.getInput(EDGES_2, EdgeGraphValue.class);
        Number gw = ctx.getInput(GRID_W_2, Number.class);
        Number gh = ctx.getInput(GRID_H_2, Number.class);
        Number gd = ctx.getInput(GRID_D_2, Number.class);
        Number rc = ctx.getInput(REUSE_COST_2, Number.class);
        Number ec = ctx.getInput(EMPTY_COST_2, Number.class);
        Number roomC = ctx.getInput(ROOM_COST_2, Number.class);
        if (rooms == null) throw new IllegalArgumentException("astar_corridors_3d: missing 'rooms'");
        if (edges == null) throw new IllegalArgumentException("astar_corridors_3d: missing 'edges'");
        int gridW = gw == null ? NUM_30 : gw.intValue();
        int gridH = gh == null ? NUM_5 : gh.intValue();
        int gridD = gd == null ? NUM_30 : gd.intValue();
        CostWeights weights = new CostWeights(
                rc == null ? D.hallwayReuseCost() : rc.doubleValue(),
                ec == null ? D.emptyCellCost() : ec.doubleValue(),
                roomC == null ? D.throughRoomCost() : roomC.doubleValue());
        TileGridValue3D tiles = AStarCorridorPathfinder3D.carve(gridW, gridH, gridD, rooms, edges, weights);
        ctx.setOutput(TILES_2, tiles);
    }
}
