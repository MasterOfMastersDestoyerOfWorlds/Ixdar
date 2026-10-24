package unit.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.gui.ui.menu.MenuBox;
import ixdar.input.KeyGuy;
import ixdar.platform.input.KeyNames;
import ixdar.platform.input.Keys;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.menu.MenuScene;
import ixdar.tools.ControlHint;

/**
 * The key handler fires a scene's control rows the way the knot editor's keys always fired: a
 * plain row stays silent under Control in every scene, a Control row needs Control, and
 * a repeating row refires while held once the repeat interval has passed.
 */
class ControlRowDispatchTest {

    private static final String PLAIN_B = "B";

    private static final String CONTROL_B = "ctrl+B";

    private static final String CYCLE = "cycle";

    private final List<String> fired = new ArrayList<>();

    private boolean terminalFocusBefore;

    private boolean menuVisibleBefore;

    private Scene canvasBefore;

    private KeyGuy keyGuy;

    /**
     * Bind a Control chord, its plain key and a repeating key to a scene's controls, the Control
     * chord first as the tool switches are.
     */
    @BeforeEach
    void bindRows() {
        terminalFocusBefore = Toggle.IsTerminalFocused.value;
        menuVisibleBefore = MenuBox.menuVisible;
        canvasBefore = Scene.instance;
        Toggle.IsTerminalFocused.value = false;
        Scene canvas = new MenuScene();
        canvas.controls.get(Scene.TOOL_SWITCH_SECTION).add(new ControlHint(CONTROL_B, Keys.B, true, CONTROL_B,
                CONTROL_B, () -> fired.add(CONTROL_B)));
        canvas.controls.get(Scene.SCENE_SECTION).add(new ControlHint(PLAIN_B, Keys.B, PLAIN_B, PLAIN_B,
                () -> fired.add(PLAIN_B)));
        canvas.controls.get(Scene.SCENE_SECTION).add(ControlHint.repeating(CYCLE, Keys.LEFT, CYCLE, CYCLE,
                () -> fired.add(CYCLE)));
        keyGuy = new KeyGuy(canvas);
    }

    /**
     * Put the terminal focus, the menu flag and the current scene back the way the suite had them.
     */
    @AfterEach
    void restoreSharedState() {
        Toggle.IsTerminalFocused.value = terminalFocusBefore;
        MenuBox.menuVisible = menuVisibleBefore;
        Scene.instance = canvasBefore;
    }

    @Test
    void aPlainRowStaysSilentUnderControl() {
        keyGuy.handleSceneKeys(Keys.LEFT, KeyNames.MOD_CONTROL);
        keyGuy.handleSceneKeys(Keys.LEFT, 0);
        assertEquals(List.of(CYCLE), fired);
    }

    @Test
    void aHeldRepeatingRowStaysSilentUnderControl() {
        keyGuy.pressedKeys.add(Keys.LEFT);
        keyGuy.controlMask = true;
        keyGuy.lastRepeatMillis -= KeyGuy.REPEAT_MILLIS + 1;
        keyGuy.paintUpdate(1f);
        assertEquals(List.of(), fired);
    }

    @Test
    void theControlChordAndItsPlainKeyEachFireTheirOwnRow() {
        keyGuy.handleSceneKeys(Keys.B, KeyNames.MOD_CONTROL);
        keyGuy.handleSceneKeys(Keys.B, 0);
        assertEquals(List.of(CONTROL_B, PLAIN_B), fired);
    }

    @Test
    void aHeldRepeatingRowRefiresOnlyAfterTheRepeatInterval() {
        keyGuy.pressedKeys.add(Keys.LEFT);
        keyGuy.handleSceneKeys(Keys.LEFT, 0);
        keyGuy.paintUpdate(1f);
        assertEquals(List.of(CYCLE), fired);
        keyGuy.lastRepeatMillis -= KeyGuy.REPEAT_MILLIS + 1;
        keyGuy.paintUpdate(1f);
        assertEquals(List.of(CYCLE, CYCLE), fired);
        keyGuy.pressedKeys.remove(Keys.LEFT);
        keyGuy.lastRepeatMillis -= KeyGuy.REPEAT_MILLIS + 1;
        keyGuy.paintUpdate(1f);
        assertEquals(List.of(CYCLE, CYCLE), fired);
    }
}
