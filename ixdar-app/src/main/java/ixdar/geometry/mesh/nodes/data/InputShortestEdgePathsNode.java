package ixdar.geometry.mesh.nodes.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import java.util.Map;

import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.FloatField;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.Dijkstra;
import ixdar.geometry.mesh.data.paths.ShortestPathForest;
import ixdar.geometry.mesh.graph.MeshFieldContext;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "input_shortest_edge_paths")
public class InputShortestEdgePathsNode implements MeshNode {
    public static final float NUM_0 = 0f;
    public static final float NUM_1e_20 = 1e-20f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_1 = 1f;
    public static final int NUM_4 = 4;
    public static final int NUM_1024 = 1024;

    public static final InputPort END = new InputPort("end", PortType.BOOLEAN, false);
    public static final InputPort EDGE_COST = new InputPort("edge_cost", PortType.FLOAT, 1.0f, 0.001f, 1000f);
    public static final OutputPort NEXT_VERTEX = new OutputPort("next_vertex", PortType.INT);
    public static final OutputPort TOTAL_COST = new OutputPort("total_cost", PortType.FLOAT);

    @Override
    public String description() {
        return "Computes shortest paths from source vertices across mesh edges using Dijkstra. Outputs per-vertex next-hop index and total cost fields.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                END.name, "Per-vertex BoolField marking source (start) vertices. Paths are computed FROM these TO every other vertex.",
                EDGE_COST.name, "Per-edge FloatField of edge traversal costs. Scalar = uniform graph distance.",
                NEXT_VERTEX.name, "Per-vertex IntField: the next hop toward the nearest source.",
                TOTAL_COST.name, "Per-vertex FloatField: accumulated path cost to the nearest source."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(END, EDGE_COST);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(NEXT_VERTEX, TOTAL_COST);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        var fc = ctx.fieldContext();
        if (fc == null || !(fc instanceof MeshFieldContext mfc)) {
            ctx.setOutput(NEXT_VERTEX.name, 0);
            ctx.setOutput(TOTAL_COST.name, NUM_0);
            return;
        }
        MeshTopology mesh = mfc.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput(NEXT_VERTEX.name, 0);
            ctx.setOutput(TOTAL_COST.name, NUM_0);
            return;
        }

        Object endObj = FieldBroadcast.getInputOrDefault(ctx, END.name, END.defaultValue);
        Object costObj = FieldBroadcast.getInputOrDefault(ctx, EDGE_COST.name, EDGE_COST.defaultValue);

        int n = mesh.vertexCount();
        int[] sources = seedSources(mesh, endObj, n);
        int[] vertIdToIdx = buildVertexIdIndex(mesh, n);
        int[] sourceIds = new int[sources.length];
        for (int i = 0; i < sources.length; i++) {
            sourceIds[i] = mesh.vertexIdAt(sources[i]);
        }

        ShortestPathForest forest = Dijkstra.forest(mesh, sourceIds, edgeCosts(mesh, vertIdToIdx, costObj));

        int[] nextIdx = new int[n];
        float[] tot = new float[n];
        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            float dist = (float) forest.distance[vid];
            tot[i] = Float.isFinite(dist) ? dist : NUM_0;
            int parentId = forest.parent[vid];
            nextIdx[i] = parentId < 0 ? 0 : denseIndex(mesh, vertIdToIdx, parentId);
        }
        ctx.setOutput(NEXT_VERTEX.name, new IntField(nextIdx));
        ctx.setOutput(TOTAL_COST.name, new FloatField(tot));
    }

    /**
     * The traversal cost of every live edge, indexed by edge id: the endpoint
     * average of the per-vertex cost field, or the scalar cost.
     */
    private static double[] edgeCosts(MeshTopology mesh, int[] vertIdToIdx, Object costObj) {
        int bound = 0;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            bound = Math.max(bound, mesh.edgeIdAt(i) + 1);
        }
        double[] cost = new double[bound];
        for (int i = 0; i < mesh.edgeCount(); i++) {
            int edgeId = mesh.edgeIdAt(i);
            int he = mesh.edgeHalfEdge(edgeId);
            int u = denseIndex(mesh, vertIdToIdx, mesh.halfEdgeVertex(he));
            int v = denseIndex(mesh, vertIdToIdx, mesh.halfEdgeEndVertex(he));
            cost[edgeId] = edgeCostBetween(costObj, u, v);
        }
        return cost;
    }

    private static int denseIndex(MeshTopology mesh, int[] vertIdToIdx, int vid) {
        return vertIdToIdx != null ? vertIdToIdx[vid] : indexOfVertexId(mesh, vid);
    }

    /**
     * The source vertex indices Dijkstra seeds, in ascending index order: the
     * BoolField's marked vertices, or every boundary vertex when {@code end} is
     * the scalar true, falling back to vertex 0 when nothing matches.
     */
    private static int[] seedSources(MeshTopology mesh, Object endObj, int n) {
        List<Integer> sources = new ArrayList<>();
        if (endObj instanceof BoolField bf) {
            int len = Math.min(bf.length(), n);
            for (int i = 0; i < len; i++) {
                if (bf.get(i)) {
                    sources.add(i);
                }
            }
        } else if (Boolean.TRUE.equals(endObj)) {
            for (int i = 0; i < n; i++) {
                if (mesh.isBoundaryVertex(mesh.vertexIdAt(i))) {
                    sources.add(i);
                }
            }
        }
        if (sources.isEmpty()) {
            sources.add(0);
        }
        int[] out = new int[sources.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = sources.get(i);
        }
        return out;
    }

    /**
     * Returns the average of the per-vertex costs at endpoints u and v.
     * When cost is a scalar, uses that value directly (clamped to a small positive minimum).
     */
    private static float edgeCostBetween(Object costObj, int u, int v) {
        if (costObj instanceof FloatField ff) {
            float cu = ff.get(u);
            float cv = ff.get(v);
            return Math.max(NUM_1e_20, (cu + cv) * NUM_0_5);
        }
        if (costObj instanceof Number num) {
            return Math.max(NUM_1e_20, num.floatValue());
        }
        return NUM_1;
    }

    private static int indexOfVertexId(MeshTopology mesh, int vertexId) {
        int n = mesh.vertexCount();
        for (int i = 0; i < n; i++) {
            if (mesh.vertexIdAt(i) == vertexId) {
                return i;
            }
        }
        return -1;
    }

    private static int[] buildVertexIdIndex(MeshTopology mesh, int n) {
        int maxId = 0;
        for (int i = 0; i < n; i++) {
            maxId = Math.max(maxId, mesh.vertexIdAt(i));
        }
        if (maxId > n * NUM_4 + NUM_1024) {
            return null;
        }
        int[] idx = new int[maxId + 1];
        Arrays.fill(idx, -1);
        for (int i = 0; i < n; i++) {
            idx[mesh.vertexIdAt(i)] = i;
        }
        return idx;
    }
}
