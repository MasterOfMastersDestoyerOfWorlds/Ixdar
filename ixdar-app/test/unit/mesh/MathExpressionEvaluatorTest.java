package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.curve.MathExpressionEvaluator;

public class MathExpressionEvaluatorTest {

    private static double eval(String expr, double x) {
        return new MathExpressionEvaluator(expr).evaluate(x);
    }

    private static double eval(String expr) {
        return eval(expr, 0.0);
    }

    // ── Constants ────────────────────────────────────────────────────

    @Test
    public void constants() {
        assertEquals(Math.PI, eval("pi"), 1e-10);
        assertEquals(Math.E, eval("e"), 1e-10);
        assertEquals(Math.PI * 2.0, eval("tau"), 1e-10);
    }

    // ── Variable x ──────────────────────────────────────────────────

    @Test
    public void variableX() {
        assertEquals(0.5, eval("x", 0.5), 1e-10);
        assertEquals(0.0, eval("x", 0.0), 1e-10);
        assertEquals(1.0, eval("x", 1.0), 1e-10);
    }

    // ── Arithmetic ──────────────────────────────────────────────────

    @Test
    public void basicArithmetic() {
        assertEquals(5.0, eval("2 + 3"), 1e-10);
        assertEquals(2.0, eval("5 - 3"), 1e-10);
        assertEquals(6.0, eval("2 * 3"), 1e-10);
        assertEquals(2.5, eval("10 / 4"), 1e-10);
    }

    @Test
    public void operatorPrecedence() {
        assertEquals(14.0, eval("2 + 3 * 4"), 1e-10);
        assertEquals(20.0, eval("(2 + 3) * 4"), 1e-10);
    }

    @Test
    public void powerOperator() {
        assertEquals(8.0, eval("2 ^ 3"), 1e-10);
    }

    @Test
    public void powerRightAssociative() {
        // 2^3^2 = 2^(3^2) = 2^9 = 512
        assertEquals(512.0, eval("2 ^ 3 ^ 2"), 1e-10);
    }

    @Test
    public void unaryMinus() {
        assertEquals(-0.5, eval("-x", 0.5), 1e-10);
        assertEquals(-5.0, eval("-(2 + 3)"), 1e-10);
        assertEquals(5.0, eval("--5"), 1e-10);
    }

    // ── 1-arg functions: trig ───────────────────────────────────────

    @Test
    public void trigFunctions() {
        assertEquals(0.0, eval("sin(0)"), 1e-10);
        assertEquals(1.0, eval("cos(0)"), 1e-10);
        assertEquals(0.0, eval("tan(0)"), 1e-10);
        assertEquals(1.0, eval("sin(x * pi)", 0.5), 1e-10);
    }

    @Test
    public void inverseTrigFunctions() {
        assertEquals(0.0, eval("asin(0)"), 1e-10);
        assertEquals(0.0, eval("acos(1)"), 1e-10);
        assertEquals(0.0, eval("atan(0)"), 1e-10);
    }

    @Test
    public void hyperbolicFunctions() {
        assertEquals(0.0, eval("sinh(0)"), 1e-10);
        assertEquals(1.0, eval("cosh(0)"), 1e-10);
        assertEquals(0.0, eval("tanh(0)"), 1e-10);
    }

    // ── 1-arg functions: rounding / sign ────────────────────────────

    @Test
    public void roundingFunctions() {
        assertEquals(2.0, eval("floor(2.7)"), 1e-10);
        assertEquals(3.0, eval("ceil(2.3)"), 1e-10);
        assertEquals(3.0, eval("round(2.7)"), 1e-10);
        assertEquals(3.0, eval("abs(-3)"), 1e-10);
        assertEquals(1.0, eval("sign(42)"), 1e-10);
        assertEquals(-1.0, eval("sign(-3)"), 1e-10);
    }

    @Test
    public void fractFunction() {
        assertEquals(0.7, eval("fract(2.7)"), 1e-10);
        assertEquals(0.3, eval("fract(0.3)"), 1e-10);
    }

    // ── 1-arg functions: exponential / logarithmic ──────────────────

    @Test
    public void expLogFunctions() {
        assertEquals(1.0, eval("exp(0)"), 1e-10);
        assertEquals(0.0, eval("log(1)"), 1e-10);
        assertEquals(1.0, eval("log2(2)"), 1e-10);
        assertEquals(2.0, eval("log10(100)"), 1e-10);
        assertEquals(2.0, eval("sqrt(4)"), 1e-10);
    }

    // ── 1-arg functions: easing ─────────────────────────────────────

    @Test
    public void easingFunctions() {
        assertEquals(0.0, eval("ease_in(0)"), 1e-10);
        assertEquals(1.0, eval("ease_in(1)"), 1e-10);
        assertEquals(0.25, eval("ease_in(0.5)"), 1e-10); // 0.5^2

        assertEquals(0.0, eval("ease_out(0)"), 1e-10);
        assertEquals(1.0, eval("ease_out(1)"), 1e-10);
        assertEquals(0.75, eval("ease_out(0.5)"), 1e-10); // 1 - 0.5^2

        assertEquals(0.0, eval("ease_in_out(0)"), 1e-10);
        assertEquals(1.0, eval("ease_in_out(1)"), 1e-10);
        assertEquals(0.5, eval("ease_in_out(0.5)"), 1e-10);
    }

    // ── 2-arg functions ─────────────────────────────────────────────

