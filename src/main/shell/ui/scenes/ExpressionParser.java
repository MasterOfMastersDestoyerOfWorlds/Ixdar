package shell.ui.scenes;

import java.util.ArrayList;
import java.util.AbstractMap;
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
                // Swizzle assignment: vec from identifier swizzle e.g., p = pos.xy or frag.rgba
                if (isSwizzle(right)) {
                    String base = right.substring(0, right.indexOf('.'));
                    String sw = right.substring(right.indexOf('.') + 1);
                    Float[] comps = resolveSwizzleVector(base, sw, env);
                    if (comps != null && comps.length > 0) {
                        ShaderDrawable.put(env, var, comps);
                        return comps.length == 1 ? comps[0] : null;
                    }
                }
                // Vector literal assignment: vecN p = vecN(...)
                if (right.startsWith("vec") && right.contains("(") && right.endsWith(")")) {
                    Float[] vec = parseVec(right, env);
                    if (vec != null && vec.length > 0) {
                        ShaderDrawable.put(env, var, vec);
                        return null;
                    } else {
                        // Could not resolve numeric components; keep the textual representation
                        env.put(var, new AbstractMap.SimpleEntry<String, Float>(right, 0f));
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
            if (!s.matches(".*([A-Za-z_][A-Za-z0-9_]*|[0-9]|sin|cos|tan|sqrt|abs|min|max|clamp|mix|distance|dot).*")) {
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
        if ("pi".equalsIgnoreCase(name))
            return (float) Math.PI;
        if ("TAU".equalsIgnoreCase(name))
            return (float) (Math.PI * 2.0f);
        if ("e".equalsIgnoreCase(name))
            return (float) Math.E;
        // Handle swizzled scalar like base.x or frag.r
        int dotIdx = name.indexOf('.');
        if (dotIdx > 0 && dotIdx == name.lastIndexOf('.')) {
            String base = name.substring(0, dotIdx);
            String sw = name.substring(dotIdx + 1);
            if (sw.length() == 1 && isValidSwizzle(sw)) {
                String suffix = componentSuffix(sw.charAt(0));
                Entry<String, Float> entry = env.get(base + suffix);
                if (entry == null) {
                    throw new RuntimeException("Unknown variable: " + base + suffix);
                }
                Float v = entry.getValue();
                return v != null ? v : 0.0f;
            }
        }
        Entry<String, Float> entry = env.get(name);
        if (entry == null) {
            throw new RuntimeException("Unknown variable: " + name);
        }
        Float v = entry.getValue();
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
        Float[] a = getVec(parts[0].trim(), env);
        Float[] b = getVec(parts[1].trim(), env);
        if (a == null || b == null)
            return null;
        int n = Math.min(a.length, b.length);
        float sum = 0f;
        for (int i = 0; i < n; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return (float) Math.sqrt(sum);
    }

    private static Float[] getVec(String token, Map<String, Entry<String, Float>> env) {
        if ("pos".equals(token)) {
            return new Float[] { getOrDefault(env, "posx"), getOrDefault(env, "posy") };
        }
        // Named vector in env with components
        String[] comps = new String[] { "_x", "_y", "_z", "_w" };
        ArrayList<Float> values = new ArrayList<>();
        for (String c : comps) {
            String key = token + c;
            if (env.containsKey(key)) {
                values.add(env.get(key).getValue());
            }
        }
        if (!values.isEmpty()) {
            return values.toArray(new Float[0]);
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

    private static Float getOrDefault(Map<String, Entry<String, Float>> env, String... keys) {
        for (String key : keys) {
            if (env.containsKey(key)) {
                return env.get(key).getValue();
            }
        }
        return 0f;
    }

    private static Float[] parseVec(String expr, Map<String, Entry<String, Float>> env) {
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
        ArrayList<Float> vals = new ArrayList<>();
        for (String p : expanded) {
            Float v = evalSimple(p.trim(), env);
            if (v == null) {
                return null;
            }
            vals.add(v);
        }
        return vals.toArray(new Float[0]);
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

    private static Float[] resolveSwizzleVector(String base, String sw, Map<String, Entry<String, Float>> env) {
        if (!isValidSwizzle(sw))
            return null;
        ArrayList<Float> list = new ArrayList<>();
        for (int i = 0; i < sw.length(); i++) {
            String key = base + componentSuffix(sw.charAt(i));
            Entry<String, Float> e = env.get(key);
            if (e == null)
                return null;
            list.add(e.getValue());
        }
        return list.toArray(new Float[0]);
    }

    // private static int swizzleLength(String sw) {
    // return (sw != null && isValidSwizzle(sw)) ? sw.length() : 0;
    // }

    private static Float evalSimple(String token, Map<String, Entry<String, Float>> env) {
        try {
            return new ExpressionParser(token, env).parse();
        } catch (Exception e) {
            Entry<String, Float> entry = env.get(token);
            return entry != null ? entry.getValue() : null;
        }
    }
}