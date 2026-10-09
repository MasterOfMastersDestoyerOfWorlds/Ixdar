package unit.input;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.canvas.Canvas3D;
import ixdar.gui.ui.tools.FreeTool;
import ixdar.gui.ui.tools.Tool;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.platform.Toggle;
import ixdar.platform.input.Keys;
import ixdar.platform.input.OrbitCameraKeyGuy;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.main.MainScene;

/**
 * The ring scene's key handler ignores every shortcut that belongs to the tour editor
 * ({@link MainScene}): pressing one throws nothing and changes no toggle and no editor state,
 * even with the editor's tool left behind by an earlier run in the same JVM.
 */
class OrbitCameraKeyGuyMainSceneShortcutsTest {

    private static final int[] CONTROL_CHORDS = {Keys.B, Keys.N, Keys.S, Keys.Q};

    private static final int[] PLAIN_KEYS = {Keys.C, Keys.B, Keys.Y, Keys.M, Keys.RIGHT_BRACKET,
        Keys.UP, Keys.LEFT_BRACKET, Keys.DOWN, Keys.O, Keys.G, Keys.U, Keys.ENTER, Keys.R,
        Keys.ESCAPE, Keys.LEFT, Keys.RIGHT};

    private final Toggle[] toggles = Toggle.values();

    private boolean[] togglesBefore;

    private Tool toolBefore;

    private boolean menuVisibleBefore;

    private Canvas3D canvasBefore;

    private Tool editorTool;

    private int knotDrawLayer;

    private OrbitCameraKeyGuy keyGuy;

    /**
     * Leave an editor tool behind as a finished tour-editor session would, focus the main pane,
     * and wire an orbit key handler the way the ring scene does.
     */
    @BeforeEach
    void wireRingHandlerAfterAnEditorSession() {
        togglesBefore = toggleValues();
        toolBefore = MainScene.tool;
        menuVisibleBefore = MenuBox.menuVisible;
        canvasBefore = Canvas3D.instance;
        editorTool = new FreeTool();
        MainScene.tool = editorTool;
        Toggle.IsMainFocused.value = true;
        Toggle.IsTerminalFocused.value = false;
        knotDrawLayer = MainScene.knotDrawLayer;
        Canvas3D canvas = new Canvas3D();
        keyGuy = new OrbitCameraKeyGuy(new OrbitMouseTrap(canvas.camera, canvas), canvas.camera,
                canvas);
        keyGuy.controlRResetsTarget = false;
    }

    /**
     * Put the toggles, the editor tool and the menu flag back the way the suite had them.
     */
    @AfterEach
    void restoreSharedState() {
        for (int index = 0; index < toggles.length; index++) {
            toggles[index].value = togglesBefore[index];
        }
        MainScene.tool = toolBefore;
        MenuBox.menuVisible = menuVisibleBefore;
        Canvas3D.instance = canvasBefore;
    }

    @Test
    void everyEditorShortcutIsInertInTheRingScene() {
        boolean[] armed = toggleValues();
        for (int key : CONTROL_CHORDS) {
            assertDoesNotThrow(() -> {
                keyGuy.keyCallback(0L, Keys.LEFT_CONTROL, 0, Keys.ACTION_PRESS, 0);
                pressAndRelease(key, OrbitCameraKeyGuy.MOD_CONTROL);
                keyGuy.keyCallback(0L, Keys.LEFT_CONTROL, 0, Keys.ACTION_RELEASE, 0);
            }, "ctrl + key code " + key);
        }
        for (int key : PLAIN_KEYS) {
            assertDoesNotThrow(() -> pressAndRelease(key, 0), "key code " + key);
        }
        assertArrayEquals(armed, toggleValues(), "a toggle changed");
        assertSame(editorTool, MainScene.tool, "the editor tool was switched");
        assertEquals(knotDrawLayer, MainScene.knotDrawLayer, "the knot layer moved");
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
