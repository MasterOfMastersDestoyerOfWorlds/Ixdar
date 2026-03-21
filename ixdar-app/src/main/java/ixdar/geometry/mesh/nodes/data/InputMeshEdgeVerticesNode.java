package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "input_mesh_edge_vertices")
public class InputMeshEdgeVerticesNode implements MeshNode {

    private static final OutputPort VERTEX_A = new OutputPort("vertex_a", PortType.FLOAT);
    private static final OutputPort VERTEX_B = new OutputPort("vertex_b", PortType.FLOAT);

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
        ctx.setOutput("vertex_a", 0f);
        ctx.setOutput("vertex_b", 0f);
    }
}
