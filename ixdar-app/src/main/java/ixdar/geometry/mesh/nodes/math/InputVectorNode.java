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
    public static final String X_2 = "x";
    public static final String Y_2 = "y";
    public static final String Z_2 = "z";
    public static final String VECTOR_2 = "vector";
    private static final InputPort X = new InputPort(X_2, PortType.FLOAT, 0.0f);
    private static final InputPort Y = new InputPort(Y_2, PortType.FLOAT, 0.0f);
    private static final InputPort Z = new InputPort(Z_2, PortType.FLOAT, 0.0f);
    private static final OutputPort VECTOR = new OutputPort(VECTOR_2, PortType.VECTOR3);

    @Override
    public String description() {
        return "Constructs a Vector3 from individual X, Y, Z float inputs.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                X_2, "X component.",
                Y_2, "Y component.",
                Z_2, "Z component.",
                VECTOR_2, "Vector3 formed from <x, y, z>."
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
        Number xn = ctx.getInput(X_2, Number.class);
        Number yn = ctx.getInput(Y_2, Number.class);
        Number zn = ctx.getInput(Z_2, Number.class);
        float x = xn == null ? 0f : xn.floatValue();
        float y = yn == null ? 0f : yn.floatValue();
        float z = zn == null ? 0f : zn.floatValue();
        ctx.setOutput(VECTOR_2, new Vector3Value(x, y, z));
    }
}
