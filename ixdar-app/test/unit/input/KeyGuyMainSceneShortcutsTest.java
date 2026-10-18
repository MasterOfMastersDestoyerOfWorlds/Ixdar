package unit.input;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.gui.ui.menu.MenuBox;
import ixdar.scenes.Scene;
import ixdar.scenes.menu.MenuScene;
import ixdar.scenes.main.Toggle;
import ixdar.input.KeyGuy;
import ixdar.platform.input.KeyNames;
import ixdar.platform.input.Keys;
import ixdar.scenes.main.MainScene;

/**
 * A key handler driving an orbit camera ignores every shortcut that belongs to the tour editor
 * ({@link MainScene}): pressing one throws nothing, changes no toggle and leaves no key held.
 */
class KeyGuyMainSceneShortcutsTest {

    private static final int[] CONTROL_CHORDS = {Keys.B, Keys.N, Keys.S, Keys.Q};

    private static final int[] PLAIN_KEYS = {Keys.C, Keys.B, Keys.Y, Keys.M, Keys.RIGHT_BRACKET,
        Keys.UP, Keys.LEFT_BRACKET, Keys.DOWN, Keys.O, Keys.G, Keys.U, Keys.ENTER, Keys.R,
        Keys.ESCAPE, Keys.LEFT, Keys.RIGHT};

    private final Toggle[] toggles = Toggle.values();

    private boolean[] togglesBefore;

    private boolean menuVisibleBefore;

    private Scene canvasBefore;

    private KeyGuy keyGuy;

    /**
     * Focus the main pane, as a finished tour-editor session leaves it, and wire a key handler
     * over a scene whose camera orbits, the way the ring scene does.
     */
    @BeforeEach
    void wireOrbitHandlerAfterAnEditorSession() {
        togglesBefore = toggleValues();
        menuVisibleBefore = MenuBox.menuVisible;
        canvasBefore = Scene.instance;
        Toggle.IsMainFocused.value = true;
        Toggle.IsTerminalFocused.value = false;
        Scene canvas = new MenuScene();
        canvas.mouse.controller = canvas.camera;
        keyGuy = new KeyGuy(canvas);
    }

    /**
     * Put the toggles and the menu flag back the way the suite had them.
     */
    @AfterEach
    void restoreSharedState() {
        for (int index = 0; index < toggles.length; index++) {
            toggles[index].value = togglesBefore[index];
        }
        MenuBox.menuVisible = menuVisibleBefore;
        Scene.instance = canvasBefore;
    }

    @Test
    void everyEditorShortcutIsInertUnderAnOrbitCamera() {
        boolean[] armed = toggleValues();
        for (int key : CONTROL_CHORDS) {
            assertDoesNotThrow(() -> {
                keyGuy.keyCallback(0L, Keys.LEFT_CONTROL, 0, Keys.ACTION_PRESS, 0);
                pressAndRelease(key, KeyNames.MOD_CONTROL);
                keyGuy.keyCallback(0L, Keys.LEFT_CONTROL, 0, Keys.ACTION_RELEASE, 0);
            }, "ctrl + key code " + key);
        }
        for (int key : PLAIN_KEYS) {
            assertDoesNotThrow(() -> pressAndRelease(key, 0), "key code " + key);
        }
        assertArrayEquals(armed, toggleValues(), "a toggle changed");
        assertEquals(List.of(), List.copyOf(keyGuy.pressedKeys), "a key stayed held");
    }

    /**
     * Press {@code key}, run one frame of the handler while it is held, then release it.
     *
     * @param key  key code
     * @param mods modifier bitmask
     */
    private void pressAndRelease(int key, int mods) {
        keyGuy.keyCallback(0L, key, 0, Keys.ACTION_PRESS, mods);
        keyGuy.paintUpdate(1f);
        keyGuy.keyCallback(0L, key, 0, Keys.ACTION_RELEASE, mods);
    }

    /**
     * The current value of every toggle, in declaration order.
     *
     * @return one flag per {@link Toggle}
     */
    private boolean[] toggleValues() {
        boolean[] values = new boolean[toggles.length];
        for (int index = 0; index < toggles.length; index++) {
            values[index] = toggles[index].value;
        }
        return values;
    }
}
