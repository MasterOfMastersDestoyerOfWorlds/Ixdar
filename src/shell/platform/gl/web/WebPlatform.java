package shell.platform.gl.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.KeyboardEvent;
import org.teavm.jso.dom.events.MouseEvent;
import org.teavm.jso.dom.events.WheelEvent;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;

import shell.platform.Platform;
import shell.render.Texture;
import shell.render.text.FontAtlasDTO;

import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import shell.platform.input.Keys;

public class WebPlatform implements Platform {

    private final HTMLCanvasElement canvas;

    private KeyCallback keyCallback;
    private CharCallback charCallback;
    private CursorPosCallback cursorPosCallback;
    private MouseButtonCallback mouseButtonCallback;
    private ScrollCallback scrollCallback;

    public WebPlatform() {
        HTMLDocument document = Window.current().getDocument();
        HTMLCanvasElement cnv = (HTMLCanvasElement) document.getElementById("ixdar-canvas");
        if (cnv == null) {
            cnv = (HTMLCanvasElement) document.createElement("canvas");
            cnv.setId("ixdar-canvas");
            HTMLElement body = document.getBody();
            body.appendChild(cnv);
        }
        this.canvas = cnv;

        // Mouse move
        canvas.addEventListener("mousemove", (EventListener<MouseEvent>) e -> {
            if (cursorPosCallback != null) {
                cursorPosCallback.onMousePos(0L, e.getClientX(), e.getClientY());
            }
        });
        // Mouse buttons
        canvas.addEventListener("mousedown", (EventListener<MouseEvent>) e -> {
            WebPlatformHelper.leftDown = true;
            if (mouseButtonCallback != null) {
                mouseButtonCallback.onMouseButton(0, Keys.ACTION_PRESS, 0);
            }
        });
        canvas.addEventListener("mouseup", (EventListener<MouseEvent>) e -> {
            WebPlatformHelper.leftDown = false;
            if (mouseButtonCallback != null) {
                mouseButtonCallback.onMouseButton(0, Keys.ACTION_RELEASE, 0);
            }
        });
        // Wheel
        canvas.addEventListener("wheel", (EventListener<WheelEvent>) e -> {
            if (scrollCallback != null) {
                scrollCallback.onScroll(0, e.getDeltaY());
            }
        });
        // Keys
        Window.current().getDocument().addEventListener("keydown", (EventListener<KeyboardEvent>) e -> {
            if (keyCallback != null) {
                keyCallback.onKey(e.getKeyCode(), 0, Keys.ACTION_PRESS, 0);
            }
        });
        Window.current().getDocument().addEventListener("keyup", (EventListener<KeyboardEvent>) e -> {
            if (keyCallback != null) {
                keyCallback.onKey(e.getKeyCode(), 0, Keys.ACTION_RELEASE, 0);
            }
        });
        Window.current().getDocument().addEventListener("keypress", (EventListener<KeyboardEvent>) e -> {
            if (charCallback != null) {
                String k = e.getKey();
                if (k != null && k.length() == 1) {
                    charCallback.onChar(k.charAt(0));
                }
            }
        });
    }

    @Override
    public void setTitle(String title) {
        setDocTitle(title);
    }

    @JSBody(params = { "t" }, script = "document.title=t;")
    private static native void setDocTitle(String t);

    @Override
    public int getWindowWidth() {
        return canvas.getWidth();
    }

    @Override
    public int getWindowHeight() {
        return canvas.getHeight();
    }

    @Override
    public void requestRepaint() {
        // RAF loop externally drives rendering
    }

    @JSBody(params = {}, script = "return Date.now()/1000.0;")
    private static native double nowSeconds();

    @Override
    public float timeSeconds() {
        return (float) nowSeconds();
    }

    @Override
    public void setKeyCallback(KeyCallback callback) {
        this.keyCallback = callback;
    }

    @Override
    public void setCharCallback(CharCallback callback) {
        this.charCallback = callback;
    }

    @Override
    public void setCursorPosCallback(CursorPosCallback callback) {
        this.cursorPosCallback = callback;
    }

    @Override
    public void setMouseButtonCallback(MouseButtonCallback callback) {
        this.mouseButtonCallback = callback;
    }

    @Override
    public void setScrollCallback(ScrollCallback callback) {
        this.scrollCallback = callback;
    }

    private interface JsRect extends JSObject {
        @JSProperty
        double getLeft();

        @JSProperty
        double getBottom();

        @JSProperty
        double getRight();

        @JSProperty
        double getTop();
    }

