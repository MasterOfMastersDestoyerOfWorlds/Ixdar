package ixdar.geometry.mesh.nodes.control;

import java.util.List;

import ixdar.annotations.meshnode.BoolField;

import java.util.Map;
import ixdar.annotations.meshnode.FloatField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "switch_float")
public class SwitchFloatNode implements MeshNode {
    public static final InputPort SWITCH = new InputPort("switch", PortType.BOOLEAN, false);
    public static final InputPort FALSE_VAL = new InputPort("false", PortType.FLOAT, 0.0f, -1000f, 1000f);
    public static final InputPort TRUE_VAL = new InputPort("true", PortType.FLOAT, 0.0f, -1000f, 1000f);
    public static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

    @Override
    public List<InputPort> inputs() {
        return List.of(SWITCH, FALSE_VAL, TRUE_VAL);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    @Override
    public String description() {
        return "Outputs one of two float values based on a boolean switch. Supports per-element field evaluation.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                SWITCH.name, "Per-element BOOLEAN selector.",
                FALSE_VAL.name, "Value used where switch is false.",
                TRUE_VAL.name, "Value used where switch is true.",
                RESULT.name, "Per-element float: switch ? true : false."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object so = FieldBroadcast.getInputOrDefault(ctx, SWITCH.name, SWITCH.defaultValue);
        Object fa = FieldBroadcast.getInputOrDefault(ctx, FALSE_VAL.name, FALSE_VAL.defaultValue);
        Object tr = FieldBroadcast.getInputOrDefault(ctx, TRUE_VAL.name, TRUE_VAL.defaultValue);

        if (so instanceof BoolField || fa instanceof FloatField || tr instanceof FloatField) {
            int n = 0;
            if (so instanceof BoolField bf) {
                n = Math.max(n, bf.length());
            }
            if (fa instanceof FloatField ff) {
                n = Math.max(n, ff.length());
            }
            if (tr instanceof FloatField tf) {
                n = Math.max(n, tf.length());
            }
            float[] out = new float[n];
            for (int i = 0; i < n; i++) {
                boolean on = FieldBroadcast.boolAt(so, i, false);
                float f = FieldBroadcast.floatAt(fa, i, 0f);
                float t = FieldBroadcast.floatAt(tr, i, 0f);
                out[i] = on ? t : f;
            }
            ctx.setOutput(RESULT.name, new FloatField(out));
            return;
        }

        boolean on = so instanceof Boolean b && b;
        float f = FieldBroadcast.floatScalarOrDefault(fa, 0f);
        float t = FieldBroadcast.floatScalarOrDefault(tr, 0f);
        ctx.setOutput(RESULT.name, on ? t : f);
    }
}
