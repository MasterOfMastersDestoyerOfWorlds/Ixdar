package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;

import java.util.Map;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;

@MeshNodeAnnotation(id = "input_vector")
public class InputVectorNode implements MeshNode {
    public static final InputPort X = new InputPort("x", PortType.FLOAT, 0.0f);
    public static final InputPort Y = new InputPort("y", PortType.FLOAT, 0.0f);
    public static final InputPort Z = new InputPort("z", PortType.FLOAT, 0.0f);
    public static final OutputPort VECTOR = new OutputPort("vector", PortType.VECTOR3);

    @Override
    public String description() {
        return "Constructs a Vector3 from individual X, Y, Z float inputs.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                X.name, "X component.",
                Y.name, "Y component.",
                Z.name, "Z component.",
                VECTOR.name, "Vector3 formed from <x, y, z>."
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
        Number xn = ctx.getInput(X.name, Number.class);
        Number yn = ctx.getInput(Y.name, Number.class);
        Number zn = ctx.getInput(Z.name, Number.class);
        float x = xn == null ? 0f : xn.floatValue();
        float y = yn == null ? 0f : yn.floatValue();
        float z = zn == null ? 0f : zn.floatValue();
        ctx.setOutput(VECTOR.name, new Vector3Value(x, y, z));
    }
}