    private interface JsGlyphEntry extends JSObject {
        @JSProperty
        int getUnicode();

        @JSProperty
        double getAdvance();

        @JSProperty
        JsRect getPlaneBounds();

        @JSProperty
        JsRect getAtlasBounds();
    }

    private interface JsAtlasInfo extends JSObject {
        @JSProperty
        String getType();

        @JSProperty
        double getDistanceRange();

        @JSProperty
        double getDistanceRangeMiddle();

        @JSProperty
        double getSize();

        @JSProperty
        int getWidth();

        @JSProperty
        int getHeight();

        @JSProperty
        String getYOrigin();
    }

    private interface JsMetrics extends JSObject {
        @JSProperty
        double getEmSize();

        @JSProperty
        double getLineHeight();

        @JSProperty
        double getAscender();

        @JSProperty
        double getDescender();

        @JSProperty
        double getUnderlineY();

        @JSProperty
        double getUnderlineThickness();
    }

    private interface JsRoot extends JSObject {
        @JSProperty
        JsAtlasInfo getAtlas();

        @JSProperty
        JsMetrics getMetrics();

        @JSProperty
        JsGlyphEntry[] getGlyphs();
        // kerning omitted for now
    }

    @JSBody(params = { "json" }, script = "return JSON.parse(json);")
    private static native JsRoot parseJsonRoot(String json);

    @Override
    public FontAtlasDTO parseFontAtlas(String json) {
        JsRoot js = parseJsonRoot(json);
        FontAtlasDTO dto = new FontAtlasDTO();
        // atlas
        FontAtlasDTO.AtlasInfo ai = new FontAtlasDTO.AtlasInfo();
        if (js.getAtlas() != null) {
            ai.type = js.getAtlas().getType();
            ai.distanceRange = js.getAtlas().getDistanceRange();
            ai.distanceRangeMiddle = js.getAtlas().getDistanceRangeMiddle();
            ai.size = js.getAtlas().getSize();
            ai.width = js.getAtlas().getWidth();
            ai.height = js.getAtlas().getHeight();
            ai.yOrigin = js.getAtlas().getYOrigin();
        }
        dto.atlas = ai;
        // metrics
        FontAtlasDTO.Metrics m = new FontAtlasDTO.Metrics();
        if (js.getMetrics() != null) {
            m.emSize = js.getMetrics().getEmSize();
            m.lineHeight = js.getMetrics().getLineHeight();
            m.ascender = js.getMetrics().getAscender();
            m.descender = js.getMetrics().getDescender();
            m.underlineY = js.getMetrics().getUnderlineY();
            m.underlineThickness = js.getMetrics().getUnderlineThickness();
        }
        dto.metrics = m;
        // glyphs
        JsGlyphEntry[] jg = js.getGlyphs();
        if (jg != null) {
            dto.glyphs = new FontAtlasDTO.GlyphEntry[jg.length];
            for (int i = 0; i < jg.length; i++) {
                JsGlyphEntry s = jg[i];
                FontAtlasDTO.GlyphEntry g = new FontAtlasDTO.GlyphEntry();
                g.unicode = s.getUnicode();
                g.advance = s.getAdvance();
                if (s.getPlaneBounds() != null) {
                    FontAtlasDTO.Rect pr = new FontAtlasDTO.Rect();
                    pr.left = s.getPlaneBounds().getLeft();
                    pr.bottom = s.getPlaneBounds().getBottom();
                    pr.right = s.getPlaneBounds().getRight();
                    pr.top = s.getPlaneBounds().getTop();
                    g.planeBounds = pr;
                }
                if (s.getAtlasBounds() != null) {
                    FontAtlasDTO.Rect ar = new FontAtlasDTO.Rect();
                    ar.left = s.getAtlasBounds().getLeft();
                    ar.bottom = s.getAtlasBounds().getBottom();
                    ar.right = s.getAtlasBounds().getRight();
                    ar.top = s.getAtlasBounds().getTop();
                    g.atlasBounds = ar;
                }
                dto.glyphs[i] = g;
            }
        } else {
            dto.glyphs = new FontAtlasDTO.GlyphEntry[0];
        }
        // kerning left null
        return dto;
    }

    @Override
    public Texture loadTexture(String resourceName) {
        // In web build, the image should already be loaded/bound by external runtime
        // Here we just create an empty Texture and expect higher-level code to
        // bind/upload
        // Alternatively, you can implement image loading via JS if desired.
        return new Texture(resourceName, 0, 0);
    }
}

final class WebPlatformHelper {
    static boolean leftDown;
}
