package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.HalfEdgeMesh;

@MeshNodeAnnotation(id = "mesh_grid")
public class GridMeshNode implements MeshNode {
    private static final InputPort U_TILES = new InputPort("u_tiles", PortType.INT, 10, (float) 1, (float) 1000);
    private static final InputPort V_TILES = new InputPort("v_tiles", PortType.INT, 10, (float) 1, (float) 1000);
    private static final InputPort U_TILE_SIZE = new InputPort("u_tile_size", PortType.FLOAT, 1.0f, 0.001f, 100f);
    private static final InputPort V_TILE_SIZE = new InputPort("v_tile_size", PortType.FLOAT, 1.0f, 0.001f, 100f);
    /** When positive, per-tile U size is {@code u_total_size / u_tiles} and overrides {@code u_tile_size}. */
    private static final InputPort U_TOTAL_SIZE = new InputPort("u_total_size", PortType.FLOAT, 0.0f, 0f, 1000f);
    /** When positive, per-tile V size is {@code v_total_size / v_tiles} and overrides {@code v_tile_size}. */
    private static final InputPort V_TOTAL_SIZE = new InputPort("v_total_size", PortType.FLOAT, 0.0f, 0f, 1000f);
    private static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(U_TILES, V_TILES, U_TILE_SIZE, V_TILE_SIZE, U_TOTAL_SIZE, V_TOTAL_SIZE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public String description() {
        return "Generates a flat quad grid on the XZ plane centered at the origin, with configurable tile counts and sizes in U/V directions; u_total_size/v_total_size override per-tile sizes when positive.";
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

        Number uTotalNum = ctx.getInput("u_total_size", Number.class);
        Number vTotalNum = ctx.getInput("v_total_size", Number.class);
        float uTotal = uTotalNum != null ? uTotalNum.floatValue() : 0.0f;
        float vTotal = vTotalNum != null ? vTotalNum.floatValue() : 0.0f;
        if (uTotal > 1e-6f) {
            uTileSize = uTotal / uTiles;
        }
        if (vTotal > 1e-6f) {
            vTileSize = vTotal / vTiles;
        }

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
