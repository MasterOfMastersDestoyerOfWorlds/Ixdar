package unit.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import ixdar.platform.Toggle;
import ixdar.platform.input.KeyActions;
import ixdar.platform.input.Keys;

/**
 * Tests for multi-level clustering UI functionality.
 * Verifies Toggle states, KeyActions, and layer navigation.
 */
@Execution(ExecutionMode.CONCURRENT)
public class ClusteringUITests {

    @BeforeEach
    public void setUp() {
        Toggle.resetAll();
    }

    // ==================== Toggle Tests ====================

    @Test
    public void test_toggle_DrawMetroDiagram_initial_state() {
        assertTrue(Toggle.DrawMetroDiagram.value, "DrawMetroDiagram should be enabled by default");
    }

    @Test
    public void test_toggle_DrawKnotGradient_initial_state() {
        assertTrue(Toggle.DrawKnotGradient.value, "DrawKnotGradient should be enabled by default");
    }

    @Test
    public void test_toggle_DrawGridLines_initial_state() {
        assertFalse(Toggle.DrawGridLines.value, "DrawGridLines should be disabled by default");
    }

    @Test
    public void test_toggle_DrawMainPath_initial_state() {
        assertFalse(Toggle.DrawMainPath.value, "DrawMainPath should be disabled by default");
    }

    @Test
    public void test_toggle_DrawDisplayedKnots_initial_state() {
        assertTrue(Toggle.DrawDisplayedKnots.value, "DrawDisplayedKnots should be enabled by default");
    }

    @Test
    public void test_toggle_CanSwitchLayer_initial_state() {
        assertTrue(Toggle.CanSwitchLayer.value, "CanSwitchLayer should be enabled by default");
    }

    @Test
    public void test_toggle_flip() {
        boolean initial = Toggle.DrawMetroDiagram.value;
        Toggle.DrawMetroDiagram.toggle();
        assertEquals(!initial, Toggle.DrawMetroDiagram.value, "Toggle should flip value");
        Toggle.DrawMetroDiagram.toggle();
        assertEquals(initial, Toggle.DrawMetroDiagram.value, "Toggle should flip back to original");
    }

    @Test
    public void test_toggle_DrawKnotGradient_flip() {
        boolean initial = Toggle.DrawKnotGradient.value;
        Toggle.DrawKnotGradient.toggle();
        assertEquals(!initial, Toggle.DrawKnotGradient.value, "DrawKnotGradient toggle should flip value");
    }

    @Test
    public void test_toggle_DrawGridLines_flip() {
        boolean initial = Toggle.DrawGridLines.value;
        Toggle.DrawGridLines.toggle();
        assertEquals(!initial, Toggle.DrawGridLines.value, "DrawGridLines toggle should flip value");
    }

    @Test
    public void test_toggle_resetAll() {
        Toggle.DrawMetroDiagram.toggle();
        Toggle.DrawKnotGradient.toggle();
        Toggle.DrawGridLines.toggle();
        
        Toggle.resetAll();
        
        assertTrue(Toggle.DrawMetroDiagram.value, "DrawMetroDiagram should reset to true");
        assertTrue(Toggle.DrawKnotGradient.value, "DrawKnotGradient should reset to true");
        assertFalse(Toggle.DrawGridLines.value, "DrawGridLines should reset to false");
    }

    @Test
    public void test_toggle_shortNames_unique() {
        Set<String> shortNames = new HashSet<>();
        for (Toggle t : Toggle.values()) {
            assertFalse(shortNames.contains(t.shortName()), 
                "Toggle short name '" + t.shortName() + "' should be unique");
            shortNames.add(t.shortName());
        }
    }

    // ==================== KeyActions Tests ====================

