package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "field_at_index")
public class FieldAtIndexNode implements MeshNode {

    private static final InputPort VALUE = new InputPort("value", PortType.FLOAT, 0.0f);
    private static final InputPort INDEX = new InputPort("index", PortType.INT, 0);
    private static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

    @Override
    public List<InputPort> inputs() {
        return List.of(VALUE, INDEX);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number v = ctx.getInput("value", Number.class);
        ctx.getInput("index", Number.class);
        ctx.setOutput("result", v == null ? 0f : v.floatValue());
    }
}
