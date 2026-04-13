package ixdar.geometry.mesh.nodes.closure;

import java.util.List;

import ixdar.annotations.meshnode.FloatField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.curve.FloatCurveKernel;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/** Evaluates a {@link FloatCurveKernel} closure at a scalar coordinate. */
@MeshNodeAnnotation(id = "evaluate_closure")
public class EvaluateClosureNode implements MeshNode {

    private static final InputPort CLOSURE = new InputPort("closure", PortType.CLOSURE, null);
    private static final InputPort VALUE = new InputPort("value", PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

    @Override
    public List<InputPort> inputs() {
        return List.of(CLOSURE, VALUE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        FloatCurveKernel kernel = ctx.getInput("closure", FloatCurveKernel.class);
        Object vo = FieldBroadcast.getInputOrDefault(ctx, "value", VALUE.defaultValue());

        if (vo instanceof FloatField field) {
            float[] out = new float[field.length()];
            for (int i = 0; i < field.length(); i++) {
                float t = field.get(i);
                out[i] = kernel == null ? t : kernel.evaluate(t);
            }
            ctx.setOutput("result", new FloatField(out));
            return;
        }

        float t = FieldBroadcast.floatScalarOrDefault(vo, 0f);
        if (kernel == null) {
            ctx.setOutput("result", t);
            return;
        }
        ctx.setOutput("result", kernel.evaluate(t));
    }
}
