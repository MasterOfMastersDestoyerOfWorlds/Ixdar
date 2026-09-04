package ixdar.geometry.mesh.nodes.control;

import java.util.Objects;
import java.util.List;

import ixdar.geometry.mesh.nodes.api.InputPort;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;

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
        GeometryBundle fa = ctx.getInput(FALSE_VAL.name, GeometryBundle.class);
        GeometryBundle tr = ctx.getInput(TRUE_VAL.name, GeometryBundle.class);
        boolean on = sw != null && sw;
        ctx.setOutput(GEOMETRY.name, Objects.requireNonNullElse(on ? tr : fa, GeometryBundle.empty()));
    }
}
