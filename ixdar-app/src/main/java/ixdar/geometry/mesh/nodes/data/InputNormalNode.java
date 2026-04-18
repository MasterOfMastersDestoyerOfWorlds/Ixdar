package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.annotations.meshnode.FieldContext;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;

@MeshNodeAnnotation(id = "input_normal")
public class InputNormalNode implements MeshNode {

    private static final OutputPort VECTOR = new OutputPort("vector", PortType.VECTOR3);

    @Override
    public String description() {
        return "Provides the per-vertex normal vectors from the current mesh field context.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "vector", "Per-vertex Vec3Field of unit outward normals. Requires a mesh field context."
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
            ctx.setOutput("vector", new Vector3Value(0f, 1f, 0f));
            return;
        }
        ctx.setOutput("vector", fc.normals());
    }
}
