package ixdar.procgen.dungeon.nodes;

import java.util.Objects;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.procgen.dungeon.algo.DungeonGrids;
import ixdar.procgen.dungeon.algo.GridToMesh2D;
import ixdar.procgen.dungeon.values.CellType;

@MeshNodeAnnotation(id = "dungeon_grid_to_mesh", scopes = { "dungeon" })
public class DungeonGridToMeshNode implements MeshNode {
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort CELL_SIZE = new InputPort("cell_size", PortType.FLOAT, 1.0f, 0.01f, 100f);
    public static final OutputPort MESH = new OutputPort("mesh", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, CELL_SIZE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public String description() {
        return "Emits an axis-aligned hollow-room mesh from 2D tile-grid geometry (quad grid with a "
                + "per-face 'cell_type' attribute): floor, ceiling, and EMPTY-bordering walls per "
                + "non-empty cell. Stage 5 (final) of the dungeon pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Tile grid from astar_corridors: quad-grid geometry with the per-face 'cell_type' IntField.",
                CELL_SIZE.name, "Edge length of one cell in world units.",
                MESH.name, "Origin-centered ArrayMesh with double-sided quads and computed normals.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle tiles = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        Number cs = ctx.getInput(CELL_SIZE.name, Number.class);
        float cellSize = cs == null ? 1.0f : cs.floatValue();
        int[] dims = DungeonGrids.gridDims(tiles);
        CellType[] cells = DungeonGrids.gridCells(tiles, dims[0], dims[1]);
        ArrayMesh mesh = GridToMesh2D.emit(dims[0], dims[1], cells, cellSize);
        ctx.setOutput(MESH.name, GeometryBundle.ofMesh(mesh));
    }
}
