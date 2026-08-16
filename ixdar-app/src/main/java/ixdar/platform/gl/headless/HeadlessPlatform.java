package ixdar.platform.gl.headless;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;

import com.google.gson.Gson;

import ixdar.geometry.mesh.quadlayout.quantization.IntegerProgram;
import ixdar.geometry.mesh.quadlayout.quantization.OjAlgoIntegerProgram;
import ixdar.geometry.mesh.quadlayout.solver.NativeCholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.PardisoBackend;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.model.AssimpModelRuntime;
import ixdar.graphics.render.model.ModelRuntime;
import ixdar.graphics.render.text.FontAtlasDTO;
import ixdar.platform.Platforms;
import ixdar.platform.concurrent.ThreadWorkerPool;
import ixdar.platform.concurrent.WorkerPool;
import ixdar.platform.file.FileManagement;
import ixdar.platform.file.TextFile;
import ixdar.platform.gl.IxBuffer;
import ixdar.platform.gl.Platform;

/**
 * Headless platform for offscreen rendering using LWJGL GLFW. Creates an
 * invisible window for OpenGL rendering. Works on macOS and Linux (with display
 * server like Xvfb in CI).
 */
public class HeadlessPlatform implements Platform {
    public static final String SRC = "src/";
    public static final int NUM_512 = 512;
    public static final int NUM_4 = 4;
    public static final double NUM_1e9 = 1e9;
    public static final int NUM_24 = 24;
    public static final int NUM_0xF = 0xFF;
    public static final int NUM_16 = 16;
    public static final int NUM_8 = 8;

    private final PardisoBackend pardisoBackend = new PardisoBackend();
    private int platformId;
    private float startTime;
    private int windowWidth = 800;
    private int windowHeight = 600;
    private int frameBufferWidth = 800;
    private int frameBufferHeight = 600;
    private HeadlessGL gl;

    /**
     * 512x512 default headless platform.
     */
    public HeadlessPlatform() {
        this(NUM_512, NUM_512);
    }

    /**
     * Build a headless platform sized {@code width x height}; window and
     * framebuffer dimensions are both initialized to that size.
     *
     * @param width  window/framebuffer width in pixels
     * @param height window/framebuffer height in pixels
     */
    public HeadlessPlatform(int width, int height) {
        this.startTime = (float) (System.nanoTime() / NUM_1e9);
        this.windowWidth = width;
        this.windowHeight = height;
        this.frameBufferWidth = width;
        this.frameBufferHeight = height;
        this.gl = new HeadlessGL(width, height);
    }

    /** {@inheritDoc}. */
    @Override
    public IxBuffer allocateFloats(int capacity) {
        return new HeadlessBuffer(capacity);
    }

    /** No-op: headless has no window decoration. */
    @Override
    public void setTitle(String title) {
        // no-op
    }

    /** {@inheritDoc}. */
    @Override
    public int getWindowWidth() {
        return windowWidth;
    }

    /** {@inheritDoc}. */
    @Override
    public int getWindowHeight() {
        return windowHeight;
    }

    /** No-op: headless renders on demand from test code. */
    @Override
    public void requestRepaint() {
        // no-op
    }

    /** {@inheritDoc}. */
    @Override
    public float timeSeconds() {
        return (float) (System.nanoTime() / NUM_1e9);
    }

    /** No-op: headless does not deliver keyboard events. */
    @Override
    public void setKeyCallback(KeyCallback callback) {
        // no-op
    }

    /** No-op: headless does not deliver text input. */
    @Override
    public void setCharCallback(CharCallback callback) {
        // no-op
    }

    /** No-op: headless does not deliver cursor positions. */
    @Override
    public void setCursorPosCallback(CursorPosCallback callback) {
        // no-op
    }

    /** No-op: headless does not deliver mouse-button events. */
    @Override
    public void setMouseButtonCallback(MouseButtonCallback callback) {
        // no-op
    }

    /** No-op: headless does not deliver scroll events. */
    @Override
    public void setScrollCallback(ScrollCallback callback) {
        // no-op
    }

    /** No-op: headless has no window or cursor. */
    @Override
    public void setCursorMode(CursorMode mode) {
        // no-op — headless has no window or cursor
    }

    /** Parse atlas JSON via Gson (same as desktop). */
    @Override
    public FontAtlasDTO parseFontAtlas(String json) {
        return new Gson().fromJson(json, FontAtlasDTO.class);
    }

    /**
     * Load {@code res/<resourceName>} from the classpath and decode it via STB into
     * a {@link Texture}, so headless screenshots render fonts and sprites. Reading
     * from the classpath (not a CWD-relative file) keeps it working regardless of
     * the launch directory. GL upload is deferred to {@link Texture#initGL()}.
     *
     * @param resourceName texture file under {@code res/}
     * @param platformId   platform id the created texture binds to
     * @param callback     receiver of the loaded texture
     */
    @Override
    public void loadTexture(String resourceName, int platformId, Consumer<Texture> callback) {
        byte[] encoded;
        try (InputStream in = HeadlessPlatform.class.getClassLoader()
                .getResourceAsStream("res/" + resourceName)) {
            if (in == null) {
                System.out.println("Can't load file " + resourceName + " (not on classpath)");
                return;
            }
            encoded = in.readAllBytes();
        } catch (IOException ex) {
            System.out.println("Can't load file " + resourceName + " " + ex.getMessage());
            return;
        }
        ByteBuffer encodedBuffer = BufferUtils.createByteBuffer(encoded.length);
        encodedBuffer.put(encoded).flip();
        STBImage.stbi_set_flip_vertically_on_load(true);
        IntBuffer width = BufferUtils.createIntBuffer(1);
        IntBuffer height = BufferUtils.createIntBuffer(1);
        IntBuffer channels = BufferUtils.createIntBuffer(1);
        ByteBuffer image = STBImage.stbi_load_from_memory(encodedBuffer, width, height, channels, NUM_4);
        if (image == null) {
            System.out.println("Can't load file " + resourceName + " " + STBImage.stbi_failure_reason());
            return;
        }
        Platforms.init(platformId);
        callback.accept(new Texture(resourceName, image, width.get(0), height.get(0)));
    }

