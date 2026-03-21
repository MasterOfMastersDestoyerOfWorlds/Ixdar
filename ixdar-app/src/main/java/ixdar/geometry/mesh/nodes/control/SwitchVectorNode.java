package ixdar.geometry.mesh.nodes.control;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;

@MeshNodeAnnotation(id = "switch_vector")
public class SwitchVectorNode implements MeshNode {

    private static final Vector3Value ZERO = new Vector3Value(0f, 0f, 0f);

    private static final InputPort SWITCH = new InputPort("switch", PortType.BOOLEAN, false);
    private static final InputPort FALSE_VAL = new InputPort("false", PortType.VECTOR3, ZERO);
    private static final InputPort TRUE_VAL = new InputPort("true", PortType.VECTOR3, ZERO);
    private static final OutputPort RESULT = new OutputPort("result", PortType.VECTOR3);

    @Override
    public List<InputPort> inputs() {
        return List.of(SWITCH, FALSE_VAL, TRUE_VAL);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Boolean sw = ctx.getInput("switch", Boolean.class);
        Vector3Value fa = ctx.getInput("false", Vector3Value.class);
        Vector3Value tr = ctx.getInput("true", Vector3Value.class);
        if (fa == null) {
            fa = ZERO;
        }
        if (tr == null) {
            tr = ZERO;
        }
        boolean on = sw != null && sw;
        ctx.setOutput("result", on ? tr : fa);
    }
}