    @Test
    public void test_keyAction_DrawMetroDiagram_key() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.M);
        assertTrue(KeyActions.DrawMetroDiagram.keyPressed(pressedKeys), 
            "M key should trigger DrawMetroDiagram");
    }

    @Test
    public void test_keyAction_DrawKnotGradient_key() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.Y);
        assertTrue(KeyActions.DrawKnotGradient.keyPressed(pressedKeys), 
            "Y key should trigger DrawKnotGradient");
    }

    @Test
    public void test_keyAction_DrawGridLines_key() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.G);
        assertTrue(KeyActions.DrawGridLines.keyPressed(pressedKeys), 
            "G key should trigger DrawGridLines");
    }

    @Test
    public void test_keyAction_DrawOriginal_key() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.O);
        assertTrue(KeyActions.DrawOriginal.keyPressed(pressedKeys), 
            "O key should trigger DrawOriginal (DrawMainPath)");
    }

    @Test
    public void test_keyAction_IncreaseKnotLayer_rightBracket() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.RIGHT_BRACKET);
        assertTrue(KeyActions.IncreaseKnotLayer.keyPressed(pressedKeys), 
            "] key should trigger IncreaseKnotLayer");
    }

    @Test
    public void test_keyAction_IncreaseKnotLayer_upArrow() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.UP);
        assertTrue(KeyActions.IncreaseKnotLayer.keyPressed(pressedKeys), 
            "Up arrow should trigger IncreaseKnotLayer");
    }

    @Test
    public void test_keyAction_DecreaseKnotLayer_leftBracket() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.LEFT_BRACKET);
        assertTrue(KeyActions.DecreaseKnotLayer.keyPressed(pressedKeys), 
            "[ key should trigger DecreaseKnotLayer");
    }

    @Test
    public void test_keyAction_DecreaseKnotLayer_downArrow() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.DOWN);
        assertTrue(KeyActions.DecreaseKnotLayer.keyPressed(pressedKeys), 
            "Down arrow should trigger DecreaseKnotLayer");
    }

    @Test
    public void test_keyAction_ColorRandomization_key() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.C);
        assertTrue(KeyActions.ColorRandomization.keyPressed(pressedKeys), 
            "C key should trigger ColorRandomization");
    }

    @Test
    public void test_keyAction_Reset_key() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.R);
        assertTrue(KeyActions.Reset.keyPressed(pressedKeys), 
            "R key should trigger Reset");
    }

    @Test
    public void test_keyAction_movement_WASD() {
        Set<Integer> pressedKeys = new HashSet<>();
        
        pressedKeys.add(Keys.W);
        assertTrue(KeyActions.MoveUp.keyPressed(pressedKeys), "W should trigger MoveUp");
        pressedKeys.clear();
        
        pressedKeys.add(Keys.A);
        assertTrue(KeyActions.MoveLeft.keyPressed(pressedKeys), "A should trigger MoveLeft");
        pressedKeys.clear();
        
        pressedKeys.add(Keys.S);
        assertTrue(KeyActions.MoveDown.keyPressed(pressedKeys), "S should trigger MoveDown");
        pressedKeys.clear();
        
        pressedKeys.add(Keys.D);
        assertTrue(KeyActions.MoveRight.keyPressed(pressedKeys), "D should trigger MoveRight");
    }

    @Test
    public void test_keyAction_zoom() {
        Set<Integer> pressedKeys = new HashSet<>();
        
        pressedKeys.add(Keys.EQUAL);
        assertTrue(KeyActions.ZoomIn.keyPressed(pressedKeys), "= key should trigger ZoomIn");
        pressedKeys.clear();
        
        pressedKeys.add(Keys.MINUS);
        assertTrue(KeyActions.ZoomOut.keyPressed(pressedKeys), "- key should trigger ZoomOut");
    }

    @Test
    public void test_keyAction_confirm_enter() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.ENTER);
        assertTrue(KeyActions.Confirm.keyPressed(pressedKeys), 
            "Enter key should trigger Confirm");
    }

    @Test
    public void test_keyAction_back_escape() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.ESCAPE);
        assertTrue(KeyActions.Back.keyPressed(pressedKeys), 
            "Escape key should trigger Back");
    }

    @Test
    public void test_keyAction_cycleTools() {
        Set<Integer> pressedKeys = new HashSet<>();
        
        pressedKeys.add(Keys.LEFT);
        assertTrue(KeyActions.CycleToolLeft.keyPressed(pressedKeys), 
            "Left arrow should trigger CycleToolLeft");
        pressedKeys.clear();
        
        pressedKeys.add(Keys.RIGHT);
        assertTrue(KeyActions.CycleToolRight.keyPressed(pressedKeys), 
            "Right arrow should trigger CycleToolRight");
    }

    @Test
    public void test_keyAction_doubleSpeed_shift() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.LEFT_SHIFT);
        assertTrue(KeyActions.DoubleSpeed.keyPressed(pressedKeys), 
            "Left Shift should trigger DoubleSpeed");
    }

    @Test
    public void test_keyAction_controlMask_blocks_non_ctrl_actions() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.LEFT_CONTROL);
        pressedKeys.add(Keys.M);
        
        // When control is pressed, non-control actions should not trigger
        assertFalse(KeyActions.DrawMetroDiagram.keyPressed(pressedKeys), 
            "DrawMetroDiagram should NOT trigger when Ctrl is held");
    }

    @Test
    public void test_keyAction_ctrl_save() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.LEFT_CONTROL);
        pressedKeys.add(Keys.S);
        
        assertTrue(KeyActions.Save.keyPressed(pressedKeys), 
            "Ctrl+S should trigger Save");
    }

    @Test
    public void test_keyAction_wrong_key_no_trigger() {
        Set<Integer> pressedKeys = new HashSet<>();
        pressedKeys.add(Keys.X);
        
        assertFalse(KeyActions.DrawMetroDiagram.keyPressed(pressedKeys), 
            "X key should NOT trigger DrawMetroDiagram");
        assertFalse(KeyActions.DrawKnotGradient.keyPressed(pressedKeys), 
            "X key should NOT trigger DrawKnotGradient");
    }

    @Test
    public void test_keyAction_empty_keys_no_trigger() {
        Set<Integer> pressedKeys = new HashSet<>();
        
        assertFalse(KeyActions.DrawMetroDiagram.keyPressed(pressedKeys), 
            "Empty key set should NOT trigger DrawMetroDiagram");
    }

    // ==================== KeyAction toString Tests ====================

    @Test
    public void test_keyAction_toString_format() {
        String moveUp = KeyActions.MoveUp.toString();
        assertTrue(moveUp.contains("MoveUp"), "toString should contain action name");
        assertTrue(moveUp.contains("W"), "toString should contain key name");
    }

    @Test
    public void test_keyAction_ctrl_toString_format() {
        String save = KeyActions.Save.toString();
        assertTrue(save.contains("Ctrl"), "Ctrl action toString should contain 'Ctrl'");
        assertTrue(save.contains("Save"), "toString should contain action name");
    }
}
