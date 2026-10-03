package unit.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.graphics.cameras.Camera3D;
import ixdar.platform.Toggle;
import ixdar.platform.input.Keys;
import ixdar.platform.input.OrbitCameraKeyGuy;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.model.ControlHint;

/**
 * While the scene terminal has focus every key belongs to it: no scene control fires except the
 * terminal toggle, and once the terminal closes each control fires again.
 */
class OrbitCameraKeyGuyTerminalFocusTest {

    private static final String TOGGLE_TERMINAL = "toggle terminal";

    private static final int[] CONTROL_KEYS = {Keys.R, Keys.T, Keys.I, Keys.S, Keys.N, Keys.C,
        Keys.X, Keys.ENTER, Keys.BACKSPACE, Keys.ESCAPE};

    private static final boolean[] CONTROL_HELD = {true, true, true, true, false, false, false,
        false, false, false};

    private static final List<String> CONTROL_LABELS = List.of("ctrl+R", "ctrl+T", "ctrl+I",
            "ctrl+S", "N", "C", "X", "enter", "backspace", "esc");

    private final List<String> fired = new ArrayList<>();

    private boolean terminalFocusBefore;

    private OrbitCameraKeyGuy keyGuy;

    /**
     * Bind the ring-editing scene's controls (the plain letters, Enter, Backspace, Esc, the
     * Control chords and the terminal toggle) to a key handler that records what fires.
     */
    @BeforeEach
    void bindSceneControls() {
        terminalFocusBefore = Toggle.IsTerminalFocused.value;
        List<ControlHint> controls = new ArrayList<>();
        controls.add(new ControlHint(Keys.GRAVE, "~", TOGGLE_TERMINAL, () -> {
            fired.add(TOGGLE_TERMINAL);
            Toggle.IsTerminalFocused.value = !Toggle.IsTerminalFocused.value;
        }));
        for (int index = 0; index < CONTROL_KEYS.length; index++) {
            String label = CONTROL_LABELS.get(index);
            controls.add(new ControlHint(CONTROL_KEYS[index], CONTROL_HELD[index], label, label,
                    () -> fired.add(label)));
        }
        OrbitMouseTrap orbit = new OrbitMouseTrap(new Camera3D(new Vector3f(), 0f, 0f, null), null);
        keyGuy = new OrbitCameraKeyGuy(orbit, null, null, controls);
        keyGuy.controlRResetsTarget = false;
    }

    /**
     * Put the global terminal-focus toggle back the way the suite had it.
     */
    @AfterEach
    void restoreTerminalFocus() {
        Toggle.IsTerminalFocused.value = terminalFocusBefore;
    }

    @Test
    void noSceneControlFiresWhileTheTerminalHasFocus() {
        Toggle.IsTerminalFocused.value = true;
        pressEveryControlKey();
        assertEquals(List.of(), fired);
        assertTrue(Toggle.IsTerminalFocused.value);
    }

    @Test
    void theToggleKeyStillClosesAndReopensTheTerminal() {
        Toggle.IsTerminalFocused.value = true;
        press(Keys.GRAVE, 0);
        assertFalse(Toggle.IsTerminalFocused.value);
        press(Keys.GRAVE, 0);
        assertTrue(Toggle.IsTerminalFocused.value);
        assertEquals(List.of(TOGGLE_TERMINAL, TOGGLE_TERMINAL), fired);
    }

    @Test
    void everyControlFiresAgainOnceTheTerminalCloses() {
        Toggle.IsTerminalFocused.value = true;
        press(Keys.GRAVE, 0);
        fired.clear();
        pressEveryControlKey();
        assertEquals(CONTROL_LABELS, fired);
    }

    /**
     * Press each bound key once, the Control chords with Control held.
     */
    private void pressEveryControlKey() {
        for (int index = 0; index < CONTROL_KEYS.length; index++) {
            press(CONTROL_KEYS[index], CONTROL_HELD[index] ? OrbitCameraKeyGuy.MOD_CONTROL : 0);
        }
    }

    /**
     * Deliver one key press to the handler's scene-control dispatch, the step of
     * {@code keyCallback} that fires controls; the rest forwards to the terminal, whose class
     * needs a GL context to load.
     *
     * @param key  key code
     * @param mods modifier bitmask
     */
    private void press(int key, int mods) {
        keyGuy.handleSceneKeys(key, mods);
    }
}
