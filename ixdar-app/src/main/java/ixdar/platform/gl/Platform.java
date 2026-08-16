package ixdar.platform.gl;

import java.io.IOException;
import java.util.function.Consumer;

import ixdar.geometry.mesh.quadlayout.quantization.IntegerProgram;
import ixdar.geometry.mesh.quadlayout.solver.NativeCholeskyBackend;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.model.ModelRuntime;
import ixdar.graphics.render.text.FontAtlasDTO;
import ixdar.platform.concurrent.WorkerPool;
import ixdar.platform.file.TextFile;

/**
 * Windowing / OS abstraction sibling of {@link GL}. Implementations: {@code LwjglPlatform}
 * (desktop), {@code HeadlessPlatform} (CI / tests), {@code WebPlatform} (TeaVM browser).
 */
public interface Platform {

    /**
     * Set the OS window / browser tab title.
     *
     * @param title new title
     */
    void setTitle(String title);

    /**
     * Logical window width.
     *
     * @return logical window width in pixels
     */
    int getWindowWidth();

    /**
     * Logical window height.
     *
     * @return logical window height in pixels
     */
    int getWindowHeight();

    /**
     * Hint that the next frame should be rendered. Desktop drives rendering from the main loop
     * and treats this as a no-op; web RAF loops likewise.
     */
    void requestRepaint();

    /**
     * Monotonic time since process start.
     *
     * @return monotonic time-since-process-start in seconds
     */
    float timeSeconds();

    /**
     * Register the keyboard key callback (translated GLFW / DOM events).
     *
     * @param callback receives key events
     */
    void setKeyCallback(KeyCallback callback);

    /**
     * Register the text-input callback for typed characters.
     *
     * @param callback receives Unicode code points
     */
    void setCharCallback(CharCallback callback);

    /**
     * Register the mouse-move callback (window/canvas-local coordinates).
     *
     * @param callback receives cursor positions
     */
    void setCursorPosCallback(CursorPosCallback callback);

    /**
     * Register the mouse-button callback.
     *
     * @param callback receives mouse-button events
     */
    void setMouseButtonCallback(MouseButtonCallback callback);

    /**
     * Register the scroll-wheel callback.
     *
     * @param callback receives scroll deltas
     */
    void setScrollCallback(ScrollCallback callback);

    /**
     * Cursor visibility / capture state.
     *
     * <ul>
     *   <li>{@link CursorMode#NORMAL} — OS cursor visible, free movement (default).</li>
     *   <li>{@link CursorMode#CAPTURED} — cursor hidden and locked to the window. Cursor-pos
     *       callbacks deliver raw deltas regardless of how far the cursor would have moved.
     *       Used for FPS mouse-look — see {@code DungeonViewerScene} player mode.</li>
     * </ul>
     *
     * @param mode requested cursor mode
     */
    void setCursorMode(CursorMode mode);

    /**
     * Parse an MSDF font-atlas JSON document. Desktop uses Gson; web uses native
     * {@code JSON.parse} bridged through TeaVM.
     *
     * @param json atlas JSON payload
     * @return parsed DTO
     */
    FontAtlasDTO parseFontAtlas(String json);

    /**
     * Terminate the process (or no-op on web / tests).
     *
     * @param code process exit code
     */
    void exit(int code);

    /**
     * Asynchronously load an image resource and hand back a {@link Texture} ready for GL upload.
     *
     * @param resourceName classpath-relative file name under {@code res/}
     * @param platformId the platform to bind into before invoking {@code callback}
     * @param callback called once the image bytes are decoded
     */
    void loadTexture(String resourceName, int platformId, Consumer<Texture> callback);

    /**
     * Asynchronously load a text resource (e.g. shader, JSON).
     *
     * @param resourceFolder folder under {@code res/} (or web root)
     * @param filename file within {@code resourceFolder}
     * @param platformId the platform to bind into before invoking {@code callback}
     * @param callback receives the loaded text (empty string on failure)
     */
    void loadSourceAsync(String resourceFolder, String filename, int platformId, Consumer<String> callback);

    /**
     * Desktop/headless: synchronous text from classpath or disk when cheap. Web: always null so callers use
     * {@link #loadSourceAsync} (no synchronous XHR — browser deprecation and main-thread jank).
     *
     * @param resourceFolder folder under {@code res/}
     * @param filename file within {@code resourceFolder}
     * @return file contents, or {@code null} when not available synchronously
     */
    String trySyncLoadSource(String resourceFolder, String filename);

    /**
     * Desktop/headless: whether {@code path} names an existing file on disk. Web: always false, since the
     * browser has no filesystem to probe.
     *
     * @param path filesystem path, absolute or relative to the working directory
     * @return {@code true} when the file exists and is readable
     */
    boolean fileExists(String path);

    /**
     * Asynchronously load a shader source from the {@code glsl/} resource folder.
     *
     * @param resourceFolder ignored on most backends (always reads from {@code glsl/})
     * @param filename shader file name
     * @param platformId the platform to bind into before invoking {@code callback}
     * @param callback receives the shader source
     */
    void loadShaderSourceAsync(String resourceFolder, String filename, int platformId, Consumer<String> callback);

    /**
     * Synchronously load a classpath / filesystem text file. Web throws — callers must use
     * {@link #loadSourceAsync} there.
     *
     * @param path resource or filesystem path
     * @throws IOException if the file cannot be read
     * @return loaded {@link TextFile}
     */
    TextFile loadFile(String path) throws IOException;

