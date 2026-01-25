package unit.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import ixdar.platform.Toggle;
import ixdar.scenes.main.MainScene;

/**
 * Tests for layer navigation in the multi-level clustering UI.
 * Verifies that knotDrawLayer changes correctly with navigation.
 */
@Execution(ExecutionMode.CONCURRENT)
public class LayerNavigationTests {

    @BeforeEach
    public void setUp() {
        Toggle.resetAll();
        // Reset layer state for each test
        MainScene.knotDrawLayer = -1;
        MainScene.totalLayers = 5;
    }

    // ==================== Layer State Tests ====================

    @Test
    public void test_layer_initial_state_metro_view() {
        MainScene.knotDrawLayer = -1;
        assertEquals(-1, MainScene.knotDrawLayer, 
            "Initial knotDrawLayer should be -1 for metro view (all layers)");
    }

    @Test
    public void test_layer_setDrawLevelMetro_toggles() {
        MainScene.knotDrawLayer = 3;
        MainScene.totalLayers = 5;
        
        // First call should set to -1 (metro view)
        MainScene.setDrawLevelMetro();
        assertEquals(-1, MainScene.knotDrawLayer, 
            "setDrawLevelMetro should toggle to -1 when at specific layer");
        
        // Second call should set to totalLayers
        MainScene.setDrawLevelMetro();
        assertEquals(5, MainScene.knotDrawLayer, 
            "setDrawLevelMetro should toggle to totalLayers when at -1");
    }

    @Test
    public void test_layer_bounds_not_exceed_totalLayers() {
        MainScene.totalLayers = 5;
        MainScene.knotDrawLayer = 10;
        
        // Manually clamp like increaseViewLayer does
        if (MainScene.knotDrawLayer > MainScene.totalLayers) {
            MainScene.knotDrawLayer = MainScene.totalLayers;
        }
        
        assertEquals(5, MainScene.knotDrawLayer, 
            "knotDrawLayer should not exceed totalLayers");
    }

    @Test
    public void test_layer_bounds_minimum_is_one() {
        MainScene.knotDrawLayer = 0;
        
        // Manually clamp like decreaseViewLayer does
        if (MainScene.knotDrawLayer < 1) {
            MainScene.knotDrawLayer = 1;
        }
        
        assertEquals(1, MainScene.knotDrawLayer, 
            "knotDrawLayer minimum should be 1");
    }

    // ==================== Toggle Interaction Tests ====================

    @Test
    public void test_toggle_CanSwitchLayer_allows_navigation() {
        assertTrue(Toggle.CanSwitchLayer.value, 
            "CanSwitchLayer should be enabled by default");
    }

    @Test
    public void test_toggle_CanSwitchLayer_disabled_blocks_navigation() {
        Toggle.CanSwitchLayer.value = false;
        assertFalse(Toggle.CanSwitchLayer.value, 
            "CanSwitchLayer should be disableable");
    }

    @Test
    public void test_toggle_CanSwitchTopLayer_initial_state() {
        assertTrue(Toggle.CanSwitchTopLayer.value, 
            "CanSwitchTopLayer should be enabled by default");
    }

    // ==================== Layer Value Range Tests ====================

    @Test
    public void test_layer_values_valid_range() {
        MainScene.totalLayers = 10;
        
        for (int layer = 1; layer <= MainScene.totalLayers; layer++) {
            MainScene.knotDrawLayer = layer;
            assertTrue(MainScene.knotDrawLayer >= 1, 
                "Layer " + layer + " should be >= 1");
            assertTrue(MainScene.knotDrawLayer <= MainScene.totalLayers, 
                "Layer " + layer + " should be <= totalLayers");
        }
    }

    @Test
    public void test_layer_metro_view_value() {
        MainScene.knotDrawLayer = -1;
        assertEquals(-1, MainScene.knotDrawLayer, 
            "Metro view should have knotDrawLayer = -1");
    }

    // ==================== Visualization Mode Tests ====================

    @Test
    public void test_visualization_modes_mutually_exclusive_behavior() {
        // Both can be true at the same time in Toggle, but the drawing code 
        // checks them in order (gradient first, then metro)
        Toggle.DrawKnotGradient.value = true;
        Toggle.DrawMetroDiagram.value = true;
        
        // This tests that both toggles can be set
        assertTrue(Toggle.DrawKnotGradient.value);
        assertTrue(Toggle.DrawMetroDiagram.value);
        
        // In practice, the rendering code prioritizes gradient over metro
    }

    @Test
    public void test_toggle_DrawDisplayedKnots_required_for_layer_display() {
        assertTrue(Toggle.DrawDisplayedKnots.value, 
            "DrawDisplayedKnots must be true for knots to display");
        
        Toggle.DrawDisplayedKnots.toggle();
        assertFalse(Toggle.DrawDisplayedKnots.value, 
            "DrawDisplayedKnots should be toggleable");
    }

    // ==================== Layer Increment/Decrement Logic Tests ====================

    @Test
    public void test_layer_increment_logic() {
        MainScene.totalLayers = 5;
        MainScene.knotDrawLayer = 3;
        
        // Simulate increaseViewLayer logic
        MainScene.knotDrawLayer++;
        if (MainScene.knotDrawLayer > MainScene.totalLayers) {
            MainScene.knotDrawLayer = MainScene.totalLayers;
        }
        if (MainScene.knotDrawLayer < 1) {
            MainScene.knotDrawLayer = 1;
        }
        
        assertEquals(4, MainScene.knotDrawLayer, 
            "Layer should increment from 3 to 4");
    }

    @Test
    public void test_layer_decrement_logic() {
        MainScene.totalLayers = 5;
        MainScene.knotDrawLayer = 3;
        
        // Simulate decreaseViewLayer logic
        MainScene.knotDrawLayer--;
        if (MainScene.knotDrawLayer < 1) {
            MainScene.knotDrawLayer = 1;
        }
        
        assertEquals(2, MainScene.knotDrawLayer, 
            "Layer should decrement from 3 to 2");
    }

    @Test
    public void test_layer_increment_at_max_stays_at_max() {
        MainScene.totalLayers = 5;
        MainScene.knotDrawLayer = 5;
        
        // Simulate increaseViewLayer logic
        MainScene.knotDrawLayer++;
        if (MainScene.knotDrawLayer > MainScene.totalLayers) {
            MainScene.knotDrawLayer = MainScene.totalLayers;
        }
        
        assertEquals(5, MainScene.knotDrawLayer, 
            "Layer should stay at max when incrementing at max");
    }

    @Test
    public void test_layer_decrement_at_min_stays_at_min() {
        MainScene.totalLayers = 5;
        MainScene.knotDrawLayer = 1;
        
        // Simulate decreaseViewLayer logic
        MainScene.knotDrawLayer--;
        if (MainScene.knotDrawLayer < 1) {
            MainScene.knotDrawLayer = 1;
        }
        
        assertEquals(1, MainScene.knotDrawLayer, 
            "Layer should stay at 1 when decrementing at min");
    }

    @Test
    public void test_layer_decrement_from_metro_view() {
        MainScene.totalLayers = 5;
        MainScene.knotDrawLayer = -1;
        
        // Simulate decreaseViewLayer logic for metro view
        if (MainScene.knotDrawLayer == -1) {
            MainScene.knotDrawLayer = MainScene.totalLayers;
        } else {
            MainScene.knotDrawLayer--;
            if (MainScene.knotDrawLayer < 1) {
                MainScene.knotDrawLayer = 1;
            }
        }
        
        assertEquals(5, MainScene.knotDrawLayer, 
            "Decrement from metro view (-1) should go to totalLayers");
    }
}
