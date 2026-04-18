package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;

@MeshNodeAnnotation(id = "input_vector")
public class InputVectorNode implements MeshNode {
    private static final InputPort X = new InputPort("x", PortType.FLOAT, 0.0f);
    private static final InputPort Y = new InputPort("y", PortType.FLOAT, 0.0f);
    private static final InputPort Z = new InputPort("z", PortType.FLOAT, 0.0f);
    private static final OutputPort VECTOR = new OutputPort("vector", PortType.VECTOR3);

    @Override
    public String description() {
        return "Constructs a Vector3 from individual X, Y, Z float inputs.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "x", "X component.",
                "y", "Y component.",
                "z", "Z component.",
                "vector", "Vector3 formed from <x, y, z>."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(X, Y, Z);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(VECTOR);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number xn = ctx.getInput("x", Number.class);
        Number yn = ctx.getInput("y", Number.class);
        Number zn = ctx.getInput("z", Number.class);
        float x = xn == null ? 0f : xn.floatValue();
        float y = yn == null ? 0f : yn.floatValue();
        float z = zn == null ? 0f : zn.floatValue();
        ctx.setOutput("vector", new Vector3Value(x, y, z));
    }
}
