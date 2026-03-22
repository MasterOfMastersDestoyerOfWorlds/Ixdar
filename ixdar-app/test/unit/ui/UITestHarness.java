package unit.ui;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.util.function.IntFunction;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWErrorCallback;

import ixdar.canvas.Canvas3D;
import ixdar.canvas.IxdarWindow;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.menu.MenuItem;
import ixdar.platform.Platforms;
import ixdar.platform.Toggle;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.lwjgl.LwjglGL;
import ixdar.platform.gl.lwjgl.LwjglPlatform;
import ixdar.scenes.trade.TradeScene;

/**
 * UI testing harness for menu navigation and interaction tests. Creates a real
 * GLFW window with OpenGL context and initializes real Canvas3D and MenuBox
 * instances for testing actual UI behavior.
 * 
 * Usage: - Call init() in @BeforeAll to set up the test environment - Call
 * resetState() in @BeforeEach to reset static state between tests - Use
 * clickMenuItem() to simulate menu clicks - Call cleanup() in @AfterAll to
 * release resources
 */
public class UITestHarness {

    private static boolean initialized = false;
    private static long window;
    private static int width = 800;
    private static int height = 600;

    private static Canvas3D canvas;
    private static LwjglPlatform platform;

    /**
     * Initialize the UI testing platform with a hidden OpenGL window. Creates real
     * Canvas3D and MenuBox instances. Safe to call multiple times - only
     * initializes once.
     */
    public static synchronized void init() {
        init(800, 600);
    }

    /**
     * Initialize the UI testing platform with specified dimensions.
     * 
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

        window = glfwCreateWindow(width, height, "UI Test Harness", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create the GLFW window for UI testing");
        }

        glfwMakeContextCurrent(window);

        // Set the window handle for IxdarWindow (used by TradeScene for title changes)
        IxdarWindow.window = window;

        // Initialize platform with window handle
        platform = new LwjglPlatform(window);
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

        // Create the canvas - this sets Canvas3D.instance
        canvas = new Canvas3D();
        canvas.initGL();

        // Initialize the menu by calling drawScene which creates MenuBox if null
        canvas.drawScene();

        // Ensure menu items are loaded and textures are ready
        waitForTexturesLoaded();

        initialized = true;
    }

    /**
     * Wait for menu textures to load so dimensions can be calculated. Polls until
     * textures are available or timeout.
     */
    private static void waitForTexturesLoaded() {
        if (canvas.menu == null) {
            return;
        }

        int maxAttempts = 100;
        int attempts = 0;

        while (attempts < maxAttempts) {
            // Draw to trigger texture loading
            canvas.drawScene();

            // Check if dimensions are now available
            if (canvas.menu.getItemHeight() > 0 && canvas.menu.getItemWidth() > 0) {
                return;
            }

            attempts++;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.err.println("Warning: Menu textures may not be fully loaded after " + maxAttempts + " attempts");
    }

    /**
     * Reset all relevant static state between tests. Call this in @BeforeEach.
     */
    public static void resetState() {
        TradeScene.active = false;
        TradeScene.instance = null;
        MenuBox.menuVisible = true;
        MenuBox.scrollOffsetY = 0;

        // Re-activate canvas for menu input
        if (canvas != null) {
            canvas.activate(true);
        }

        // Ensure GameMode toggle is set for game menu
        Toggle.GameMode.value = true;
        MenuBox.refreshMenuForMode();
    }

    /**
     * Get the Canvas3D instance.
     */
    public static Canvas3D getCanvas() {
        return canvas;
    }

    /**
     * Get the MenuBox instance.
     */
    public static MenuBox getMenu() {
        return canvas != null ? canvas.menu : null;
    }

    /**
     * Calculate the screen position of a menu item by index.
     * 
     * @param index the menu item index (0-based)
     * @return float array [centerX, centerY] of the item position
     */
    public static float[] getMenuItemPosition(int index) {
        if (canvas == null || canvas.menu == null) {
            throw new IllegalStateException("UITestHarness not initialized");
        }

        float centerX = Platforms.get().getFrameBufferWidth() / 2f;
        float screenCenterY = Platforms.get().getFrameBufferHeight() / 2f;

        float itemHeight = canvas.menu.getItemHeight();
        float itemCenterY = screenCenterY - itemHeight - (itemHeight * index * 1.5f) - MenuBox.scrollOffsetY;

        return new float[] { centerX, itemCenterY };
    }

    /**
     * Find the index of a menu item by its label text.
     * 
     * @param labelText the text to search for (case-insensitive partial match)
     * @return the index of the menu item, or -1 if not found
     */
    public static int findMenuItemIndex(String labelText) {
        if (MenuBox.menuItems == null) {
            return -1;
        }

        String searchLower = labelText.toLowerCase();
        for (int i = 0; i < MenuBox.menuItems.size(); i++) {
            MenuItem item = MenuBox.menuItems.get(i);
            // Extract actual text from HyperString's strMap (line 0 contains the label)
            String itemText = getMenuItemText(item);
            if (itemText != null && itemText.toLowerCase().contains(searchLower)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Extract the display text from a MenuItem's HyperString label.
     */
    private static String getMenuItemText(MenuItem item) {
        if (item == null || item.itemString() == null) {
            return null;
        }
        // HyperString stores text content in strMap, with line 0 being the first line
        var strMap = item.itemString().strMap;
        if (strMap != null && strMap.containsKey(0)) {
            return strMap.get(0);
        }
        return null;
    }

    /**
     * Simulate a click on a menu item by index. Sets hover position and triggers
     * click.
     * 
     * @param index the menu item index (0-based)
     */
    public static void clickMenuItem(int index) {
        if (canvas == null || canvas.menu == null) {
            throw new IllegalStateException("UITestHarness not initialized");
        }

        if (index < 0 || index >= MenuBox.menuItems.size()) {
            throw new IllegalArgumentException("Menu item index out of bounds: " + index);
        }

        float[] pos = getMenuItemPosition(index);
        canvas.menu.setHover(pos[0], pos[1]);
        canvas.menu.click(pos[0], pos[1]);
    }

    /**
     * Simulate a click on a menu item by label text.
     * 
     * @param labelText the text to search for (case-insensitive partial match)
     * @throws IllegalArgumentException if no matching menu item is found
     */
    public static void clickMenuItemByName(String labelText) {
        int index = findMenuItemIndex(labelText);
        if (index < 0) {
            throw new IllegalArgumentException("Menu item not found: " + labelText);
        }
        clickMenuItem(index);
    }

    /**
     * Get the number of menu items currently displayed.
     */
    public static int getMenuItemCount() {
        return MenuBox.menuItems != null ? MenuBox.menuItems.size() : 0;
    }

    /**
     * Get the label text of a menu item.
     * 
     * @param index the menu item index (0-based)
     */
    public static String getMenuItemLabel(int index) {
        if (MenuBox.menuItems == null || index < 0 || index >= MenuBox.menuItems.size()) {
            return null;
        }
        return getMenuItemText(MenuBox.menuItems.get(index));
    }

    /**
     * Check if the harness is initialized.
     */
    public static boolean isInitialized() {
        return initialized;
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
     * Cleanup resources. Call in @AfterAll when done with UI testing.
     */
    public static synchronized void cleanup() {
        if (initialized && window != NULL) {
            canvas = null;
            Canvas3D.instance = null;
            glfwDestroyWindow(window);
            glfwTerminate();
            initialized = false;
        }
    }
}
