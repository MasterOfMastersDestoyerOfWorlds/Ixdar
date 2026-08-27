package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D.CostWeights;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder3D;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue3D;
import ixdar.procgen.dungeon.values.TileGridValue3D;

@MeshNodeAnnotation(id = "astar_corridors_3d", scopes = { "dungeon" })
public class AStarCorridors3DNode implements MeshNode {
    public static final int NUM_30 = 30;
    public static final int NUM_5 = 5;

    public static final InputPort ROOMS = new InputPort("rooms", PortType.ROOM_LIST_3D, null);
    public static final InputPort EDGES = new InputPort("edges", PortType.EDGE_GRAPH, null);
    public static final InputPort GRID_W = new InputPort("grid_w", PortType.INT, 30, 1f, 1000f);
    public static final InputPort GRID_H = new InputPort("grid_h", PortType.INT, 5, 1f, 100f);
    public static final InputPort GRID_D = new InputPort("grid_d", PortType.INT, 30, 1f, 1000f);
    public static final InputPort REUSE_COST = new InputPort("reuse_cost", PortType.FLOAT,
            (float) AStarCorridorPathfinder2D.DEFAULT_WEIGHTS.hallwayReuseCost(), 0.001f, 100_000f);
    public static final InputPort EMPTY_COST = new InputPort("empty_cost", PortType.FLOAT,
            (float) AStarCorridorPathfinder2D.DEFAULT_WEIGHTS.emptyCellCost(), 0.001f, 100_000f);
    public static final InputPort ROOM_COST = new InputPort("room_cost", PortType.FLOAT,
            (float) AStarCorridorPathfinder2D.DEFAULT_WEIGHTS.throughRoomCost(), 0.001f, 1_000_000f);
    public static final OutputPort TILES = new OutputPort("tiles", PortType.TILE_GRID_3D);

    private static final CostWeights D = AStarCorridorPathfinder2D.DEFAULT_WEIGHTS;

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
                ROOMS.name, "3D rooms.",
                EDGES.name, "MST + extras edges to carve.",
                GRID_W.name, "Grid width (X).",
                GRID_H.name, "Grid height (Y) in floors.",
                GRID_D.name, "Grid depth (Z).",
                REUSE_COST.name, "Cost to step through an existing HALLWAY/STAIR cell.",
                EMPTY_COST.name, "Cost to cut through EMPTY space.",
                ROOM_COST.name, "Cost to path through a ROOM interior.",
                TILES.name, "Populated 3D grid of CellType.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        RoomListValue3D rooms = ctx.getInput(ROOMS.name, RoomListValue3D.class);
        EdgeGraphValue edges = ctx.getInput(EDGES.name, EdgeGraphValue.class);
        Number gw = ctx.getInput(GRID_W.name, Number.class);
        Number gh = ctx.getInput(GRID_H.name, Number.class);
        Number gd = ctx.getInput(GRID_D.name, Number.class);
        Number rc = ctx.getInput(REUSE_COST.name, Number.class);
        Number ec = ctx.getInput(EMPTY_COST.name, Number.class);
        Number roomC = ctx.getInput(ROOM_COST.name, Number.class);
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
        ctx.setOutput(TILES.name, tiles);
    }
}
