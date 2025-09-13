package shell.ui.scenes;

import shell.cameras.Bounds;
import shell.cameras.Camera;
import shell.cameras.Camera2D;
import shell.platform.input.MouseTrap;
import shell.render.color.Color;
import shell.render.sdf.ShaderDrawable;
import shell.render.text.HyperString;
import shell.ui.Canvas3D;
import shell.ui.Drawing;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Renders shader source code into a scrollable pane area using HyperString.
 * Owns its HyperString buffer and a scroll subscription bound.
 */
public class ShaderCodePane implements MouseTrap.ScrollHandler {

    private final HyperString codeText;
    private float scrollOffsetY;
    private final float scrollSpeed;
    private final ArrayList<String> displayedLines = new ArrayList<>();
    private final ArrayList<String> cachedSuffixes = new ArrayList<>();
    private float lastMouseX = Float.NaN;
    private float lastMouseY = Float.NaN;
    private final ShaderProgram targetShader;
    private final String title;
    public  Bounds paneBounds;
    private Bounds parentBounds;
    private boolean showCode;
    private HyperString showCodeButton;

    private final ShaderDrawable uniformProvider;

    public static final String DEFAULT_VIEW_RIGHT = "RIGHT_CODE";

    public ShaderCodePane(Bounds parentBounds, Map<String, Bounds> webViews, float scrollSpeed, ShaderProgram shader,
            String title,
            ShaderDrawable provider, Camera2D camera) {

        parentBounds.setUpdateCallback(
                b -> b.update(0, 0, showCode ? Canvas3D.frameBufferWidth / 2 : Canvas3D.frameBufferWidth,
                        Canvas3D.frameBufferHeight));
        this.parentBounds = parentBounds;
        this.scrollSpeed = scrollSpeed;
        this.codeText = new HyperString();
        this.targetShader = shader != null ? shader : ShaderType.Font.shader;
        this.title = title != null ? title : "Shader";
        this.uniformProvider = provider;
        showCode = true;
        paneBounds = new Bounds(Canvas3D.frameBufferWidth / 2, 0, 0, Canvas3D.frameBufferHeight,
                b -> b.update(
                        Canvas3D.frameBufferWidth / 2,
                        0,
                        showCode ? Canvas3D.frameBufferWidth / 2f : 0f,
                        Canvas3D.frameBufferHeight),
                DEFAULT_VIEW_RIGHT);

        showCodeButton = new HyperString();
        showCodeButton.addDynamicWordClick(() -> {
            return showCode ? "Hide Code" : "Show Code";
        }, Color.CYAN, () -> {
            showCode = !showCode;
            if (showCode) {
                paneBounds.viewWidth = Canvas3D.frameBufferWidth / 2f;
                parentBounds.viewWidth = Canvas3D.frameBufferWidth / 2f;
            } else {
                paneBounds.viewWidth = 0f;
                parentBounds.viewWidth = Canvas3D.frameBufferWidth;
            }
            camera.updateView(paneBounds.id);
            camera.updateView(parentBounds.id);
        });
        webViews.put(paneBounds.id, paneBounds);
        loadCode(this.targetShader, this.title);
        camera.updateView(paneBounds.id);
        MouseTrap.subscribeScrollRegion(this.paneBounds, this);
    }

    private void loadCode(ShaderProgram shader, String headerTitle) {
        try {
            displayedLines.clear();
            cachedSuffixes.clear();
            String fs = shader != null ? shader.getFragmentSource() : "";
            int gIndex = 0;
            // Live mouse coordinates header
            codeText.addWord("Mouse: ", Color.CYAN);
            codeText.addDynamicWord(() -> mouseText());
            codeText.newLine();
            displayedLines.add(null);
            gIndex++;
            codeText.addLine("// " + headerTitle + " - Fragment Shader", Color.WHITE);
            displayedLines.add(null);
            gIndex++;
            codeText.addDynamicWord(() -> updateCacheIfMouseMoved(), Color.BLUE_WHITE);
            for (String ln : fs.split("\n")) {
                final int idx = gIndex;
                codeText.addWord(ln, Color.WHITE);
                codeText.addWord("  ", Color.WHITE);
                codeText.addDynamicWord(() -> dynamicSuffix(idx), Color.BLUE_WHITE);
                codeText.newLine();
                displayedLines.add(ln);
                gIndex++;
            }
            // Initialize cache to correct size
            for (int i = 0; i < displayedLines.size(); i++) {
                cachedSuffixes.add("");
            }
            // Force recompute on first draw
            lastMouseX = Float.NaN;
            lastMouseY = Float.NaN;
        } catch (Exception e) {
            codeText.addLine("Failed to load shader from program", Color.RED);
        }
    }

    private String dynamicSuffix(int lineIndex) {
        if (lineIndex < 0 || lineIndex >= displayedLines.size()) {
            return "";
        }
        String cached = cachedSuffixes.get(lineIndex);
        return cached != null ? cached : "";
    }

