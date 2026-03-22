package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "input_int")
public class InputIntNode implements MeshNode {
    private static final InputPort VALUE = new InputPort("value", PortType.INT, 0);
    private static final OutputPort OUTPUT = new OutputPort("output", PortType.INT);

    @Override
    public List<InputPort> inputs() {
        return List.of(VALUE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(OUTPUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number n = ctx.getInput("value", Number.class);
        int v = n == null ? 0 : n.intValue();
        ctx.setOutput("output", v);
    }
}
