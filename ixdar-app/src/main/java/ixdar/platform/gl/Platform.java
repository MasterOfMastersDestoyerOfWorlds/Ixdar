package ixdar.platform.gl;

import java.io.IOException;
import java.util.function.Consumer;

import ixdar.graphics.render.Texture;
import ixdar.graphics.render.text.FontAtlasDTO;
import ixdar.platform.file.TextFile;

public interface Platform {

    /**
     * TODO: document {@code setTitle}.
     *
     * @param title TODO: describe
     */
    void setTitle(String title);

    /**
     * TODO: document {@code getWindowWidth}.
     *
     * @return TODO: describe
     */
    int getWindowWidth();

    /**
     * TODO: document {@code getWindowHeight}.
     *
     * @return TODO: describe
     */
    int getWindowHeight();

    /**
     * TODO: document {@code requestRepaint}.
     */
    void requestRepaint();

    /**
     * TODO: document {@code timeSeconds}.
     *
     * @return TODO: describe
     */
    float timeSeconds();

    /**
     * TODO: document {@code setKeyCallback}.
     *
     * @param callback TODO: describe
     */
    void setKeyCallback(KeyCallback callback);

    /**
     * TODO: document {@code setCharCallback}.
     *
     * @param callback TODO: describe
     */
    void setCharCallback(CharCallback callback);

    /**
     * TODO: document {@code setCursorPosCallback}.
     *
     * @param callback TODO: describe
     */
    void setCursorPosCallback(CursorPosCallback callback);

    /**
     * TODO: document {@code setMouseButtonCallback}.
     *
     * @param callback TODO: describe
     */
    void setMouseButtonCallback(MouseButtonCallback callback);

    /**
     * TODO: document {@code setScrollCallback}.
     *
     * @param callback TODO: describe
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
     * @param mode TODO: describe
     */
    void setCursorMode(CursorMode mode);

    /**
     * TODO: document {@code parseFontAtlas}.
     *
     * @param json TODO: describe
     * @return TODO: describe
     */
    FontAtlasDTO parseFontAtlas(String json);

    /**
     * TODO: document {@code exit}.
     *
     * @param code TODO: describe
     */
    void exit(int code);

    /**
     * TODO: document {@code loadTexture}.
     *
     * @param resourceName TODO: describe
     * @param platformId TODO: describe
     * @param callback TODO: describe
     */
    void loadTexture(String resourceName, int platformId, Consumer<Texture> callback);

    /**
     * TODO: document {@code loadSourceAsync}.
     *
     * @param resourceFolder TODO: describe
     * @param filename TODO: describe
     * @param platformId TODO: describe
     * @param callback TODO: describe
     */
    void loadSourceAsync(String resourceFolder, String filename, int platformId, Consumer<String> callback);

    /**
     * Desktop/headless: synchronous text from classpath or disk when cheap. Web: always null so callers use
     * {@link #loadSourceAsync} (no synchronous XHR — browser deprecation and main-thread jank).
     *
     * @param resourceFolder TODO: describe
     * @param filename TODO: describe
     * @return TODO: describe
     */
    String trySyncLoadSource(String resourceFolder, String filename);

    /**
     * TODO: document {@code loadShaderSourceAsync}.
     *
     * @param resourceFolder TODO: describe
     * @param filename TODO: describe
     * @param platformId TODO: describe
     * @param callback TODO: describe
     */
    void loadShaderSourceAsync(String resourceFolder, String filename, int platformId, Consumer<String> callback);

    /**
     * TODO: document {@code loadFile}.
     *
     * @param path TODO: describe
     * @throws IOException TODO: describe
     * @return TODO: describe
     */
    TextFile loadFile(String path) throws IOException;

    /**
     * TODO: document {@code loadExternalFile}.
     *
     * @param absolutePath TODO: describe
     * @throws IOException TODO: describe
     * @return TODO: describe
     */
    TextFile loadExternalFile(String absolutePath) throws IOException;

    /**
     * TODO: document {@code writeTextFile}.
     *
     * @param path TODO: describe
     * @param append TODO: describe
     * @throws IOException TODO: describe
     */
    void writeTextFile(TextFile path, boolean append) throws IOException;

    /**
     * TODO: document {@code startTime}.
     *
     * @return TODO: describe
     */
    float startTime();

    /**
     * TODO: document {@code log}.
     *
     * @param msg TODO: describe
     */
    void log(String msg);

    /**
     * TODO: document {@code canHotReload}.
     *
     * @return TODO: describe
     */
    boolean canHotReload();

    /**
     * TODO: document {@code allocateFloats}.
     *
     * @param i TODO: describe
     * @return TODO: describe
     */
    IxBuffer allocateFloats(int i);

    /**
     * TODO: document {@code setFrameBufferSize}.
     *
     * @param f TODO: describe
     * @param g TODO: describe
     */
    void setFrameBufferSize(float f, float g);

    /**
     * TODO: document {@code getFrameBufferWidth}.
     *
     * @return TODO: describe
     */
    int getFrameBufferWidth();

    /**
     * TODO: document {@code getFrameBufferHeight}.
     *
     * @return TODO: describe
     */
    int getFrameBufferHeight();

    /**
     * TODO: document {@code getPlatformID}.
     *
     * @return TODO: describe
     */
    int getPlatformID();

    /**
     * TODO: document {@code setPlatformID}.
     *
     * @param p TODO: describe
     */
    void setPlatformID(Integer p);

    /**
     * TODO: document {@code processInputQueue}.
     */
    void processInputQueue();

    enum CursorMode { NORMAL, CAPTURED }

    interface KeyCallback {
        /**
         * TODO: document {@code onKey}.
         *
         * @param key TODO: describe
         * @param scancode TODO: describe
         * @param action TODO: describe
         * @param mods TODO: describe
         */
        void onKey(int key, int scancode, int action, int mods);
    }

    interface CharCallback {
        /**
         * TODO: document {@code onChar}.
         *
         * @param codepoint TODO: describe
         */
        void onChar(int codepoint);
    }

    interface CursorPosCallback {
        /**
         * TODO: document {@code onMousePos}.
         *
         * @param window TODO: describe
         * @param x TODO: describe
         * @param y TODO: describe
         */
        void onMousePos(long window, double x, double y);
    }

    interface MouseButtonCallback {
        /**
         * TODO: document {@code onMouseButton}.
         *
         * @param button TODO: describe
         * @param action TODO: describe
         * @param mods TODO: describe
         */
        void onMouseButton(int button, int action, int mods);
    }

    interface ScrollCallback {
        /**
         * TODO: document {@code onScroll}.
         *
         * @param xoffset TODO: describe
         * @param yoffset TODO: describe
         */
        void onScroll(double xoffset, double yoffset);
    }
}
