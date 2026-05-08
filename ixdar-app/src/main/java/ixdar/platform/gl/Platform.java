package ixdar.platform.gl;

import java.io.IOException;
import java.util.function.Consumer;

import ixdar.graphics.render.Texture;
import ixdar.graphics.render.text.FontAtlasDTO;
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
     * @return logical window width in pixels
     */
    int getWindowWidth();

    /**
     * @return logical window height in pixels
     */
    int getWindowHeight();

    /**
     * Hint that the next frame should be rendered. Desktop drives rendering from the main loop
     * and treats this as a no-op; web RAF loops likewise.
     */
    void requestRepaint();

    /**
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
     * @return cached framebuffer width (drawable pixels, may differ from window width on HiDPI)
     */
    int getFrameBufferWidth();

    /**
     * @return cached framebuffer height
     */
    int getFrameBufferHeight();

    /**
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
