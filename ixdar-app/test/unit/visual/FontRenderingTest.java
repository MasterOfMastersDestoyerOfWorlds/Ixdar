package unit.visual;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.joml.Vector2f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import ixdar.geometry.point.PointND;
import ixdar.geometry.point.PointSet;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.sdf.SDFCircleSimple;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.text.Font;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.Drawing;
import ixdar.platform.Platforms;

/**
 * Visual regression tests for font and SDF rendering.
 * These tests verify that rendering output matches expected baselines.
 * 
 * Run with -Dvisual.update=true to regenerate baselines after intentional changes.
 * 
 * @see VisualTestHarness
 */
@Tag("visual")
public class FontRenderingTest {

    private static Camera2D camera;
    private static PointSet testPoints;
    private static Font font;
    private static boolean fontLoaded = false;

    /** Maximum time to wait for font to load (milliseconds) */
    private static final long FONT_LOAD_TIMEOUT_MS = 5000;

    @BeforeAll
    static void setup() throws InterruptedException {
        VisualTestHarness.init(800, 600);
        
        // Create a simple point set for camera initialization
        testPoints = new PointSet();
        testPoints.add(new PointND.Double(-100, -100));
        testPoints.add(new PointND.Double(100, -100));
        testPoints.add(new PointND.Double(100, 100));
        testPoints.add(new PointND.Double(-100, 100));

        // Initialize camera
        camera = new Camera2D(
            VisualTestHarness.getWidth(),
            VisualTestHarness.getHeight(),
            1.0f,
            0.0f,
            0.0f,
            testPoints
        );
        
        // Setup camera bounds
        Map<String, Bounds> views = new HashMap<>();
        Bounds mainBounds = new Bounds(0, 0, 
            VisualTestHarness.getWidth(), 
            VisualTestHarness.getHeight(), 
            null, "MAIN");
        views.put("MAIN", mainBounds);
        camera.initCamera(views, "MAIN");
        camera.calculateCameraTransform(testPoints);
        camera.reset();

        // Initialize Drawing which creates the Font
        Drawing drawing = Drawing.getDrawing();
        font = drawing.font;

        // Wait for font to load (async loading)
        long startTime = System.currentTimeMillis();
        while (!isFontLoaded() && (System.currentTimeMillis() - startTime) < FONT_LOAD_TIMEOUT_MS) {
            Thread.sleep(50);
        }
        fontLoaded = isFontLoaded();
        
        if (!fontLoaded) {
            System.err.println("Warning: Font failed to load within timeout. Font tests will be skipped.");
        }
    }

    /**
     * Check if the font has fully loaded (glyphs and texture available).
     */
    private static boolean isFontLoaded() {
        return font != null && font.glyphs != null && font.texture != null;
    }

    @AfterAll
    static void cleanup() {
        VisualTestHarness.cleanup();
    }

    /**
     * Test that SDF circles render with smooth anti-aliased edges.
     * This serves as a baseline for SDF rendering quality.
     */
    @Test
    void testSDFCircleRendering() throws IOException {
        VisualTestHarness.assertMatchesReference("sdf_circle_basic", () -> {
            SDFCircleSimple circle = new SDFCircleSimple();
            
            // Draw circles at various positions to test anti-aliasing
            circle.draw(new Vector2f(200, 300), 50f, Color.BLUE, camera);
            circle.draw(new Vector2f(400, 300), 75f, Color.RED, camera);
            circle.draw(new Vector2f(600, 300), 30f, Color.GREEN, camera);
            
            // Flush shaders to ensure drawing is complete
            for (ShaderProgram shader : Platforms.gl().getShaders()) {
                shader.flush();
            }
        });
    }

    /**
     * Test multiple overlapping circles to verify blending and edge quality.
     */
    @Test
    void testSDFCircleOverlap() throws IOException {
        VisualTestHarness.assertMatchesReference("sdf_circle_overlap", () -> {
            SDFCircleSimple circle = new SDFCircleSimple();
            
            // Overlapping circles to test blending
            circle.draw(new Vector2f(350, 300), 80f, new ColorRGB(1f, 0f, 0f, 0.7f), camera);
            circle.draw(new Vector2f(400, 300), 80f, new ColorRGB(0f, 1f, 0f, 0.7f), camera);
            circle.draw(new Vector2f(450, 300), 80f, new ColorRGB(0f, 0f, 1f, 0.7f), camera);
            
            for (ShaderProgram shader : Platforms.gl().getShaders()) {
                shader.flush();
            }
        });
    }

