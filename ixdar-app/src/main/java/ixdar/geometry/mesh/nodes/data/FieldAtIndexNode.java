package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.FloatField;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "field_at_index")
public class FieldAtIndexNode implements MeshNode {
    public static final InputPort VALUE = new InputPort("value", PortType.FLOAT, 0.0f, -1000f, 1000f);
    public static final InputPort INDEX = new InputPort("index", PortType.INT, 0, 0f, 1000000f);
    public static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

    @Override
    public String description() {
        return "Samples a float field at a given index or indices, enabling indirect/gather-style lookups into per-vertex data.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                VALUE.name, "Per-element FloatField to sample from.",
                INDEX.name, "Indices into the field. Scalar or IntField.",
                RESULT.name, "Sampled float(s) at the requested index/indices."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(VALUE, INDEX);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object vo = FieldBroadcast.getInputOrDefault(ctx, VALUE.name, VALUE.defaultValue);
        Object io = FieldBroadcast.getInputOrDefault(ctx, INDEX.name, INDEX.defaultValue);

        if (vo instanceof FloatField vf && io instanceof IntField idxf) {
            int n = idxf.length();
            float[] out = new float[n];
            for (int i = 0; i < n; i++) {
                int j = idxf.get(i);
                if (j >= 0 && j < vf.length()) {
                    out[i] = vf.get(j);
                } else {
                    out[i] = 0f;
                }
            }
            ctx.setOutput(RESULT.name, new FloatField(out));
            return;
        }

        if (vo instanceof FloatField vf) {
            int j = FieldBroadcast.intScalarOrDefault(io, 0);
            if (j >= 0 && j < vf.length()) {
                ctx.setOutput(RESULT.name, vf.get(j));
            } else {
                ctx.setOutput(RESULT.name, 0f);
            }
            return;
        }

        float v = FieldBroadcast.floatScalarOrDefault(vo, 0f);
        ctx.setOutput(RESULT.name, v);
    }
}
