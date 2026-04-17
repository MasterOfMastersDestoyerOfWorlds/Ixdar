package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.annotations.meshnode.FloatField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.IntField;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "field_at_index")
public class FieldAtIndexNode implements MeshNode {

    private static final InputPort VALUE = new InputPort("value", PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final InputPort INDEX = new InputPort("index", PortType.INT, 0, 0f, 1000000f);
    private static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

    @Override
    public String description() {
        return "Samples a float field at a given index or indices, enabling indirect/gather-style lookups into per-vertex data.";
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
        Object vo = FieldBroadcast.getInputOrDefault(ctx, "value", VALUE.defaultValue());
        Object io = FieldBroadcast.getInputOrDefault(ctx, "index", INDEX.defaultValue());

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
            ctx.setOutput("result", new FloatField(out));
            return;
        }

        if (vo instanceof FloatField vf) {
            int j = FieldBroadcast.intScalarOrDefault(io, 0);
            if (j >= 0 && j < vf.length()) {
                ctx.setOutput("result", vf.get(j));
            } else {
                ctx.setOutput("result", 0f);
            }
            return;
        }

        float v = FieldBroadcast.floatScalarOrDefault(vo, 0f);
        ctx.setOutput("result", v);
    }
}