    /** {@inheritDoc}. */
    @Override
    public float startTime() {
        return startTime;
    }

    /** No-op: tests must not terminate the JVM. */
    @Override
    public void exit(int code) {
        // no-op for tests
    }

    /**
     * Read {@code resourceFolder/filename} from the classpath synchronously.
     *
     * @param resourceFolder folder under the classpath (e.g. {@code "glsl"})
     * @param filename       file within {@code resourceFolder}
     * @return file contents, or {@code null} if missing
     */
    @Override
    public String trySyncLoadSource(String resourceFolder, String filename) {
        try {
            String path = resourceFolder + "/" + filename;
            try (InputStream in = HeadlessPlatform.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    return null;
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Probe the real filesystem, the same as the desktop backend.
     *
     * @param path filesystem path, absolute or relative to the working directory
     * @return {@code true} when the file exists and is readable
     */
    @Override
    public boolean fileExists(String path) {
        return path != null && !path.isEmpty() && Files.isReadable(Paths.get(path));
    }

    /**
     * Load models through Assimp, the same as the desktop backend.
     *
     * @throws Exception if Assimp cannot be initialized
     * @return a new Assimp-backed model runtime
     */
    @Override
    public ModelRuntime newModelRuntime() throws Exception {
        return new AssimpModelRuntime();
    }

    /**
     * Accelerate solves with PARDISO, the same as the desktop backend.
     *
     * @return the MKL-backed native Cholesky backend
     */
    @Override
    public NativeCholeskyBackend nativeCholeskyBackend() {
        return pardisoBackend;
    }

    /**
     * Solve integer programs with ojAlgo, the same as the desktop backend.
     *
     * @return a new ojAlgo-backed integer program
     */
    @Override
    public IntegerProgram newIntegerProgram() {
        return new OjAlgoIntegerProgram();
    }

    /**
     * Fan work out across real threads, the same as the desktop backend.
     *
     * @param workerCount number of threads
     * @param threadName name given to each thread
     * @return a new thread-backed pool
     */
    @Override
    public WorkerPool newWorkerPool(int workerCount, String threadName) {
        return new ThreadWorkerPool(workerCount, threadName);
    }

    /**
     * Resolves synchronously via the classpath; falls back to {@code ""} on miss.
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
     * Force shader sources to be loaded from the {@code glsl/} classpath folder.
     */
    @Override
    public void loadShaderSourceAsync(String resourceFolder, String filename, int platformId,
            Consumer<String> callback) {
        loadSourceAsync("glsl", filename, platformId, callback);
    }

    /**
     * Load a classpath resource as a {@link TextFile}, accommodating the test
     * layout where paths may begin with {@code ./src/main/resources/} or be
     * relative to {@code src/}.
     *
     * @param path resource path
     * @throws IOException if the resource cannot be located
     * @return loaded text
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
     * Load an external (filesystem) text file by absolute path.
     *
     * @param absolutePath absolute filesystem path
     * @throws IOException if the file is missing
     * @return loaded text
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
     * Write {@code file} to disk, creating parent directories as needed.
     *
     * @param file   file (path + lines) to persist
     * @param append true to append, false to truncate
     * @throws IOException on filesystem failure
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

    /** Route logs to {@code System.out}. */
    @Override
    public void log(String msg) {
        System.out.println(msg);
    }

    /** {@inheritDoc}. */
    @Override
    public void log(String fmt, Object... obj) {
        System.out.println(String.format(fmt, obj));
    }

    /** {@code false}: no live resource reloading in headless tests. */
    @Override
    public boolean canHotReload() {
        return false;
    }

    /** {@inheritDoc}. */
    @Override
    public void setFrameBufferSize(float width, float height) {
        this.frameBufferWidth = (int) width;
        this.frameBufferHeight = (int) height;
    }

    /** {@inheritDoc}. */
    @Override
    public int getFrameBufferWidth() {
        return frameBufferWidth;
    }

    /** {@inheritDoc}. */
    @Override
    public int getFrameBufferHeight() {
        return frameBufferHeight;
    }

    /** {@inheritDoc}. */
    @Override
    public int getPlatformID() {
        return platformId;
    }

    /** {@inheritDoc}. */
    @Override
    public void setPlatformID(Integer p) {
        this.platformId = p == null ? -1 : p.intValue();
    }

    /** No-op: headless has no input queue to drain. */
    @Override
    public void processInputQueue() {
        // no-op for headless
    }

    /**
     * Access this platform's headless GL implementation.
     *
     * @return the {@link HeadlessGL} created at construction
     */
    public HeadlessGL getGL() {
        return gl;
    }

    /**
     * Read the framebuffer back via {@link HeadlessGL#readPixels} and write the
     * result to a PNG file (Y-flipping from GL's bottom-left origin to AWT's
     * top-left).
     *
     * @param outputPath PNG output path (parent dirs are created)
     * @throws IOException if GL is not initialized or PNG encoding fails
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
                0);

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
     * Tear down the GL context and destroy the offscreen window.
     */
    public void shutdown() {
        if (gl != null) {
            gl.shutdown();
            gl = null;
        }
    }
}
