package ixdar.nodegraph.nodes.data;

import java.util.Objects;
import java.util.List;

import ixdar.nodegraph.field.FloatField;

import java.util.Map;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.field.FieldBroadcast;

@MeshNodeAnnotation(id = "capture_attribute", category = NodeCategory.FIELD_DATA)
public class CaptureAttributeNode implements MeshNode {
    public static final String ATTR = "attr";
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort NAME = new InputPort("name", PortType.STRING, ATTR);
    public static final InputPort VALUE = new InputPort("value", PortType.FLOAT, 0.0f, -1000f, 1000f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Stores a float value or FloatField as a named attribute slot on a geometry bundle for downstream retrieval.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output bundle. The output carries a new slot named `name` holding `value`.",
                NAME.name, "Slot key (string) under which to store the attribute.",
                VALUE.name, "Float scalar or FloatField to store. Downstream nodes read via this same name."
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
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        String name = ctx.getInput(NAME.name, String.class);
        if (name == null || name.isBlank()) {
            name = ATTR;
        }
        Object vo = FieldBroadcast.getInputOrDefault(ctx, VALUE.name, VALUE.defaultValue);
        if (vo instanceof FloatField ff) {
            ctx.setOutput(GEOMETRY.name, base.withSlot(name, ff));
            return;
        }
        float f = FieldBroadcast.floatScalarOrDefault(vo, 0f);
        int n = base.mesh() == null ? 0 : base.mesh().vertexCount();
        ctx.setOutput(GEOMETRY.name, base.withSlot(name, FloatField.constant(f, Math.max(1, n))));
    }
}
