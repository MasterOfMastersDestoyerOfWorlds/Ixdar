package unit.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;

import ixdar.canvas.Canvas3D;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.scenes.trade.TradeScene;

/**
 * Tests for menu navigation flow using real UI components. Uses UITestHarness
 * to create a real GLFW window with Canvas3D and MenuBox, then simulates clicks
 * at actual screen coordinates.
 * 
 * Key behavior: When returning to menu via Escape, TradeScene.returnToMenu()
 * must call canvas.activate(true) to restore Canvas3D's input handlers so that
 * menu button clicks work properly.
 */
@Tag("visual")
@TestMethodOrder(MethodOrderer.MethodName.class)
public class MenuNavigationTests {

    @BeforeAll
    public static void initHarness() {
        UITestHarness.init(800, 600);
    }

    @AfterAll
    public static void cleanupHarness() {
        UITestHarness.cleanup();
    }

    @BeforeEach
    public void setUp() {
        UITestHarness.resetState();
    }

    // ==================== Menu State Tests ====================

    @Test
    public void test_01_menu_initial_state() {
        assertTrue(MenuBox.menuVisible,
                "Menu should be visible initially");
        assertFalse(TradeScene.active,
                "TradeScene should not be active initially");
        assertTrue(UITestHarness.getCanvas().active,
                "Canvas should be active initially for menu input");
    }

    @Test
    public void test_02_menu_has_start_new_game_option() {
        int index = UITestHarness.findMenuItemIndex("Start New Game");
        assertTrue(index >= 0,
                "Menu should have 'Start New Game' option");
    }

    @Test
    public void test_03_clickStartNewGame_activates_TradeScene() {
        // Verify initial state
        assertTrue(MenuBox.menuVisible, "Menu should be visible before clicking");
        assertFalse(TradeScene.active, "TradeScene should not be active before clicking");

        // Click "Start New Game" menu item
        UITestHarness.clickMenuItemByName("Start New Game");

        // Verify TradeScene is now active and menu is hidden
        assertTrue(TradeScene.active,
                "TradeScene should be active after clicking 'Start New Game'");
        assertFalse(MenuBox.menuVisible,
                "Menu should be hidden after starting game");
        assertNotNull(TradeScene.instance,
                "TradeScene.instance should be set after starting game");
    }

    @Test
    public void test_04_returnToMenu_restores_menu_visibility() {
        // Start a game first
        UITestHarness.clickMenuItemByName("Start New Game");

        // Verify game started
        assertTrue(TradeScene.active, "TradeScene should be active");
        assertFalse(MenuBox.menuVisible, "Menu should be hidden during game");

        // Return to menu
        TradeScene.instance.returnToMenu();

        // Verify menu is visible again
        assertTrue(MenuBox.menuVisible,
                "Menu should be visible after returnToMenu()");
        assertFalse(TradeScene.active,
                "TradeScene should be inactive after returnToMenu()");
    }

    @Test
    public void test_05_returnToMenu_restores_canvas_active_state() {
        // Start a game first
        UITestHarness.clickMenuItemByName("Start New Game");

        // Verify canvas is inactive during game (TradeScene has its own handlers)
        Canvas3D canvas = UITestHarness.getCanvas();
        assertFalse(canvas.active,
                "Canvas should be inactive during game (TradeScene handles input)");

        // Return to menu
        TradeScene.instance.returnToMenu();

        // Canvas must be reactivated for menu clicks to work
        assertTrue(canvas.active,
                "Canvas should be active after returnToMenu() to handle menu clicks");
    }

    @Test
    public void test_06_full_navigation_cycle_start_escape_start() {
        // This test verifies the complete flow:
        // 1. Start game -> TradeScene active, menu hidden
        // 2. Exit game -> menu shown, canvas reactivated
        // 3. Start game again -> should work because canvas handles input

        Canvas3D canvas = UITestHarness.getCanvas();

        // Step 1: Click "Start New Game"
        UITestHarness.clickMenuItemByName("Start New Game");

        assertFalse(MenuBox.menuVisible, "Menu should be hidden during game");
        assertTrue(TradeScene.active, "TradeScene should be active during game");
        assertFalse(canvas.active, "Canvas should be inactive during game");
        TradeScene firstGame = TradeScene.instance;
        assertNotNull(firstGame, "First game instance should exist");

        // Step 2: Return to menu (simulates pressing Escape)
        firstGame.returnToMenu();

        assertTrue(MenuBox.menuVisible, "Menu should be visible after escape");
        assertFalse(TradeScene.active, "TradeScene should be inactive after escape");
        assertTrue(canvas.active,
                "Canvas should be active after escape to handle Start New Game button");

        // Step 3: Start a new game again
        // This would fail in the real app if canvas wasn't properly reactivated
        UITestHarness.clickMenuItemByName("Start New Game");

        assertTrue(TradeScene.active, "Second game should be able to start");
        assertFalse(MenuBox.menuVisible, "Menu should be hidden during second game");
        assertNotNull(TradeScene.instance, "Second game instance should exist");
    }

    @Test
    public void test_07_multiple_start_escape_cycles() {
        // Test that we can start and exit multiple times
        Canvas3D canvas = UITestHarness.getCanvas();

        for (int i = 0; i < 3; i++) {
            // Start game
            UITestHarness.clickMenuItemByName("Start New Game");
            assertTrue(TradeScene.active, "Game " + (i + 1) + " should start");

            // Return to menu
            TradeScene.instance.returnToMenu();
            assertTrue(MenuBox.menuVisible, "Menu should be visible after game " + (i + 1));
            assertTrue(canvas.active, "Canvas should be active after game " + (i + 1));
        }
    }
}
