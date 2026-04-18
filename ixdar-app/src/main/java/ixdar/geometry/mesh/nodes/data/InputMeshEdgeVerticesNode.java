package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.IntField;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.MeshFieldContext;

@MeshNodeAnnotation(id = "input_mesh_edge_vertices")
public class InputMeshEdgeVerticesNode implements MeshNode {

    private static final OutputPort VERTEX_A = new OutputPort("vertex_a", PortType.INT);
    private static final OutputPort VERTEX_B = new OutputPort("vertex_b", PortType.INT);

    @Override
    public String description() {
        return "Outputs per-edge IntFields containing the start and end vertex indices for every edge in the mesh.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "vertex_a", "Per-edge IntField: the start vertex index of each edge.",
                "vertex_b", "Per-edge IntField: the end vertex index of each edge."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of();
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(VERTEX_A, VERTEX_B);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        var fc = ctx.fieldContext();
        if (fc == null || !(fc instanceof MeshFieldContext mfc)) {
            ctx.setOutput("vertex_a", 0);
            ctx.setOutput("vertex_b", 0);
            return;
        }
        MeshTopology mesh = mfc.mesh();
        if (mesh == null || mesh.edgeCount() == 0) {
            ctx.setOutput("vertex_a", 0);
            ctx.setOutput("vertex_b", 0);
            return;
        }
        int ne = mesh.edgeCount();
        int[] a = new int[ne];
        int[] b = new int[ne];
        for (int ei = 0; ei < ne; ei++) {
            int eid = mesh.edgeIdAt(ei);
            int he = mesh.edgeHalfEdge(eid);
            a[ei] = mesh.halfEdgeVertex(he);
            b[ei] = mesh.halfEdgeEndVertex(he);
        }
        ctx.setOutput("vertex_a", new IntField(a));
        ctx.setOutput("vertex_b", new IntField(b));
    }
}
