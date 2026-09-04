package ixdar.procgen.dungeon.nodes;

import java.util.Objects;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder3D;
import ixdar.procgen.dungeon.algo.DungeonGrids;
import ixdar.procgen.dungeon.values.CellType;

@MeshNodeAnnotation(id = "astar_corridors_3d", scopes = { "dungeon" })
public class AStarCorridors3DNode implements MeshNode {
    public static final int NUM_30 = 30;
    public static final int NUM_5 = 5;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, null);
    public static final InputPort GRID_W = new InputPort("grid_w", PortType.INT, 30, 1f, 1000f);
    public static final InputPort GRID_H = new InputPort("grid_h", PortType.INT, 5, 1f, 100f);
    public static final InputPort GRID_D = new InputPort("grid_d", PortType.INT, 30, 1f, 1000f);
    public static final InputPort REUSE_COST = new InputPort("reuse_cost", PortType.FLOAT,
            (float) AStarCorridorPathfinder2D.DEFAULT_HALLWAY_REUSE_COST, 0.001f, 100_000f);
    public static final InputPort EMPTY_COST = new InputPort("empty_cost", PortType.FLOAT,
            (float) AStarCorridorPathfinder2D.DEFAULT_EMPTY_CELL_COST, 0.001f, 100_000f);
    public static final InputPort ROOM_COST = new InputPort("room_cost", PortType.FLOAT,
            (float) AStarCorridorPathfinder2D.DEFAULT_THROUGH_ROOM_COST, 0.001f, 1_000_000f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, SELECTION, GRID_W, GRID_H, GRID_D, REUSE_COST, EMPTY_COST, ROOM_COST);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "3D corridor carving via A* with stair transitions between floors, over the selected "
                + "edges of the rooms graph. Emits the tile grid as a point lattice (one vertex per "
                + "cell center) with a per-vertex 'cell_type' int attribute. Kept separate from "
                + "astar_corridors because the stair move set has no 2D analog. Stage 4 of the 3D pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output bundle. In: rooms graph (delaunay_graph output) with 3D "
                        + "'half_extent' points and wire edges as corridor candidates. Out: point "
                        + "lattice with one vertex per cell center (x+0.5, y+0.5, z+0.5) and the "
                        + "per-vertex 'cell_type' IntField of CellType ordinals (EMPTY/ROOM/HALLWAY/STAIR_*).",
                SELECTION.name, "Per-edge BOOLEAN mask of edges to carve (typically minimum_spanning_tree output). Missing selects every edge.",
                GRID_W.name, "Grid width (X).",
                GRID_H.name, "Grid height (Y) in floors.",
                GRID_D.name, "Grid depth (Z).",
                REUSE_COST.name, "Cost to step through an existing HALLWAY/STAIR cell.",
                EMPTY_COST.name, "Cost to cut through EMPTY space.",
                ROOM_COST.name, "Cost to path through a ROOM interior.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle graph = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        Object selObj = ctx.getInput(SELECTION.name, Object.class);
        BoolField selection = selObj instanceof BoolField b ? b : null;
        Number gw = ctx.getInput(GRID_W.name, Number.class);
        Number gh = ctx.getInput(GRID_H.name, Number.class);
        Number gd = ctx.getInput(GRID_D.name, Number.class);
        Number rc = ctx.getInput(REUSE_COST.name, Number.class);
        Number ec = ctx.getInput(EMPTY_COST.name, Number.class);
        Number roomC = ctx.getInput(ROOM_COST.name, Number.class);
        int gridW = gw == null ? NUM_30 : gw.intValue();
        int gridH = gh == null ? NUM_5 : gh.intValue();
        int gridD = gd == null ? NUM_30 : gd.intValue();
        double reuseCost = rc == null ? AStarCorridorPathfinder2D.DEFAULT_HALLWAY_REUSE_COST : rc.doubleValue();
        double emptyCost = ec == null ? AStarCorridorPathfinder2D.DEFAULT_EMPTY_CELL_COST : ec.doubleValue();
        double roomCost = roomC == null ? AStarCorridorPathfinder2D.DEFAULT_THROUGH_ROOM_COST : roomC.doubleValue();
        int[] pairs = DungeonGrids.selectedEdgePairs(graph.mesh(), selection);
        CellType[] cells = AStarCorridorPathfinder3D.carve(
                gridW, gridH, gridD, graph.mesh(), DungeonGrids.halfExtents(graph), pairs,
                reuseCost, emptyCost, roomCost);
        ctx.setOutput(GEOMETRY.name, DungeonGrids.latticeBundle(gridW, gridH, gridD, cells));
    }
}
