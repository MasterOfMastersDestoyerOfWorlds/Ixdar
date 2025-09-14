package shell.ui.scenes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import shell.render.color.Color;
import shell.render.sdf.ShaderDrawable;
import shell.render.text.ColorText;

public class ExpressionParser {

    private final String s;
    private int pos;
    private final Map<String, ColorText<Float>> env;

    public static final ColorText<Float> MISSING = new ColorText<Float>("?Missing?", Color.PINK);

    ExpressionParser(String s, Map<String, ColorText<Float>> env) {
        this.s = s;
        this.env = env;
        this.pos = 0;
    }

    public static ColorText<Float> evaluateAndAssign(String line, Map<String, ColorText<Float>> env) {
        // strip comments and semicolon
        String s = line;
        if (line.contains("sigDist")) {
            float z = 0;
        }
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
            ColorText<Float> v = tryEvalDistance(s, env);
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
                // Swizzle assignment: vec from identifier swizzle e.g., p = pos.xy or frag.rgba
                if (isSwizzle(right)) {
                    String base = right.substring(0, right.indexOf('.'));
                    String sw = right.substring(right.indexOf('.') + 1);
                    ArrayList<ColorText<Float>> comps = resolveSwizzleVector(base, sw, env);
                    if (comps != null && comps.size() > 0) {
                        ShaderDrawable.put(env, var, comps);
                        if (comps.size() == 1)
                            return comps.get(0);
                        return new ColorText<Float>("vec" + comps.size() + "(swizzle)", Color.BLUE_WHITE);
                    }
                }
                // Vector literal assignment: vecN p = vecN(...)
                if (right.startsWith("vec") && right.contains("(") && right.endsWith(")")) {
                    ArrayList<ColorText<Float>> vec = parseVec(right, env);
                    if (vec != null && vec.size() > 0) {
                        ShaderDrawable.put(env, var, vec);
                        return new ColorText<Float>("vec" + vec.size() + "(...)", Color.BLUE_WHITE);
                    } else {
                        env.put(var, new ColorText<Float>(right, Color.BLUE_WHITE));
                        return env.get(var);
                    }
                }
                // distance with vectors on RHS
                if (right.startsWith("distance(") && right.endsWith(")")) {
                    ColorText<Float> dv = tryEvalDistance(right, env);
                    if (dv != null) {
                        if (dv.data != null)
                            ShaderDrawable.put(env, var, dv.data);
                        return dv;
                    }
                }
                try {
                    ColorText<Float> val = new ExpressionParser(right, env).parse();
                    if (val != null && val.data != null)
                        ShaderDrawable.put(env, var, val.data);
                    return val;
                } catch (Exception ex) {
                    return null;
                }
            }
        } else {
            // Expression only
            // Heuristic: only attempt if it references known vars, functions, or digits
            if (!s.matches(".*([A-Za-z_][A-Za-z0-9_]*|[0-9]|sin|cos|tan|sqrt|abs|min|max|clamp|mix|distance|dot).*")) {
                return null;
            }
            if (s.startsWith("distance(") && s.endsWith(")")) {
                ColorText<Float> v = tryEvalDistance(s, env);
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

    public static String extractAssignedVar(String line) {
        if (line == null) {
            return null;
        }
        String s = line;
        int cidx = s.indexOf("//");
        if (cidx >= 0) {
            s = s.substring(0, cidx);
        }
        int eq = s.indexOf('=');
        if (eq > 0 && s.indexOf('=', eq + 1) == -1) {
            String left = s.substring(0, eq).trim();
            return extractVarName(left);
        }
        return null;
    }

    ColorText<Float> parse() {
        ColorText<Float> v = parseExpr();
        skipWs();
        return v;
    }

    private ColorText<Float> parseExpr() {
        ColorText<Float> v = parseTerm();
        while (true) {
            skipWs();
            if (match('+')) {
                ColorText<Float> r = parseTerm();
                v = new ColorText<Float>("+", Color.BLUE_WHITE,
                        (v != null && v.data != null ? v.data : 0f) + (r != null && r.data != null ? r.data : 0f));
            } else if (match('-')) {
                ColorText<Float> r = parseTerm();
                v = new ColorText<Float>("-", Color.BLUE_WHITE,
                        (v != null && v.data != null ? v.data : 0f) - (r != null && r.data != null ? r.data : 0f));
            } else {
                return v;
            }
        }
    }

    private ColorText<Float> parseTerm() {
        ColorText<Float> v = parseFactor();
        while (true) {
            skipWs();
            if (match('*')) {
                ColorText<Float> r = parseFactor();
                Float out = (v != null && v.data != null ? v.data : 0f) * (r != null && r.data != null ? r.data : 1f);
                v = new ColorText<Float>(ShaderDrawable.formatFixed(out), Color.GLSL_FLOAT, out);
                return v;
            } else if (match('/')) {
                ColorText<Float> r = parseFactor();
                Float out = (v != null && v.data != null ? v.data : 0f) / (r != null && r.data != null ? r.data : 1f);
                v = new ColorText<Float>(ShaderDrawable.formatFixed(out), Color.GLSL_FLOAT, out);
                return v;
            } else {
                return v;
            }
        }
    }

    private ColorText<Float> parseFactor() {
        skipWs();
        if (match('+')) {
            return parseFactor();
        }
        if (match('-')) {
            ColorText<Float> f = parseFactor();
            Float out = f.data * -1f;
            return new ColorText<Float>(ShaderDrawable.formatFixed(out), Color.GLSL_FLOAT, out);
        }
        if (match('(')) {
            ColorText<Float> v = parseExpr();
            expect(')');
            return v;
        }
        if (isAlpha(peek())) {
            String ident = parseIdent();
            skipWs();
            if (match('(')) {
                List<ColorText<Float>> args = new ArrayList<>();
                skipWs();
                if (!peekIs(')')) {
                    do {
                        args.add(parseExpr());
                        skipWs();
                    } while (match(','));
                }
                expect(')');
                Float out = applyFunc(ident, args);
                return new ColorText<Float>(ShaderDrawable.formatFixed(out), Color.GLSL_FLOAT, out);
            } else {
                // Support scalar swizzles like base.x or frag.a
                if (peekIs('.')) {
                    match('.');
                    StringBuilder sb = new StringBuilder();
                    while (pos < s.length() && isSwizzleChar(s.charAt(pos))) {
                        sb.append(s.charAt(pos++));
                    }
                    String sw = sb.toString();
                    if (sw.length() >= 1) {
                        String name = ident + "." + sw.charAt(0);
                        return resolveVar(name);
                    }
                }
                return resolveVar(ident);
            }
        }
        return parseNumber();
    }

    private ColorText<Float> parseNumber() {
        skipWs();
        int start = pos;
        while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.'))
            pos++;
        if (start == pos) {
            throw new RuntimeException("Expected number at " + pos);
        }
        Float val = Float.parseFloat(s.substring(start, pos));
        return new ColorText<Float>(ShaderDrawable.formatFixed(val), Color.BLUE_WHITE, val);
    }

    private String parseIdent() {
        int start = pos;
        while (pos < s.length() && (isAlphaNum(s.charAt(pos)) || s.charAt(pos) == '_'))
            pos++;
        return s.substring(start, pos);
    }

    private ColorText<Float> resolveVar(String name) {
        if ("pi".equalsIgnoreCase(name))
            return new ColorText<Float>("pi", Color.BLUE_WHITE, (float) Math.PI);
        if ("TAU".equalsIgnoreCase(name))
            return new ColorText<Float>("TAU", Color.BLUE_WHITE, (float) (Math.PI * 2.0f));
        if ("e".equalsIgnoreCase(name))
            return new ColorText<Float>("e", Color.BLUE_WHITE, (float) Math.E);
        // Handle swizzled scalar like base.x or frag.r
        int dotIdx = name.indexOf('.');
        if (dotIdx > 0 && dotIdx == name.lastIndexOf('.')) {
            String base = name.substring(0, dotIdx);
            String sw = name.substring(dotIdx + 1);
            if (sw.length() == 1 && isValidSwizzle(sw)) {
                String suffix = componentSuffix(sw.charAt(0));
                ColorText<Float> entry = env.get(base + suffix);
                if (entry == null) {
                    throw new RuntimeException("Unknown variable: " + base + suffix);
                }
                ColorText<Float> v = entry;
                return v == null ? ColorText.BLANK : v;
            }
        }
        ColorText<Float> v = env.get(name);
        if (v == null) {
            throw new RuntimeException("Unknown variable: " + name);
        }
        return v;
    }

    private Float applyFunc(String name, List<ColorText<Float>> a) {
        switch (name) {
        case "sin":
            return (float) Math.sin(a.get(0).data);
        case "cos":
            return (float) Math.cos(a.get(0).data);
        case "tan":
            return (float) Math.tan(a.get(0).data);
        case "sqrt":
            return (float) Math.sqrt(a.get(0).data);
        case "abs":
            return (float) Math.abs(a.get(0).data);
        case "floor":
            return (float) Math.floor(a.get(0).data);
        case "ceil":
            return (float) Math.ceil(a.get(0).data);
        case "round":
            return (float) Math.round(a.get(0).data);
        case "min":
            return (float) Math.min(a.get(0).data, a.get(1).data);
        case "max":
            return Math.max(a.get(0).data, a.get(1).data);
        case "dot":
            return a.get(0).data * a.get(1).data;
        case "distance":
            return Math.abs(a.get(0).data - a.get(1).data);
        case "mix": {
            ColorText<Float> x = a.get(0), y = a.get(1), t = a.get(2);
            return x.data * (1.0f - t.data) + y.data * t.data;
        }
        case "smoothstep": {
            Float edge0 = a.get(0).data, edge1 = a.get(1).data, x = a.get(2).data;
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
            Float x = a.get(0).data, lo = a.get(1).data, hi = a.get(2).data;
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

    private static ColorText<Float> tryEvalDistance(String expr, Map<String, ColorText<Float>> env2) {
        // Expect distance(A, B)
        int l = expr.indexOf('(');
        int r = expr.lastIndexOf(')');
        if (l < 0 || r < 0 || r <= l + 1)
            return null;
        String inside = expr.substring(l + 1, r);
        String[] parts = inside.split(",");
        if (parts.length != 2)
            return null;
        ArrayList<ColorText<Float>> a = getVec(parts[0].trim(), env2);
        ArrayList<ColorText<Float>> b = getVec(parts[1].trim(), env2);
        if (a == null || b == null)
            return null;
        int n = Math.min(a.size(), b.size());
        float sum = 0f;
        for (int i = 0; i < n; i++) {
            float d = a.get(i).data - b.get(i).data;
            sum += d * d;
        }
        Float out = (float) Math.sqrt(sum);
        return new ColorText<Float>(ShaderDrawable.formatFixed(out), Color.GLSL_FLOAT, out);
    }

    private static ArrayList<ColorText<Float>> getVec(String token, Map<String, ColorText<Float>> env) {
        // Named vector in env with components
        String[] comps = new String[] { "_x", "_y", "_z", "_w" };
        ArrayList<ColorText<Float>> values = new ArrayList<>();
        for (String c : comps) {
            String key = token + c;
            if (env.containsKey(key)) {
                values.add(env.get(key));
            }
        }
        if (!values.isEmpty()) {
            return values;
        }
        // Swizzle like base.xy or frag.rgba
        if (isSwizzle(token)) {
            String base = token.substring(0, token.indexOf('.'));
            String sw = token.substring(token.indexOf('.') + 1);
            return resolveSwizzleVector(base, sw, env);
        }
        // Literal vector
        if (token.startsWith("vec") && token.contains("(") && token.endsWith(")")) {
            return parseVec(token, env);
        }
        return null;
    }

    private static ArrayList<ColorText<Float>> parseVec(String expr, Map<String, ColorText<Float>> env) {
        int l = expr.indexOf('(');
        int r = expr.lastIndexOf(')');
        if (l < 0 || r < 0 || r <= l + 1)
            return null;
        String inside = expr.substring(l + 1, r);
        ArrayList<String> args = splitTopLevelArgs(inside);
        ArrayList<String> expanded = new ArrayList<>();
        for (String a : args) {
            String t = a.trim();
            if (isPureSwizzle(t)) {
                int dot = t.indexOf('.');
                String base = t.substring(0, dot).trim();
                String sw = t.substring(dot + 1).trim();
                for (int i = 0; i < sw.length(); i++) {
                    expanded.add(base + "." + sw.charAt(i));
                }
            } else {
                expanded.add(t);
            }
        }
        ArrayList<ColorText<Float>> vals = new ArrayList<>();
        for (String p : expanded) {
            ColorText<Float> v = evalSimple(p.trim(), env);
            if (v == null) {
                return null;
            }
            vals.add(v);
        }
        return vals;
    }

    private static ArrayList<String> splitTopLevelArgs(String s) {
        ArrayList<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            parts.add(cur.toString());
        }
        return parts;
    }

    private static boolean isPureSwizzle(String token) {
        token = token.trim();
        int dot = token.indexOf('.');
        if (dot <= 0)
            return false;
        String left = token.substring(0, dot).trim();
        String right = token.substring(dot + 1).trim();
        if (left.isEmpty() || right.isEmpty())
            return false;
        if (right.matches(".*[+\\-*/].*"))
            return false;
        if (!left.matches("[A-Za-z_][A-Za-z0-9_]*"))
            return false;
        return isValidSwizzle(right);
    }

    private static boolean isSwizzle(String s) {
        int dot = s.indexOf('.');
        if (dot <= 0 || dot == s.length() - 1)
            return false;
        String sw = s.substring(dot + 1);
        return isValidSwizzle(sw);
    }

    // private static String extractSwizzle(String s) {
    // int dot = s.indexOf('.');
    // if (dot <= 0 || dot == s.length() - 1)
    // return null;
    // String sw = s.substring(dot + 1);
    // return isValidSwizzle(sw) ? sw : null;
    // }

    private static boolean isValidSwizzle(String sw) {
        if (sw == null || sw.isEmpty() || sw.length() > 4)
            return false;
        for (int i = 0; i < sw.length(); i++) {
            char c = sw.charAt(i);
            if (!isSwizzleChar(c))
                return false;
        }
        return true;
    }

    private static boolean isSwizzleChar(char c) {
        switch (Character.toLowerCase(c)) {
        case 'x':
        case 'y':
        case 'z':
        case 'w':
        case 'r':
        case 'g':
        case 'b':
        case 'a':
            return true;
        default:
            return false;
        }
    }

    private static String componentSuffix(char c) {
        switch (Character.toLowerCase(c)) {
        case 'x':
        case 'r':
            return "_x";
        case 'y':
        case 'g':
            return "_y";
        case 'z':
        case 'b':
            return "_z";
        case 'w':
        case 'a':
            return "_w";
        default:
            return "_x";
        }
    }

    private static ArrayList<ColorText<Float>> resolveSwizzleVector(String base, String sw,
            Map<String, ColorText<Float>> env) {
        if (!isValidSwizzle(sw))
            return null;
        ArrayList<ColorText<Float>> list = new ArrayList<>();
        for (int i = 0; i < sw.length(); i++) {
            String key = base + componentSuffix(sw.charAt(i));
            ColorText<Float> e = env.get(key);
            if (e == null)
                return null;
            list.add(e);
        }
        return list;
    }

    // private static int swizzleLength(String sw) {
    // return (sw != null && isValidSwizzle(sw)) ? sw.length() : 0;
    // }

    private static ColorText<Float> evalSimple(String token, Map<String, ColorText<Float>> env) {
        try {
            return new ExpressionParser(token, env).parse();
        } catch (Exception e) {
            return env.get(token);
        }
    }
}