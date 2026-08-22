package ixdar.geometry.mesh.nodes.data;

import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

import java.util.Map;

import ixdar.annotations.meshnode.BoolField;
import ixdar.annotations.meshnode.FloatField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.IntField;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.MeshTopology;
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
        float[] dist = new float[n];
        int[] parent = new int[n];
        Arrays.fill(dist, Float.POSITIVE_INFINITY);
        Arrays.fill(parent, -1);

        PriorityQueue<Node> pq = new PriorityQueue<>();

        seedSources(mesh, endObj, n, dist, parent, pq);

        int[] vertIdToIdx = buildVertexIdIndex(mesh, n);

        while (!pq.isEmpty()) {
            Node cur = pq.poll();
            int u = cur.vertex;
            if (cur.dist > dist[u]) {
                continue;
            }
            int uid = mesh.vertexIdAt(u);
            int outCount = mesh.vertexOutgoingHalfEdgeCount(uid);
            for (int k = 0; k < outCount; k++) {
                int he = mesh.vertexOutgoingHalfEdgeAt(uid, k);
                int vid = mesh.halfEdgeEndVertex(he);
                int v = vertIdToIdx != null ? vertIdToIdx[vid] : indexOfVertexId(mesh, vid);
                if (v < 0) {
                    continue;
                }
                float ec = edgeCostBetween(costObj, u, v);
                float nd = dist[u] + ec;
                if (nd < dist[v]) {
                    dist[v] = nd;
                    parent[v] = u;
                    pq.add(new Node(v, nd));
                }
            }
        }

        int[] nextIdx = new int[n];
        float[] tot = new float[n];
        for (int i = 0; i < n; i++) {
            tot[i] = Float.isFinite(dist[i]) ? dist[i] : NUM_0;
            nextIdx[i] = parent[i] < 0 ? 0 : parent[i];
        }
        ctx.setOutput(NEXT_VERTEX.name, new IntField(nextIdx));
        ctx.setOutput(TOTAL_COST.name, new FloatField(tot));
    }

    private static void seedSources(MeshTopology mesh, Object endObj, int n,
            float[] dist, int[] parent, PriorityQueue<Node> pq) {
        if (endObj instanceof BoolField bf) {
            boolean any = false;
            int len = Math.min(bf.length(), n);
            for (int i = 0; i < len; i++) {
                if (bf.get(i)) {
                    any = true;
                    dist[i] = NUM_0;
                    parent[i] = i;
                    pq.add(new Node(i, NUM_0));
                }
            }
            if (!any) {
                dist[0] = NUM_0;
                parent[0] = 0;
                pq.add(new Node(0, NUM_0));
            }
        } else if (Boolean.TRUE.equals(endObj)) {
            boolean anyBoundary = false;
            for (int i = 0; i < n; i++) {
                int vid = mesh.vertexIdAt(i);
                if (mesh.isBoundaryVertex(vid)) {
                    anyBoundary = true;
                    dist[i] = NUM_0;
                    parent[i] = i;
                    pq.add(new Node(i, NUM_0));
                }
            }
            if (!anyBoundary) {
                dist[0] = NUM_0;
                parent[0] = 0;
                pq.add(new Node(0, NUM_0));
            }
        } else {
            dist[0] = NUM_0;
            parent[0] = 0;
            pq.add(new Node(0, NUM_0));
        }
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

    private record Node(int vertex, float dist) implements Comparable<Node> {
        @Override
        public int compareTo(Node o) {
            return Float.compare(dist, o.dist);
        }
    }
}
