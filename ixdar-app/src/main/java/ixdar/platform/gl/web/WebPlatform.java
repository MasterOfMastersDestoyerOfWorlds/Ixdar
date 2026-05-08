package ixdar.platform.gl.web;

import java.io.IOException;
import java.nio.ByteBuffer;
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

import ixdar.canvas.WebLauncher;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.text.FontAtlasDTO;
import ixdar.platform.Platforms;
import ixdar.platform.file.TextFile;
import ixdar.platform.gl.IxBuffer;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.Keys;

public class WebPlatform implements Platform {
    public static final String SRC_MAIN_RESOURCES = "src/main/resources/";
    public static final String SRC_MAIN_RESOURCES_2 = "./src/main/resources/";
    // Global (document-level) key callbacks shared across canvases
    private static KeyCallback sKeyCallback;
    private static CharCallback sCharCallback;

    private static final Map<String, String> shaderCache = new HashMap<>();
    private static final Map<String, List<Consumer<String>>> pendingCallbacks = new HashMap<>();

    private HTMLCanvasElement canvas;
    private String currentCanvasId;

    private KeyCallback keyCallback;
    private CharCallback charCallback;

    private CursorPosCallback cursorPosCallback;
    private MouseButtonCallback mouseButtonCallback;
    private ScrollCallback scrollCallback;
    private float frameBufferSizeX;
    private float frameBufferSizeY;
    private int platformId = -1;
    private int shadersToLoad;

    /**
     * Build a {@link WebPlatform} bound to a specific HTML canvas; the supplied {@code id}
     * lets {@link Platforms} route input/render calls back to the right canvas when several
     * are mounted on the same page.
     *
     * @param canvas DOM canvas this platform owns
     * @param id stable identifier for this canvas (typically its DOM id)
     */
    public WebPlatform(HTMLCanvasElement canvas, String id) {
        this.currentCanvasId = id;
        this.canvas = canvas;
        setupEventListeners(canvas);
    }

    /**
     * Get the current canvas ID.
     *
     * @return DOM id of the canvas this platform was constructed against
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

    /** {@inheritDoc} */
    @Override
    public void setTitle(String title) {
        setDocTitle(title);
    }

    @JSBody(params = { "t" }, script = "document.title=t;")
    private static native void setDocTitle(String t);

    /** {@inheritDoc} */
    @Override
    public int getWindowWidth() {
        return canvas.getClientWidth();
    }

    /** {@inheritDoc} */
    @Override
    public int getWindowHeight() {
        return canvas.getClientHeight();
    }

    /** {@inheritDoc} */
    @Override
    public void requestRepaint() {
        // RAF loop externally drives rendering
    }

    @JSBody(params = {}, script = "return Date.now()/1000.0;")
    private static native double nowSeconds();

    /** {@inheritDoc} */
    @Override
    public float timeSeconds() {
        return (float) nowSeconds();
    }

    /** {@inheritDoc} */
    @Override
    public void setKeyCallback(KeyCallback callback) {
        this.keyCallback = callback;
        sKeyCallback = callback;
    }

    /** {@inheritDoc} */
    @Override
    public void setCharCallback(CharCallback callback) {
        this.charCallback = callback;
        sCharCallback = callback;
    }

    /** {@inheritDoc} */
    @Override
    public void setCursorPosCallback(CursorPosCallback callback) {
        this.cursorPosCallback = callback;
    }

    /** {@inheritDoc} */
    @Override
    public void setMouseButtonCallback(MouseButtonCallback callback) {
        this.mouseButtonCallback = callback;
    }

    /** {@inheritDoc} */
    @Override
    public void setScrollCallback(ScrollCallback callback) {
        this.scrollCallback = callback;
    }

    /** {@inheritDoc} */
    @Override
    public void setCursorMode(CursorMode mode) {
        if (canvas == null) return;
        switch (mode) {
            case CAPTURED -> requestPointerLock(canvas);
            case NORMAL -> exitPointerLock();
        }
    }

    @JSBody(params = "el", script = "if (el.requestPointerLock) el.requestPointerLock();")
    private static native void requestPointerLock(HTMLCanvasElement el);

    @JSBody(script = "if (document.exitPointerLock) document.exitPointerLock();")
    private static native void exitPointerLock();

    @JSBody(params = { "json" }, script = "try { return JSON.parse(json); } catch (e) { return null; }")
    private static native JsRoot parseJsonRoot(String json);

