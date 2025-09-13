package shell.ui.scenes;

import shell.cameras.Bounds;
import shell.cameras.Camera2D;
import shell.platform.input.MouseTrap;
import shell.render.color.Color;
import shell.render.text.HyperString;
import shell.ui.Canvas3D;
import shell.ui.Drawing;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders shader source code into a scrollable pane area using HyperString.
 * Owns its HyperString buffer and a scroll subscription bound.
 */
public class ShaderCodePane implements MouseTrap.ScrollHandler {

    private final Bounds paneBounds;
    private final HyperString codeText;
    private float scrollOffsetY;
    private final float scrollSpeed;
    private final ArrayList<String> displayedLines = new ArrayList<>();
    private final ArrayList<String> cachedSuffixes = new ArrayList<>();
    private float lastMouseX = Float.NaN;
    private float lastMouseY = Float.NaN;
    private final ShaderProgram targetShader;
    private final String title;

    public ShaderCodePane(Bounds paneBounds, float scrollSpeed) {
        this.paneBounds = paneBounds;
        this.scrollSpeed = scrollSpeed;
        this.codeText = new HyperString();
        this.targetShader = ShaderType.Font.shader;
        this.title = "Font Shader";
        loadCode(targetShader, title);
        codeText.draw();
        MouseTrap.subscribeScrollRegion(this.paneBounds, this);
    }

    public ShaderCodePane(Bounds paneBounds, float scrollSpeed, ShaderProgram shader, String title) {
        this.paneBounds = paneBounds;
        this.scrollSpeed = scrollSpeed;
        this.codeText = new HyperString();
        this.targetShader = shader != null ? shader : ShaderType.Font.shader;
        this.title = title != null ? title : "Shader";
        loadCode(this.targetShader, this.title);
        codeText.draw();
        MouseTrap.subscribeScrollRegion(this.paneBounds, this);
    }

    private void loadCode(ShaderProgram shader, String headerTitle) {
        try {
            displayedLines.clear();
            cachedSuffixes.clear();
            String vs = shader != null ? shader.getVertexSource() : "";
            String fs = shader != null ? shader.getFragmentSource() : "";
            int gIndex = 0;
            // Live mouse coordinates header
            codeText.addWord("Mouse: ", Color.CYAN);
            codeText.addDynamicWord(() -> mouseText());
            codeText.newLine();
            displayedLines.add(null);
            gIndex++;
            codeText.addLine("// " + headerTitle + " - Vertex Shader", Color.WHITE);
            displayedLines.add(null);
            gIndex++;
            for (String ln : vs.split("\n")) {
                final int idx = gIndex;
                codeText.addWord(ln, Color.WHITE);
                codeText.addWord("  ", Color.WHITE);
                codeText.addDynamicWord(() -> dynamicSuffix(idx));
                codeText.newLine();
                displayedLines.add(ln);
                gIndex++;
            }
            codeText.addLine(" ", Color.WHITE);
            displayedLines.add(null);
            gIndex++;
            codeText.addLine("// " + headerTitle + " - Fragment Shader", Color.WHITE);
            displayedLines.add(null);
            gIndex++;
            for (String ln : fs.split("\n")) {
                final int idx = gIndex;
                codeText.addWord(ln, Color.WHITE);
                codeText.addWord("  ", Color.WHITE);
                codeText.addDynamicWord(() -> dynamicSuffix(idx));
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
        updateCacheIfMouseMoved();
        String cached = cachedSuffixes.get(lineIndex);
        return cached != null ? cached : "";
    }

    private void updateCacheIfMouseMoved() {
        float mx = 0f;
        float my = 0f;
        if (Canvas3D.mouse != null) {
            mx = Canvas3D.mouse.normalizedPosX;
            my = Canvas3D.mouse.normalizedPosY;
        }
        if (mx == lastMouseX && my == lastMouseY) {
            return;
        }
        Map<String, Double> env = new HashMap<>();
        env.put("mx", (double) mx);
        env.put("my", (double) my);
        env.put("x", (double) mx);
        env.put("y", (double) my);
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
                Double res = evaluateAndAssign(line, env);
                if (res != null && !res.isNaN() && !res.isInfinite()) {
                    out = "// = " + formatFixed(res, 4);
                }
            }
            cachedSuffixes.set(i, out);
        }
        lastMouseX = mx;
        lastMouseY = my;
    }