    /**
     * Load a file by absolute filesystem path (used for {@link FontAtlasDTO}/asset repo files
     * that live outside the classpath).
     *
     * @param absolutePath absolute filesystem path
     * @throws IOException if the file is missing or unreadable
     * @return loaded {@link TextFile}
     */
    TextFile loadExternalFile(String absolutePath) throws IOException;

    /**
     * Write the line buffer of {@code path} back to disk. No-op on web.
     *
     * @param path target file
     * @param append true to append, false to truncate
     * @throws IOException on filesystem failure
     */
    void writeTextFile(TextFile path, boolean append) throws IOException;

    /**
     * Backend that loads and draws asset-repo model files. Desktop and headless go through Assimp;
     * web has no equivalent, so the browser backend refuses rather than linking a native importer
     * into the JavaScript build.
     *
     * @throws Exception if the backend's importer cannot be initialized
     * @return a new model runtime owned by the caller
     */
    ModelRuntime newModelRuntime() throws Exception;

    /**
     * Native acceleration for the quad-layout Cholesky solves, or {@code null} when the platform has
     * none and the pure-Java path should be used. Naming the implementation here rather than in the
     * solver is what keeps MKL and JavaCPP out of the browser build.
     *
     * @return the platform's native backend, or {@code null}
     */
    NativeCholeskyBackend nativeCholeskyBackend();

    /**
     * A fresh integer-program builder for the quantization solve. Naming the solver here rather than
     * in the quantization code is what keeps ojAlgo — whose static initializers reach for executors
     * and {@code ManagementFactory} — out of the browser build.
     *
     * @return a new, empty integer program
     */
    IntegerProgram newIntegerProgram();

    /**
     * A pool for fanning identical work out across {@code workerCount} slices. Backends without
     * threads return one that runs every task inline.
     *
     * @param workerCount number of workers the caller will submit per round, at least one
     * @param threadName name for the pool's threads, for profiler and stack-trace readability
     * @return a new pool the caller must shut down
     */
    WorkerPool newWorkerPool(int workerCount, String threadName);

    /**
     * Wall-clock time the application started.
     *
     * @return seconds-since-epoch (or platform clock origin) when the application started
     */
    float startTime();

    /**
     * Diagnostic logger; routes to {@code stdout} on desktop / headless, {@code console.log} on web.
     *
     * @param msg message to log
     */
    void log(String msg);

    /**
     * Diagnostic logger taking a {@link String#format} pattern and its arguments.
     *
     * @param format format string
     * @param args   arguments the format string consumes
     */
    void log(String format, Object... args);

    /**
     * Whether this platform supports hot reload of resources.
     *
     * @return true when this platform supports live reload of resources (desktop only)
     */
    boolean canHotReload();

    /**
     * Allocate a backend-appropriate {@link IxBuffer} of {@code i} floats.
     *
     * @param i capacity in floats
     * @return new buffer
     */
    IxBuffer allocateFloats(int i);

    /**
     * Cache the framebuffer size reported by GLFW / canvas resize callbacks.
     *
     * @param f width in pixels
     * @param g height in pixels
     */
    void setFrameBufferSize(float f, float g);

    /**
     * Drawable framebuffer width in pixels.
     *
     * @return cached framebuffer width (drawable pixels, may differ from window width on HiDPI)
     */
    int getFrameBufferWidth();

    /**
     * Drawable framebuffer height in pixels.
     *
     * @return cached framebuffer height
     */
    int getFrameBufferHeight();

    /**
     * Read this platform's assigned ID.
     *
     * @return platform ID assigned by {@link ixdar.platform.Platforms#init(Platform, GL)}
     */
    int getPlatformID();

    /**
     * Stamp this platform with its ID (called from {@link ixdar.platform.Platforms}).
     *
     * @param p platform ID
     */
    void setPlatformID(Integer p);

    /**
     * Drain queued input events on the GL thread (LWJGL marshals callbacks into a queue so they
     * fire in step with the render loop; web is a no-op since events are already on the main
     * thread).
     */
    void processInputQueue();

    enum CursorMode { NORMAL, CAPTURED }

    interface KeyCallback {
        /**
         * Called for every key event.
         *
         * @param key platform-mapped key code (see {@code Keys})
         * @param scancode raw scancode (GLFW; 0 on web)
         * @param action {@code ACTION_PRESS} / {@code ACTION_RELEASE} / {@code ACTION_REPEAT}
         * @param mods modifier-key bitmask
         */
        void onKey(int key, int scancode, int action, int mods);
    }

    interface CharCallback {
        /**
         * Called for typed text after IME composition.
         *
         * @param codepoint Unicode code point
         */
        void onChar(int codepoint);
    }

    interface CursorPosCallback {
        /**
         * Called for every mouse-move event.
         *
         * @param window GLFW window handle (always 0 on web)
         * @param x cursor x in window coordinates
         * @param y cursor y in window coordinates
         */
        void onMousePos(long window, double x, double y);
    }

    interface MouseButtonCallback {
        /**
         * Called for mouse-button presses and releases.
         *
         * @param button button index (0 = left, 1 = right, ...)
         * @param action {@code ACTION_PRESS} or {@code ACTION_RELEASE}
         * @param mods modifier-key bitmask
         */
        void onMouseButton(int button, int action, int mods);
    }

    interface ScrollCallback {
        /**
         * Called for scroll-wheel / trackpad scroll events.
         *
         * @param xoffset horizontal scroll delta
         * @param yoffset vertical scroll delta
         */
        void onScroll(double xoffset, double yoffset);
    }
}
