package ixdar.gui.ui.code;

import java.util.ArrayList;
import java.util.Map;

import org.joml.Vector2f;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.Clock;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorLerp;
import ixdar.graphics.render.sdf.ShaderDrawable;
import ixdar.graphics.render.sdf.ShaderDrawable.Quad;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.shaders.ShaderProgram.ShaderType;
import ixdar.graphics.render.text.ColorText;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.Drawing;
import ixdar.parsing.glsl.GLSLExpressionParser;
import ixdar.parsing.glsl.GLSLParseText;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.platform.input.MouseTrap;

/**
 * Renders shader source code into a scrollable pane area using HyperString.
 * Owns its HyperString buffer and a scroll subscription bound.
 */
public class ShaderCodePane implements MouseTrap.ScrollHandler {
    public static final float NUM_2 = 2f;
    public static final float NUM_0 = 0f;
    public static final float NUM_8 = 8f;
    public static final float NUM_20 = 20f;
    public static final float NUM_1 = 1f;

    // private ExpressionParser expressionParser;

    public static final String DEFAULT_VIEW_RIGHT = "RIGHT_CODE";
    public Bounds paneBounds;

    private final HyperString codeText;
    private float scrollOffsetY;
    private final float scrollSpeed;
    private final ArrayList<String> displayedLines = new ArrayList<>();
    private final ArrayList<GLSLParseText> cachedSuffixes = new ArrayList<>();
    private float lastMouseX = Float.NaN;
    private float lastMouseY = Float.NaN;
    private final ShaderProgram targetShader;
    private final String title;
    private Bounds parentBounds;
    private boolean showCode;
    private HyperString showCodeButton;

    private final ShaderDrawable uniformProvider;
    private Canvas3D canvas;

    private int hoverLineIndex = -1;
    private int clickedLineIndex = -1;
    private ShaderBranchInjector shaderBranchInjector;

    private boolean crosshairLocked = false;
    private float lockedX = 0f;
    private float lockedY = 0f;
    private Vector2f crosshairScreenPos = null;
    private boolean loaded = false;

    /**
     * Wire this pane up, resolving the target shader from {@code shader}, then {@code provider},
     * then the font shader, and registering its bounds, resize callbacks, and {@link MouseTrap}
     * scroll and click subscriptions.
     *
     * @param parentBounds host region that the pane lays itself out within
     * @param webViews registry of named bounds keyed by view id; this pane registers itself
     * @param scrollSpeed pixels-per-second factor applied during {@link #onScroll}
     * @param shader explicit shader to introspect, or {@code null} to fall back to {@code provider}
     * @param title human-readable label (defaults to {@code "Shader"} when {@code null})
     * @param provider runtime uniform/quad supplier used by mouse-driven evaluation
     * @param camera 2D camera whose views are refreshed when the pane resizes
     * @param canvas 3D canvas whose mouse state drives the live preview
     */
    public ShaderCodePane(Bounds parentBounds, Map<String, Bounds> webViews, float scrollSpeed, ShaderProgram shader,
            String title,
            ShaderDrawable provider, Camera2D camera, Canvas3D canvas) {

        this.canvas = canvas;
        parentBounds.setUpdateCallback(
                b -> b.update(0, 0,
                        showCode ? Platforms.get().getFrameBufferWidth() / 2 : Platforms.get().getFrameBufferWidth(),
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
                        showCode ? Platforms.get().getFrameBufferWidth() / NUM_2 : NUM_0,
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
                paneBounds.viewWidth = Platforms.get().getFrameBufferWidth() / NUM_2;
                parentBounds.viewWidth = Platforms.get().getFrameBufferWidth() / NUM_2;
            } else {
                paneBounds.viewWidth = NUM_0;
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
        MouseTrap.subscribeScrollRegion(this.paneBounds, this);
        MouseTrap.subscribeClickRegion(parentBounds, (button) -> handleParentClick(button));
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
                final boolean isAssignment = GLSLExpressionParser.isAssignmentLine(ln);
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
                                dyn.addWord(w, ColorLerp.flashColor(Color.YELLOW, NUM_8));
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
                cachedSuffixes.add(GLSLParseText.BLANK);
            }
            // Force recompute on first draw
            lastMouseX = Float.NaN;
            lastMouseY = Float.NaN;
            loaded = true;
        } catch (Exception e) {
            codeText.addLine("Failed to load shader from program", Color.RED);
        }
    }

    private GLSLParseText dynamicSuffix(int lineIndex) {
        if (lineIndex < 0 || lineIndex >= displayedLines.size()) {
            return GLSLParseText.BLANK;
        }
        GLSLParseText cached = cachedSuffixes.get(lineIndex);
        return cached != null ? cached : GLSLParseText.BLANK;
    }

