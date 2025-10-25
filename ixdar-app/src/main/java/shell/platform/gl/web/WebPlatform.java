package shell.platform.gl.web;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.teavm.jso.typedarrays.Uint8ClampedArray;

import shell.file.TextFile;
import shell.platform.Platforms;
import shell.platform.gl.IxBuffer;
import shell.platform.gl.Platform;
import shell.platform.input.Keys;
import shell.render.Texture;
import shell.render.text.FontAtlasDTO;
import shell.ui.WebLauncher;

public class WebPlatform implements Platform {

    private HTMLCanvasElement canvas;
    private String currentCanvasId;

    private KeyCallback keyCallback;
    private CharCallback charCallback;
    // Global (document-level) key callbacks shared across canvases
    private static KeyCallback sKeyCallback;
    private static CharCallback sCharCallback;

    private static final Map<String, String> shaderCache = new HashMap<>();
    private static final Map<String, List<Consumer<String>>> pendingCallbacks = new HashMap<>();

    private CursorPosCallback cursorPosCallback;
    private MouseButtonCallback mouseButtonCallback;
    private ScrollCallback scrollCallback;
    private float frameBufferSizeX;
    private float frameBufferSizeY;
    private Integer platformId;
    private int shadersToLoad;

    public WebPlatform(HTMLCanvasElement canvas, String id) {
        this.currentCanvasId = id;
        this.canvas = canvas;
        setupEventListeners(canvas);
    }

    /**
     * Get the current canvas ID
     */
    public String getCurrentCanvasId() {
        return currentCanvasId;
    }

    private void setupEventListeners(HTMLCanvasElement htmlCanvas) {
        // For now, use the fallback callback system to avoid Canvas3D static conflicts
        // The specific canvas3D instance will be handled during rendering

        // Mouse move
        htmlCanvas.addEventListener("mousemove", (EventListener<MouseEvent>) e -> {
            if (cursorPosCallback != null) {
                // Use offsetX and offsetY for canvas-relative coordinates
                double canvasX = e.getOffsetX();
                double canvasY = e.getOffsetY();
                cursorPosCallback.onMousePos(0L, canvasX, canvasY);
            }
        });

        // Prevent context menu on right-click
        htmlCanvas.addEventListener("contextmenu", (EventListener<MouseEvent>) e -> {
            e.preventDefault();
        });

        // Mouse buttons
        htmlCanvas.addEventListener("mousedown", (EventListener<MouseEvent>) e -> {
            int button = mapBrowserButtonToAppButton(e.getButton());
            if (button == 0) {
                WebPlatformHelper.leftDown = true;
            }
            if (mouseButtonCallback != null) {
                mouseButtonCallback.onMouseButton(button, Keys.ACTION_PRESS, 0);
            }
            e.preventDefault();
        });

        htmlCanvas.addEventListener("mouseup", (EventListener<MouseEvent>) e -> {
            int button = mapBrowserButtonToAppButton(e.getButton());
            if (button == 0) {
                WebPlatformHelper.leftDown = false;
            }
            if (mouseButtonCallback != null) {
                mouseButtonCallback.onMouseButton(button, Keys.ACTION_RELEASE, 0);
            }
            e.preventDefault();
        });

        // Wheel
        htmlCanvas.addEventListener("wheel", (EventListener<WheelEvent>) e -> {
            if (scrollCallback != null) {
                scrollCallback.onScroll(0, e.getDeltaY());
            }
            e.preventDefault();
        }, false);

        // Keys - attach to document for global key handling (shared across all
        // canvases)
        // Only set up once to avoid duplicate listeners
        if (!WebPlatformHelper.keysInstalled) {
            WebPlatformHelper.keysInstalled = true;
            Window.current().getDocument().addEventListener("keydown", (EventListener<KeyboardEvent>) e -> {
                if (sKeyCallback != null) {
                    sKeyCallback.onKey(e.getKeyCode(), 0, Keys.ACTION_PRESS, 0);
                }
            });
            Window.current().getDocument().addEventListener("keyup", (EventListener<KeyboardEvent>) e -> {
                if (sKeyCallback != null) {
                    sKeyCallback.onKey(e.getKeyCode(), 0, Keys.ACTION_RELEASE, 0);
                }
            });
            Window.current().getDocument().addEventListener("keypress", (EventListener<KeyboardEvent>) e -> {
                if (sCharCallback != null) {
                    String k = e.getKey();
                    if (k != null && k.length() == 1) {
                        sCharCallback.onChar(k.charAt(0));
                    }
                }
            });
        }
    }

