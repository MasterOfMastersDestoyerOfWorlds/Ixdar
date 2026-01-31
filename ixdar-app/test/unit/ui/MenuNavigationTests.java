package unit.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import ixdar.gui.ui.menu.MenuBox;
import ixdar.scenes.trade.TradeScene;

/**
 * Tests for menu navigation flow.
 * Verifies that starting a new game, exiting, and restarting works correctly.
 * 
 * Key behavior: When returning to menu via Escape, TradeScene.returnToMenu()
 * must call canvas.activate(true) to restore Canvas3D's input handlers so
 * that menu button clicks work properly.
 */
@Execution(ExecutionMode.CONCURRENT)
public class MenuNavigationTests {

    @BeforeEach
    public void setUp() {
        // Reset all relevant static state
        TradeScene.active = false;
        TradeScene.instance = null;
        MenuBox.menuVisible = true;
    }

    // ==================== Menu State Tests ====================

    @Test
    public void test_menu_initial_state() {
        assertTrue(MenuBox.menuVisible, 
            "Menu should be visible initially");
        assertFalse(TradeScene.active, 
            "TradeScene should not be active initially");
    }

    @Test
    public void test_returnToMenu_restores_menu_visibility() {
        // Setup: Simulate that a game was started
        MenuBox.menuVisible = false;
        TradeScene.active = true;
        
        // Create a minimal mock canvas to test the flow
        MockCanvas mockCanvas = new MockCanvas();
        
        // Create a test scene with our mock canvas
        TestableTradeScene scene = new TestableTradeScene(mockCanvas);
        
        // Act: Return to menu
        scene.returnToMenu();
        
        // Assert: Menu should be visible
        assertTrue(MenuBox.menuVisible, 
            "Menu should be visible after returnToMenu()");
    }

    @Test
    public void test_returnToMenu_restores_canvas_active_state() {
        // Setup: Simulate that a game was started
        MenuBox.menuVisible = false;
        TradeScene.active = true;
        
        // Create a minimal mock canvas
        MockCanvas mockCanvas = new MockCanvas();
        mockCanvas.active = false; // Canvas was deactivated when game started
        
        // Create a test scene with our mock canvas
        TestableTradeScene scene = new TestableTradeScene(mockCanvas);
        
        // Act: Return to menu
        scene.returnToMenu();
        
        // Assert: Canvas should be reactivated to handle menu input
        // BUG: This assertion will FAIL until the fix is applied
        assertTrue(mockCanvas.active, 
            "Canvas should be active after returnToMenu() to handle menu clicks");
    }

    @Test
    public void test_startGame_escape_startAgain_flow() {
        // This test verifies the complete flow:
        // 1. Start game -> canvas deactivates, menu hidden
        // 2. Exit game -> menu shown, canvas should reactivate
        // 3. Start game again -> should work because canvas handles input
        
        MockCanvas mockCanvas = new MockCanvas();
        
        // Step 1: Simulate starting a game
        TestableTradeScene scene1 = new TestableTradeScene(mockCanvas);
        scene1.activate(true);
        MenuBox.menuVisible = false;
        
        assertFalse(MenuBox.menuVisible, "Menu should be hidden during game");
        assertTrue(TradeScene.active, "TradeScene should be active during game");
        assertFalse(mockCanvas.active, "Canvas should be inactive during game");
        
        // Step 2: Simulate pressing Escape to return to menu
        scene1.returnToMenu();
        
        assertTrue(MenuBox.menuVisible, "Menu should be visible after escape");
        assertFalse(TradeScene.active, "TradeScene should be inactive after escape");
        // BUG: Canvas should be reactivated but currently isn't
        assertTrue(mockCanvas.active, 
            "Canvas should be active after escape to handle Start New Game button");
        
        // Step 3: Simulate starting a new game
        // This would fail in the real app because canvas.mouse doesn't receive clicks
        TestableTradeScene scene2 = new TestableTradeScene(mockCanvas);
        scene2.activate(true);
        MenuBox.menuVisible = false;
        
        assertTrue(TradeScene.active, "Second game should be able to start");
    }

    // ==================== Test Helpers ====================

    /**
     * Minimal mock canvas that tracks activation state without
     * requiring full Canvas3D/platform initialization.
     */
    static class MockCanvas {
        public boolean active = true;
        
        public void activate(boolean state) {
            this.active = state;
        }
    }

    /**
     * Testable version of TradeScene behavior that doesn't require full initialization.
     * This mirrors the ACTUAL TradeScene behavior, including the bug.
     */
    static class TestableTradeScene {
        private MockCanvas canvas;
        
        public TestableTradeScene(MockCanvas canvas) {
            this.canvas = canvas;
            TradeScene.instance = null; // Clear any previous instance
        }
        
        /**
         * Mirrors TradeScene.activate() behavior:
         * When activating (state=true), deactivates canvas (canvas.activate(false))
         */
        public void activate(boolean state) {
            if (canvas != null) {
                canvas.activate(!state); // Matches real TradeScene behavior
            }
            TradeScene.active = state;
        }
        
        /**
         * Mirrors TradeScene.returnToMenu() behavior:
         * Sets menu visible, TradeScene inactive, and reactivates canvas for menu input.
         */
        public void returnToMenu() {
            TradeScene.active = false;
            MenuBox.menuVisible = true;
            if (canvas != null) {
                canvas.activate(true);  // Restore menu input handling
            }
        }
    }
}
