package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.annotations.meshnode.FloatField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "capture_attribute")
public class CaptureAttributeNode implements MeshNode {
    public static final String GEOMETRY_2 = "geometry";
    public static final String NAME_2 = "name";
    public static final String ATTR = "attr";
    public static final String VALUE_2 = "value";

    private static final InputPort GEOMETRY = new InputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort NAME = new InputPort(NAME_2, PortType.STRING, ATTR);
    private static final InputPort VALUE = new InputPort(VALUE_2, PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Stores a float value or FloatField as a named attribute slot on a geometry bundle for downstream retrieval.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                GEOMETRY_2, "Input/output bundle. The output carries a new slot named `name` holding `value`.",
                NAME_2, "Slot key (string) under which to store the attribute.",
                VALUE_2, "Float scalar or FloatField to store. Downstream nodes read via this same name."
        );
    }

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
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY_2, Object.class));
        String name = ctx.getInput(NAME_2, String.class);
        if (name == null || name.isBlank()) {
            name = ATTR;
        }
        Object vo = FieldBroadcast.getInputOrDefault(ctx, VALUE_2, VALUE.defaultValue());
        if (vo instanceof FloatField ff) {
            ctx.setOutput(GEOMETRY_2, base.withSlot(name, ff));
            return;
        }
        float f = FieldBroadcast.floatScalarOrDefault(vo, 0f);
        int n = base.mesh() == null ? 0 : base.mesh().vertexCount();
        ctx.setOutput(GEOMETRY_2, base.withSlot(name, FloatField.constant(f, Math.max(1, n))));
    }
}
