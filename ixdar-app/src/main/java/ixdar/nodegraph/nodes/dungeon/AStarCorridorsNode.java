package ixdar.nodegraph.nodes.dungeon;

import java.util.Objects;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.field.BoolField;
import ixdar.nodegraph.field.Vector3Field;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder3D;
import ixdar.procgen.dungeon.algo.DungeonGrids;
import ixdar.procgen.dungeon.values.CellType;

@MeshNodeAnnotation(id = "astar_corridors", scopes = { "dungeon" }, category = NodeCategory.DUNGEON)
public class AStarCorridorsNode implements MeshNode {
    public static final int DEFAULT_GRID_SIZE = 30;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, null);
    public static final InputPort GRID_W = new InputPort("grid_w", PortType.INT, 30, 1f, 1000f);
    public static final InputPort GRID_H = new InputPort("grid_h", PortType.INT, 30, 1f, 1000f);
    public static final InputPort GRID_D = new InputPort("grid_d", PortType.INT, 0, 0f, 1000f);
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
        return "Carves HALLWAY cells between room pairs via grid A* over the selected edges of the "
                + "rooms graph. Cost weights favor reusing existing hallway cells over empty over "
                + "passing through rooms. grid_d = 0 emits the planar tile grid as quad-grid geometry "
                + "with a per-face 'cell_type' int attribute; grid_d > 0 carves across grid_h floors "
                + "with stair transitions and emits a point lattice, one vertex per cell center, with "
                + "a per-vertex 'cell_type'. Stage 4 of the dungeon pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output bundle. In: rooms graph (delaunay_graph output) with "
                        + "'half_extent' points and wire edges as corridor candidates. Out, planar: quad "
                        + "grid over [0,grid_w]x[0,grid_h] on the XZ plane, one face per cell, with the "
                        + "per-face 'cell_type' IntField of CellType ordinals (EMPTY/ROOM/HALLWAY). Out, "
                        + "grid_d > 0: point lattice with one vertex per cell center (x+0.5, y+0.5, z+0.5) "
                        + "and the per-vertex 'cell_type' IntField (EMPTY/ROOM/HALLWAY/STAIR_*).",
                SELECTION.name, "Per-edge BOOLEAN mask of edges to carve (typically minimum_spanning_tree output). Missing selects every edge.",
                GRID_W.name, "Grid width (X) in cells.",
                GRID_H.name, "Grid height in cells: the second planar axis when grid_d = 0, the floor count (Y) when grid_d > 0.",
                GRID_D.name, "Grid depth (Z) in cells. 0 (default) carves the planar grid.",
                REUSE_COST.name, "Per-cell cost to step through an existing HALLWAY or STAIR cell (cheap).",
                EMPTY_COST.name, "Per-cell cost to cut a new corridor through EMPTY space (moderate).",
                ROOM_COST.name, "Per-cell cost to path through a ROOM interior (steep to force go-around).");
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
        int gridW = gw == null ? DEFAULT_GRID_SIZE : gw.intValue();
        int gridH = gh == null ? DEFAULT_GRID_SIZE : gh.intValue();
        int gridD = gd == null ? 0 : gd.intValue();
        double reuseCost = rc == null ? AStarCorridorPathfinder2D.DEFAULT_HALLWAY_REUSE_COST : rc.doubleValue();
        double emptyCost = ec == null ? AStarCorridorPathfinder2D.DEFAULT_EMPTY_CELL_COST : ec.doubleValue();
        double roomCost = roomC == null ? AStarCorridorPathfinder2D.DEFAULT_THROUGH_ROOM_COST : roomC.doubleValue();
        int[] pairs = DungeonGrids.selectedEdgePairs(graph.mesh(), selection);
        Vector3Field halfExtents = DungeonGrids.halfExtents(graph);
        if (gridD <= 0) {
            CellType[] cells = AStarCorridorPathfinder2D.carve(
                    gridW, gridH, graph.mesh(), halfExtents, pairs, reuseCost, emptyCost, roomCost);
            ctx.setOutput(GEOMETRY.name, DungeonGrids.gridBundle(gridW, gridH, cells));
        } else {
            CellType[] cells = AStarCorridorPathfinder3D.carve(
                    gridW, gridH, gridD, graph.mesh(), halfExtents, pairs, reuseCost, emptyCost, roomCost);
            ctx.setOutput(GEOMETRY.name, DungeonGrids.latticeBundle(gridW, gridH, gridD, cells));
        }
    }
}
