package ixdar.geometry.mesh.nodes.curve;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;

import java.util.Map;
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
    public static final String SIN_X_PI = "sin(x * pi)";
    public static final int NUM_32 = 32;
    public static final int NUM_256 = 256;
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;

    public static final InputPort EXPRESSION =
            new InputPort("expression", PortType.STRING, SIN_X_PI);
    public static final InputPort RESOLUTION =
            new InputPort("resolution", PortType.INT, 32, 2f, 256f);
    public static final OutputPort CLOSURE =
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
        String expr = ctx.getInput(EXPRESSION.name, String.class);
        if (expr == null || expr.isBlank()) {
            expr = SIN_X_PI;
        }

        Number resNum = ctx.getInput(RESOLUTION.name, Number.class);
        int resolution = resNum != null ? resNum.intValue() : NUM_32;
        resolution = Math.max(2, Math.min(NUM_256, resolution));

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
            xs = new float[]{NUM_0, NUM_1};
            ys = new float[]{NUM_0, NUM_1};
        }

        ctx.setOutput(CLOSURE.name, new FloatCurveKernel(xs, ys));
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
    public Map<String, String> socketDocs() {
        return Map.of(
                EXPRESSION.name, "Math expression in variable `x`. Supports trig, exp/log, clamp, smoothstep, ease_*, smin, pingpong, and more (see node description for the full list).",
                RESOLUTION.name, "Number of samples taken over x∈[0,1] when rendering the curve for display; does NOT limit evaluation precision at runtime.",
                CLOSURE.name, "Float closure wrapping the compiled expression. Sample at any x via evaluate_closure."
        );
    }
}
