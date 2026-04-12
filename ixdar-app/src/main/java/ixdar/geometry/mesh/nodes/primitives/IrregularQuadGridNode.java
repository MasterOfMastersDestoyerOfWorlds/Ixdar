package ixdar.geometry.mesh.nodes.primitives;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector2f;
import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.game.IrregularQuadLayoutGenerator;
import ixdar.geometry.mesh.data.HalfEdgeMesh;

/**
 * Generates an Oskar-style irregular quad grid using deterministic blue-noise
 * sampling, iterative Lloyd/CVT relaxation, and quad-construction from Voronoi dual.
 * 
 * This node produces a playable tile topology suitable for trade-map systems.
 * Interior faces are quads with explicit boundary handling for edge cases.
 */
@MeshNodeAnnotation(id = "mesh_irregular_quad_grid")
public class IrregularQuadGridNode implements MeshNode {

    private static final InputPort SEED = new InputPort("seed", PortType.INT, 123L);
    private static final InputPort GRID_WIDTH = new InputPort("grid_width", PortType.INT, 10);
    private static final InputPort GRID_HEIGHT = new InputPort("grid_height", PortType.INT, 10);
    private static final InputPort TILE_SIZE = new InputPort("tile_size", PortType.FLOAT, 1.0f);
    private static final InputPort RELAX_ITERATIONS = new InputPort("relax_iterations", PortType.INT, 8);
    private static final InputPort BOUNDARY_MARGIN = new InputPort("boundary_margin", PortType.FLOAT, 0.1f);
    private static final InputPort DEBUG_MODE = new InputPort("debug_mode", PortType.BOOLEAN, false);

