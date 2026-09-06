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
import ixdar.geometry.mesh.csg.MeshBooleanBackend;
import ixdar.geometry.mesh.quadlayout.quantization.IntegerProgram;
import ixdar.geometry.mesh.quadlayout.solver.chol.NativeCholeskyBackend;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.model.ModelRuntime;
import ixdar.graphics.render.text.FontAtlasDTO;
import ixdar.platform.Platforms;
import ixdar.platform.concurrent.InlineWorkerPool;
import ixdar.platform.concurrent.WorkerPool;
import ixdar.platform.file.TextFile;
import ixdar.platform.gl.IxBuffer;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.Keys;
import ixdar.platform.json.JsonValue;

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
     * Build a {@link WebPlatform} bound to a specific HTML canvas; the supplied
     * {@code id} lets {@link Platforms} route input/render calls back to the right
     * canvas when several are mounted on the same page.
     *
     * @param canvas DOM canvas this platform owns
     * @param id     stable identifier for this canvas (typically its DOM id)
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

    /**
     * {@inheritDoc}.
     *
     * @param title text to set as the browser document title
     */
    @Override
    public void setTitle(String title) {
        setDocTitle(title);
    }

    @JSBody(params = { "t" }, script = "document.title=t;")
    private static native void setDocTitle(String t);

    /**
     * {@inheritDoc}.
     *
     * @return canvas client width in CSS pixels
     */
    @Override
    public int getWindowWidth() {
        return canvas.getClientWidth();
    }

    /**
     * {@inheritDoc}.
     *
     * @return canvas client height in CSS pixels
     */
    @Override
    public int getWindowHeight() {
        return canvas.getClientHeight();
    }

    /** {@inheritDoc}. */
    @Override
    public void requestRepaint() {
        // RAF loop externally drives rendering
    }

    @JSBody(params = {}, script = "return Date.now()/1000.0;")
    private static native double nowSeconds();

    /**
     * {@inheritDoc}.
     *
     * @return current wall-clock time in seconds (from {@code Date.now()})
     */
    @Override
    public float timeSeconds() {
        return (float) nowSeconds();
    }

    /**
     * {@inheritDoc}.
     *
     * @param callback handler invoked for document-level keydown/keyup events
     */
    @Override
    public void setKeyCallback(KeyCallback callback) {
        this.keyCallback = callback;
        sKeyCallback = callback;
    }

    /**
     * {@inheritDoc}.
     *
     * @param callback handler invoked for typed character input from keypress
     *                 events
     */
    @Override
    public void setCharCallback(CharCallback callback) {
        this.charCallback = callback;
        sCharCallback = callback;
    }

    /**
     * {@inheritDoc}.
     *
     * @param callback handler invoked with canvas-relative mouse coordinates on
     *                 mousemove
     */
    @Override
    public void setCursorPosCallback(CursorPosCallback callback) {
        this.cursorPosCallback = callback;
    }

    /**
     * {@inheritDoc}.
     *
     * @param callback handler invoked on mouse press and release events
     */
    @Override
    public void setMouseButtonCallback(MouseButtonCallback callback) {
        this.mouseButtonCallback = callback;
    }

    /**
     * {@inheritDoc}.
     *
     * @param callback handler invoked with wheel delta on scroll events
     */
    @Override
    public void setScrollCallback(ScrollCallback callback) {
        this.scrollCallback = callback;
    }

    /**
     * {@inheritDoc}.
     *
     * @param mode {@code CAPTURED} requests pointer lock, {@code NORMAL} releases
     *             it
     */
    @Override
    public void setCursorMode(CursorMode mode) {
        if (canvas == null)
            return;
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

    /** {@inheritDoc}. */
    @Override
    public JsonValue parseJson(String json) {
        return WebJsonTree.parse(json);
    }

    /**
     * {@inheritDoc}.
     *
     * @param json msdf-atlas-gen JSON descriptor for the font
     * @throws IllegalArgumentException if {@code json} is null/empty or fails to
     *                                  parse
     * @return populated {@link FontAtlasDTO} with atlas info, metrics, and glyph
     *         entries
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
     * {@inheritDoc}.
     *
     * @param resourceName image filename under {@code /ixdar/res/}
     * @param platformId   platform context to re-bind before invoking the callback
     * @param callback     receives the decoded {@link Texture} once pixels have
     *                     been read
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
     * {@inheritDoc}.
     *
     * @return the {@link WebLauncher} startup timestamp captured at page boot
     */
    @Override
    public float startTime() {
        return WebLauncher.startTime;
    }

    /**
     * {@inheritDoc}.
     *
     * @param code ignored on the web platform; the browser owns the lifecycle
     */
    @Override
    public void exit(int code) {
        // no-op on web
    }

    /**
     * {@inheritDoc}.
     *
     * @param resourceFolder folder under {@code /ixdar/} that holds the shader file
     * @param filename       shader source file name
     * @param platformId     platform context to re-bind before invoking the
     *                       callback
     * @param callback       receives the loaded shader source text
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
     * {@inheritDoc}.
     *
     * @param resourceFolder ignored; synchronous loading is unsupported on web
     * @param filename       ignored; synchronous loading is unsupported on web
     * @return always {@code null}; callers must use the async variant
     */
    @Override
    public String trySyncLoadSource(String resourceFolder, String filename) {
        return null;
    }

    /**
     * {@inheritDoc}.
     *
     * @param path ignored; the browser has no filesystem to probe
     * @return always {@code false}
     */
    @Override
    public boolean fileExists(String path) {
        return false;
    }

    /**
     * {@inheritDoc}.
     *
     * <p>Assimp is a native importer with no browser counterpart. Refusing here is what keeps it out
     * of the JavaScript build: the web launcher only ever reaches this backend, so the desktop one —
     * and the whole LWJGL native stack behind it — never enters TeaVM's reachability graph.
     *
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public ModelRuntime newModelRuntime() {
        throw new UnsupportedOperationException("Asset-repo model loading is desktop-only; "
                + "use a data-obj or data-dsl canvas on web");
    }

    /**
     * {@inheritDoc}.
     *
     * <p>Returning null is what keeps MKL and JavaCPP out of the JavaScript output: the web launcher
     * only ever reaches this backend, so no native solver enters TeaVM's reachability graph.
     *
     * @return always {@code null}; solves take the pure-Java EJML path
     */
    @Override
    public NativeCholeskyBackend nativeCholeskyBackend() {
        return null;
    }

    /**
     * {@inheritDoc}.
     *
     * <p>Refusing here is what keeps ojAlgo out of the JavaScript output; the quad-layout
     * quantization is a desktop pipeline stage and never runs in the browser.
     *
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public IntegerProgram newIntegerProgram() {
        throw new UnsupportedOperationException("Integer programming is desktop-only");
    }

    /**
     * {@inheritDoc}.
     *
     * <p>JavaScript has no threads, so every task runs inline on the caller.
     *
     * @param workerCount ignored; the inline pool has no threads to size
     * @param threadName ignored; no thread is created
     * @return a pool that runs each task on the calling thread
     */
    @Override
    public WorkerPool newWorkerPool(int workerCount, String threadName) {
        return new InlineWorkerPool();
    }

    /**
     * {@inheritDoc}.
     *
     * <p>Refusing here keeps the CSG kernel's natives out of the JavaScript output; mesh booleans
     * are a desktop authoring operation.
     *
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    @Override
    public MeshBooleanBackend meshBooleanBackend() {
        throw new UnsupportedOperationException("Mesh booleans are desktop-only");
    }

    /**
     * {@inheritDoc}.
     *
     * @param resourceFolder folder under {@code /ixdar/} that holds the file
     * @param filename       text resource file name to fetch
     * @param platformId     platform context to re-bind before invoking the
     *                       callback
     * @param callback       receives the fetched text (empty string on fetch
     *                       failure)
     */
    @Override
    public void loadSourceAsync(String resourceFolder, String filename, int platformId, Consumer<String> callback) {
        String url = "/ixdar/" + resourceFolder + "/" + filename;
        fetchTextAsync(url, new TextCallback() {
            /**
             * {@inheritDoc}.
             *
             * @param text fetched text body, or null when the fetch failed
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
     * {@inheritDoc}.
     *
     * @param path ignored; included in the error message
     * @throws IOException always, since synchronous file loading is not supported
     *                     on web
     * @return never returns; always throws
     */
    @Override
    public TextFile loadFile(String path) throws IOException {
        throw new IOException("Synchronous loadFile is not supported on web; use async loading: " + path);
    }

    /**
     * {@inheritDoc}.
     *
     * @param absolutePath ignored; included in the error message
     * @throws IOException always, since external filesystem access is unavailable
     *                     on web
     * @return never returns; always throws
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
     * Fetch {@code url} as an image, decode it via {@code createImageBitmap}, and
     * deliver its RGBA pixel data to {@code callback}.
     *
     * @param url      image URL to fetch
     * @param callback receives the decoded width, height, and RGBA pixel buffer
     */
    @JSBody(params = { "url", "callback" }, script = "fetch(url)" +
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
     * {@inheritDoc}.
     *
     * @param file   ignored; the web platform cannot write to local files
     * @param append ignored; the web platform cannot write to local files
     * @throws IOException never; this implementation is a silent no-op
     */
    @Override
    public void writeTextFile(TextFile file, boolean append) throws IOException {
        // No-op for web (cannot write). Intentionally ignored.
    }

    /**
     * {@inheritDoc}.
     *
     * @param msg message to forward to the browser {@code console.log}
     */
    @Override
    public void log(String msg) {
        WebPlatform.jsLog(msg);
    }

    /** {@inheritDoc}. */
    @Override
    public void log(String fmt, Object... obj) {
        WebPlatform.jsLog(String.format(fmt, obj));
    }

    @JSBody(params = { "msg" }, script = "console.log(msg == null ? '(null)' : msg);")
    private static native void jsLog(String msg);

    /**
     * {@inheritDoc}.
     *
     * @return always {@code false}; hot reload is not supported on web
     */
    @Override
    public boolean canHotReload() {
        return false;
    }

    /**
     * {@inheritDoc}.
     *
     * @param i number of floats the buffer must hold
     * @return a {@link WebBuffer} sized for {@code i} floats
     */
    @Override
    public IxBuffer allocateFloats(int i) {
        return new WebBuffer(i);
    }

    /**
     * {@inheritDoc}.
     *
     * @param f framebuffer width in physical pixels
     * @param g framebuffer height in physical pixels
     */
    @Override
    public void setFrameBufferSize(float f, float g) {
        frameBufferSizeX = f;
        frameBufferSizeY = g;
    }

    /**
     * {@inheritDoc}.
     *
     * @return framebuffer width in physical pixels, truncated to int
     */
    @Override
    public int getFrameBufferWidth() {
        return (int) frameBufferSizeX;
    }

    /**
     * {@inheritDoc}.
     *
     * @return framebuffer height in physical pixels, truncated to int
     */
    @Override
    public int getFrameBufferHeight() {
        return (int) frameBufferSizeY;
    }

    /**
     * {@inheritDoc}.
     *
     * @return id assigned by {@link Platforms}, or {@code -1} if not yet assigned
     */
    @Override
    public int getPlatformID() {
        return platformId;
    }

    /**
     * {@inheritDoc}.
     *
     * @param p platform id assigned by {@link Platforms}; {@code null} clears it to
     *          {@code -1}
     */
    @Override
    public void setPlatformID(Integer p) {
        this.platformId = p == null ? -1 : p.intValue();
    }

    /**
     * Indicates whether all in-flight shader fetches have completed.
     *
     * @return {@code true} once every shader requested via
     *         {@link #loadShaderSourceAsync} has resolved
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
     * {@inheritDoc}.
     *
     * @throws UnsupportedOperationException always; the web platform delivers input
     *                                       synchronously via DOM events
     */
    @Override
    public void processInputQueue() {
        throw new UnsupportedOperationException("Unimplemented method 'processInputQueue'");
    }

    private interface JsRect extends JSObject {
        /**
         * {@inheritDoc}.
         *
         * @return left edge of the rectangle from the parsed JSON
         */
        @JSProperty
        double getLeft();

        /**
         * {@inheritDoc}.
         *
         * @return bottom edge of the rectangle from the parsed JSON
         */
        @JSProperty
        double getBottom();

        /**
         * {@inheritDoc}.
         *
         * @return right edge of the rectangle from the parsed JSON
         */
        @JSProperty
        double getRight();

        /**
         * {@inheritDoc}.
         *
         * @return top edge of the rectangle from the parsed JSON
         */
        @JSProperty
        double getTop();
    }

    private interface JsGlyphEntry extends JSObject {
        /**
         * {@inheritDoc}.
         *
         * @return unicode codepoint of the glyph
         */
        @JSProperty
        int getUnicode();

        /**
         * {@inheritDoc}.
         *
         * @return horizontal advance width in em units
         */
        @JSProperty
        double getAdvance();

        /**
         * {@inheritDoc}.
         *
         * @return glyph bounds in plane (em) coordinates, or null if absent
         */
        @JSProperty
        JsRect getPlaneBounds();

        /**
         * {@inheritDoc}.
         *
         * @return glyph bounds in atlas pixel coordinates, or null if absent
         */
        @JSProperty
        JsRect getAtlasBounds();
    }

    private interface JsAtlasInfo extends JSObject {
        /**
         * {@inheritDoc}.
         *
         * @return atlas field type (e.g. {@code msdf}, {@code mtsdf})
         */
        @JSProperty
        String getType();

        /**
         * {@inheritDoc}.
         *
         * @return distance field range in pixels
         */
        @JSProperty
        double getDistanceRange();

        /**
         * {@inheritDoc}.
         *
         * @return distance field midpoint value
         */
        @JSProperty
        double getDistanceRangeMiddle();

        /**
         * {@inheritDoc}.
         *
         * @return em-size in pixels used when generating the atlas
         */
        @JSProperty
        double getSize();

        /**
         * {@inheritDoc}.
         *
         * @return atlas image width in pixels
         */
        @JSProperty
        int getWidth();

        /**
         * {@inheritDoc}.
         *
         * @return atlas image height in pixels
         */
        @JSProperty
        int getHeight();

        /**
         * {@inheritDoc}.
         *
         * @return Y-axis origin convention used by the atlas (e.g. {@code bottom},
         *         {@code top})
         */
        @JSProperty("yOrigin")
        String getYOrigin();
    }

    private interface JsMetrics extends JSObject {
        /**
         * {@inheritDoc}.
         *
         * @return em size in font units
         */
        @JSProperty
        double getEmSize();

        /**
         * {@inheritDoc}.
         *
         * @return line height in font units
         */
        @JSProperty
        double getLineHeight();

        /**
         * {@inheritDoc}.
         *
         * @return ascender height in font units
         */
        @JSProperty
        double getAscender();

        /**
         * {@inheritDoc}.
         *
         * @return descender depth in font units (typically negative)
         */
        @JSProperty
        double getDescender();

        /**
         * {@inheritDoc}.
         *
         * @return underline Y position in font units
         */
        @JSProperty
        double getUnderlineY();

        /**
         * {@inheritDoc}.
         *
         * @return underline stroke thickness in font units
         */
        @JSProperty
        double getUnderlineThickness();
    }

    private interface JsRoot extends JSObject {
        /**
         * {@inheritDoc}.
         *
         * @return parsed {@code atlas} sub-object, or null if absent
         */
        @JSProperty
        JsAtlasInfo getAtlas();

        /**
         * {@inheritDoc}.
         *
         * @return parsed {@code metrics} sub-object, or null if absent
         */
        @JSProperty
        JsMetrics getMetrics();

        /**
         * {@inheritDoc}.
         *
         * @return array of glyph entries, or null if absent
         */
        @JSProperty
        JsGlyphEntry[] getGlyphs();
        // kerning omitted for now
    }

    @JSFunctor
    interface TextCallback extends JSObject {
        /**
         * Invoked once {@code fetch} resolves with the response body.
         *
         * @param text fetched text content; may be null if the fetch failed
         */
        void onText(String text);
    }

    @JSFunctor
    public interface ImagePixelsCallback extends JSObject {
        /**
         * Invoked once {@code createImageBitmap} has decoded the image and pixels have
         * been read.
         *
         * @param width  decoded image width in pixels
         * @param height decoded image height in pixels
         * @param data   RGBA byte data laid out row by row
         */
        void onPixels(int width, int height, Uint8ClampedArray data);
    }
}

final class WebPlatformHelper {
    static boolean leftDown;
    static boolean keysInstalled;
}
