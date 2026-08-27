package ixdar.geometry.mesh.nodes.selection;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.BoolField;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.MeshFieldContext;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "edge_paths_to_selection")
public class EdgePathsToSelectionNode implements MeshNode {
    public static final InputPort START = new InputPort("start", PortType.BOOLEAN, false);
    public static final InputPort NEXT_VERTEX = new InputPort("next_vertex", PortType.INT, 0, 0f, 1000000f);
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(START, NEXT_VERTEX);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(SELECTION);
    }

    @Override
    public String description() {
        return "Converts vertex path chains (defined by next_vertex indices) into an edge selection mask.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                START.name, "Per-vertex BoolField marking path start vertices.",
                NEXT_VERTEX.name, "Per-vertex IntField: next-hop vertex index for each vertex (from input_shortest_edge_paths).",
                SELECTION.name, "Per-edge BoolField: true for edges that lie on any traced path."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object no = FieldBroadcast.getInputOrDefault(ctx, NEXT_VERTEX.name, NEXT_VERTEX.defaultValue);
        var fc = ctx.fieldContext();
        if (fc == null || !(fc instanceof MeshFieldContext mfc) || !(no instanceof IntField next)) {
            ctx.setOutput(SELECTION.name, false);
            return;
        }
        MeshTopology mesh = mfc.mesh();
        if (mesh == null || next.length() != mesh.vertexCount()) {
            ctx.setOutput(SELECTION.name, false);
            return;
        }

        int ne = mesh.edgeCount();
        boolean[] sel = new boolean[ne];
        for (int ei = 0; ei < ne; ei++) {
            int eid = mesh.edgeIdAt(ei);
            int he = mesh.edgeHalfEdge(eid);
            int va = mesh.halfEdgeVertex(he);
            int vb = mesh.halfEdgeEndVertex(he);
            int ia = vertexActiveIndex(mesh, va);
            int ib = vertexActiveIndex(mesh, vb);
            if (ia < 0 || ib < 0) {
                sel[ei] = false;
                continue;
            }
            int na = next.get(ia);
            int nb = next.get(ib);
            sel[ei] = na == ib || nb == ia;
        }
        ctx.setOutput(SELECTION.name, new BoolField(sel));
    }

    private static int vertexActiveIndex(MeshTopology mesh, int vertexId) {
        int n = mesh.vertexCount();
        for (int i = 0; i < n; i++) {
            if (mesh.vertexIdAt(i) == vertexId) {
                return i;
            }
        }
        return -1;
    }
}
