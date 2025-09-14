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
    public Bounds paneBounds;
    private Bounds parentBounds;
    private boolean showCode;
    private HyperString showCodeButton;

    private final ShaderDrawable uniformProvider;

    private ExpressionParser expressionParser;

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
                    String name = ExpressionParser.extractUniformName(decl);
                    if (name != null) {
                        Entry<String, Float> val = env.get(name);
                        if (val != null) {
                            out = "// = " + val.getKey();
                        }
                    }
                } else {
                    Float res = ExpressionParser.evaluateAndAssign(line, env);
                    if (res != null && !res.isNaN() && !res.isInfinite()) {
                        out = "// = " + ExpressionParser.formatFixed(res, 4);
                    }
                }
            }
            cachedSuffixes.set(i, out);
        }
        lastMouseX = mx;
        lastMouseY = my;
        return "";
    }

    private String mouseText() {
        float mx = 0f, my = 0f;
        if (Canvas3D.mouse != null) {
            mx = Canvas3D.mouse.normalizedPosX;
            my = Canvas3D.mouse.normalizedPosY;
        }
        return "mx=" + ExpressionParser.formatFixed(mx, 1) + " my=" + ExpressionParser.formatFixed(my, 1);
    }

    public void draw(Camera2D camera) {
        if (paneBounds.viewWidth > 0 && showCode) {
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
