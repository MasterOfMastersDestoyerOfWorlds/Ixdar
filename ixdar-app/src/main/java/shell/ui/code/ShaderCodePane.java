package shell.ui.code;

import java.util.ArrayList;
import java.util.Map;

import org.joml.Vector2f;

import shell.cameras.Bounds;
import shell.cameras.Camera2D;
import shell.platform.input.MouseTrap;
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.color.ColorRGB;
import shell.render.sdf.ShaderDrawable;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;
import shell.render.text.ColorText;
import shell.render.text.HyperString;
import shell.render.text.SpecialGlyphs;
import shell.ui.Canvas3D;
import shell.ui.Drawing;

/**
 * Renders shader source code into a scrollable pane area using HyperString.
 * Owns its HyperString buffer and a scroll subscription bound.
 */
public class ShaderCodePane implements MouseTrap.ScrollHandler {

    private final HyperString codeText;
    private float scrollOffsetY;
    private final float scrollSpeed;
    private final ArrayList<String> displayedLines = new ArrayList<>();
    private final ArrayList<ParseText> cachedSuffixes = new ArrayList<>();
    private float lastMouseX = Float.NaN;
    private float lastMouseY = Float.NaN;
    private final ShaderProgram targetShader;
    private final String title;
    public Bounds paneBounds;
    private Bounds parentBounds;
    private boolean showCode;
    private HyperString showCodeButton;

    private final ShaderDrawable uniformProvider;
    private Canvas3D canvas;

    // private ExpressionParser expressionParser;

    public static final String DEFAULT_VIEW_RIGHT = "RIGHT_CODE";

