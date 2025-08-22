package shell.platform.gl.web;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.function.Consumer;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.KeyboardEvent;
import org.teavm.jso.dom.events.MouseEvent;
import org.teavm.jso.dom.events.WheelEvent;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;
import org.teavm.jso.typedarrays.Uint8ClampedArray;

import shell.file.TextFile;
import shell.platform.gl.Platform;
import shell.platform.input.Keys;
import shell.render.Texture;
import shell.render.text.FontAtlasDTO;
import shell.ui.WebLauncher;

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
        String getYorigin();
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
            ai.yorigin = js.getAtlas().getYorigin();
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
    public void loadTexture(String resourceName, Consumer<Texture> callback) {
        // In web build, the image should already be loaded/bound by external runtime
        // Here we just create an empty Texture and expect higher-level code to
        // bind/upload
        // Alternatively, you can implement image loading via JS if desired.
        loadImagePixels("res/" + resourceName, (w, h, data) -> {
            ByteBuffer bb = ByteBuffer.allocate(data.getLength());
            for (int i = 0; i < data.getLength(); i++) {
                bb.put((byte) data.get(i));
            }
            bb.flip();
            callback.accept(new Texture(resourceName, bb, w, h));
        });
    }

    @Override
    public float startTime() {
        return WebLauncher.startTime;
    }

    @Override
    public void exit(int code) {
        // no-op on web
    }

    @Override
    public String loadShaderSource(String filename) {
        String rel = "glsl/" + filename;
        String text = fetchTextSync(rel);
        return text;
    }

    @Override
    public String loadSource(String resourceFolder, String filename) throws UnsupportedEncodingException, IOException {
        String rel = resourceFolder + "/" + filename;
        return fetchTextSync(rel);
    }

    @Override
    public TextFile loadFile(String path) throws IOException {
        String norm = normalizePath(path);
        String text = fetchTextSync(norm);
        if (text == null) {
            text = fetchTextSync("/" + norm);
        }
        if (text != null) {
            ArrayList<String> fileContents = new ArrayList<>();
            for (String s : text.split("\n")) {
                fileContents.add(s);
            }
            return new TextFile(path, fileContents);
        }
        throw new IOException(path + " not found");
    }

    @JSBody(params = {
            "url" }, script = "try{var xhr=new XMLHttpRequest();xhr.open('GET', url, false);xhr.overrideMimeType('text/plain; charset=utf-8');xhr.send(null);if(xhr.status===0||(xhr.status>=200&&xhr.status<300)){return xhr.responseText||'';}return null;}catch(e){return null;}")
    private static native String fetchTextSync(String url);

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String p = path;
        if (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.startsWith("src/main/resources/")) {
            p = p.substring("src/main/resources/".length());
        }
        if (p.startsWith("./src/main/resources/")) {
            p = p.substring("./src/main/resources/".length());
        }
        return p;
    }

    @JSFunctor
    public interface ImagePixelsCallback extends JSObject {
        void onPixels(int width, int height, Uint8ClampedArray data);
    }

    @org.teavm.jso.JSBody(params = { "url", "callback" }, script = "fetch(url)" +
            "  .then(function(r) { return r.blob(); })" +
            "  .then(function(blob) { return createImageBitmap(blob); })" +
            "  .then(function(bitmap) {" +
            "    var canvas = document.createElement('canvas');" +
            "    canvas.width = bitmap.width;" +
            "    canvas.height = bitmap.height;" +
            "    var ctx = canvas.getContext('2d');" +
            "    ctx.drawImage(bitmap, 0, 0);" +
            "    var imageData = ctx.getImageData(0, 0, bitmap.width, bitmap.height);" +
            "    callback(bitmap.width, bitmap.height, imageData.data);" +
            "  });")
    public static native void loadImagePixels(String url, ImagePixelsCallback callback);

    @Override
    public void writeTextFile(TextFile file, boolean append) throws java.io.IOException {
        // No-op for web (cannot write). Intentionally ignored.
    }

}

final class WebPlatformHelper {
    static boolean leftDown;
}
