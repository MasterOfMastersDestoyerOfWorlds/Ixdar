package ixdar.nodegraph.nodes.data;

import java.util.List;

import ixdar.nodegraph.field.FieldContext;

import java.util.Map;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.Vector3Value;

@MeshNodeAnnotation(id = "input_normal", category = NodeCategory.FIELD_DATA)
public class InputNormalNode implements MeshNode {
    public static final OutputPort VECTOR = new OutputPort("vector", PortType.VECTOR3);

    @Override
    public String description() {
        return "Provides the per-vertex normal vectors from the current mesh field context.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                VECTOR.name, "Per-vertex Vector3field of unit outward normals. Requires a mesh field context."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of();
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(VECTOR);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        FieldContext fc = ctx.fieldContext();
        if (fc == null || fc.elementCount() == 0) {
            ctx.setOutput(VECTOR.name, new Vector3Value(0f, 1f, 0f));
            return;
        }
        ctx.setOutput(VECTOR.name, fc.normals());
    }
}
