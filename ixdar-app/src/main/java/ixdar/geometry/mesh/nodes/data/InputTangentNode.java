package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;

@MeshNodeAnnotation(id = "input_tangent")
public class InputTangentNode implements MeshNode {

    private static final OutputPort TANGENT = new OutputPort("tangent", PortType.VECTOR3);

    @Override
    public List<InputPort> inputs() {
        return List.of();
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(TANGENT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ctx.setOutput("tangent", new Vector3Value(1f, 0f, 0f));
    }
}