    /** {@inheritDoc} */
    @Override
    public FontAtlasDTO parseFontAtlas(String json) {
        if (json == null || json.isEmpty()) {
            throw new IllegalArgumentException(
                    "Font atlas JSON missing or empty; deploy /ixdar/res/opensans.json with the TeaVM build.");
        }
        JsRoot js = parseJsonRoot(json);
        if (js == null) {
            throw new IllegalArgumentException("Font atlas JSON parse failed");
        }
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
            ai.yorigin = js.getAtlas().getYOrigin();
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

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Override
    public float startTime() {
        return WebLauncher.startTime;
    }

    /** {@inheritDoc} */
    @Override
    public void exit(int code) {
        // no-op on web
    }

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Override
    public String trySyncLoadSource(String resourceFolder, String filename) {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void loadSourceAsync(String resourceFolder, String filename, int platformId, Consumer<String> callback) {
        String url = "/ixdar/" + resourceFolder + "/" + filename;
        fetchTextAsync(url, new TextCallback() {
            /** {@inheritDoc} */
            @Override
            public void onText(String text) {
                Platforms.init(platformId);
                String safe = text == null ? "" : text;
                callback.accept(safe);
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public TextFile loadFile(String path) throws IOException {
        throw new IOException("Synchronous loadFile is not supported on web; use async loading: " + path);
    }

    /** {@inheritDoc} */
    @Override
    public TextFile loadExternalFile(String absolutePath) throws IOException {
        throw new IOException("External filesystem assets are not available on web platform: " + absolutePath);
    }

    @JSBody(params = { "url", "callback" }, script = "fetch(url)" +
            "  .then(function(response) {" +
            "    return response.text().then(function(body) {" +
            "      if (!response.ok) {" +
            "        console.error('Fetch failed:', url, response.status, body);" +
            "        return '';" +
            "      }" +
            "      return (body == null || body === undefined) ? '' : ('' + body);" +
            "    });" +
            "  })" +
            "  .then(function(text) { callback((text == null || text === undefined) ? '' : ('' + text)); })" +
            "  .catch(function(error) { console.error('Fetch failed (shader/source):', error); callback(''); });")
    private static native void fetchTextAsync(String url, TextCallback callback);

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String p = path;
        if (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.startsWith(SRC_MAIN_RESOURCES)) {
            p = p.substring(SRC_MAIN_RESOURCES.length());
        }
        if (p.startsWith(SRC_MAIN_RESOURCES_2)) {
            p = p.substring(SRC_MAIN_RESOURCES_2.length());
        }
        return p;
    }

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Override
    public void writeTextFile(TextFile file, boolean append) throws java.io.IOException {
        // No-op for web (cannot write). Intentionally ignored.
    }

    /** {@inheritDoc} */
    @Override
    public void log(String msg) {
        WebPlatform.jsLog(msg);
    }

    @JSBody(params = { "msg" }, script = "console.log(msg == null ? '(null)' : msg);")
    private static native void jsLog(String msg);

    /** {@inheritDoc} */
    @Override
    public boolean canHotReload() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public IxBuffer allocateFloats(int i) {
        return new WebBuffer(i);
    }

    /** {@inheritDoc} */
    @Override
    public void setFrameBufferSize(float f, float g) {
        frameBufferSizeX = f;
        frameBufferSizeY = g;
    }

    /** {@inheritDoc} */
    @Override
    public int getFrameBufferWidth() {
        return (int) frameBufferSizeX;
    }

    /** {@inheritDoc} */
    @Override
    public int getFrameBufferHeight() {
        return (int) frameBufferSizeY;
    }

    /** {@inheritDoc} */
    @Override
    public int getPlatformID() {
        return platformId;
    }

    /** {@inheritDoc} */
    @Override
    public void setPlatformID(Integer p) {
        this.platformId = p == null ? -1 : p.intValue();
    }

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Override
    public void processInputQueue() {
        throw new UnsupportedOperationException("Unimplemented method 'processInputQueue'");
    }

    private interface JsRect extends JSObject {
        /** {@inheritDoc} */
        @JSProperty
        double getLeft();

        /** {@inheritDoc} */
        @JSProperty
        double getBottom();

        /** {@inheritDoc} */
        @JSProperty
        double getRight();

        /** {@inheritDoc} */
        @JSProperty
        double getTop();
    }

    private interface JsGlyphEntry extends JSObject {
        /** {@inheritDoc} */
        @JSProperty
        int getUnicode();

        /** {@inheritDoc} */
        @JSProperty
        double getAdvance();

        /** {@inheritDoc} */
        @JSProperty
        JsRect getPlaneBounds();

        /** {@inheritDoc} */
        @JSProperty
        JsRect getAtlasBounds();
    }

    private interface JsAtlasInfo extends JSObject {
        /** {@inheritDoc} */
        @JSProperty
        String getType();

        /** {@inheritDoc} */
        @JSProperty
        double getDistanceRange();

        /** {@inheritDoc} */
        @JSProperty
        double getDistanceRangeMiddle();

        /** {@inheritDoc} */
        @JSProperty
        double getSize();

        /** {@inheritDoc} */
        @JSProperty
        int getWidth();

        /** {@inheritDoc} */
        @JSProperty
        int getHeight();

        /** {@inheritDoc} */
        @JSProperty("yOrigin")
        String getYOrigin();
    }

    private interface JsMetrics extends JSObject {
        /** {@inheritDoc} */
        @JSProperty
        double getEmSize();

        /** {@inheritDoc} */
        @JSProperty
        double getLineHeight();

        /** {@inheritDoc} */
        @JSProperty
        double getAscender();

        /** {@inheritDoc} */
        @JSProperty
        double getDescender();

        /** {@inheritDoc} */
        @JSProperty
        double getUnderlineY();

        /** {@inheritDoc} */
        @JSProperty
        double getUnderlineThickness();
    }

    private interface JsRoot extends JSObject {
        /** {@inheritDoc} */
        @JSProperty
        JsAtlasInfo getAtlas();

        /** {@inheritDoc} */
        @JSProperty
        JsMetrics getMetrics();

        /** {@inheritDoc} */
        @JSProperty
        JsGlyphEntry[] getGlyphs();
        // kerning omitted for now
    }

    @JSFunctor
    interface TextCallback extends JSObject {
        /** {@inheritDoc} */
        void onText(String text);
    }

    @JSFunctor
    public interface ImagePixelsCallback extends JSObject {
        /** {@inheritDoc} */
        void onPixels(int width, int height, Uint8ClampedArray data);
    }
}

final class WebPlatformHelper {
    static boolean leftDown;
    static boolean keysInstalled;
}
