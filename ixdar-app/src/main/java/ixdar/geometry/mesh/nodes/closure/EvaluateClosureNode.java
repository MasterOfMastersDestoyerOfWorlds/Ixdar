package ixdar.geometry.mesh.nodes.closure;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.curve.FloatCurveKernel;

/** Evaluates a {@link FloatCurveKernel} closure at a scalar coordinate (Blender NodeEvaluateClosure subset). */
@MeshNodeAnnotation(id = "evaluate_closure")
public class EvaluateClosureNode implements MeshNode {

    private static final InputPort CLOSURE = new InputPort("closure", PortType.CLOSURE, null);
    private static final InputPort VALUE = new InputPort("value", PortType.FLOAT, 0.0f);
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
        Number v = ctx.getInput("value", Number.class);
        float t = v == null ? 0f : v.floatValue();
        if (kernel == null) {
            ctx.setOutput("result", t);
            return;
        }
        ctx.setOutput("result", kernel.evaluate(t));
    }
}
