package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.InputPort;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.MeshFieldContext;

@MeshNodeAnnotation(id = "input_mesh_edge_vertices")
public class InputMeshEdgeVerticesNode implements MeshNode {
    public static final OutputPort VERTEX_A = new OutputPort("vertex_a", PortType.INT);
    public static final OutputPort VERTEX_B = new OutputPort("vertex_b", PortType.INT);

    @Override
    public String description() {
        return "Outputs per-edge IntFields containing the start and end vertex indices for every edge in the mesh.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                VERTEX_A.name, "Per-edge IntField: the start vertex index of each edge.",
                VERTEX_B.name, "Per-edge IntField: the end vertex index of each edge."
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
            ctx.setOutput(VERTEX_A.name, 0);
            ctx.setOutput(VERTEX_B.name, 0);
            return;
        }
        MeshTopology mesh = mfc.mesh();
        if (mesh == null || mesh.edgeCount() == 0) {
            ctx.setOutput(VERTEX_A.name, 0);
            ctx.setOutput(VERTEX_B.name, 0);
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
        ctx.setOutput(VERTEX_A.name, new IntField(a));
        ctx.setOutput(VERTEX_B.name, new IntField(b));
    }
}
