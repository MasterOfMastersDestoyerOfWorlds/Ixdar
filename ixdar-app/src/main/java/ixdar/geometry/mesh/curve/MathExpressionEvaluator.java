package ixdar.geometry.mesh.curve;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent evaluator for math expressions in the single variable {@code x}.
 *
 * <p>Supports arithmetic ({@code + - * / ^}), constants ({@code pi e tau}) and a broad set of
 * functions for procedural curve definitions. Exponentiation is right-associative; unary sign
 * binds tighter than any binary operator.
 */
public final class MathExpressionEvaluator {
    public static final String AT_POSITION = "' at position ";
    public static final double NUM_2_0 = 2.0;
    public static final double NUM_0_5 = 0.5;
    public static final int NUM_3 = 3;
    public static final double NUM_3_0 = 3.0;
    public static final double NUM_6_0 = 6.0;
    public static final double NUM_15_0 = 15.0;
    public static final double NUM_10_0 = 10.0;
    public static final int NUM_5 = 5;
    public static final int NUM_4 = 4;

    private final String expression;
    private String s;
    private int pos;
    private double xValue;

    /**
     * Capture the source expression for repeated evaluation; parsing happens lazily in
     * {@link #evaluate(double)}.
     *
     * @param expression source text in the grammar described above
     * @throws IllegalArgumentException if {@code expression} is null or blank
     */
    public MathExpressionEvaluator(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Expression must not be blank");
        }
        this.expression = expression;
    }

    /**
     * Parse and evaluate the captured expression with the variable {@code x} bound to the
     * supplied value. Re-parses on every call.
     *
     * @param x value bound to the {@code x} identifier inside the expression
     * @throws IllegalArgumentException on syntax errors, unknown identifiers/functions,
     *         wrong-arity calls, or trailing characters after the expression
     * @return the numeric result
     */
    public double evaluate(double x) {
        this.s = expression;
        this.pos = 0;
        this.xValue = x;
        double result = parseExpr();
        skipWs();
        if (pos < s.length()) {
            throw new IllegalArgumentException(
                    "Unexpected character '" + s.charAt(pos) + AT_POSITION + pos);
        }
        return result;
    }

    // ── Grammar ──────────────────────────────────────────────────────

    private double parseExpr() {
        double v = parseTerm();
        while (true) {
            skipWs();
            if (match('+')) {
                v += parseTerm();
            } else if (match('-')) {
                v -= parseTerm();
            } else {
                return v;
            }
        }
    }

    private double parseTerm() {
        double v = parsePower();
        while (true) {
            skipWs();
            if (match('*')) {
                v *= parsePower();
            } else if (match('/')) {
                v /= parsePower();
            } else {
                return v;
            }
        }
    }

    private double parsePower() {
        double base = parseUnary();
        skipWs();
        if (match('^')) {
            return Math.pow(base, parsePower()); // right-associative
        }
        return base;
    }

    private double parseUnary() {
        skipWs();
        if (match('-')) {
            return -parseUnary();
        }
        if (match('+')) {
            return parseUnary();
        }
        return parseAtom();
    }

    private double parseAtom() {
        skipWs();
        if (match('(')) {
            double v = parseExpr();
            expect(')');
            return v;
        }
        if (isAlpha(( pos < s.length() ? s.charAt(pos) : '\0'))) {
            String ident = parseIdent();
            skipWs();
            if (match('(')) {
                List<Double> args = new ArrayList<>();
                skipWs();
                if (!peekIs(')')) {
                    args.add(parseExpr());
                    while (match(',')) {
                        args.add(parseExpr());
                    }
                }
                expect(')');
                return switch (ident.toLowerCase()) {
            // 1-arg: trig
            case "sin" -> { requireArgs(ident, args, 1); yield Math.sin(args.get(0)); }
            case "cos" -> { requireArgs(ident, args, 1); yield Math.cos(args.get(0)); }
            case "tan" -> { requireArgs(ident, args, 1); yield Math.tan(args.get(0)); }
            case "asin" -> { requireArgs(ident, args, 1); yield Math.asin(args.get(0)); }
            case "acos" -> { requireArgs(ident, args, 1); yield Math.acos(args.get(0)); }
            case "atan" -> { requireArgs(ident, args, 1); yield Math.atan(args.get(0)); }
            // 1-arg: hyperbolic
            case "sinh" -> { requireArgs(ident, args, 1); yield Math.sinh(args.get(0)); }
            case "cosh" -> { requireArgs(ident, args, 1); yield Math.cosh(args.get(0)); }
            case "tanh" -> { requireArgs(ident, args, 1); yield Math.tanh(args.get(0)); }
            // 1-arg: rounding / sign
            case "floor" -> { requireArgs(ident, args, 1); yield Math.floor(args.get(0)); }
            case "ceil" -> { requireArgs(ident, args, 1); yield Math.ceil(args.get(0)); }
            case "round" -> { requireArgs(ident, args, 1); yield Math.round(args.get(0)); }
            case "sign" -> { requireArgs(ident, args, 1); yield Math.signum(args.get(0)); }
            case "abs" -> { requireArgs(ident, args, 1); yield Math.abs(args.get(0)); }
            case "fract" -> { requireArgs(ident, args, 1); double a = args.get(0); yield a - Math.floor(a); }
            // 1-arg: exponential / logarithmic
            case "exp" -> { requireArgs(ident, args, 1); yield Math.exp(args.get(0)); }
            case "log" -> { requireArgs(ident, args, 1); yield Math.log(args.get(0)); }
            case "log2" -> { requireArgs(ident, args, 1); yield Math.log(args.get(0)) / Math.log(NUM_2_0); }
            case "log10" -> { requireArgs(ident, args, 1); yield Math.log10(args.get(0)); }
            case "sqrt" -> { requireArgs(ident, args, 1); yield Math.sqrt(args.get(0)); }
            // 1-arg: easing (quadratic, defined over [0,1])
            case "ease_in" -> { requireArgs(ident, args, 1); double t = args.get(0); yield t * t; }
            case "ease_out" -> { requireArgs(ident, args, 1); double t = args.get(0); yield 1.0 - (1.0 - t) * (1.0 - t); }
            case "ease_in_out" -> {
                requireArgs(ident, args, 1);
                double t = args.get(0);
                yield t < NUM_0_5 ? NUM_2_0 * t * t : 1.0 - NUM_2_0 * (1.0 - t) * (1.0 - t);
            }
            // 2-arg
            case "pow" -> { requireArgs(ident, args, 2); yield Math.pow(args.get(0), args.get(1)); }
            case "min" -> { requireArgs(ident, args, 2); yield Math.min(args.get(0), args.get(1)); }
            case "max" -> { requireArgs(ident, args, 2); yield Math.max(args.get(0), args.get(1)); }
            case "mod" -> { requireArgs(ident, args, 2); yield args.get(0) % args.get(1); }
            case "atan2" -> { requireArgs(ident, args, 2); yield Math.atan2(args.get(0), args.get(1)); }
            case "step" -> {
                requireArgs(ident, args, 2);
                yield args.get(1) < args.get(0) ? 0.0 : 1.0;
            }
            case "pingpong" -> {
                requireArgs(ident, args, 2);
                double v = args.get(0);
                double len = args.get(1);
                if (len == 0.0) yield 0.0;
                double t = v % (len * NUM_2_0);
                if (t < 0.0) t += len * NUM_2_0;
                yield len - Math.abs(t - len);
            }
            // 3-arg: interpolation / easing
            case "clamp" -> {
                requireArgs(ident, args, NUM_3);
                yield Math.max(args.get(1), Math.min(args.get(2), args.get(0)));
            }
            case "smoothstep" -> {
                requireArgs(ident, args, NUM_3);
                double edge0 = args.get(0), edge1 = args.get(1), v = args.get(2);
                if (edge0 == edge1) yield v < edge0 ? 0.0 : 1.0;
                double t = (v - edge0) / (edge1 - edge0);
                t = Math.max(0.0, Math.min(1.0, t));
                yield t * t * (NUM_3_0 - NUM_2_0 * t);
            }
            case "smootherstep" -> {
                requireArgs(ident, args, NUM_3);
                double edge0 = args.get(0), edge1 = args.get(1), v = args.get(2);
                if (edge0 == edge1) yield v < edge0 ? 0.0 : 1.0;
                double t = (v - edge0) / (edge1 - edge0);
                t = Math.max(0.0, Math.min(1.0, t));
                yield t * t * t * (t * (t * NUM_6_0 - NUM_15_0) + NUM_10_0);
            }
            case "lerp" -> {
                requireArgs(ident, args, NUM_3);
                double a = args.get(0), b = args.get(1), t = args.get(2);
                yield a + (b - a) * t;
            }
            case "inverselerp" -> {
                requireArgs(ident, args, NUM_3);
                double a = args.get(0), b = args.get(1), v = args.get(2);
                if (a == b) yield 0.0;
                yield (v - a) / (b - a);
            }
            case "smin" -> {
                requireArgs(ident, args, NUM_3);
                double a = args.get(0), b = args.get(1), k = args.get(2);
                if (k <= 0.0) yield Math.min(a, b);
                double h = Math.max(0.0, Math.min(1.0, NUM_0_5 + NUM_0_5 * (b - a) / k));
                yield a * h + b * (1.0 - h) - k * h * (1.0 - h);
            }
            // 5-arg
            case "remap" -> {
                requireArgs(ident, args, NUM_5);
                double v = args.get(0);
                double inLo = args.get(1), inHi = args.get(2);
                double outLo = args.get(NUM_3), outHi = args.get(NUM_4);
                if (inLo == inHi) yield outLo;
                double t = (v - inLo) / (inHi - inLo);
                yield outLo + (outHi - outLo) * t;
            }
            default -> throw new IllegalArgumentException("Unknown function: " + ident);
        };
            }
            return switch (ident.toLowerCase()) {
            case "x" -> xValue;
            case "pi" -> Math.PI;
            case "e" -> Math.E;
            case "tau" -> Math.PI * NUM_2_0;
            default -> throw new IllegalArgumentException("Unknown variable: " + ident);
        };
        }
        return parseNumber();
    }

    private double parseNumber() {
        skipWs();
        int start = pos;
        boolean sawDigit = false;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (Character.isDigit(c) || c == '.') {
                sawDigit = true;
                pos++;
            } else {
                break;
            }
        }
        if (!sawDigit) {
            throw new IllegalArgumentException("Expected number at position " + pos);
        }
        return Double.parseDouble(s.substring(start, pos));
    }

    private String parseIdent() {
        int start = pos;
        while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
            pos++;
        }
        return s.substring(start, pos);
    }

    private static void requireArgs(String name, List<Double> args, int expected) {
        if (args.size() != expected) {
            throw new IllegalArgumentException(
                    name + "() requires " + expected + " argument(s), got " + args.size());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void skipWs() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }

    private boolean match(char c) {
        skipWs();
        if (pos < s.length() && s.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        skipWs();
        if (pos >= s.length() || s.charAt(pos) != c) {
            throw new IllegalArgumentException("Expected '" + c + AT_POSITION + pos);
        }
        pos++;
    }

    private boolean peekIs(char c) {
        skipWs();
        return pos < s.length() && s.charAt(pos) == c;
    }

    private static boolean isAlpha(char c) {
        return Character.isLetter(c) || c == '_';
    }
}
