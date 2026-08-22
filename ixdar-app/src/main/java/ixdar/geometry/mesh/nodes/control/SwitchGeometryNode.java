package ixdar.geometry.mesh.nodes.control;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;

import java.util.Map;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;

@MeshNodeAnnotation(id = "switch_geometry")
public class SwitchGeometryNode implements MeshNode {
    public static final InputPort SWITCH = new InputPort("switch", PortType.BOOLEAN, false);
    public static final InputPort FALSE_VAL = new InputPort("false", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort TRUE_VAL = new InputPort("true", PortType.GEOMETRY_BUNDLE, null);
    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

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
    public Map<String, String> socketDocs() {
        return Map.of(
                SWITCH.name, "Scalar BOOLEAN selector.",
                FALSE_VAL.name, "Geometry used when switch is false.",
                TRUE_VAL.name, "Geometry used when switch is true.",
                GEOMETRY.name, "Selected geometry bundle."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Boolean sw = ctx.getInput(SWITCH.name, Boolean.class);
        Object fa = ctx.getInput(FALSE_VAL.name, Object.class);
        Object tr = ctx.getInput(TRUE_VAL.name, Object.class);
        boolean on = sw != null && sw;
        Object pick = on ? tr : fa;
        GeometryBundle b = GeometryBundles.bundlePart(pick);
        if (b == null) {
            b = GeometryBundle.empty();
        }
        ctx.setOutput(GEOMETRY.name, b);
    }
}