    private String updateCacheIfMouseMoved() {
        float mx = 0f;
        float my = 0f;
        if (Canvas3D.mouse != null) {
            mx = Canvas3D.mouse.normalizedPosX;
            my = Canvas3D.mouse.normalizedPosY;
        }
        if (mx == lastMouseX && my == lastMouseY) {
            return "";
        }
        Map<String, Entry<String, Float>> env = uniformProvider.getUniformMap();
        Entry<String, Float> x = new AbstractMap.SimpleEntry<String, Float>(Float.toString(mx), mx);
        Entry<String, Float> y = new AbstractMap.SimpleEntry<String, Float>(Float.toString(my), my);
        env.put("mx", x);
        env.put("my", y);
        env.put("x", x);
        env.put("y", y);
        env.put("posx", x);
        env.put("posy", y);
        if (uniformProvider != null) {
            try {

            } catch (Throwable t) {
            }
        }
        // Ensure cache size matches displayed lines
        if (cachedSuffixes.size() != displayedLines.size()) {
            cachedSuffixes.clear();
            for (int i = 0; i < displayedLines.size(); i++)
                cachedSuffixes.add("");
        }
        for (int i = 0; i < displayedLines.size(); i++) {
            String line = displayedLines.get(i);
            String out = "";
            if (line != null) {
                // If this line declares a uniform, show its current value
                String decl = line.trim();
                if (decl.startsWith("uniform ")) {
                    String name = extractUniformName(decl);
                    if (name != null) {
                        Entry<String, Float> val = env.get(name);
                        if (val != null) {
                            out = "// = " + val.getKey();
                        }
                    }
                } else {
                    Float res = evaluateAndAssign(line, env);
                    if (res != null && !res.isNaN() && !res.isInfinite()) {
                        out = "// = " + formatFixed(res, 4);
                    }
                }
            }
            cachedSuffixes.set(i, out);
        }
        lastMouseX = mx;
        lastMouseY = my;
        return "";
    }

    private Float evaluateAndAssign(String line, Map<String, Entry<String, Float>> env) {
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

    private String mouseText() {
        float mx = 0f, my = 0f;
        if (Canvas3D.mouse != null) {
            mx = Canvas3D.mouse.normalizedPosX;
            my = Canvas3D.mouse.normalizedPosY;
        }
        return "mx=" + formatFixed(mx, 1) + " my=" + formatFixed(my, 1);
    }

    // Unused after moving uniform display inline with declarations; keep for
    // potential future use
    // private String uniformsText() { ... }

    private String extractUniformName(String decl) {
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

    private Float tryEvalDistance(String expr, Map<String, Entry<String, Float>> env) {
        // Expect distance(A, B)
        int l = expr.indexOf('(');
        int r = expr.lastIndexOf(')');
        if (l < 0 || r < 0 || r <= l + 1)
            return null;
        String inside = expr.substring(l + 1, r);
        String[] parts = inside.split(",");
        if (parts.length != 2)
            return null;
        Float[] a = getVec2(trim(parts[0]), env);
        Float[] b = getVec2(trim(parts[1]), env);
        if (a == null || b == null)
            return null;
        Float dx = a[0] - b[0];
        Float dy = a[1] - b[1];
        return (float) Math.sqrt((dx * dx + dy * dy));
    }

    private String trim(String s) {
        return s.trim();
    }

    private Float[] getVec2(String token, Map<String, Entry<String, Float>> env) {
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

    private Float getOrDefault(Map<String, Entry<String, Float>> env, String... keys) {
        for (String key : keys) {
            if (env.containsKey(key)) {
                return env.get(key).getValue();
            }
        }
        return 0f;
    }

    private Float[] parseVec2(String expr, Map<String, Entry<String, Float>> env) {
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

    private Float evalSimple(String token, Map<String, Entry<String, Float>> env) {
        try {
            return new ExpressionParser(token, env).parse();
        } catch (Exception e) {
            return env.get(token).getValue();
        }
    }

    private String extractVarName(String left) {
        // Remove qualifiers/types
        String cleaned = left.replaceAll("^(const\\s+)?(uniform\\s+|varying\\s+)?(float|Float|int)\\s+", "").trim();
        // Take last identifier-like token
        String[] parts = cleaned.split("[^A-Za-z0-9_]+");
        if (parts.length == 0) {
            return null;
        }
        return parts[parts.length - 1];
    }

    private String formatFixed(Float val, int digits) {
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

    private static class ExpressionParser {
        private final String s;
        private int pos;
        private final Map<String, Entry<String, Float>> env;

        ExpressionParser(String s, Map<String, Entry<String, Float>> env) {
            this.s = s;
            this.env = env;
            this.pos = 0;
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
    }

    public void draw(Camera2D camera) {
        if (paneBounds.viewWidth > 0) {
            camera.updateView(paneBounds.id);
            Drawing.font.drawHyperStringRows(codeText, 0, scrollOffsetY, Drawing.FONT_HEIGHT_PIXELS, camera);
        }
        camera.updateView(parentBounds.id);
        Drawing.font.drawHyperStringRows(showCodeButton, 0, 0, Drawing.FONT_HEIGHT_PIXELS, camera);
    }

    @Override
    public void onScroll(boolean scrollUp, double deltaSeconds) {
        if (scrollUp) {
            scrollOffsetY -= scrollSpeed * (float) deltaSeconds;
            if (scrollOffsetY < 0) {
                scrollOffsetY = 0;
            }
        } else {
            float bottom = codeText.getLastWord().yScreenOffset;
            if (bottom < 0) {
                scrollOffsetY += scrollSpeed * (float) deltaSeconds;
            }
        }
    }

    public Bounds getBounds() {
        return paneBounds;
    }
}
