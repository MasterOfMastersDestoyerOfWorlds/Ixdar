package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.FieldContext;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.api.Vector3Value;

@MeshNodeAnnotation(id = "input_position")
public class InputPositionNode implements MeshNode {
    public static final OutputPort VECTOR = new OutputPort("vector", PortType.VECTOR3);

    @Override
    public String description() {
        return "Provides the per-vertex position vectors from the current mesh field context.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                VECTOR.name, "Per-vertex Vector3field of world-space positions. Requires a mesh field context."
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
            ctx.setOutput(VECTOR.name, new Vector3Value(0f, 0f, 0f));
            return;
        }
        ctx.setOutput(VECTOR.name, fc.positions());
    }
}