    private Double evaluateAndAssign(String line, Map<String, Double> env) {
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
        // Handle assignment with optional type prefixes
        int eq = s.indexOf('=');
        if (eq > 0 && s.indexOf('=', eq + 1) == -1) {
            String left = s.substring(0, eq).trim();
            String right = s.substring(eq + 1).trim();
            String var = extractVarName(left);
            if (var != null && !var.isEmpty()) {
                try {
                    double val = new ExpressionParser(right, env).parse();
                    env.put(var, val);
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

    private String extractVarName(String left) {
        // Remove qualifiers/types
        String cleaned = left.replaceAll("^(const\\s+)?(uniform\\s+|varying\\s+)?(float|double|int)\\s+", "").trim();
        // Take last identifier-like token
        String[] parts = cleaned.split("[^A-Za-z0-9_]+");
        if (parts.length == 0) {
            return null;
        }
        return parts[parts.length - 1];
    }

    private String formatFixed(double val, int digits) {
        double pow = Math.pow(10, digits);
        double rounded = Math.round(val * pow) / pow;
        String s = Double.toString(rounded);
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
        private final Map<String, Double> env;

        ExpressionParser(String s, Map<String, Double> env) {
            this.s = s;
            this.env = env;
            this.pos = 0;
        }

        double parse() {
            double v = parseExpr();
            skipWs();
            return v;
        }

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
            double v = parseFactor();
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

        private double parseFactor() {
            skipWs();
            if (match('+')) {
                return parseFactor();
            }
            if (match('-')) {
                return -parseFactor();
            }
            if (match('(')) {
                double v = parseExpr();
                expect(')');
                return v;
            }
            if (isAlpha(peek())) {
                String ident = parseIdent();
                skipWs();
                if (match('(')) {
                    List<Double> args = new ArrayList<>();
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

        private double parseNumber() {
            skipWs();
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.'))
                pos++;
            if (start == pos) {
                throw new RuntimeException("Expected number at " + pos);
            }
            return Double.parseDouble(s.substring(start, pos));
        }

        private String parseIdent() {
            int start = pos;
            while (pos < s.length() && (isAlphaNum(s.charAt(pos)) || s.charAt(pos) == '_'))
                pos++;
            return s.substring(start, pos);
        }

        private double resolveVar(String name) {
            if ("pi".equalsIgnoreCase(name) || "PI".equals(name))
                return Math.PI;
            if ("TAU".equals(name))
                return Math.PI * 2.0;
            if ("e".equalsIgnoreCase(name))
                return Math.E;
            Double v = env.get(name);
            return v != null ? v : 0.0;
        }

        private double applyFunc(String name, List<Double> a) {
            switch (name) {
            case "sin":
                return Math.sin(a.get(0));
            case "cos":
                return Math.cos(a.get(0));
            case "tan":
                return Math.tan(a.get(0));
            case "sqrt":
                return Math.sqrt(a.get(0));
            case "abs":
                return Math.abs(a.get(0));
            case "floor":
                return Math.floor(a.get(0));
            case "ceil":
                return Math.ceil(a.get(0));
            case "round":
                return (double) Math.round(a.get(0));
            case "min":
                return Math.min(a.get(0), a.get(1));
            case "max":
                return Math.max(a.get(0), a.get(1));
            case "dot":
                return a.get(0) * a.get(1);
            case "distance":
                return Math.abs(a.get(0) - a.get(1));
            case "mix": {
                double x = a.get(0), y = a.get(1), t = a.get(2);
                return x * (1.0 - t) + y * t;
            }
            case "smoothstep": {
                double edge0 = a.get(0), edge1 = a.get(1), x = a.get(2);
                if (edge0 == edge1)
                    return x < edge0 ? 0.0 : 1.0;
                double t = (x - edge0) / (edge1 - edge0);
                if (t < 0.0)
                    t = 0.0;
                if (t > 1.0)
                    t = 1.0;
                return t * t * (3.0 - 2.0 * t);
            }
            case "clamp": {
                double x = a.get(0), lo = a.get(1), hi = a.get(2);
                return Math.max(lo, Math.min(hi, x));
            }
            default:
                return 0.0;
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
        Drawing.font.drawHyperStringRows(codeText, 0, scrollOffsetY, Drawing.FONT_HEIGHT_PIXELS, camera);
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
