package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;

/** Stub: stores scalar in bundle slots under {@code name}. */
@MeshNodeAnnotation(id = "capture_attribute")
public class CaptureAttributeNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort NAME = new InputPort("name", PortType.STRING, "attr");
    private static final InputPort VALUE = new InputPort("value", PortType.FLOAT, 0.0f);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, NAME, VALUE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        String name = ctx.getInput("name", String.class);
        if (name == null || name.isBlank()) {
            name = "attr";
        }
        Number v = ctx.getInput("value", Number.class);
        float f = v == null ? 0f : v.floatValue();
        ctx.setOutput("geometry", base.withSlot(name, f));
    }
}
