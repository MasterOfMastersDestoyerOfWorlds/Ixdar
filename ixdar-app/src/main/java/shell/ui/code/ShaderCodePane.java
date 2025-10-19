package shell.ui.code;

import java.util.ArrayList;
import java.util.Map;

import org.joml.Vector2f;

import shell.cameras.Bounds;
import shell.cameras.Camera2D;
import shell.platform.Platforms;
import shell.platform.input.MouseTrap;
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.color.ColorLerp;
import shell.render.sdf.ShaderDrawable;
import shell.render.sdf.ShaderDrawable.Quad;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;
import shell.render.text.ColorText;
import shell.render.text.HyperString;
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

    private int hoverLineIndex = -1;
    private int clickedLineIndex = -1;
    private boolean consumedLineClickRecently = false;
    private long lastLineClickMillis = 0L;
    private ShaderBranchInjector shaderBranchInjector;

    // private ExpressionParser expressionParser;

    public static final String DEFAULT_VIEW_RIGHT = "RIGHT_CODE";

    public ShaderCodePane(Bounds parentBounds, Map<String, Bounds> webViews, float scrollSpeed, ShaderProgram shader,
            String title,
            ShaderDrawable provider, Camera2D camera, Canvas3D canvas) {

        this.canvas = canvas;
        parentBounds.setUpdateCallback(
                b -> b.update(0, 0, showCode ? Platforms.get().getFrameBufferWidth() / 2 : Platforms.get().getFrameBufferWidth(),
                        Platforms.get().getFrameBufferHeight()));
        this.parentBounds = parentBounds;
        this.scrollSpeed = scrollSpeed;
        this.codeText = new HyperString();
        ShaderProgram resolved = shader;
        if (resolved == null && provider != null) {
            resolved = provider.getShader();
        }
        this.targetShader = resolved != null ? resolved : ShaderType.Font.getShader();
        this.title = title != null ? title : "Shader";
        this.uniformProvider = provider;
        showCode = true;
        paneBounds = new Bounds(Platforms.get().getFrameBufferWidth() / 2, 0, 0, Platforms.get().getFrameBufferHeight(),
                b -> b.update(
                        Platforms.get().getFrameBufferWidth() / 2,
                        0,
                        showCode ? Platforms.get().getFrameBufferWidth() / 2f : 0f,
                        Platforms.get().getFrameBufferHeight()),
                DEFAULT_VIEW_RIGHT);

        showCodeButton = new HyperString();
        showCodeButton.addDynamicWordClick(() -> {
            final ColorText<Float> HIDE = new ColorText<Float>("Hide Code", Color.CYAN);
            final ColorText<Float> SHOW = new ColorText<Float>("Show Code", Color.CYAN);
            return showCode ? HIDE : SHOW;
        }, Color.CYAN, () -> {
            showCode = !showCode;
            if (showCode) {
                paneBounds.viewWidth = Platforms.get().getFrameBufferWidth() / 2f;
                parentBounds.viewWidth = Platforms.get().getFrameBufferWidth() / 2f;
            } else {
                paneBounds.viewWidth = 0f;
                parentBounds.viewWidth = Platforms.get().getFrameBufferWidth();
            }
            camera.updateView(paneBounds.id);
            camera.updateView(parentBounds.id);
        });
        showCodeButton.newLine();
        showCodeButton.addDynamicWord(() -> mouseText());
        showCodeButton.addDynamicWord(() -> {
            return new ColorText<Float>("FPS: " + Clock.fps(), Color.CYAN);
        });
        webViews.put(paneBounds.id, paneBounds);
        loadCode(this.targetShader, this.title);
        camera.updateView(paneBounds.id);
        MouseTrap.subscribeScrollRegion(this.paneBounds, this);

        // Subscribe outside-click to restore shader when clicking outside code lines
        MouseTrap.subscribeClickRegion(parentBounds, (x, y) -> {
            long now = System.currentTimeMillis();
            if (consumedLineClickRecently && now - lastLineClickMillis < 100) {
                // Ignore the parent click triggered in same cycle as a line click
                consumedLineClickRecently = false;
                return;
            }
            if (clickedLineIndex >= 0) {
                restoreOriginal();
            }
        });
    }

    private void loadCode(ShaderProgram shader, String headerTitle) {
        try {
            displayedLines.clear();
            cachedSuffixes.clear();
            String fs = shader != null ? shader.getFragmentSource() : "";
            shaderBranchInjector = new ShaderBranchInjector(uniformProvider, fs, shader);
            int gIndex = 0;
            codeText.addDynamicWord(() -> updateCacheIfMouseMoved(), Color.BLUE_WHITE);
            for (String ln : fs.split("\n")) {
                final int idx = gIndex;
                final boolean isAssignment = ExpressionParser.isAssignmentLine(ln);
                codeText.addDynamicWord(() -> {
                    ColorText<?> dyn = new ColorText<>("");
                    dyn.resetText();
                    boolean isClicked = (idx == clickedLineIndex);
                    boolean isHoverPulse = (isAssignment && (idx == hoverLineIndex) && (idx != clickedLineIndex));
                    for (var t : GLSLColorizer.colorize(ln)) {
                        int k = 0;
                        for (String w : t.text) {
                            if (isClicked) {
                                dyn.addWord(w, Color.YELLOW);
                            } else if (isHoverPulse) {
                                dyn.addWord(w, ColorLerp.flashColor(Color.YELLOW, 8f));
                            } else {
                                dyn.addWord(w, t.color.get(Math.min(k, t.color.size() - 1)));
                            }
                            k++;
                        }
                    }
                    return dyn;
                }, Color.WHITE,
                        // hover: only set highlight for assignments
                        () -> {
                            if (isAssignment)
                                hoverLineIndex = idx;
                        },
                        () -> {
                            if (hoverLineIndex == idx)
                                hoverLineIndex = -1;
                        },
                        // click: only inject for assignments
                        () -> {
                            if (isAssignment)
                                onLineClicked(idx);
                        });
                // click targets for this line: gap and suffix, only if assignment
                if (isAssignment) {
                    codeText.addWord("  ", Color.WHITE, () -> {
                        hoverLineIndex = idx;
                    }, () -> {
                        if (hoverLineIndex == idx)
                            hoverLineIndex = -1;
                    }, () -> onLineClicked(idx));
                    codeText.addDynamicWord(() -> dynamicSuffix(idx), Color.BLUE_WHITE, () -> {
                        hoverLineIndex = idx;
                    }, () -> {
                        if (hoverLineIndex == idx)
                            hoverLineIndex = -1;
                    },
                            () -> onLineClicked(idx));
                } else {
                    codeText.addWord("  ", Color.WHITE);
                    codeText.addDynamicWord(() -> dynamicSuffix(idx), Color.BLUE_WHITE);
                }
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
        ParseText.put(env, "pos", mx, my, 0f);
        Quad q = uniformProvider.getQuad();
        if (q != null) {
            Vector2f m = new Vector2f(mx, my);

            Vector2f a = new Vector2f(q.bottomLeft);
            Vector2f b = new Vector2f(q.bottomRight).sub(new Vector2f(q.bottomLeft),
                    new Vector2f());
            Vector2f c = new Vector2f(q.topLeft).sub(new Vector2f(q.bottomLeft),
                    new Vector2f());
            Vector2f am = m.sub(a, new Vector2f());

            float u = (am.dot(b) / b.lengthSquared());
            float v = (am.dot(c) / c.lengthSquared());

            ParseText.put(env, "textureCoord", Math.clamp(u, 0, 1), Math.clamp(v, 0, 1));
            ParseText.put(env, "scaledTextureCoord", Math.clamp(u * q.widthToHeightRatio, 0, q.texWidth),
                    Math.clamp(v, 0, q.texHeight));
        }
        // Ensure cache size matches displayed lines
        if (cachedSuffixes.size() != displayedLines.size()) {
            cachedSuffixes.clear();
            for (int i = 0; i < displayedLines.size(); i++)
                cachedSuffixes.add(ParseText.BLANK);
        }
        for (int i = 0; i < displayedLines.size(); i++) {
            // placeholder sync to maintain size; actual suffixes will be set below
            cachedSuffixes.set(i, ParseText.BLANK);
        }
        // Delegate line-by-line evaluation with control flow to the parser
        ExpressionParser.evaluateAndAssign(displayedLines, env, cachedSuffixes);
        lastMouseX = mx;
        lastMouseY = my;
        return ParseText.BLANK;
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

    private void onLineClicked(int idx) {
        consumedLineClickRecently = true;
        lastLineClickMillis = System.currentTimeMillis();
        clickedLineIndex = idx;
        shaderBranchInjector.injectAndReload(idx);
    }

    private void restoreOriginal() {
        if (shaderBranchInjector != null && shaderBranchInjector.originalFragmentSource != null) {
            targetShader.reloadWithFragmentSource(shaderBranchInjector.originalFragmentSource);
        }
        hoverLineIndex = -1;
        clickedLineIndex = -1;
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