    private GLSLParseText updateCacheIfMouseMoved() {
        float mx = NUM_0;
        float my = NUM_0;
        if (crosshairLocked) {
            mx = lockedX;
            my = lockedY;
        } else if (canvas.mouse != null) {
            mx = canvas.mouse.normalizedPosX;
            my = canvas.mouse.normalizedPosY;
        }
        if (!crosshairLocked && mx == lastMouseX && my == lastMouseY) {
            return GLSLParseText.BLANK;
        }
        Map<String, GLSLParseText> env = uniformProvider.getUniformMap();
        GLSLParseText.put(env, "pos", mx, my, NUM_0);
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

            GLSLParseText.put(env, "textureCoord", Math.clamp(u, 0, 1), Math.clamp(v, 0, 1));
            GLSLParseText.put(env, "scaledTextureCoord", Math.clamp(u * q.widthToHeightRatio, 0, q.texWidth),
                    Math.clamp(v, 0, q.texHeight));
        }
        // Ensure cache size matches displayed lines
        if (cachedSuffixes.size() != displayedLines.size()) {
            cachedSuffixes.clear();
            for (int i = 0; i < displayedLines.size(); i++)
                cachedSuffixes.add(GLSLParseText.BLANK);
        }
        for (int i = 0; i < displayedLines.size(); i++) {
            // placeholder sync to maintain size; actual suffixes will be set below
            cachedSuffixes.set(i, GLSLParseText.BLANK);
        }
        // Delegate line-by-line evaluation with control flow to the parser
        GLSLExpressionParser.evaluateAndAssign(displayedLines, env, cachedSuffixes);
        lastMouseX = mx;
        lastMouseY = my;
        return GLSLParseText.BLANK;
    }

    private GLSLParseText mouseText() {
        float mx = NUM_0, my = NUM_0;
        if (canvas.mouse != null) {
            mx = canvas.mouse.normalizedPosX;
            my = canvas.mouse.normalizedPosY;
        }
        return new GLSLParseText("mx=" + GLSLParseText.formatFixed(mx) + " my=" + GLSLParseText.formatFixed(my));
    }

    /**
     * Render the pane: lazy-load the shader source on first call, draw the colourised
     * code lines under the pane view, draw the show/hide button plus readouts under the
     * parent view, and overlay a cyan crosshair when the live-preview lock is active.
     *
     * @param camera 2D camera supplying the view transforms for both bounds
     */
    public void draw(Camera2D camera) {
        if (!loaded) {
            loadCode(this.targetShader, this.title);
            loaded = true;
        }
        camera.updateView(paneBounds.id);
        Drawing d = Drawing.getDrawing();
        d.font.drawHyperStringRows(codeText, 0, scrollOffsetY, Drawing.FONT_HEIGHT_PIXELS, camera);
        camera.updateView(parentBounds.id);
        d.font.drawHyperStringRows(showCodeButton, 0, 0, Drawing.FONT_HEIGHT_PIXELS, camera);

        if (crosshairLocked && crosshairScreenPos != null) {
            float crosshairSize = NUM_20;
            float cx = crosshairScreenPos.x;
            float cy = crosshairScreenPos.y;

            d.sdfLine.setStroke(NUM_2, false, NUM_1, NUM_0, false, false, false, camera);
            d.sdfLine.draw(new Vector2f(cx - crosshairSize, cy), new Vector2f(cx + crosshairSize, cy), Color.CYAN,
                    camera);
            d.sdfLine.draw(new Vector2f(cx, cy - crosshairSize), new Vector2f(cx, cy + crosshairSize), Color.CYAN,
                    camera);
        }
    }

    private void onLineClicked(int idx) {
        clickedLineIndex = idx;
        shaderBranchInjector.injectAndReload(idx);
    }

    private void handleParentClick(int button) {
        if (button == Keys.MOUSE_BUTTON_LEFT) {
            restoreOriginal();
            crosshairLocked = false;
            crosshairScreenPos = null;
        } else if (button == Keys.MOUSE_BUTTON_RIGHT) {
            crosshairLocked = !crosshairLocked;
            if (crosshairLocked) {
                if (canvas.mouse != null) {
                    lockedX = canvas.mouse.normalizedPosX;
                    lockedY = canvas.mouse.normalizedPosY;
                    crosshairScreenPos = new Vector2f(lockedX, lockedY);
                }
            } else {
                crosshairScreenPos = null;
            }
        }
    }

    private void restoreOriginal() {
        if (shaderBranchInjector != null && shaderBranchInjector.originalFragmentSource != null) {
            targetShader.reloadWithFragmentSource(shaderBranchInjector.originalFragmentSource);
        }
        hoverLineIndex = -1;
        clickedLineIndex = -1;
    }

    /**
     * Adjust {@link #scrollOffsetY} in response to a scroll-wheel tick: scroll up clamps
     * at zero, scroll down only advances while the cached last-word is still off-screen.
     *
     * @param scrollUp {@code true} when scrolling upward (towards earlier code)
     * @param deltaSeconds frame delta used to scale {@link #scrollSpeed}
     */
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

    /**
     * Bounds of the pane's drawing region (the right-hand half of the framebuffer when
     * code is shown, zero width when hidden).
     *
     * @return the pane's {@link Bounds} instance
     */
    public Bounds getBounds() {
        return paneBounds;
    }
}