    private static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);
    private static final OutputPort METRICS = new OutputPort("metrics", PortType.FLOAT);

    @Override
    public List<InputPort> inputs() {
        return List.of(SEED, GRID_WIDTH, GRID_HEIGHT, TILE_SIZE, RELAX_ITERATIONS, BOUNDARY_MARGIN, DEBUG_MODE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH, METRICS);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        long seed = ctx.getInput("seed", Number.class) != null
                ? ctx.getInput("seed", Number.class).longValue()
                : 123L;
        int gridWidth = ctx.getInput("grid_width", Number.class) != null
                ? Math.max(4, ctx.getInput("grid_width", Number.class).intValue())
                : 10;
        int gridHeight = ctx.getInput("grid_height", Number.class) != null
                ? Math.max(4, ctx.getInput("grid_height", Number.class).intValue())
                : 10;
        float tileSize = ctx.getInput("tile_size", Number.class) != null
                ? Math.max(1e-6f, ctx.getInput("tile_size", Number.class).floatValue())
                : 1.0f;
        int relaxIterations = ctx.getInput("relax_iterations", Number.class) != null
                ? Math.max(0, ctx.getInput("relax_iterations", Number.class).intValue())
                : 8;
        float boundaryMargin = ctx.getInput("boundary_margin", Number.class) != null
                ? Math.max(0f, ctx.getInput("boundary_margin", Number.class).floatValue())
                : 0.1f;
        boolean debugMode = ctx.getInput("debug_mode", Boolean.class) != null
                ? ctx.getInput("debug_mode", Boolean.class)
                : false;

        // Generate the irregular quad layout
        float width = gridWidth * tileSize;
        float height = gridHeight * tileSize;
        IrregularQuadLayoutGenerator.Layout layout = IrregularQuadLayoutGenerator.generate(
                gridWidth * gridHeight,
                width,
                height,
                boundaryMargin,
                seed,
                relaxIterations,
                debugMode ? 0.02f : 0f);

        // Build the HalfEdgeMesh from the layout
        HalfEdgeMesh mesh = buildMeshFromLayout(layout, tileSize);

        // Compute and export metrics
        float[] metrics = computeMetrics(layout);

        ctx.setOutput("mesh", mesh);
        ctx.setOutput("metrics", metrics);

        if (debugMode) {
            System.out.println("[IrregularQuadGridNode] Generated grid with seed=" + seed
                    + ", points=" + layout.points.size()
                    + ", quads=" + layout.edges.size() / 4
                    + ", relax=" + relaxIterations);
        }
    }

    /**
     * Builds a HalfEdgeMesh from the generated layout. The layout contains:
     * - points: vertex positions (primal vertices)
     * - dualPoints: face centers (dual vertices for quad centers)
     * - edges: edge connectivity
     * 
     * This creates quads by connecting primal vertices with their edge midpoints
     * and face centers.
     */
    private HalfEdgeMesh buildMeshFromLayout(IrregularQuadLayoutGenerator.Layout layout, float tileSize) {
        if (layout == null || layout.points == null || layout.points.isEmpty()) {
            return new HalfEdgeMesh();
        }

        // Estimate capacity
        int vertexCount = layout.points.size();
        int quadCount = layout.edges.size() / 4; // rough estimate
        int estimatedFaces = Math.max(1, quadCount);
        int estimatedEdges = Math.max(1, layout.edges.size() * 2);
        int estimatedHalfEdges = Math.max(8, estimatedFaces * 4 * 2);

        // Use bulk allocation for performance
        HalfEdgeMesh mesh = new HalfEdgeMesh(vertexCount, estimatedEdges, estimatedFaces, estimatedHalfEdges);

        // Add vertices from layout points
        ArrayList<Vector2f> points = layout.points;
        for (int i = 0; i < points.size(); i++) {
            Vector2f p = points.get(i);
            mesh.addVertex(p.x, 0f, p.y); // Z is up in Ixdar
        }

        // Build quads from the layout
        // The layout.edges contain vertex indices that form quad boundaries
        // We need to reconstruct quads from these edges
        ArrayList<int[]> quads = reconstructQuadsFromEdges(points, layout.edges);

        // Add quads to mesh
        for (int[] quad : quads) {
            if (quad.length == 4) {
                mesh.addFace(quad[0], quad[1], quad[2], quad[3]);
            }
        }

        // Compute normals for proper shading
        mesh.computeNormals();

        return mesh;
    }

    /**
     * Reconstructs quads from the edge list. This is a simplified approach that
     * creates quads by grouping edges that share vertices in a grid-like pattern.
     */
    private ArrayList<int[]> reconstructQuadsFromEdges(ArrayList<Vector2f> points, ArrayList<int[]> edges) {
        ArrayList<int[]> quads = new ArrayList<>();

        // Build adjacency from edges
        ArrayList<ArrayList<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            adj.add(new ArrayList<>());
        }
        for (int[] edge : edges) {
            if (edge.length == 2 && edge[0] >= 0 && edge[1] >= 0 && edge[0] < points.size() && edge[1] < points.size()) {
                adj.get(edge[0]).add(edge[1]);
                adj.get(edge[1]).add(edge[0]);
            }
        }

        // For a proper quad reconstruction, we need to find cycles of 4 edges
        // This is a simplified approach - in practice, the layout should provide
        // face connectivity directly
        boolean[] edgeUsed = new boolean[edges.size()];

        // Try to find quad cycles
        for (int start = 0; start < points.size() && quads.size() < edges.size() / 4 * 3; start++) {
            ArrayList<Integer> neighbors = adj.get(start);
            if (neighbors.size() < 4) {
                continue;
            }

            // Try to find a quad starting from this vertex
            for (int n0 = 0; n0 < neighbors.size(); n0++) {
                int v0 = neighbors.get(n0);
                ArrayList<Integer> neighbors0 = adj.get(v0);
                for (int n1 = 0; n1 < neighbors0.size(); n1++) {
                    int v1 = neighbors0.get(n1);
                    if (v1 == start) continue;
                    ArrayList<Integer> neighbors1 = adj.get(v1);
                    for (int n2 = 0; n2 < neighbors1.size(); n2++) {
                        int v2 = neighbors1.get(n2);
                        if (v2 == v0 || v2 == start) continue;
                        if (!adj.get(v2).contains(start)) continue;

                        // Found a quad: start -> v0 -> v1 -> v2 -> start
                        int[] quad = { start, v0, v1, v2 };
                        quads.add(quad);
                        edgeUsed[edgeIndex(edges, start, v0)] = true;
                        edgeUsed[edgeIndex(edges, v0, v1)] = true;
                        edgeUsed[edgeIndex(edges, v1, v2)] = true;
                        edgeUsed[edgeIndex(edges, v2, start)] = true;
                    }
                }
            }
        }

        return quads;
    }

    /**
     * Finds the edge index for a given vertex pair.
     */
    private int edgeIndex(ArrayList<int[]> edges, int a, int b) {
        for (int i = 0; i < edges.size(); i++) {
            int[] e = edges.get(i);
            if ((e[0] == a && e[1] == b) || (e[0] == b && e[1] == a)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Computes metrics about the generated grid for debugging and validation.
     */
    private float[] computeMetrics(IrregularQuadLayoutGenerator.Layout layout) {
        float[] metrics = new float[10];
        if (layout == null) {
            return metrics;
        }

        metrics[0] = layout.points.size(); // vertex count
        metrics[1] = layout.edges.size(); // edge count
        metrics[2] = layout.dualPoints.size(); // dual point count
        metrics[3] = layout.horizontalEdgeMean;
        metrics[4] = layout.verticalEdgeMean;
        metrics[5] = layout.horizontalEdgeStdDev;
        metrics[6] = layout.verticalEdgeStdDev;
        metrics[7] = layout.rows;
        metrics[8] = layout.cols;
        metrics[9] = layout.relaxIterations;

        return metrics;
    }
}
