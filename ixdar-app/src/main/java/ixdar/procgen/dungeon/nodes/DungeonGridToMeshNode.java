package ixdar.procgen.dungeon.nodes;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.procgen.dungeon.algo.GridToMesh2D;
import ixdar.procgen.dungeon.values.TileGridValue;

@MeshNodeAnnotation(id = "dungeon_grid_to_mesh", scopes = { "dungeon" })
public class DungeonGridToMeshNode implements MeshNode {
    public static final String TILES_2 = "tiles";
    public static final String CELL_SIZE_2 = "cell_size";
    public static final String MESH_2 = "mesh";

    private static final InputPort TILES = new InputPort(TILES_2, PortType.TILE_GRID, null);
    private static final InputPort CELL_SIZE = new InputPort(CELL_SIZE_2, PortType.FLOAT, 1.0f, 0.01f, 100f);
    private static final OutputPort MESH = new OutputPort(MESH_2, PortType.MESH);

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
                TILES_2, "Tile grid from astar_corridors.",
                CELL_SIZE_2, "Edge length of one cell in world units.",
                MESH_2, "ArrayMesh with 6 quads per non-empty cell. Winding matches cube(), normals computed.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        TileGridValue tiles = ctx.getInput(TILES_2, TileGridValue.class);
        Number cs = ctx.getInput(CELL_SIZE_2, Number.class);
        if (tiles == null) throw new IllegalArgumentException("dungeon_grid_to_mesh: missing 'tiles'");
        float cellSize = cs == null ? 1.0f : cs.floatValue();
        ArrayMesh mesh = GridToMesh2D.emit(tiles, cellSize);
        ctx.setOutput(MESH_2, mesh);
    }
}
