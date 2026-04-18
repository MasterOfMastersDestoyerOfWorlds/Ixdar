package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.annotations.meshnode.FieldContext;
import ixdar.annotations.meshnode.FloatField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.MeshFieldContext;

/**
 * Outputs a per-element identity index field: {@code data[i] = i}.
 * <p>
 * When consumed per-face (e.g. by {@code extrude_mesh} or {@code inset_faces}),
 * element {@code i} returns face index {@code i}. Combine with {@code compare}
 * to select specific faces by index.
 */
@MeshNodeAnnotation(id = "input_face_index")
public class InputFaceIndexNode implements MeshNode {

    private static final OutputPort INDEX = new OutputPort("index", PortType.FLOAT);

    @Override
    public String description() {
        return "Outputs a per-element identity index field (data[i] = i) for face-domain selection and filtering.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "index", "Per-face FloatField where index[i] = i. Feed into compare + boolean_math to build per-face selection masks."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of();
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(INDEX);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        FieldContext fc = ctx.fieldContext();
        int n = 0;
        if (fc instanceof MeshFieldContext mfc && mfc.mesh() != null) {
            MeshTopology mesh = mfc.mesh();
            n = Math.max(mesh.vertexCount(), mesh.faceCount());
        } else if (fc != null) {
            n = fc.elementCount();
        }
        if (n == 0) {
            ctx.setOutput("index", 0.0f);
            return;
        }
        float[] data = new float[n];
        for (int i = 0; i < n; i++) {
            data[i] = i;
        }
        ctx.setOutput("index", new FloatField(data));
    }
}
