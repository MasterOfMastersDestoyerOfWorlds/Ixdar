package unit.visual;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.IntFunction;

import javax.imageio.ImageIO;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWErrorCallback;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.lwjgl.LwjglGL;
import ixdar.platform.gl.lwjgl.LwjglPlatform;

/**
 * Visual regression testing harness for rendering tests.
 * Creates a hidden OpenGL window to render test scenes and compare
 * against reference screenshots.
 */
public class VisualTestHarness {

    private static boolean initialized = false;
    private static long window;
    private static int width = 800;
    private static int height = 600;

    /** Directory containing reference images for comparison */
    public static final String REFERENCE_DIR = "test/resources/visual-references/";
    
    /** Directory for storing actual test output (for debugging failures) */
    public static final String OUTPUT_DIR = "test/resources/visual-output/";

    /** Default pixel difference tolerance (0.0 = exact match, 1.0 = any difference allowed) */
    public static final double DEFAULT_TOLERANCE = 0.02;

    /**
     * Initialize the visual testing platform with a hidden OpenGL window.
     * Safe to call multiple times - only initializes once.
     */
    public static synchronized void init() {
        init(800, 600);
    }

    /**
     * Initialize the visual testing platform with specified dimensions.
     * @param w width of the render target
     * @param h height of the render target
     */
    public static synchronized void init(int w, int h) {
        if (initialized) {
            return;
        }

        width = w;
        height = h;

        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        // Create a hidden window
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);

        window = glfwCreateWindow(width, height, "Visual Test Harness", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create the GLFW window for visual testing");
        }

        glfwMakeContextCurrent(window);

        // Initialize platform with window handle
        LwjglPlatform platform = new LwjglPlatform(window);
        platform.setFrameBufferSize(width, height);
        LwjglGL gl = new LwjglGL();
        Platforms.init(platform, gl);

        // Initialize OpenGL capabilities
        GL glContext = Platforms.gl();
        glContext.createCapabilities(false, (IntFunction<PointerBuffer>) null);
        glContext.viewport(0, 0, width, height);
        glContext.enable(glContext.DEPTH_TEST());
        glContext.blendFunc(glContext.SRC_ALPHA(), glContext.ONE_MINUS_SRC_ALPHA());
        glContext.enable(glContext.BLEND());

        // Ensure output directories exist
        ensureDirectoryExists(REFERENCE_DIR);
        ensureDirectoryExists(OUTPUT_DIR);