    @Override
    public void setTitle(String title) {
        setDocTitle(title);
    }

    @JSBody(params = { "t" }, script = "document.title=t;")
    private static native void setDocTitle(String t);

    @Override
    public int getWindowWidth() {
        return canvas.getClientWidth();
    }

    @Override
    public int getWindowHeight() {
        return canvas.getClientHeight();
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
        sKeyCallback = callback;
    }

    @Override
    public void setCharCallback(CharCallback callback) {
        this.charCallback = callback;
        sCharCallback = callback;
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
    public void loadTexture(String resourceName, int platformId, Consumer<Texture> callback) {
        loadImagePixels("/ixdar/res/" + resourceName, (w, h, data) -> {
            ByteBuffer bb = ByteBuffer.allocate(data.getLength());
            for (int i = 0; i < data.getLength(); i++) {
                bb.put((byte) data.get(i));
            }
            bb.flip();
            Platforms.init(platformId);
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
    public void loadShaderSourceAsync(String resourceFolder, String filename, int platformId,
            Consumer<String> callback) {
        shadersToLoad += 1;
        Consumer<String> callback2 = (text) -> {
            shadersToLoad -= 1;
            callback.accept(text);
        };
        loadSourceAsync(resourceFolder, filename, platformId, callback2);
    }

    @Override
    public void loadSourceAsync(String resourceFolder, String filename, int platformId, Consumer<String> callback) {
        String url = "/ixdar/" + resourceFolder + "/" + filename;
        fetchTextAsync(url, new TextCallback() {
            @Override
            public void onText(String text) {
                Platforms.init(platformId);
                callback.accept(text);
            }
        });
    }

    @Override
    public TextFile loadFile(String path) throws IOException {
        String norm = normalizePath(path);
        String text = fetchTextSync("/ixdar/" + norm);
        if (text == null) {
            text = fetchTextSync("/ixdar/" + norm);
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

    @JSFunctor
    interface TextCallback extends JSObject {
        void onText(String text);
    }

    @JSBody(params = { "url", "callback" }, script = "fetch(url)" +
            "  .then(function(response) { return response.text(); })" +
            "  .then(function(text) { callback(text); })" +
            "  .catch(function(error) { console.error('Failed to load shader:', error); callback(''); });")
    private static native void fetchTextAsync(String url, TextCallback callback);

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

    @Override
    public void log(String msg) {
        WebPlatform.jsLog(msg);
    }

    @JSBody(params = { "msg" }, script = "console.log(msg);")
    private static native void jsLog(String msg);

    @Override
    public boolean canHotReload() {
        return false;
    }

    @Override
    public IxBuffer allocateFloats(int i) {
        return new WebBuffer(i);
    }

    @Override
    public void setFrameBufferSize(float f, float g) {
        frameBufferSizeX = f;
        frameBufferSizeY = g;
    }

    @Override
    public int getFrameBufferWidth() {
        return (int) frameBufferSizeX;
    }

    @Override
    public int getFrameBufferHeight() {
        return (int) frameBufferSizeY;
    }

    @Override
    public int getPlatformID() {
        return platformId;
    }

    @Override
    public void setPlatformID(Integer p) {
        this.platformId = p;
    }

    public boolean loadedShaders() {
        return shadersToLoad == 0;
    }

    private int mapBrowserButtonToAppButton(short browserButton) {
        switch (browserButton) {
        case 0:
            return Keys.MOUSE_BUTTON_LEFT;
        case 2:
            return Keys.MOUSE_BUTTON_RIGHT;
        default:
            return browserButton;
        }
    }
}

final class WebPlatformHelper {
    static boolean leftDown;
    static boolean keysInstalled;
}