    @Test
    public void twoArgFunctions() {
        assertEquals(8.0, eval("pow(2, 3)"), 1e-10);
        assertEquals(3.0, eval("min(3, 5)"), 1e-10);
        assertEquals(5.0, eval("max(3, 5)"), 1e-10);
        assertEquals(1.0, eval("mod(7, 3)"), 1e-10);
    }

    @Test
    public void atan2Function() {
        assertEquals(Math.atan2(1.0, 0.0), eval("atan2(1, 0)"), 1e-10);
    }

    @Test
    public void stepFunction() {
        assertEquals(0.0, eval("step(0.5, 0.3)"), 1e-10); // 0.3 < 0.5
        assertEquals(1.0, eval("step(0.5, 0.7)"), 1e-10); // 0.7 >= 0.5
        assertEquals(1.0, eval("step(0.5, 0.5)"), 1e-10); // edge case: equal
    }

    @Test
    public void pingpongFunction() {
        assertEquals(0.5, eval("pingpong(0.5, 1)"), 1e-10);
        assertEquals(0.5, eval("pingpong(1.5, 1)"), 1e-10); // bounces back
        assertEquals(0.0, eval("pingpong(0, 1)"), 1e-10);
        assertEquals(1.0, eval("pingpong(1, 1)"), 1e-10);
    }

    // ── 3-arg functions ─────────────────────────────────────────────

    @Test
    public void clampFunction() {
        assertEquals(1.0, eval("clamp(1.5, 0, 1)"), 1e-10);
        assertEquals(0.0, eval("clamp(-0.5, 0, 1)"), 1e-10);
        assertEquals(0.5, eval("clamp(0.5, 0, 1)"), 1e-10);
    }

    @Test
    public void smoothstepFunction() {
        assertEquals(0.0, eval("smoothstep(0, 1, 0)"), 1e-10);
        assertEquals(1.0, eval("smoothstep(0, 1, 1)"), 1e-10);
        assertEquals(0.5, eval("smoothstep(0, 1, 0.5)"), 1e-10);
    }

    @Test
    public void smootherstepFunction() {
        assertEquals(0.0, eval("smootherstep(0, 1, 0)"), 1e-10);
        assertEquals(1.0, eval("smootherstep(0, 1, 1)"), 1e-10);
        assertEquals(0.5, eval("smootherstep(0, 1, 0.5)"), 1e-10);
    }

    @Test
    public void lerpFunction() {
        assertEquals(5.0, eval("lerp(0, 10, 0.5)"), 1e-10);
        assertEquals(0.0, eval("lerp(0, 10, 0)"), 1e-10);
        assertEquals(10.0, eval("lerp(0, 10, 1)"), 1e-10);
    }

    @Test
    public void inverseLerpFunction() {
        assertEquals(0.5, eval("inverselerp(0, 10, 5)"), 1e-10);
        assertEquals(0.0, eval("inverselerp(0, 10, 0)"), 1e-10);
        assertEquals(1.0, eval("inverselerp(0, 10, 10)"), 1e-10);
    }

    @Test
    public void sminFunction() {
        // With k=0 it's just min
        assertEquals(2.0, eval("smin(2, 5, 0)"), 1e-10);
        // With k>0 and |a-b| < k, the result is below min(a,b)
        double result = eval("smin(2, 2.5, 1)");
        assertTrue(result < 2.0, "smin should produce a value below min(a,b) when values are within k");
    }

    // ── 5-arg functions ─────────────────────────────────────────────

    @Test
    public void remapFunction() {
        // Remap 0.5 from [0,1] to [10,20]
        assertEquals(15.0, eval("remap(0.5, 0, 1, 10, 20)"), 1e-10);
        assertEquals(10.0, eval("remap(0, 0, 1, 10, 20)"), 1e-10);
        assertEquals(20.0, eval("remap(1, 0, 1, 10, 20)"), 1e-10);
    }

    // ── Composite expressions ───────────────────────────────────────

    @Test
    public void compositeExpression() {
        // sin(x * pi) at x=0.5 → sin(pi/2) → 1.0
        assertEquals(1.0, eval("sin(x * pi)", 0.5), 1e-10);
    }

    @Test
    public void whitespaceHandling() {
        assertEquals(1.0, eval("  sin ( x * pi ) ", 0.5), 1e-10);
    }

    // ── Error cases ─────────────────────────────────────────────────

    @Test
    public void malformedExpressionThrows() {
        assertThrows(IllegalArgumentException.class, () -> eval("sin("));
    }

    @Test
    public void unknownVariableThrows() {
        assertThrows(IllegalArgumentException.class, () -> eval("y"));
    }

    @Test
    public void unknownFunctionThrows() {
        assertThrows(IllegalArgumentException.class, () -> eval("foo(1)"));
    }

    @Test
    public void wrongArgCountThrows() {
        assertThrows(IllegalArgumentException.class, () -> eval("sin(1, 2)"));
        assertThrows(IllegalArgumentException.class, () -> eval("clamp(1)"));
    }

    @Test
    public void divisionByZeroProducesInfinity() {
        double result = eval("1 / 0");
        assertTrue(Double.isInfinite(result));
    }

    @Test
    public void blankExpressionThrows() {
        assertThrows(IllegalArgumentException.class, () -> new MathExpressionEvaluator(""));
        assertThrows(IllegalArgumentException.class, () -> new MathExpressionEvaluator("  "));
    }
}
