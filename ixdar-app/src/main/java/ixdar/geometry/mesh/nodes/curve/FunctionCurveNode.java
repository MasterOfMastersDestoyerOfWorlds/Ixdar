package ixdar.geometry.mesh.nodes.curve;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.curve.FloatCurveKernel;
import ixdar.geometry.mesh.curve.MathExpressionEvaluator;

/**
 * Generates a float curve closure by evaluating a math expression at evenly-spaced points on [0, 1].
 * <p>
 * Use this when the curve shape follows a known mathematical formula. Use {@code float_curve} for
 * hand-tuned control points.
 * <p>
 * Example DSL:
 * <pre>
 *   curve = function_curve(expression="sin(x * pi)", resolution=64)
 *   deformed = curve_deform(geometry=mesh, closure=curve.closure, ...)
 * </pre>
 */
@MeshNodeAnnotation(id = "function_curve")
public class FunctionCurveNode implements MeshNode {

    private static final InputPort EXPRESSION =
            new InputPort("expression", PortType.STRING, "sin(x * pi)");
    private static final InputPort RESOLUTION =
            new InputPort("resolution", PortType.INT, 32, 2f, 256f);
    private static final OutputPort CLOSURE =
            new OutputPort("closure", PortType.CLOSURE);

    @Override
    public List<InputPort> inputs() {
        return List.of(EXPRESSION, RESOLUTION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(CLOSURE);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        String expr = ctx.getInput("expression", String.class);
        if (expr == null || expr.isBlank()) {
            expr = "sin(x * pi)";
        }

        Number resNum = ctx.getInput("resolution", Number.class);
        int resolution = resNum != null ? resNum.intValue() : 32;
        resolution = Math.max(2, Math.min(256, resolution));

        float[] xs = new float[resolution];
        float[] ys = new float[resolution];

        try {
            MathExpressionEvaluator evaluator = new MathExpressionEvaluator(expr);
            for (int i = 0; i < resolution; i++) {
                float x = (float) i / (float) (resolution - 1);
                xs[i] = x;
                double y = evaluator.evaluate(x);
                if (Double.isNaN(y)) {
                    y = 0.0;
                } else if (y == Double.POSITIVE_INFINITY) {
                    y = Float.MAX_VALUE;
                } else if (y == Double.NEGATIVE_INFINITY) {
                    y = -Float.MAX_VALUE;
                }
                ys[i] = (float) y;
            }
        } catch (IllegalArgumentException e) {
            // Malformed expression — fall back to identity curve
            xs = new float[]{0f, 1f};
            ys = new float[]{0f, 1f};
        }

        ctx.setOutput("closure", new FloatCurveKernel(xs, ys));
    }

    @Override
    public String description() {
        return "Evaluates a math expression over x in [0,1] to produce a float curve closure. "
                + "Variable: x. Constants: pi, e, tau. Operators: + - * / ^ (power). "
                + "Trig: sin cos tan asin acos atan atan2(y,x) sinh cosh tanh. "
                + "Rounding: floor ceil round sign abs fract. "
                + "Exp: exp log log2 log10 sqrt pow(x,n). "
                + "Range: min max clamp(x,lo,hi) step(edge,x) mod(x,y). "
                + "Interpolation: lerp(a,b,t) inverselerp(a,b,x) remap(x,inLo,inHi,outLo,outHi). "
                + "Easing: smoothstep(e0,e1,x) smootherstep(e0,e1,x) ease_in(x) ease_out(x) ease_in_out(x). "
                + "Organic: smin(a,b,k) pingpong(x,len). "
                + "Use function_curve for math formulas, float_curve for hand-tuned control points.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "expression", "Math expression in variable `x`. Supports trig, exp/log, clamp, smoothstep, ease_*, smin, pingpong, and more (see node description for the full list).",
                "resolution", "Number of samples taken over x∈[0,1] when rendering the curve for display; does NOT limit evaluation precision at runtime.",
                "closure", "Float closure wrapping the compiled expression. Sample at any x via evaluate_closure."
        );
    }
}
