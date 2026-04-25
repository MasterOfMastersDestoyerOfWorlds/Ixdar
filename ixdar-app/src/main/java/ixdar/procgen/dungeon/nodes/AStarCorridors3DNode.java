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

    private static final CostWeights D = AStarCorridorPathfinder2D.DEFAULT_WEIGHTS;

    private static final InputPort ROOMS = new InputPort("rooms", PortType.ROOM_LIST_3D, null);
    private static final InputPort EDGES = new InputPort("edges", PortType.EDGE_GRAPH, null);
    private static final InputPort GRID_W = new InputPort("grid_w", PortType.INT, 30, 1f, 1000f);
    private static final InputPort GRID_H = new InputPort("grid_h", PortType.INT, 5, 1f, 100f);
    private static final InputPort GRID_D = new InputPort("grid_d", PortType.INT, 30, 1f, 1000f);
    private static final InputPort REUSE_COST = new InputPort(
            "reuse_cost", PortType.FLOAT, (float) D.hallwayReuseCost(), 0.001f, 100_000f);
    private static final InputPort EMPTY_COST = new InputPort(
            "empty_cost", PortType.FLOAT, (float) D.emptyCellCost(), 0.001f, 100_000f);
    private static final InputPort ROOM_COST = new InputPort(
            "room_cost", PortType.FLOAT, (float) D.throughRoomCost(), 0.001f, 1_000_000f);
    private static final OutputPort TILES = new OutputPort("tiles", PortType.TILE_GRID_3D);

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
                "rooms", "3D rooms.",
                "edges", "MST + extras edges to carve.",
                "grid_w", "Grid width (X).",
                "grid_h", "Grid height (Y) in floors.",
                "grid_d", "Grid depth (Z).",
                "reuse_cost", "Cost to step through an existing HALLWAY/STAIR cell.",
                "empty_cost", "Cost to cut through EMPTY space.",
                "room_cost", "Cost to path through a ROOM interior.",
                "tiles", "Populated 3D grid of CellType.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        RoomListValue3D rooms = ctx.getInput("rooms", RoomListValue3D.class);
        EdgeGraphValue edges = ctx.getInput("edges", EdgeGraphValue.class);
        Number gw = ctx.getInput("grid_w", Number.class);
        Number gh = ctx.getInput("grid_h", Number.class);
        Number gd = ctx.getInput("grid_d", Number.class);
        Number rc = ctx.getInput("reuse_cost", Number.class);
        Number ec = ctx.getInput("empty_cost", Number.class);
        Number roomC = ctx.getInput("room_cost", Number.class);
        if (rooms == null) throw new IllegalArgumentException("astar_corridors_3d: missing 'rooms'");
        if (edges == null) throw new IllegalArgumentException("astar_corridors_3d: missing 'edges'");
        int gridW = gw == null ? 30 : gw.intValue();
        int gridH = gh == null ? 5 : gh.intValue();
        int gridD = gd == null ? 30 : gd.intValue();
        CostWeights weights = new CostWeights(
                rc == null ? D.hallwayReuseCost() : rc.doubleValue(),
                ec == null ? D.emptyCellCost() : ec.doubleValue(),
                roomC == null ? D.throughRoomCost() : roomC.doubleValue());
        TileGridValue3D tiles = AStarCorridorPathfinder3D.carve(gridW, gridH, gridD, rooms, edges, weights);
        ctx.setOutput("tiles", tiles);
    }
}
