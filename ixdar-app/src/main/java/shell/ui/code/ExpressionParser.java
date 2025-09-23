package shell.ui.code;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.apache.commons.lang3.function.TriFunction;
import org.joml.Vector4f;

import shell.render.color.Color;

public class ExpressionParser {

    private final String s;
    private int pos;
    private final Map<String, ParseText> env;

    public static final ParseText MISSING = new ParseText("?Missing?", Color.PINK, -1);

    ExpressionParser(String s, Map<String, ParseText> env) {
        this.s = s;
        this.env = env;
        this.pos = 0;
    }

    public static ParseText evaluateAndAssign(String line, Map<String, ParseText> env) {

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

        String sl = s.toLowerCase();
        if (s.contains("==") || s.contains("?") || s.contains(":") || s.startsWith("#")
                || sl.startsWith("in ") || sl.startsWith("out ") || sl.startsWith("uniform ")
                || sl.startsWith("layout") || sl.startsWith("precision ") || sl.startsWith("void ")
                || sl.startsWith("struct ") || sl.startsWith("attribute ") || sl.startsWith("varying ")) {
            return null;
        }

        int eq = s.indexOf('=');
        if (eq > 0 && s.indexOf('=', eq + 1) == -1) {
            String left = s.substring(0, eq).trim();
            String right = s.substring(eq + 1).trim();
            String var = extractVarName(left);
            if (var != null && !var.isEmpty()) {

                if (isSwizzle(right)) {
                    String base = right.substring(0, right.indexOf('.'));
                    String sw = right.substring(right.indexOf('.') + 1);
                    ArrayList<ParseText> comps = resolveSwizzleVector(base, sw, env);
                    if (comps != null && comps.size() > 0) {
                        ParseText.putVec(env, var, comps);
                        return env.get(var);
                    }
                }

                if (right.startsWith("vec") && right.contains("(") && right.endsWith(")")) {
                    ArrayList<ParseText> vec = parseVec(right, env);
                    if (vec != null && vec.size() > 0) {
                        ParseText.putVec(env, var, vec);
                        return env.get(var);
                    } else {
                        env.put(var, new ParseText(right, Color.BLUE_WHITE, -1));
                        return env.get(var);
                    }
                }
                try {
                    ParseText val = new ExpressionParser(right, env).parse();
                    if (val != null && val.data != null)
                        env.put(var, val);
                    return val;
                } catch (Exception ex) {
                    return null;
                }
            }
        } else {

            if (!s.matches(".*([A-Za-z_][A-Za-z0-9_]*|[0-9]|sin|cos|tan|sqrt|abs|min|max|clamp|mix|distance|dot).*")) {
                return null;
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

        String cleaned = left.replaceAll("^(const\\s+)?(uniform\\s+|varying\\s+)?(float|Float|int)\\s+", "").trim();

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

    ParseText parse() {
        ParseText v = parseExpr();
        skipWs();
        return v;
    }

    private ParseText parseExpr() {
        ParseText v = parseTerm();
        while (true) {
            skipWs();
            if (match('+')) {
                ParseText r = parseTerm();
                List<ParseText> list = new ArrayList<>();
                list.add(v);
                list.add(r);
                v = applyTwoArgFunc((x, y) -> x + y, list);
            } else if (match('-')) {
                ParseText r = parseTerm();
                List<ParseText> list = new ArrayList<>();
                list.add(v);
                list.add(r);
                v = applyTwoArgFunc((x, y) -> x - y, list);
            } else {
                return v;
            }
        }
    }

    private ParseText parseTerm() {
        ParseText v = parseFactor();
        while (true) {
            skipWs();
            if (match('*')) {
                ParseText r = parseFactor();
                List<ParseText> list = new ArrayList<>();
                list.add(v);
                list.add(r);
                v = applyTwoArgFunc((x, y) -> x * y, list);
            } else if (match('/')) {
                ParseText r = parseFactor();
                List<ParseText> list = new ArrayList<>();
                list.add(v);
                list.add(r);
                v = applyTwoArgFunc((x, y) -> x / y, list);
            } else {
                return v;
            }
        }
    }

    private ParseText parseFactor() {
        skipWs();
        if (match('+')) {
            return parseFactor();
        }
        if (match('-')) {
            ParseText f = parseFactor();
            Vector4f out = new Vector4f(f.data).negate();
            return new ParseText("", Color.GLSL_FLOAT, out, f.vectorLength, "");
        }
        if (match('(')) {
            ParseText v = parseExpr();
            expect(')');
            return v;
        }
        if (isAlpha(peek())) {
            String ident = parseIdent();
            skipWs();
            if (match('(')) {
                List<ParseText> args = new ArrayList<>();
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

                if (peekIs('.')) {
                    match('.');
                    StringBuilder sb = new StringBuilder();
                    while (pos < s.length() && isSwizzleChar(s.charAt(pos))) {
                        sb.append(s.charAt(pos++));
                    }
                    String sw = sb.toString();
                    if (sw.length() >= 1) {
                        String name = ident + "." + sw;
                        return resolveVar(name);
                    }
                }
                return resolveVar(ident);
            }
        }
        return parseNumberOrParenExpr();
    }

    private ParseText parseNumberOrParenExpr() {
        skipWs();
        int start = pos;
        // Allow nested parenthesis and unary before numbers
        if (match('(')) {
            ParseText inner = parseExpr();
            expect(')');
            return inner;
        }
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
            throw new RuntimeException("Expected number at " + pos);
        }
        Float val = Float.parseFloat(s.substring(start, pos));
        return new ParseText(val);
    }

    private ParseText parseNumber() {
        skipWs();
        int start = pos;
        while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.'))
            pos++;
        if (start == pos) {
            throw new RuntimeException("Expected number at " + pos);
        }
        Float val = Float.parseFloat(s.substring(start, pos));
        return new ParseText(val);
    }

    private String parseIdent() {
        int start = pos;
        while (pos < s.length() && (isAlphaNum(s.charAt(pos)) || s.charAt(pos) == '_'))
            pos++;
        return s.substring(start, pos);
    }

    private ParseText resolveVar(String name) {
        if ("pi".equalsIgnoreCase(name))
            return new ParseText("pi", (float) Math.PI);
        if ("TAU".equalsIgnoreCase(name))
            return new ParseText("TAU", (float) (Math.PI * 2.0f));
        if ("e".equalsIgnoreCase(name))
            return new ParseText("e", (float) Math.E);

        int dotIdx = name.indexOf('.');
        if (dotIdx > 0 && dotIdx == name.lastIndexOf('.')) {
            String base = name.substring(0, dotIdx);
            String sw = name.substring(dotIdx + 1);
            int vectorLength = sw.length();
            float[] xyzw = new float[4];
            if (isValidSwizzle(sw)) {
                Vector4f org = env.get(base).getData();
                for (int i = 0; i < vectorLength; i++) {
                    int component = componentSuffix(sw.charAt(i));
                    xyzw[i] = org.get(component);
                }
                Vector4f vec = new Vector4f(xyzw);
                return new ParseText(name, vec, vectorLength);
            }
        }
        ParseText v = env.get(name);
        if (v == null) {
            throw new RuntimeException("Unknown variable: " + name);
        }
        return v;
    }

    private ParseText applyFunc(String name, List<ParseText> a) {
        switch (name) {
        case "sin":
            return applyOneArgFunc(Math::sin, a);
        case "cos":
            return applyOneArgFunc(Math::cos, a);
        case "tan":
            return applyOneArgFunc(Math::tan, a);
        case "sqrt":
            return applyOneArgFunc(Math::sqrt, a);
        case "abs":
            return applyOneArgFunc(Math::abs, a);
        case "floor":
            return applyOneArgFunc(Math::floor, a);
        case "ceil":
            return applyOneArgFunc(Math::ceil, a);
        case "round":
            return applyOneArgFunc((x) -> x - (x.intValue()) < 0.5 ? Math.floor(x) : Math.ceil(x), a);
        case "min":
            return applyTwoArgFunc(Math::min, a);
        case "max":
            return applyTwoArgFunc(Math::max, a);
        case "dot":
            return applyTwoArgFunc((x, y) -> x * y, a);
        case "distance":
            return distanceFunc(a);
        case "mix": {
            return mixFunc(a);
        }
        case "float": {
            // GLSL float(x): cast to float; we just forward the value (use first component)
            ParseText arg = a.get(0);
            Vector4f v = arg.data;
            Vector4f res = new Vector4f(v.x, 0f, 0f, 0f);
            return new ParseText(s, res, 1, "");
        }
        case "smoothstep": {
            return applyThreeArgFunc((edge0, edge1, x) -> {
                if (edge0 == edge1)
                    return x < edge0 ? 0.0 : 1.0;
                double t = (x - edge0) / (edge1 - edge0);
                if (t < 0.0)
                    t = 0.0;
                if (t > 1.0)
                    t = 1.0;
                return t * t * (3.0 - 2.0 * t);
            }, a);

        }
        case "clamp": {
            return applyThreeArgFunc((x, lo, hi) -> Math.max(lo, Math.min(hi, x)), a);
        }
        case "vec2":
            return constructVecN(2, a);
        case "vec3":
            return constructVecN(3, a);
        case "vec4":
            return constructVecN(4, a);
        default:
            return ParseText.BLANK;
        }
    }

    private ParseText constructVecN(int n, List<ParseText> args) {
        float[] out = new float[4];
        int filled = 0;
        for (int i = 0; i < args.size() && filled < n; i++) {
            ParseText a = args.get(i);
            int len = Math.max(1, a.vectorLength);
            for (int k = 0; k < len && filled < n; k++) {
                out[filled++] = a.data.get(Math.min(k, len - 1));
            }
        }
        while (filled < n)
            out[filled++] = 0f;
        Vector4f result = new Vector4f(out);
        return new ParseText(s, result, n, "");
    }

    private ParseText applyOneArgFunc(Function<Double, Double> func, List<ParseText> a) {
        ParseText arg = a.get(0);
        Vector4f data = arg.data;
        float[] result = new float[4];
        for (int i = 0; i < arg.vectorLength; i++) {
            result[i] = func.apply((double) data.get(i)).floatValue();
        }
        Vector4f resultVec = new Vector4f(result);
        return new ParseText(s, resultVec, arg.vectorLength, "");
    }

    private ParseText applyTwoArgFunc(BiFunction<Double, Double, Double> func, List<ParseText> a) {
        ParseText lhs = a.get(0);
        ParseText rhs = a.get(1);
        Vector4f l = lhs.data;
        Vector4f r = rhs.data;
        int len = Math.max(lhs.vectorLength, rhs.vectorLength);
        if (len < 1)
            len = 1;
        float[] result = new float[4];
        for (int i = 0; i < len; i++) {
            int li = Math.min(i, Math.max(0, lhs.vectorLength - 1));
            int ri = Math.min(i, Math.max(0, rhs.vectorLength - 1));
            result[i] = func.apply((double) l.get(li), (double) r.get(ri)).floatValue();
        }
        Vector4f resultVec = new Vector4f(result);
        return new ParseText(s, resultVec, len, "");
    }

    private ParseText applyThreeArgFunc(TriFunction<Double, Double, Double, Double> func, List<ParseText> a) {
        ParseText arg = a.get(0);
        Vector4f data = arg.data;
        ParseText arg2 = a.get(1);
        Vector4f data2 = arg2.data;
        ParseText arg3 = a.get(2);
        Vector4f data3 = arg3.data;
        float[] result = new float[4];
        for (int i = 0; i < arg.vectorLength; i++) {
            result[i] = func.apply((double) data.get(i), (double) data2.get(i), (double) data3.get(i)).floatValue();
        }
        Vector4f resultVec = new Vector4f(result);
        return new ParseText(s, resultVec, arg.vectorLength, "");
    }

    private ParseText mixFunc(List<ParseText> a) {
        ParseText x = a.get(0);
        ParseText y = a.get(1);
        ParseText t = a.get(2);
        Vector4f xv = x.data;
        Vector4f yv = y.data;
        Vector4f tv = t.data;
        int len = Math.max(x.vectorLength, y.vectorLength);
        if (len < 1)
            len = 1;
        float[] result = new float[4];
        for (int i = 0; i < len; i++) {
            int xi = Math.min(i, Math.max(0, x.vectorLength - 1));
            int yi = Math.min(i, Math.max(0, y.vectorLength - 1));
            // GLSL mix allows scalar or vector t; support both
            int ti = Math.min(i, Math.max(0, t.vectorLength - 1));
            double tt = tv.get(t.vectorLength == 1 ? 0 : ti);
            result[i] = (float) (xv.get(xi) * (1.0 - tt) + yv.get(yi) * tt);
        }
        Vector4f resultVec = new Vector4f(result);
        return new ParseText(s, resultVec, len, "");
    }

    private ParseText distanceFunc(List<ParseText> a) {
        ParseText arg = a.get(0);
        Vector4f data = arg.data;
        ParseText arg2 = a.get(1);
        Vector4f data2 = arg2.data;
        float result = 0.0f;
        for (int i = 0; i < arg.vectorLength; i++) {
            result += Math.pow(data.get(i) - data2.get(i), 2);
        }

        Vector4f resultVec = new Vector4f((float) Math.sqrt(result), 0f, 0f, 0f);
        return new ParseText(s, resultVec, 1, "");
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

    static String extractUniformName(String decl) {

        try {
            int semi = decl.indexOf(';');
            String s = semi >= 0 ? decl.substring(0, semi) : decl;
            String[] parts = s.split("\\s+");
            if (parts.length >= 3) {
                String cand = parts[2];

                cand = cand.replaceAll("[;,]", "");
                return cand;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static ArrayList<ParseText> parseVec(String expr, Map<String, ParseText> env) {
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
        ArrayList<ParseText> vals = new ArrayList<>();
        for (String p : expanded) {
            ParseText v = evalSimple(p.trim(), env);
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

    private static int componentSuffix(char c) {
        switch (Character.toLowerCase(c)) {
        case 'x':
        case 'r':
            return 0;
        case 'y':
        case 'g':
            return 1;
        case 'z':
        case 'b':
            return 2;
        case 'w':
        case 'a':
            return 3;
        default:
            return 4;
        }
    }

    private static ArrayList<ParseText> resolveSwizzleVector(String base, String sw,
            Map<String, ParseText> env) {
        if (!isValidSwizzle(sw))
            return null;
        ArrayList<ParseText> list = new ArrayList<>();
        for (int i = 0; i < sw.length(); i++) {
            int component = componentSuffix(sw.charAt(i));
            ParseText e = new ParseText(env.get(base).getData().get(component));
            list.add(e);
        }
        return list;
    }

    private static ParseText evalSimple(String token, Map<String, ParseText> env) {
        try {
            return new ExpressionParser(token, env).parse();
        } catch (Exception e) {
            return MISSING;
        }
    }
}