        initialized = true;
    }

    /**
     * Render a test scene and capture the result.
     * @param drawCall the rendering code to execute
     * @return pixel data as int[] (RGB packed)
     */
    public static int[] renderAndCapture(Runnable drawCall) {
        if (!initialized) {
            init();
        }

        GL gl = Platforms.gl();

        // Clear the screen
        gl.clearColor(0.07f, 0.07f, 0.07f, 1.0f);
        gl.clear(gl.COLOR_BUFFER_BIT() | gl.DEPTH_BUFFER_BIT());

        // Execute the draw call
        drawCall.run();

        // Flush all shaders
        for (var shader : gl.getShaders()) {
            shader.flush();
        }

        // Capture the framebuffer
        return gl.readPixels(0, 0, width, height, gl.RGBA(), gl.UNSIGNED_BYTE(), width * height * 4);
    }

    /**
     * Save pixels to a PNG file.
     * @param pixels pixel data from readPixels
     * @param filePath path to save the PNG
     */
    public static void saveScreenshot(int[] pixels, String filePath) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // OpenGL reads pixels from bottom-left, BufferedImage expects top-left
        // Flip vertically while copying
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int srcIndex = (height - 1 - y) * width + x;
                int rgb = pixels[srcIndex];
                image.setRGB(x, y, rgb);
            }
        }

        File file = new File(filePath);
        file.getParentFile().mkdirs();
        ImageIO.write(image, "PNG", file);
    }

    /**
     * Load a reference image from file.
     * @param filePath path to the PNG file
     * @return pixel data as int[] (RGB packed)
     */
    public static int[] loadReference(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }

        BufferedImage image = ImageIO.read(file);
        int w = image.getWidth();
        int h = image.getHeight();
        int[] pixels = new int[w * h];

        // Convert to same format as readPixels (bottom-left origin)
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int destIndex = (h - 1 - y) * w + x;
                pixels[destIndex] = image.getRGB(x, y) & 0xFFFFFF; // Strip alpha
            }
        }

        return pixels;
    }

    /**
     * Compare two pixel arrays and return the difference ratio.
     * @param actual the rendered pixels
     * @param expected the reference pixels
     * @return ratio of differing pixels (0.0 = identical, 1.0 = completely different)
     */
    public static double compareImages(int[] actual, int[] expected) {
        if (actual.length != expected.length) {
            return 1.0; // Completely different if sizes don't match
        }

        int diffCount = 0;
        for (int i = 0; i < actual.length; i++) {
            if (!pixelsMatch(actual[i], expected[i])) {
                diffCount++;
            }
        }

        return (double) diffCount / actual.length;
    }

    /**
     * Check if two pixels match within a small tolerance (for anti-aliasing differences).
     */
    private static boolean pixelsMatch(int a, int b) {
        int rA = (a >> 16) & 0xFF;
        int gA = (a >> 8) & 0xFF;
        int bA = a & 0xFF;

        int rB = (b >> 16) & 0xFF;
        int gB = (b >> 8) & 0xFF;
        int bB = b & 0xFF;

        // Allow small differences for anti-aliasing
        int threshold = 5;
        return Math.abs(rA - rB) <= threshold &&
               Math.abs(gA - gB) <= threshold &&
               Math.abs(bA - bB) <= threshold;
    }

    /**
     * Render a scene and assert it matches the reference image.
     * If no reference exists, saves the current render as the new baseline.
     * 
     * @param testName name of the test (used for file naming)
     * @param drawCall the rendering code to execute
     * @throws AssertionError if the rendered image differs from reference beyond tolerance
     */
    public static void assertMatchesReference(String testName, Runnable drawCall) throws IOException {
        assertMatchesReference(testName, drawCall, DEFAULT_TOLERANCE);
    }

    /**
     * Render a scene and assert it matches the reference image.
     * 
     * @param testName name of the test (used for file naming)
     * @param drawCall the rendering code to execute
     * @param tolerance maximum allowed difference ratio (0.0 to 1.0)
     * @throws AssertionError if the rendered image differs from reference beyond tolerance
     */
    public static void assertMatchesReference(String testName, Runnable drawCall, double tolerance) throws IOException {
        int[] actual = renderAndCapture(drawCall);

        String referencePath = REFERENCE_DIR + testName + "_baseline.png";
        String outputPath = OUTPUT_DIR + testName + "_actual.png";
        String diffPath = OUTPUT_DIR + testName + "_diff.png";

        int[] expected = loadReference(referencePath);

        if (expected == null) {
            // No reference exists - save current as baseline
            saveScreenshot(actual, referencePath);
            System.out.println("Created new baseline: " + referencePath);
            return;
        }

        double diff = compareImages(actual, expected);

        if (diff > tolerance) {
            // Save the actual output for debugging
            saveScreenshot(actual, outputPath);
            saveDiffImage(actual, expected, diffPath);

            throw new AssertionError(String.format(
                "Visual regression detected for '%s': %.2f%% pixels differ (tolerance: %.2f%%)\n" +
                "  Reference: %s\n" +
                "  Actual:    %s\n" +
                "  Diff:      %s",
                testName, diff * 100, tolerance * 100,
                referencePath, outputPath, diffPath
            ));
        }
    }

    /**
     * Generate and save a diff image highlighting pixel differences.
     */
    private static void saveDiffImage(int[] actual, int[] expected, String filePath) throws IOException {
        int[] diff = new int[actual.length];

        for (int i = 0; i < actual.length; i++) {
            if (!pixelsMatch(actual[i], expected[i])) {
                diff[i] = 0xFF0000; // Red for differences
            } else {
                // Dim the matching pixels
                int r = ((actual[i] >> 16) & 0xFF) / 4;
                int g = ((actual[i] >> 8) & 0xFF) / 4;
                int b = (actual[i] & 0xFF) / 4;
                diff[i] = (r << 16) | (g << 8) | b;
            }
        }

        saveScreenshot(diff, filePath);
    }

    /**
     * Update the baseline image for a test.
     * Use this when intentional visual changes are made.
     * 
     * @param testName name of the test
     * @param drawCall the rendering code to execute
     */
    public static void updateBaseline(String testName, Runnable drawCall) throws IOException {
        int[] pixels = renderAndCapture(drawCall);
        String referencePath = REFERENCE_DIR + testName + "_baseline.png";
        saveScreenshot(pixels, referencePath);
        System.out.println("Updated baseline: " + referencePath);
    }

    /**
     * Get the current render width.
     */
    public static int getWidth() {
        return width;
    }

    /**
     * Get the current render height.
     */
    public static int getHeight() {
        return height;
    }

    /**
     * Cleanup resources. Call when done with visual testing.
     */
    public static synchronized void cleanup() {
        if (initialized && window != NULL) {
            glfwDestroyWindow(window);
            glfwTerminate();
            initialized = false;
        }
    }

    private static void ensureDirectoryExists(String path) {
        Path dir = Paths.get(path);
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                System.err.println("Warning: Could not create directory: " + path);
            }
        }
    }
}
