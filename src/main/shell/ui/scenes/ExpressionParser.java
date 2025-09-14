package shell.ui.scenes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import shell.render.sdf.ShaderDrawable;

public class ExpressionParser {

    private final String s;
    private int pos;
    private final Map<String, Entry<String, Float>> env;

    ExpressionParser(String s, Map<String, Entry<String, Float>> env) {
        this.s = s;
        this.env = env;
        this.pos = 0;
    }

    public static Float evaluateAndAssign(String line, Map<String, Entry<String, Float>> env) {
        // strip comments and semicolon
        String s = line;
        int cidx = s.indexOf("//");
        if (cidx >= 0) {
            s = s.substring(0, cidx);
        }
        s = s.trim();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return null;
        }
        // Ignore obvious non-expressions / declarations
        String sl = s.toLowerCase();
        if (s.contains("==") || s.contains("?") || s.contains(":") || s.startsWith("#")
                || sl.startsWith("in ") || sl.startsWith("out ") || sl.startsWith("uniform ")
                || sl.startsWith("layout") || sl.startsWith("precision ") || sl.startsWith("void ")
                || sl.startsWith("struct ") || sl.startsWith("attribute ") || sl.startsWith("varying ")) {
            return null;
        }
        // Special-case vector distance like: distance(p, pointA)
        if (s.startsWith("distance(") && s.endsWith(")")) {
            Float v = tryEvalDistance(s, env);
            if (v != null)
                return v;
        }
        // Handle assignment with optional type prefixes
        int eq = s.indexOf('=');
        if (eq > 0 && s.indexOf('=', eq + 1) == -1) {
            String left = s.substring(0, eq).trim();
            String right = s.substring(eq + 1).trim();
            String var = extractVarName(left);
            if (var != null && !var.isEmpty()) {
                // Vector assignment from mouse pos: vec2 p = pos.xy
                if (right.contains("pos.xy") || right.equals("pos")) {
                    Float px = getOrDefault(env, "posx", "mx");
                    Float py = getOrDefault(env, "posy", "my");
                    ShaderDrawable.put(env, var, px, py);
                    return null;
                }
                // Vector literal assignment: vec2 p = vec2(a,b)
                if (right.startsWith("vec2(")) {
                    Float[] v2 = parseVec2(right, env);
                    if (v2 != null) {
                        ShaderDrawable.put(env, var, v2);
                        return null;
                    }
                }
                // distance with vectors on RHS
                if (right.startsWith("distance(") && right.endsWith(")")) {
                    Float dv = tryEvalDistance(right, env);
                    if (dv != null) {
                        ShaderDrawable.put(env, var, dv);
                        return dv;
                    }
                }
                try {
                    Float val = new ExpressionParser(right, env).parse();
                    ShaderDrawable.put(env, var, val);
                    return val;
                } catch (Exception ex) {
                    return null;
                }
            }
        } else {
            // Expression only
            // Heuristic: only attempt if it references known vars, functions, or digits
            if (!s.matches(".*(mx|my|[0-9]|sin|cos|tan|sqrt|abs|min|max|clamp|mix|distance|dot).*")) {
                return null;
            }
            if (s.startsWith("distance(") && s.endsWith(")")) {
                Float v = tryEvalDistance(s, env);
                if (v != null)
                    return v;
            }
            try {
                return new ExpressionParser(s, env).parse();
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }

    private static String extractVarName(String left) {
        // Remove qualifiers/types
        String cleaned = left.replaceAll("^(const\\s+)?(uniform\\s+|varying\\s+)?(float|Float|int)\\s+", "").trim();
        // Take last identifier-like token
        String[] parts = cleaned.split("[^A-Za-z0-9_]+");
        if (parts.length == 0) {
            return null;
        }
        return parts[parts.length - 1];
    }

    static String formatFixed(Float val, int digits) {
        Float pow = (float) Math.pow(10, digits);
        Float rounded = Math.round(val * pow) / pow;
        String s = Float.toString(rounded);
        int dot = s.indexOf('.');
        if (dot < 0) {
            StringBuilder sb = new StringBuilder(s);
            sb.append('.');
            for (int i = 0; i < digits; i++)
                sb.append('0');
            return sb.toString();
        }
        int need = digits - (s.length() - dot - 1);
        if (need > 0) {
            StringBuilder sb = new StringBuilder(s);
            for (int i = 0; i < need; i++)
                sb.append('0');
            return sb.toString();
        }
        if (need < 0) {
            return s.substring(0, dot + 1 + digits);
        }
        return s;
    }

    Float parse() {
        Float v = parseExpr();
        skipWs();
        return v;
    }

    private Float parseExpr() {
        Float v = parseTerm();
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

    private Float parseTerm() {
        Float v = parseFactor();
        while (true) {
            skipWs();
            if (match('*')) {
                v *= parseFactor();
            } else if (match('/')) {
                v /= parseFactor();
            } else {
                return v;
            }
        }
    }

    private Float parseFactor() {
        skipWs();
        if (match('+')) {
            return parseFactor();
        }
        if (match('-')) {
            return -parseFactor();
        }
        if (match('(')) {
            Float v = parseExpr();
            expect(')');
            return v;
        }
        if (isAlpha(peek())) {
            String ident = parseIdent();
            skipWs();
            if (match('(')) {
                List<Float> args = new ArrayList<>();
                skipWs();
                if (!peekIs(')')) {
                    do {
                        args.add(parseExpr());
                        skipWs();
                    } while (match(','));
                }
                expect(')');
                return applyFunc(ident, args);
            } else {
                return resolveVar(ident);
            }
        }
        return parseNumber();
    }

    private Float parseNumber() {
        skipWs();
        int start = pos;
        while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.'))
            pos++;
        if (start == pos) {
            throw new RuntimeException("Expected number at " + pos);
        }
        return Float.parseFloat(s.substring(start, pos));
    }

    private String parseIdent() {
        int start = pos;
        while (pos < s.length() && (isAlphaNum(s.charAt(pos)) || s.charAt(pos) == '_'))
            pos++;
        return s.substring(start, pos);
    }

    private Float resolveVar(String name) {
        if ("pi".equalsIgnoreCase(name) || "PI".equals(name))
            return (float) Math.PI;
        if ("TAU".equals(name))
            return (float) (Math.PI * 2.0f);
        if ("e".equalsIgnoreCase(name))
            return (float) Math.E;
        Float v = env.get(name).getValue();
        return v != null ? v : 0.0f;
    }

    private Float applyFunc(String name, List<Float> a) {
        switch (name) {
        case "sin":
            return (float) Math.sin(a.get(0));
        case "cos":
            return (float) Math.cos(a.get(0));
        case "tan":
            return (float) Math.tan(a.get(0));
        case "sqrt":
            return (float) Math.sqrt(a.get(0));
        case "abs":
            return (float) Math.abs(a.get(0));
        case "floor":
            return (float) Math.floor(a.get(0));
        case "ceil":
            return (float) Math.ceil(a.get(0));
        case "round":
            return (float) Math.round(a.get(0));
        case "min":
            return (float) Math.min(a.get(0), a.get(1));
        case "max":
            return Math.max(a.get(0), a.get(1));
        case "dot":
            return a.get(0) * a.get(1);
        case "distance":
            return Math.abs(a.get(0) - a.get(1));
        case "mix": {
            Float x = a.get(0), y = a.get(1), t = a.get(2);
            return x * (1.0f - t) + y * t;
        }
        case "smoothstep": {
            Float edge0 = a.get(0), edge1 = a.get(1), x = a.get(2);
            if (edge0 == edge1)
                return x < edge0 ? 0.0f : 1.0f;
            Float t = (x - edge0) / (edge1 - edge0);
            if (t < 0.0f)
                t = 0.0f;
            if (t > 1.0f)
                t = 1.0f;
            return t * t * (3.0f - 2.0f * t);
        }
        case "clamp": {
            Float x = a.get(0), lo = a.get(1), hi = a.get(2);
            return Math.max(lo, Math.min(hi, x));
        }
        default:
            return 0.0f;
        }
    }

    private void skipWs() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos)))
            pos++;
    }

    private boolean match(char c) {
        if (pos < s.length() && s.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!match(c))
            throw new RuntimeException("Expected '" + c + "' at " + pos);
    }

    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private boolean peekIs(char c) {
        return pos < s.length() && s.charAt(pos) == c;
    }

    private boolean isAlpha(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private boolean isAlphaNum(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // Unused after moving uniform display inline with declarations; keep for
    // potential future use
    // private String uniformsText() { ... }

    static String extractUniformName(String decl) {
        // Examples: "uniform float radius;", "uniform vec2 pointA;"
        try {
            int semi = decl.indexOf(';');
            String s = semi >= 0 ? decl.substring(0, semi) : decl;
            String[] parts = s.split("\\s+");
            if (parts.length >= 3) {
                String cand = parts[2];
                // strip array or trailing commas if any
                cand = cand.replaceAll("[;,]", "");
                return cand;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static Float tryEvalDistance(String expr, Map<String, Entry<String, Float>> env) {
        // Expect distance(A, B)
        int l = expr.indexOf('(');
        int r = expr.lastIndexOf(')');
        if (l < 0 || r < 0 || r <= l + 1)
            return null;
        String inside = expr.substring(l + 1, r);
        String[] parts = inside.split(",");
        if (parts.length != 2)
            return null;
        Float[] a = getVec2(parts[0].trim(), env);
        Float[] b = getVec2(parts[1].trim(), env);
        if (a == null || b == null)
            return null;
        Float dx = a[0] - b[0];
        Float dy = a[1] - b[1];
        return (float) Math.sqrt((dx * dx + dy * dy));
    }

    private static Float[] getVec2(String token, Map<String, Entry<String, Float>> env) {
        if ("pos".equals(token)) {
            return new Float[] { getOrDefault(env, "posx"), getOrDefault(env, "posy") };
        }
        if (env.containsKey(token + "_x") && env.containsKey(token + "_y")) {
            return new Float[] { env.get(token + "_x").getValue(), env.get(token + "_y").getValue() };
        }
        if ("pointA".equals(token)) {
            return new Float[] { getOrDefault(env, "pointA_x"), getOrDefault(env, "pointA_y") };
        }
        if (token.startsWith("vec2(")) {
            return parseVec2(token, env);
        }
        return null;
    }

    private static Float getOrDefault(Map<String, Entry<String, Float>> env, String... keys) {
        for (String key : keys) {
            if (env.containsKey(key)) {
                return env.get(key).getValue();
            }
        }
        return 0f;
    }

    private static Float[] parseVec2(String expr, Map<String, Entry<String, Float>> env) {
        int l = expr.indexOf('(');
        int r = expr.lastIndexOf(')');
        if (l < 0 || r < 0 || r <= l + 1)
            return null;
        String inside = expr.substring(l + 1, r);
        String[] parts = inside.split(",");
        if (parts.length != 2)
            return null;
        Float a = evalSimple(parts[0].trim(), env);
        Float b = evalSimple(parts[1].trim(), env);
        if (a == null || b == null)
            return null;
        return new Float[] { a, b };
    }

    private static Float evalSimple(String token, Map<String, Entry<String, Float>> env) {
        try {
            return new ExpressionParser(token, env).parse();
        } catch (Exception e) {
            return env.get(token).getValue();
        }
    }
}