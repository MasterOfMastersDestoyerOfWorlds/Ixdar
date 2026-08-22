package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

@MeshNodeAnnotation(id = "mesh_grid")
public class GridMeshNode implements MeshNode {
    public static final float NUM_1e_6 = 1e-6f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0 = 0f;
    public static final InputPort U_TILES = new InputPort("u_tiles", PortType.INT, 10, (float) 1, (float) 1000);
    public static final InputPort V_TILES = new InputPort("v_tiles", PortType.INT, 10, (float) 1, (float) 1000);
    public static final InputPort U_TILE_SIZE = new InputPort("u_tile_size", PortType.FLOAT, 1.0f, 0.001f, 100f);
    public static final InputPort V_TILE_SIZE = new InputPort("v_tile_size", PortType.FLOAT, 1.0f, 0.001f, 100f);
    /**
     * When positive, per-tile U size is {@code u_total_size / u_tiles} and
     * overrides {@code u_tile_size}.
     */
    public static final InputPort U_TOTAL_SIZE = new InputPort("u_total_size", PortType.FLOAT, 0.0f, 0f, 1000f);
    /**
     * When positive, per-tile V size is {@code v_total_size / v_tiles} and
     * overrides {@code v_tile_size}.
     */
    public static final InputPort V_TOTAL_SIZE = new InputPort("v_total_size", PortType.FLOAT, 0.0f, 0f, 1000f);
    public static final InputPort TRIANGULATE = new InputPort("triangulate", PortType.BOOLEAN, false);
    public static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(U_TILES, V_TILES, U_TILE_SIZE, V_TILE_SIZE, U_TOTAL_SIZE, V_TOTAL_SIZE,
                TRIANGULATE);
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
    public Map<String, String> socketDocs() {
        return Map.of(
                U_TILES.name, "Number of quads along the U (X) axis.",
                V_TILES.name, "Number of quads along the V (Z) axis.",
                U_TILE_SIZE.name,
                "Per-tile edge length along U. Grid extent along X = u_tiles × u_tile_size, vertices at ±extent/2. Ignored when u_total_size > 0.",
                V_TILE_SIZE.name,
                "Per-tile edge length along V. Grid extent along Z = v_tiles × v_tile_size, vertices at ±extent/2. Ignored when v_total_size > 0.",
                U_TOTAL_SIZE.name,
                "Total extent along U. When > 0, per-tile U size becomes u_total_size / u_tiles (overrides u_tile_size).",
                V_TOTAL_SIZE.name,
                "Total extent along V. When > 0, per-tile V size becomes v_total_size / v_tiles (overrides v_tile_size).",
                TRIANGULATE.name, "Split each quad into two triangles along the v00-v11 diagonal."
                        + " Needed by the quad-layout pipeline, which consumes triangle meshes."
                        + " Default false.",
                MESH.name, "Flat grid on XZ plane, centered at origin, Y=0; quads, or triangles when"
                        + " triangulate is set.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        int uTiles = ctx.getInput(U_TILES.name, Number.class) != null
                ? ctx.getInput(U_TILES.name, Number.class).intValue()
                : 1;
        int vTiles = ctx.getInput(V_TILES.name, Number.class) != null
                ? ctx.getInput(V_TILES.name, Number.class).intValue()
                : 1;
        float uTileSize = ctx.getInput(U_TILE_SIZE.name, Number.class) != null
                ? ctx.getInput(U_TILE_SIZE.name, Number.class).floatValue()
                : 1.0f;
        float vTileSize = ctx.getInput(V_TILE_SIZE.name, Number.class) != null
                ? ctx.getInput(V_TILE_SIZE.name, Number.class).floatValue()
                : 1.0f;

        uTiles = Math.max(1, uTiles);
        vTiles = Math.max(1, vTiles);
        Boolean triangulateInput = ctx.getInput(TRIANGULATE.name, Boolean.class);
        boolean triangulate = triangulateInput != null && triangulateInput;

        Number uTotalNum = ctx.getInput(U_TOTAL_SIZE.name, Number.class);
        Number vTotalNum = ctx.getInput(V_TOTAL_SIZE.name, Number.class);
        float uTotal = uTotalNum != null ? uTotalNum.floatValue() : 0.0f;
        float vTotal = vTotalNum != null ? vTotalNum.floatValue() : 0.0f;
        if (uTotal > NUM_1e_6) {
            uTileSize = uTotal / uTiles;
        }
        if (vTotal > NUM_1e_6) {
            vTileSize = vTotal / vTiles;
        }

        uTileSize = Math.max(NUM_1e_6, uTileSize);
        vTileSize = Math.max(NUM_1e_6, vTileSize);

        float totalU = uTiles * uTileSize;
        float totalV = vTiles * vTileSize;
        float x0 = -totalU * NUM_0_5;
        float z0 = -totalV * NUM_0_5;

        int vertsU = uTiles + 1;
        int vertsV = vTiles + 1;
        int[][] vid = new int[vertsU][vertsV];

        HalfEdgeMesh mesh = new HalfEdgeMesh();

        for (int i = 0; i < vertsU; i++) {
            for (int j = 0; j < vertsV; j++) {
                float x = x0 + i * uTileSize;
                float z = z0 + j * vTileSize;
                vid[i][j] = mesh.addVertex(x, NUM_0, z);
            }
        }

        for (int i = 0; i < uTiles; i++) {
            for (int j = 0; j < vTiles; j++) {
                int v00 = vid[i][j];
                int v10 = vid[i + 1][j];
                int v11 = vid[i + 1][j + 1];
                int v01 = vid[i][j + 1];
                if (triangulate) {
                    mesh.addFace(v00, v01, v11);
                    mesh.addFace(v00, v11, v10);
                } else {
                    mesh.addFace(v00, v01, v11, v10);
                }
            }
        }

        mesh.computeNormals();
        ctx.setOutput(MESH.name, mesh);
    }

    /**
     * A unit-spaced triangulated grid with the given vertex counts, on the XZ plane
     * centered at the origin, split along the v00-v11 diagonal.
     *
     * @param columns vertex count along U
     * @param rows    vertex count along V
     * @return the generated mesh
     */
    public static HalfEdgeMesh triangulated(int columns, int rows) {
        GridMeshNode node = new GridMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(GridMeshNode.U_TILES.name, columns - 1);
        context.setInput(GridMeshNode.V_TILES.name, rows - 1);
        context.setInput(GridMeshNode.TRIANGULATE.name, true);
        node.evaluate(context);
        return context.getOutput(GridMeshNode.MESH.name, HalfEdgeMesh.class);
    }

    /**
     * The source vertex id at a grid position, under the node's U-major id
     * ordering.
     *
     * @param rows   vertex count along V
     * @param column grid column, the U index
     * @param row    grid row, the V index
     * @return the source vertex id there
     */
    public static int vertexId(int rows, int column, int row) {
        return column * rows + row;
    }

    /**
     * The world Z coordinate of a grid row, under the node's origin-centered
     * placement.
     *
     * @param rows vertex count along V
     * @param row  grid row, the V index
     * @return the Z coordinate of that row's vertices
     */
    public static float rowCoordinate(int rows, int row) {
        return row - (rows - 1) * 0.5f;
    }
}
