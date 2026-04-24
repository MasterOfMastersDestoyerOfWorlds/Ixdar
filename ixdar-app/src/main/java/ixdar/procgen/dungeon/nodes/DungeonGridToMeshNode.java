package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.procgen.dungeon.algo.GridToMesh2D;
import ixdar.procgen.dungeon.values.TileGridValue;

@MeshNodeAnnotation(id = "dungeon_grid_to_mesh", scopes = { "dungeon" })
public class DungeonGridToMeshNode implements MeshNode {

    private static final InputPort TILES = new InputPort("tiles", PortType.TILE_GRID, null);
    private static final InputPort CELL_SIZE = new InputPort("cell_size", PortType.FLOAT, 1.0f, 0.01f, 100f);
    private static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(TILES, CELL_SIZE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public String description() {
        return "Emits an axis-aligned boxed mesh from a TileGrid — one box per non-empty cell, HALLWAY "
                + "cells shorter than ROOM cells for visual distinction. Stage 5 (final) of the dungeon pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                "tiles", "Tile grid from astar_corridors.",
                "cell_size", "Edge length of one cell in world units.",
                "mesh", "ArrayMesh with 6 quads per non-empty cell. Winding matches cube(), normals computed.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        TileGridValue tiles = ctx.getInput("tiles", TileGridValue.class);
        Number cs = ctx.getInput("cell_size", Number.class);
        if (tiles == null) throw new IllegalArgumentException("dungeon_grid_to_mesh: missing 'tiles'");
        float cellSize = cs == null ? 1.0f : cs.floatValue();
        ArrayMesh mesh = GridToMesh2D.emit(tiles, cellSize);
        ctx.setOutput("mesh", mesh);
    }
}
