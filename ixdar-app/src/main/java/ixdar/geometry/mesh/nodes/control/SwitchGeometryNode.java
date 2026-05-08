package ixdar.geometry.mesh.nodes.control;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;

@MeshNodeAnnotation(id = "switch_geometry")
public class SwitchGeometryNode implements MeshNode {
    public static final String SWITCH_2 = "switch";
    public static final String FALSE = "false";
    public static final String TRUE = "true";
    public static final String GEOMETRY_2 = "geometry";

    private static final InputPort SWITCH = new InputPort(SWITCH_2, PortType.BOOLEAN, false);
    private static final InputPort FALSE_VAL = new InputPort(FALSE, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort TRUE_VAL = new InputPort(TRUE, PortType.GEOMETRY_BUNDLE, null);
    private static final OutputPort GEOMETRY = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(SWITCH, FALSE_VAL, TRUE_VAL);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public String description() {
        return "Outputs one of two geometry inputs based on a boolean switch.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                SWITCH_2, "Scalar BOOLEAN selector.",
                FALSE, "Geometry used when switch is false.",
                TRUE, "Geometry used when switch is true.",
                GEOMETRY_2, "Selected geometry bundle."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Boolean sw = ctx.getInput(SWITCH_2, Boolean.class);
        Object fa = ctx.getInput(FALSE, Object.class);
        Object tr = ctx.getInput(TRUE, Object.class);
        boolean on = sw != null && sw;
        Object pick = on ? tr : fa;
        GeometryBundle b = GeometryBundles.bundlePart(pick);
        if (b == null) {
            b = GeometryBundle.empty();
        }
        ctx.setOutput(GEOMETRY_2, b);
    }
}
