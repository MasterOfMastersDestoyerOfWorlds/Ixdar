package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;

@MeshNodeAnnotation(id = "separate_xyz")
public class SeparateXyzNode implements MeshNode {

    private static final InputPort VECTOR = new InputPort("vector", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final OutputPort X = new OutputPort("x", PortType.FLOAT);
    private static final OutputPort Y = new OutputPort("y", PortType.FLOAT);
    private static final OutputPort Z = new OutputPort("z", PortType.FLOAT);

    @Override
    public List<InputPort> inputs() {
        return List.of(VECTOR);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(X, Y, Z);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Vector3Value v = ctx.getInput("vector", Vector3Value.class);
        if (v == null) {
            v = new Vector3Value(0f, 0f, 0f);
        }
        ctx.setOutput("x", v.x());
        ctx.setOutput("y", v.y());
        ctx.setOutput("z", v.z());
    }
}
