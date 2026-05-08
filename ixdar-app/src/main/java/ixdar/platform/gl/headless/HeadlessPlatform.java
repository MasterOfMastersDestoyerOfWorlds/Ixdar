package ixdar.platform.gl.headless;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import com.google.gson.Gson;

import ixdar.graphics.render.Texture;
import ixdar.graphics.render.text.FontAtlasDTO;
import ixdar.platform.file.FileManagement;
import ixdar.platform.file.TextFile;
import ixdar.platform.gl.IxBuffer;
import ixdar.platform.gl.Platform;

/**
 * Headless platform for offscreen rendering using LWJGL GLFW.
 * Creates an invisible window for OpenGL rendering.
 * Works on macOS and Linux (with display server like Xvfb in CI).
 */
public class HeadlessPlatform implements Platform {
    public static final String SRC = "src/";
    public static final int NUM_512 = 512;
    public static final double NUM_1e9 = 1e9;
    public static final int NUM_24 = 24;
    public static final int NUM_0xF = 0xFF;
    public static final int NUM_16 = 16;
    public static final int NUM_8 = 8;

    private int platformId;
    private float startTime;
    private int windowWidth = 800;
    private int windowHeight = 600;
    private int frameBufferWidth = 800;
    private int frameBufferHeight = 600;
    private HeadlessGL gl;

    /**
     * TODO: document {@code HeadlessPlatform}.
     */
    public HeadlessPlatform() {
        this(NUM_512, NUM_512);
    }
    
    /**
     * TODO: document {@code HeadlessPlatform}.
     *
     * @param width TODO: describe
     * @param height TODO: describe
     */
    public HeadlessPlatform(int width, int height) {
        this.startTime = (float) (System.nanoTime() / NUM_1e9);
        this.windowWidth = width;
        this.windowHeight = height;
        this.frameBufferWidth = width;
        this.frameBufferHeight = height;
        this.gl = new HeadlessGL(width, height);
    }

    /**
     * TODO: document {@code allocateFloats}.
     *
     * @param capacity TODO: describe
     * @return TODO: describe
     */
    @Override
    public IxBuffer allocateFloats(int capacity) {
        return new HeadlessBuffer(capacity);
    }

    /**
     * TODO: document {@code setTitle}.
     *
     * @param title TODO: describe
     */
    @Override
    public void setTitle(String title) {
        // no-op
    }

    /**
     * TODO: document {@code getWindowWidth}.
     *
     * @return TODO: describe
     */
    @Override
    public int getWindowWidth() {
        return windowWidth;
    }

    /**
     * TODO: document {@code getWindowHeight}.
     *
     * @return TODO: describe
     */
    @Override
    public int getWindowHeight() {
        return windowHeight;
    }

    /**
     * TODO: document {@code requestRepaint}.
     */
    @Override
    public void requestRepaint() {
        // no-op
    }

    /**
     * TODO: document {@code timeSeconds}.
     *
     * @return TODO: describe
     */
    @Override
    public float timeSeconds() {
        return (float) (System.nanoTime() / NUM_1e9);
    }

    /**
     * TODO: document {@code setKeyCallback}.
     *
     * @param callback TODO: describe
     */
    @Override
    public void setKeyCallback(KeyCallback callback) {
        // no-op
    }

    /**
     * TODO: document {@code setCharCallback}.
     *
     * @param callback TODO: describe
     */
    @Override
    public void setCharCallback(CharCallback callback) {
        // no-op
    }

    /**
     * TODO: document {@code setCursorPosCallback}.
     *
     * @param callback TODO: describe
     */
    @Override
    public void setCursorPosCallback(CursorPosCallback callback) {
        // no-op
    }

    /**
     * TODO: document {@code setMouseButtonCallback}.
     *
     * @param callback TODO: describe
     */
    @Override
    public void setMouseButtonCallback(MouseButtonCallback callback) {
        // no-op
    }

    /**
     * TODO: document {@code setScrollCallback}.
     *
     * @param callback TODO: describe
     */
    @Override
    public void setScrollCallback(ScrollCallback callback) {
        // no-op
    }

    /**
     * TODO: document {@code setCursorMode}.
     *
     * @param mode TODO: describe
     */
    @Override
    public void setCursorMode(CursorMode mode) {
        // no-op — headless has no window or cursor
    }

    /**
     * TODO: document {@code parseFontAtlas}.
     *
     * @param json TODO: describe
     * @return TODO: describe
     */
    @Override
    public FontAtlasDTO parseFontAtlas(String json) {
        return new Gson().fromJson(json, FontAtlasDTO.class);
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
        // no-op for headless
    }

    /**
     * TODO: document {@code startTime}.
     *
     * @return TODO: describe
     */
    @Override
    public float startTime() {
        return startTime;
    }

