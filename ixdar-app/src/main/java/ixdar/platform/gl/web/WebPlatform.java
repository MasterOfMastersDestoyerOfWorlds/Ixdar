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
     * TODO: document {@code WebPlatform}.
     *
     * @param canvas TODO: describe
     * @param id TODO: describe
     */
    public WebPlatform(HTMLCanvasElement canvas, String id) {
        this.currentCanvasId = id;
        this.canvas = canvas;
        setupEventListeners(canvas);
    }

    /**
     * Get the current canvas ID.
     *
     * @return TODO: describe
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

    /**
     * TODO: document {@code setTitle}.
     *
     * @param title TODO: describe
     */
    @Override
    public void setTitle(String title) {
        setDocTitle(title);
    }

    @JSBody(params = { "t" }, script = "document.title=t;")
    private static native void setDocTitle(String t);

    /**
     * TODO: document {@code getWindowWidth}.
     *
     * @return TODO: describe
     */
    @Override
    public int getWindowWidth() {
        return canvas.getClientWidth();
    }

    /**
     * TODO: document {@code getWindowHeight}.
     *
     * @return TODO: describe
     */
    @Override
    public int getWindowHeight() {
        return canvas.getClientHeight();
    }

    /**
     * TODO: document {@code requestRepaint}.
     */
    @Override
    public void requestRepaint() {
        // RAF loop externally drives rendering
    }

    @JSBody(params = {}, script = "return Date.now()/1000.0;")
    private static native double nowSeconds();

    /**
     * TODO: document {@code timeSeconds}.
     *
     * @return TODO: describe
     */
    @Override
    public float timeSeconds() {
        return (float) nowSeconds();
    }

    /**
     * TODO: document {@code setKeyCallback}.
     *
     * @param callback TODO: describe
     */
    @Override
    public void setKeyCallback(KeyCallback callback) {
        this.keyCallback = callback;
        sKeyCallback = callback;
    }

    /**
     * TODO: document {@code setCharCallback}.
     *
     * @param callback TODO: describe
     */
    @Override
    public void setCharCallback(CharCallback callback) {
        this.charCallback = callback;
        sCharCallback = callback;
    }

    /**
     * TODO: document {@code setCursorPosCallback}.
     *
     * @param callback TODO: describe
     */
    @Override
    public void setCursorPosCallback(CursorPosCallback callback) {
        this.cursorPosCallback = callback;
    }

    /**
     * TODO: document {@code setMouseButtonCallback}.
     *
     * @param callback TODO: describe
     */
    @Override
    public void setMouseButtonCallback(MouseButtonCallback callback) {
        this.mouseButtonCallback = callback;
    }

    /**
     * TODO: document {@code setScrollCallback}.
     *
     * @param callback TODO: describe
     */
    @Override
    public void setScrollCallback(ScrollCallback callback) {
        this.scrollCallback = callback;
    }

    /**
     * TODO: document {@code setCursorMode}.
     *
     * @param mode TODO: describe
     */
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

    /**
     * TODO: document {@code parseFontAtlas}.
     *
     * @param json TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
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

    /**
     * TODO: document {@code loadTexture}.
     *
     * @param resourceName TODO: describe
     * @param platformId TODO: describe
     * @param callback TODO: describe
     */
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

    /**
     * TODO: document {@code startTime}.
     *
     * @return TODO: describe
     */
    @Override
    public float startTime() {
        return WebLauncher.startTime;
    }

    /**
     * TODO: document {@code exit}.
     *
     * @param code TODO: describe
     */
    @Override
    public void exit(int code) {
        // no-op on web
    }

    /**
     * TODO: document {@code loadShaderSourceAsync}.
     *
     * @param resourceFolder TODO: describe
     * @param filename TODO: describe
     * @param platformId TODO: describe
     * @param callback TODO: describe
     */
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

    /**
     * TODO: document {@code trySyncLoadSource}.
     *
     * @param resourceFolder TODO: describe
     * @param filename TODO: describe
     * @return TODO: describe
     */
    @Override
    public String trySyncLoadSource(String resourceFolder, String filename) {
        return null;
    }

    /**
     * TODO: document {@code loadSourceAsync}.
     *
     * @param resourceFolder TODO: describe
     * @param filename TODO: describe
     * @param platformId TODO: describe
     * @param callback TODO: describe
     */
    @Override
    public void loadSourceAsync(String resourceFolder, String filename, int platformId, Consumer<String> callback) {
        String url = "/ixdar/" + resourceFolder + "/" + filename;
        fetchTextAsync(url, new TextCallback() {
            /**
             * TODO: document {@code onText}.
             *
             * @param text TODO: describe
             */
            @Override
            public void onText(String text) {
                Platforms.init(platformId);
                String safe = text == null ? "" : text;
                callback.accept(safe);
            }
        });
    }

    /**
     * TODO: document {@code loadFile}.
     *
     * @param path TODO: describe
     * @throws IOException TODO: describe
     * @return TODO: describe
     */
    @Override
    public TextFile loadFile(String path) throws IOException {
        throw new IOException("Synchronous loadFile is not supported on web; use async loading: " + path);
    }

    /**
     * TODO: document {@code loadExternalFile}.
     *
     * @param absolutePath TODO: describe
     * @throws IOException TODO: describe
     * @return TODO: describe
     */
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

    /**
     * TODO: document {@code loadImagePixels}.
     *
     * @param url TODO: describe
     * @param callback TODO: describe
     */
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

    /**
     * TODO: document {@code writeTextFile}.
     *
     * @param file TODO: describe
     * @param append TODO: describe
     * @throws IOException TODO: describe
     */
    @Override
    public void writeTextFile(TextFile file, boolean append) throws java.io.IOException {
        // No-op for web (cannot write). Intentionally ignored.
    }

    /**
     * TODO: document {@code log}.
     *
     * @param msg TODO: describe
     */
    @Override
    public void log(String msg) {
        WebPlatform.jsLog(msg);
    }

    @JSBody(params = { "msg" }, script = "console.log(msg == null ? '(null)' : msg);")
    private static native void jsLog(String msg);

    /**
     * TODO: document {@code canHotReload}.
     *
     * @return TODO: describe
     */
    @Override
    public boolean canHotReload() {
        return false;
    }

    /**
     * TODO: document {@code allocateFloats}.
     *
     * @param i TODO: describe
     * @return TODO: describe
     */
    @Override
    public IxBuffer allocateFloats(int i) {
        return new WebBuffer(i);
    }

    /**
     * TODO: document {@code setFrameBufferSize}.
     *
     * @param f TODO: describe
     * @param g TODO: describe
     */
    @Override
    public void setFrameBufferSize(float f, float g) {
        frameBufferSizeX = f;
        frameBufferSizeY = g;
    }

    /**
     * TODO: document {@code getFrameBufferWidth}.
     *
     * @return TODO: describe
     */
    @Override
    public int getFrameBufferWidth() {
        return (int) frameBufferSizeX;
    }

    /**
     * TODO: document {@code getFrameBufferHeight}.
     *
     * @return TODO: describe
     */
    @Override
    public int getFrameBufferHeight() {
        return (int) frameBufferSizeY;
    }

    /**
     * TODO: document {@code getPlatformID}.
     *
     * @return TODO: describe
     */
    @Override
    public int getPlatformID() {
        return platformId;
    }

    /**
     * TODO: document {@code setPlatformID}.
     *
     * @param p TODO: describe
     */
    @Override
    public void setPlatformID(Integer p) {
        this.platformId = p == null ? -1 : p.intValue();
    }

    /**
     * TODO: document {@code loadedShaders}.
     *
     * @return TODO: describe
     */
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

    /**
     * TODO: document {@code processInputQueue}.
     *
     * @throws UnsupportedOperationException TODO: describe
     */
    @Override
    public void processInputQueue() {
        throw new UnsupportedOperationException("Unimplemented method 'processInputQueue'");
    }

    private interface JsRect extends JSObject {
        /**
         * TODO: document {@code getLeft}.
         *
         * @return TODO: describe
         */
        @JSProperty
        double getLeft();

        /**
         * TODO: document {@code getBottom}.
         *
         * @return TODO: describe
         */
        @JSProperty
        double getBottom();

        /**
         * TODO: document {@code getRight}.
         *
         * @return TODO: describe
         */
        @JSProperty
        double getRight();

        /**
         * TODO: document {@code getTop}.
         *
         * @return TODO: describe
         */
        @JSProperty
        double getTop();
    }

    private interface JsGlyphEntry extends JSObject {
        /**
         * TODO: document {@code getUnicode}.
         *
         * @return TODO: describe
         */
        @JSProperty
        int getUnicode();

        /**
         * TODO: document {@code getAdvance}.
         *
         * @return TODO: describe
         */
        @JSProperty
        double getAdvance();

        /**
         * TODO: document {@code getPlaneBounds}.
         *
         * @return TODO: describe
         */
        @JSProperty
        JsRect getPlaneBounds();

        /**
         * TODO: document {@code getAtlasBounds}.
         *
         * @return TODO: describe
         */
        @JSProperty
        JsRect getAtlasBounds();
    }

    private interface JsAtlasInfo extends JSObject {
        /**
         * TODO: document {@code getType}.
         *
         * @return TODO: describe
         */
        @JSProperty
        String getType();

        /**
         * TODO: document {@code getDistanceRange}.
         *
         * @return TODO: describe
         */
        @JSProperty
        double getDistanceRange();

        /**
         * TODO: document {@code getDistanceRangeMiddle}.
         *
         * @return TODO: describe
         */
        @JSProperty
        double getDistanceRangeMiddle();

        /**
         * TODO: document {@code getSize}.
         *
         * @return TODO: describe
         */
        @JSProperty
        double getSize();

        /**
         * TODO: document {@code getWidth}.
         *
         * @return TODO: describe
         */
        @JSProperty
        int getWidth();

        /**
         * TODO: document {@code getHeight}.
         *
         * @return TODO: describe
         */
        @JSProperty
        int getHeight();

        /**
         * TODO: document {@code getYOrigin}.
         *
         * @return TODO: describe
         */
        @JSProperty("yOrigin")
        String getYOrigin();
    }

    private interface JsMetrics extends JSObject {
        /**
         * TODO: document {@code getEmSize}.
         *
         * @return TODO: describe
         */
        @JSProperty
        double getEmSize();

        /**
         * TODO: document {@code getLineHeight}.
         *
         * @return TODO: describe
         */
        @JSProperty
        double getLineHeight();

        /**
         * TODO: document {@code getAscender}.
         *
         * @return TODO: describe
         */
        @JSProperty
        double getAscender();

        /**
         * TODO: document {@code getDescender}.
         *
         * @return TODO: describe
         */
        @JSProperty
        double getDescender();

        /**
         * TODO: document {@code getUnderlineY}.
         *
         * @return TODO: describe
         */
        @JSProperty
        double getUnderlineY();

        /**
         * TODO: document {@code getUnderlineThickness}.
         *
         * @return TODO: describe
         */
        @JSProperty
        double getUnderlineThickness();
    }

    private interface JsRoot extends JSObject {
        /**
         * TODO: document {@code getAtlas}.
         *
         * @return TODO: describe
         */
        @JSProperty
        JsAtlasInfo getAtlas();

        /**
         * TODO: document {@code getMetrics}.
         *
         * @return TODO: describe
         */
        @JSProperty
        JsMetrics getMetrics();

        /**
         * TODO: document {@code getGlyphs}.
         *
         * @return TODO: describe
         */
        @JSProperty
        JsGlyphEntry[] getGlyphs();
        // kerning omitted for now
    }

    @JSFunctor
    interface TextCallback extends JSObject {
        /**
         * TODO: document {@code onText}.
         *
         * @param text TODO: describe
         */
        void onText(String text);
    }

    @JSFunctor
    public interface ImagePixelsCallback extends JSObject {
        /**
         * TODO: document {@code onPixels}.
         *
         * @param width TODO: describe
         * @param height TODO: describe
         * @param data TODO: describe
         */
        void onPixels(int width, int height, Uint8ClampedArray data);
    }
}

final class WebPlatformHelper {
    static boolean leftDown;
    static boolean keysInstalled;
}
