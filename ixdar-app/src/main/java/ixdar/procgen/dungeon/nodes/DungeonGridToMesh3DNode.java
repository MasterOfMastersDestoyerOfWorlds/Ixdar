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
import ixdar.procgen.dungeon.algo.GridToMesh3D;
import ixdar.procgen.dungeon.values.TileGridValue3D;

@MeshNodeAnnotation(id = "dungeon_grid_to_mesh_3d", scopes = { "dungeon" })
public class DungeonGridToMesh3DNode implements MeshNode {

    private static final InputPort TILES = new InputPort("tiles", PortType.TILE_GRID_3D, null);
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
        return "Emits a 3D boxed mesh from a TileGrid3D. Multi-floor dungeon with stair cells "
                + "rendered as mid-height slabs. Stage 5 (final) of the 3D dungeon pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                "tiles", "3D tile grid from astar_corridors_3d.",
                "cell_size", "Edge length of one cell in world units.",
                "mesh", "ArrayMesh centered at world origin.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        TileGridValue3D tiles = ctx.getInput("tiles", TileGridValue3D.class);
        Number cs = ctx.getInput("cell_size", Number.class);
        if (tiles == null) throw new IllegalArgumentException("dungeon_grid_to_mesh_3d: missing 'tiles'");
        float cellSize = cs == null ? 1.0f : cs.floatValue();
        ArrayMesh mesh = GridToMesh3D.emit(tiles, cellSize);
        ctx.setOutput("mesh", mesh);
    }
}