    /**
     * TODO: document {@code exit}.
     *
     * @param code TODO: describe
     */
    @Override
    public void exit(int code) {
        // no-op for tests
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
        try {
            String path = resourceFolder + "/" + filename;
            try (InputStream in = HeadlessPlatform.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    return null;
                }
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null;
        }
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
        String sync = trySyncLoadSource(resourceFolder, filename);
        if (sync != null) {
            callback.accept(sync);
            return;
        }
        callback.accept("");
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
        loadSourceAsync("glsl", filename, platformId, callback);
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
        path = path.replaceAll("./src/main/resources/", "");
        InputStream in = FileManagement.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            String alt = path;
            if (!alt.startsWith(SRC)) {
                alt = SRC + path;
            }
            in = FileManagement.class.getClassLoader().getResourceAsStream(alt);
        }
        if (in == null) {
            throw new IOException("File not found: " + path);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        ArrayList<String> lines = new ArrayList<>(reader.lines().collect(Collectors.toList()));
        return new TextFile(path, lines);
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
        Path path = Path.of(absolutePath);
        if (!Files.exists(path)) {
            throw new IOException("External asset file not found: " + absolutePath);
        }
        ArrayList<String> lines = new ArrayList<>(Files.readAllLines(path));
        return new TextFile(path.toString(), lines);
    }

    /**
     * TODO: document {@code writeTextFile}.
     *
     * @param file TODO: describe
     * @param append TODO: describe
     * @throws IOException TODO: describe
     */
    @Override
    public void writeTextFile(TextFile file, boolean append) throws IOException {
        File newFile = new File(file.getPath());
        File parent = newFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileWriter fw = new FileWriter(newFile, append);
                BufferedWriter out = new BufferedWriter(fw)) {
            for (String s : file.getLines()) {
                out.write(s);
                out.newLine();
            }
            out.flush();
        }
    }

    /**
     * TODO: document {@code log}.
     *
     * @param msg TODO: describe
     */
    @Override
    public void log(String msg) {
        System.out.println(msg);
    }

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
     * TODO: document {@code setFrameBufferSize}.
     *
     * @param width TODO: describe
     * @param height TODO: describe
     */
    @Override
    public void setFrameBufferSize(float width, float height) {
        this.frameBufferWidth = (int) width;
        this.frameBufferHeight = (int) height;
    }

    /**
     * TODO: document {@code getFrameBufferWidth}.
     *
     * @return TODO: describe
     */
    @Override
    public int getFrameBufferWidth() {
        return frameBufferWidth;
    }

    /**
     * TODO: document {@code getFrameBufferHeight}.
     *
     * @return TODO: describe
     */
    @Override
    public int getFrameBufferHeight() {
        return frameBufferHeight;
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
     * TODO: document {@code processInputQueue}.
     */
    @Override
    public void processInputQueue() {
        // no-op for headless
    }

    /**
     * Get the headless GL instance.
     *
     * @return TODO: describe
     */
    public HeadlessGL getGL() {
        return gl;
    }

    /**
     * Capture a screenshot and save to PNG file.
     *
     * @param outputPath TODO: describe
     * @throws IOException TODO: describe
     */
    public void screenshot(String outputPath) throws IOException {
        if (gl == null) {
            throw new IOException("HeadlessGL not initialized");
        }
        
        HeadlessGL headlessGL = gl;
        int width = headlessGL.getWidth();
        int height = headlessGL.getHeight();
        
        // Read pixels from framebuffer
        int[] pixels = headlessGL.readPixels(
            0, 0, width, height,
            headlessGL.RGBA(),
            headlessGL.UNSIGNED_BYTE(),
            0
        );
        
        // Create BufferedImage (AWT uses top-left origin, OpenGL uses bottom-left)
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        
        // Flip Y and copy pixels
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = pixels[y * width + x];
                // Convert from GL format (ABGR in int) to AWT format (ARGB)
                int a = (pixel >> NUM_24) & NUM_0xF;
                int r = (pixel >> NUM_16) & NUM_0xF;
                int g = (pixel >> NUM_8) & NUM_0xF;
                int b = pixel & NUM_0xF;
                int awtPixel = (a << NUM_24) | (r << NUM_16) | (g << NUM_8) | b;
                image.setRGB(x, height - 1 - y, awtPixel);
            }
        }
        
        // Write PNG
        File outputFile = new File(outputPath);
        File parent = outputFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        ImageIO.write(image, "png", outputFile);
        
        log("[HeadlessPlatform] Screenshot saved: " + outputPath);
    }

    /**
     * Release all resources.
     */
    public void shutdown() {
        if (gl != null) {
            gl.shutdown();
            gl = null;
        }
    }
}
