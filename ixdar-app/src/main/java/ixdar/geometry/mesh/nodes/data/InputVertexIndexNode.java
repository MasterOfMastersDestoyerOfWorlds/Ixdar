package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.FieldContext;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.FloatField;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.MeshFieldContext;

/**
 * Outputs a per-vertex identity index field: {@code data[i] = i}.
 * <p>
 * When consumed per-vertex (e.g. by {@code set_position}), element {@code i}
 * returns vertex index {@code i}. Combine with {@code compare} to select
 * specific vertices by index.
 */
@MeshNodeAnnotation(id = "input_vertex_index")
public class InputVertexIndexNode implements MeshNode {
    public static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

    @Override
    public String description() {
        return "Outputs a per-element identity index field (data[i] = i) for vertex-domain selection and filtering.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                RESULT.name, "Per-vertex FloatField where result[i] = i. Feed into compare + boolean_math to build per-vertex selection masks."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of();
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
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
            ctx.setOutput(RESULT.name,0.0f);
            return;
        }
        float[] data = new float[n];
        for (int i = 0; i < n; i++) {
            data[i] = i;
        }
        ctx.setOutput(RESULT.name,new FloatField(data));
    }
}