    public ShaderCodePane(Bounds parentBounds, Map<String, Bounds> webViews, float scrollSpeed, ShaderProgram shader,
            String title,
            ShaderDrawable provider, Camera2D camera, Canvas3D canvas) {

        this.canvas = canvas;
        parentBounds.setUpdateCallback(
                b -> b.update(0, 0, showCode ? Canvas3D.frameBufferWidth / 2 : Canvas3D.frameBufferWidth,
                        Canvas3D.frameBufferHeight));
        this.parentBounds = parentBounds;
        this.scrollSpeed = scrollSpeed;
        this.codeText = new HyperString();
        this.targetShader = shader != null ? shader : ShaderType.Font.getShader();
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
            final ColorText<Float> HIDE = new ColorText<Float>("Hide Code", Color.CYAN);
            final ColorText<Float> SHOW = new ColorText<Float>("Show Code", Color.CYAN);
            return showCode ? HIDE : SHOW;
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
        showCodeButton.newLine();
        showCodeButton.addWord("Mouse: ", Color.CYAN);
        showCodeButton.addDynamicWord(() -> mouseText());
        showCodeButton.addDynamicWord(() -> {return new ColorText<Float>("FPS: " + Clock.fps(), Color.CYAN);});
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
            codeText.addDynamicWord(() -> updateCacheIfMouseMoved(), Color.BLUE_WHITE);
            for (String ln : fs.split("\n")) {
                final int idx = gIndex;
                ColorText<?> ct = new ColorText<>("");
                ct.resetText();
                for (var t : GLSLColorizer.colorize(ln)) {
                    int k = 0;
                    for (String w : t.text) {
                        ct.addWord(w, t.color.get(Math.min(k, t.color.size() - 1)));
                        k++;
                    }
                }
                codeText.addDynamicWord(() -> ct, Color.WHITE);
                codeText.addWord("  ", Color.WHITE);
                codeText.addDynamicWord(() -> dynamicSuffix(idx), Color.BLUE_WHITE);
                codeText.newLine();
                displayedLines.add(ln);
                gIndex++;
            }
            codeText.wrap();
            // Initialize cache to correct size
            for (int i = 0; i < displayedLines.size(); i++) {
                cachedSuffixes.add(ParseText.BLANK);
            }
            // Force recompute on first draw
            lastMouseX = Float.NaN;
            lastMouseY = Float.NaN;
        } catch (Exception e) {
            codeText.addLine("Failed to load shader from program", Color.RED);
        }
    }

    private ParseText dynamicSuffix(int lineIndex) {
        if (lineIndex < 0 || lineIndex >= displayedLines.size()) {
            return ParseText.BLANK;
        }
        ParseText cached = cachedSuffixes.get(lineIndex);
        return cached != null ? cached : ParseText.BLANK;
    }

    private ParseText updateCacheIfMouseMoved() {
        float mx = 0f;
        float my = 0f;
        if (canvas.mouse != null) {
            mx = canvas.mouse.normalizedPosX;
            my = canvas.mouse.normalizedPosY;
        }
        if (mx == lastMouseX && my == lastMouseY) {
            return ParseText.BLANK;
        }
        Map<String, ParseText> env = uniformProvider.getUniformMap();
        // Inject mouse position as a vector `pos` with component aliases `pos_x`,
        // `pos_y`
        ParseText.put(env, "pos", mx, my, 0f);
        if (uniformProvider.bottomLeft != null) {
            Vector2f m = new Vector2f(mx, my);

            Vector2f a = new Vector2f(uniformProvider.bottomLeft);
            Vector2f b = new Vector2f(uniformProvider.bottomRight).sub(new Vector2f(uniformProvider.bottomLeft),
                    new Vector2f());
            Vector2f c = new Vector2f(uniformProvider.topLeft).sub(new Vector2f(uniformProvider.bottomLeft),
                    new Vector2f());
            Vector2f am = m.sub(a, new Vector2f());

            float u = (am.dot(b) / b.lengthSquared());
            float v = (am.dot(c) / c.lengthSquared());

            ParseText.put(env, "textureCoord", Math.clamp(u, 0, 1), Math.clamp(v, 0, 1));
        }
        // Ensure cache size matches displayed lines
        if (cachedSuffixes.size() != displayedLines.size()) {
            cachedSuffixes.clear();
            for (int i = 0; i < displayedLines.size(); i++)
                cachedSuffixes.add(ParseText.BLANK);
        }
        for (int i = 0; i < displayedLines.size(); i++) {
            String line = displayedLines.get(i);
            ParseText out = ParseText.BLANK;
            if (line != null) {
                // If this line declares a uniform, show its current value
                String decl = line.trim();
                if (decl.startsWith("uniform") || decl.startsWith("in")) {
                    String name = ExpressionParser.extractUniformName(decl);
                    if (name != null) {
                        ParseText v = env.get(name);
                        if (v != null) {
                            out = commentStart(v).join(new ParseText(" = ")).join(v);
                        }
                    }
                } else {
                    ParseText res = ExpressionParser.evaluateAndAssign(line, env);
                    if (res != null) {

                        out = commentStart(res).join(new ParseText(" = ")).join(res);
                    }
                }
            }
            cachedSuffixes.set(i, out);
        }
        lastMouseX = mx;
        lastMouseY = my;
        return ParseText.BLANK;
    }

    private ParseText commentStart(ParseText res) {
        if (res.vectorLength == 4) {
            return new ParseText(SpecialGlyphs.COLOR_TRACKER.getChar() + "",
                    new ColorRGB(res.data.x, res.data.y, res.data.z, res.data.w));
        } else {
            return new ParseText("//");
        }

    }

    private ParseText mouseText() {
        float mx = 0f, my = 0f;
        if (canvas.mouse != null) {
            mx = canvas.mouse.normalizedPosX;
            my = canvas.mouse.normalizedPosY;
        }
        return new ParseText("mx=" + ParseText.formatFixed(mx) + " my=" + ParseText.formatFixed(my));
    }

    public void draw(Camera2D camera) {
        Drawing d = Drawing.getDrawing();
        if (paneBounds.viewWidth > 0 && showCode) {
            camera.updateView(paneBounds.id);
            d.font.drawHyperStringRows(codeText, 0, scrollOffsetY, Drawing.FONT_HEIGHT_PIXELS, camera);
        }
        camera.updateView(parentBounds.id);
        d.font.drawHyperStringRows(showCodeButton, 0, 0, Drawing.FONT_HEIGHT_PIXELS, camera);
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