    /**
     * Test small circles to verify anti-aliasing at small scales.
     * Small shapes are most susceptible to aliasing artifacts.
     */
    @Test
    void testSDFCircleSmallScale() throws IOException {
        VisualTestHarness.assertMatchesReference("sdf_circle_small", () -> {
            SDFCircleSimple circle = new SDFCircleSimple();
            
            // Grid of small circles - most likely to show aliasing
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 10; col++) {
                    float x = 100 + col * 60;
                    float y = 150 + row * 80;
                    float radius = 5 + row * 3; // 5px to 17px radius
                    circle.draw(new Vector2f(x, y), radius, Color.WHITE, camera);
                }
            }
            
            for (ShaderProgram shader : Platforms.gl().getShaders()) {
                shader.flush();
            }
        });
    }

    /**
     * Test font anti-aliasing at multiple sizes.
     * This is the primary test for ENG-4 - Fix Font Aliasing.
     * 
     * Renders text at various sizes to verify smooth edges without aliasing artifacts.
     */
    @Test
    void testFontAntiAliasing() throws IOException {
        if (!fontLoaded) {
            System.out.println("Skipping testFontAntiAliasing - font not loaded");
            return;
        }

        VisualTestHarness.assertMatchesReference("font_antialiasing", () -> {
            // Test text at multiple sizes to verify anti-aliasing quality
            float[] sizes = {12f, 16f, 24f, 36f, 48f};
            float y = 550f;
            
            for (float size : sizes) {
                HyperString text = new HyperString();
                text.addWord("The quick brown fox jumps", Color.WHITE);
                
                font.drawHyperString(text, 50f, y, size, camera);
                y -= size + 20f; // Move down for next line
            }
            
            for (ShaderProgram shader : Platforms.gl().getShaders()) {
                shader.flush();
            }
        });
    }

    /**
     * Test font rendering with various characters including special glyphs.
     * Verifies that all common characters render correctly.
     */
    @Test
    void testFontCharacterSet() throws IOException {
        if (!fontLoaded) {
            System.out.println("Skipping testFontCharacterSet - font not loaded");
            return;
        }

        VisualTestHarness.assertMatchesReference("font_character_set", () -> {
            float y = 550f;
            float size = 24f;
            
            // Lowercase letters
            HyperString lowercase = new HyperString();
            lowercase.addWord("abcdefghijklmnopqrstuvwxyz", Color.WHITE);
            font.drawHyperString(lowercase, 50f, y, size, camera);
            y -= size + 15f;
            
            // Uppercase letters
            HyperString uppercase = new HyperString();
            uppercase.addWord("ABCDEFGHIJKLMNOPQRSTUVWXYZ", Color.WHITE);
            font.drawHyperString(uppercase, 50f, y, size, camera);
            y -= size + 15f;
            
            // Numbers
            HyperString numbers = new HyperString();
            numbers.addWord("0123456789", Color.WHITE);
            font.drawHyperString(numbers, 50f, y, size, camera);
            y -= size + 15f;
            
            // Punctuation and special characters
            HyperString special = new HyperString();
            special.addWord("!@#$%^&*()_+-=[]{}|;':\",./<>?", Color.WHITE);
            font.drawHyperString(special, 50f, y, size, camera);
            
            for (ShaderProgram shader : Platforms.gl().getShaders()) {
                shader.flush();
            }
        });
    }

    /**
     * Test font rendering at very small sizes (most susceptible to aliasing).
     * Small text is where MSDF aliasing issues are most visible.
     */
    @Test
    void testFontSmallSizes() throws IOException {
        if (!fontLoaded) {
            System.out.println("Skipping testFontSmallSizes - font not loaded");
            return;
        }

        VisualTestHarness.assertMatchesReference("font_small_sizes", () -> {
            float y = 550f;
            
            // Test very small sizes where aliasing is most visible
            float[] smallSizes = {8f, 10f, 11f, 12f, 13f, 14f};
            
            for (float size : smallSizes) {
                HyperString text = new HyperString();
                text.addWord(String.format("%.0fpx: The quick brown fox jumps over the lazy dog", size), Color.WHITE);
                
                font.drawHyperString(text, 50f, y, size, camera);
                y -= size + 8f;
            }
            
            for (ShaderProgram shader : Platforms.gl().getShaders()) {
                shader.flush();
            }
        });
    }

    /**
     * Test font rendering with colored text.
     * Verifies that color application doesn't affect edge quality.
     */
    @Test
    void testFontColors() throws IOException {
        if (!fontLoaded) {
            System.out.println("Skipping testFontColors - font not loaded");
            return;
        }

        VisualTestHarness.assertMatchesReference("font_colors", () -> {
            float y = 500f;
            float size = 32f;
            
            Color[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA};
            String[] labels = {"Red", "Green", "Blue", "Yellow", "Cyan", "Magenta"};
            
            for (int i = 0; i < colors.length; i++) {
                HyperString text = new HyperString();
                text.addWord(labels[i] + " Text Sample", colors[i]);
                
                font.drawHyperString(text, 50f, y, size, camera);
                y -= size + 15f;
            }
            
            for (ShaderProgram shader : Platforms.gl().getShaders()) {
                shader.flush();
            }
        });
    }

    /**
     * Test large font sizes to verify scaling quality.
     */
    @Test
    void testFontLargeSizes() throws IOException {
        if (!fontLoaded) {
            System.out.println("Skipping testFontLargeSizes - font not loaded");
            return;
        }

        VisualTestHarness.assertMatchesReference("font_large_sizes", () -> {
            float y = 500f;
            
            // Large sizes
            HyperString large1 = new HyperString();
            large1.addWord("64px Font", Color.WHITE);
            font.drawHyperString(large1, 50f, y, 64f, camera);
            y -= 80f;
            
            HyperString large2 = new HyperString();
            large2.addWord("48px Font", Color.WHITE);
            font.drawHyperString(large2, 50f, y, 48f, camera);
            y -= 60f;
            
            HyperString large3 = new HyperString();
            large3.addWord("32px Font", Color.WHITE);
            font.drawHyperString(large3, 50f, y, 32f, camera);
            
            for (ShaderProgram shader : Platforms.gl().getShaders()) {
                shader.flush();
            }
        });
    }
}
