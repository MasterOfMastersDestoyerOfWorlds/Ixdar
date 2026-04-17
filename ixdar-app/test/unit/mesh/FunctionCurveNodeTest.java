package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.curve.FloatCurveKernel;
import ixdar.geometry.mesh.nodes.curve.FunctionCurveNode;

public class FunctionCurveNodeTest {

    private static FloatCurveKernel evaluate(String expression, int resolution) {
        FunctionCurveNode node = new FunctionCurveNode();
        MapNodeContext ctx = new MapNodeContext(node);
        if (expression != null) {
            ctx.setInput("expression", expression);
        }
        ctx.setInput("resolution", resolution);
        node.evaluate(ctx);
        return ctx.getOutput("closure", FloatCurveKernel.class);
    }

    @Test
    public void identityCurve() {
        FloatCurveKernel k = evaluate("x", 3);
        assertNotNull(k);
        assertEquals(0f, k.evaluate(0f), 1e-5f);
        assertEquals(0.5f, k.evaluate(0.5f), 1e-5f);
        assertEquals(1f, k.evaluate(1f), 1e-5f);
    }

    @Test
    public void sinCurve() {
        FloatCurveKernel k = evaluate("sin(x * pi)", 5);
        // x=0.0: sin(0) = 0
        assertEquals(0f, k.evaluate(0f), 1e-3f);
        // x=0.25: sin(pi/4) ≈ 0.707
        assertEquals(0.707f, k.evaluate(0.25f), 0.02f);
        // x=0.5: sin(pi/2) = 1
        assertEquals(1f, k.evaluate(0.5f), 1e-3f);
        // x=1.0: sin(pi) ≈ 0
        assertEquals(0f, k.evaluate(1f), 1e-3f);
    }

    @Test
    public void defaultExpressionOnNull() {
        // Null expression falls back to "sin(x * pi)"
        FloatCurveKernel k = evaluate(null, 5);
        assertNotNull(k);
        assertEquals(1f, k.evaluate(0.5f), 1e-3f);
    }

    @Test
    public void malformedExpressionFallsBackToIdentity() {
        FloatCurveKernel k = evaluate("sin(", 10);
        assertNotNull(k);
        // Fallback identity: y=x
        assertEquals(0f, k.evaluate(0f), 1e-5f);
        assertEquals(1f, k.evaluate(1f), 1e-5f);
    }

    @Test
    public void resolutionClampedToMin() {
        // resolution=1 should be clamped to 2
        FloatCurveKernel k = evaluate("x", 1);
        assertNotNull(k);
        assertEquals(0f, k.evaluate(0f), 1e-5f);
        assertEquals(1f, k.evaluate(1f), 1e-5f);
    }

    @Test
    public void resolutionClampedToMax() {
        // resolution=500 should be clamped to 256
        FloatCurveKernel k = evaluate("x", 500);
        assertNotNull(k);
        assertEquals(0.5f, k.evaluate(0.5f), 1e-3f);
    }

    @Test
    public void nanHandling() {
        // 0/0 produces NaN at x=0, should be sanitized to 0
        FloatCurveKernel k = evaluate("x / x", 3);
        assertNotNull(k);
        // x=0 → 0/0 → NaN → 0
        assertEquals(0f, k.evaluate(0f), 1e-5f);
        // x=0.5 → 0.5/0.5 → 1
        assertEquals(1f, k.evaluate(0.5f), 1e-3f);
    }

    @Test
    public void descriptionIsNonEmpty() {
        FunctionCurveNode node = new FunctionCurveNode();
        String desc = node.description();
        assertNotNull(desc);
        assertTrue(desc.contains("sin"));
        assertTrue(desc.contains("smoothstep"));
        assertTrue(desc.contains("smin"));
    }
}
