package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;

@MeshNodeAnnotation(id = "combine_xyz")
public class CombineXyzNode implements MeshNode {

    private static final InputPort X = new InputPort("x", PortType.FLOAT, 0.0f);
    private static final InputPort Y = new InputPort("y", PortType.FLOAT, 0.0f);
    private static final InputPort Z = new InputPort("z", PortType.FLOAT, 0.0f);
    private static final OutputPort VECTOR = new OutputPort("vector", PortType.VECTOR3);

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
        float x = num(ctx, "x");
        float y = num(ctx, "y");
        float z = num(ctx, "z");
        ctx.setOutput("vector", new Vector3Value(x, y, z));
    }

    private static float num(NodeContext ctx, String name) {
        Number n = ctx.getInput(name, Number.class);
        return n == null ? 0f : n.floatValue();
    }
}
