package ixdar.geometry.mesh.nodes.selection;

import java.util.List;

import ixdar.annotations.meshnode.BoolField;
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

@MeshNodeAnnotation(id = "edge_paths_to_selection")
public class EdgePathsToSelectionNode implements MeshNode {

    private static final InputPort START = new InputPort("start", PortType.BOOLEAN, false);
    private static final InputPort NEXT_VERTEX = new InputPort("next_vertex", PortType.INT, 0, 0f, 1000000f);
    private static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

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
    public void evaluate(NodeContext ctx) {
        Object no = FieldBroadcast.getInputOrDefault(ctx, "next_vertex", NEXT_VERTEX.defaultValue());
        var fc = ctx.fieldContext();
        if (fc == null || !(fc instanceof MeshFieldContext mfc) || !(no instanceof IntField next)) {
            ctx.setOutput("selection", false);
            return;
        }
        MeshTopology mesh = mfc.mesh();
        if (mesh == null || next.length() != mesh.vertexCount()) {
            ctx.setOutput("selection", false);
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
        ctx.setOutput("selection", new BoolField(sel));
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
