package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.HalfEdgeMesh;

@MeshNodeAnnotation(id = "mesh_grid")
public class GridMeshNode implements MeshNode {
    private static final InputPort U_TILES = new InputPort("u_tiles", PortType.INT, 10);
    private static final InputPort V_TILES = new InputPort("v_tiles", PortType.INT, 10);
    private static final InputPort U_TILE_SIZE = new InputPort("u_tile_size", PortType.FLOAT, 1.0f);
    private static final InputPort V_TILE_SIZE = new InputPort("v_tile_size", PortType.FLOAT, 1.0f);
    private static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(U_TILES, V_TILES, U_TILE_SIZE, V_TILE_SIZE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        int uTiles = ctx.getInput("u_tiles", Number.class) != null ? ctx.getInput("u_tiles", Number.class).intValue() : 1;
        int vTiles = ctx.getInput("v_tiles", Number.class) != null ? ctx.getInput("v_tiles", Number.class).intValue() : 1;
        float uTileSize = ctx.getInput("u_tile_size", Number.class) != null
                ? ctx.getInput("u_tile_size", Number.class).floatValue()
                : 1.0f;
        float vTileSize = ctx.getInput("v_tile_size", Number.class) != null
                ? ctx.getInput("v_tile_size", Number.class).floatValue()
                : 1.0f;

        uTiles = Math.max(1, uTiles);
        vTiles = Math.max(1, vTiles);
        uTileSize = Math.max(1e-6f, uTileSize);
        vTileSize = Math.max(1e-6f, vTileSize);

        float totalU = uTiles * uTileSize;
        float totalV = vTiles * vTileSize;
        float x0 = -totalU * 0.5f;
        float z0 = -totalV * 0.5f;

        int vertsU = uTiles + 1;
        int vertsV = vTiles + 1;
        int[][] vid = new int[vertsU][vertsV];

        HalfEdgeMesh mesh = new HalfEdgeMesh();

        for (int i = 0; i < vertsU; i++) {
            for (int j = 0; j < vertsV; j++) {
                float x = x0 + i * uTileSize;
                float z = z0 + j * vTileSize;
                vid[i][j] = mesh.addVertex(x, 0f, z);
            }
        }

        for (int i = 0; i < uTiles; i++) {
            for (int j = 0; j < vTiles; j++) {
                int v00 = vid[i][j];
                int v10 = vid[i + 1][j];
                int v11 = vid[i + 1][j + 1];
                int v01 = vid[i][j + 1];
                mesh.addFace(v00, v01, v11, v10);
            }
        }

        mesh.computeNormals();
        ctx.setOutput("mesh", mesh);
    }
}
