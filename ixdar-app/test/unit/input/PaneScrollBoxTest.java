package unit.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.function.Supplier;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.ColorText;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.menu.MenuScrollBar;
import ixdar.gui.ui.text.HyperString;
import ixdar.gui.ui.text.HyperWord;
import ixdar.input.MouseTrap;
import ixdar.input.PointerDispatcher;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.Keys;
import ixdar.platform.input.PointerRegion;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.menu.MenuScene;
import unit.GlStandIn;

/**
 * On a 1.25-scaled display the terminal's scroll box takes the wheel and the presses over its
 * framebuffer rectangle, runs a clicked word and focuses the terminal, and snaps to the prompt.
 */
class PaneScrollBoxTest {

    private static final int WINDOW_WIDTH = 800;

    private static final int WINDOW_HEIGHT = 600;

    private static final int FRAMEBUFFER_WIDTH = 1000;

    private static final int FRAMEBUFFER_HEIGHT = 750;

    private static final float CONTENT_SCALE = (float) FRAMEBUFFER_WIDTH / WINDOW_WIDTH;

    private static final float ROW = Drawing.FONT_HEIGHT_PIXELS;

    private static final int BOX_ROWS = 4;

    private static final float BOX_WIDTH = 500f;

    private static final float BOX_HEIGHT = BOX_ROWS * ROW;

    private static final int HISTORY_LINES = 30;

    private static final float ROWS_X = 100f;

    private static final float BAR_X = BOX_WIDTH - MenuScrollBar.WIDTH_PIXELS / 2;

    private static final float MIDDLE_Y = BOX_HEIGHT / 2;

    private static final float EPSILON = 1e-3f;

    private static final String DYNAMIC_TEXT = "live";

    private static final String CLICKABLE_TEXT = "clickable";

    private final int[] clicks = new int[1];

    private Platform suitePlatform;

    private GL suiteGl;

    private boolean suiteTerminalFocus;

    private MenuScene scene;

    private Terminal terminal;

    private Camera2D camera;

    private Camera3D orbitCamera;

    private MouseTrap trap;

    private PointerDispatcher pointer;

    /**
     * Install a scaled platform over the suite's, a GL stand-in so the font loads, and a terminal
     * of 30 history lines in a four-row box at the framebuffer's bottom left, subscribed for input.
     */
    @BeforeEach
    void installScaledTerminal() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        suiteTerminalFocus = Toggle.IsTerminalFocused.value;
        Platform scaled = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getWindowWidth":
                            return WINDOW_WIDTH;
                        case "getWindowHeight":
                            return WINDOW_HEIGHT;
                        case "getFrameBufferWidth":
                            return FRAMEBUFFER_WIDTH;
                        case "getFrameBufferHeight":
                            return FRAMEBUFFER_HEIGHT;
                        default:
                            return method.invoke(suitePlatform, arguments);
                    }
                });
        Platforms.init(scaled, GlStandIn.create());
        camera = new Camera2D(1, 1, 1f, 0, 0, null);
        scene = new MenuScene();
        scene.active = true;
        Toggle.IsTerminalFocused.value = true;
        terminal = new Terminal(".", scene);
        for (int line = 0; line < HISTORY_LINES; line++) {
            terminal.history.addLine("line " + line);
        }
        terminal.scrollBox.bounds.update(0, 0, BOX_WIDTH, BOX_HEIGHT);
        terminal.draw(camera);
        pointer = PointerDispatcher.current();
        pointer.subscribe(terminal.scrollBox.bounds, terminal.scrollBox);
        orbitCamera = new Camera3D(new Vector3f(), 0f, 0f);
        trap = new MouseTrap(orbitCamera, null);
        trap.controller = orbitCamera;
        trap.active = true;
    }

    /**
     * Drop the subscription and put the suite's platform and terminal focus back.
     */
    @AfterEach
    void restoreSuitePlatform() {
        pointer.unsubscribe(terminal.scrollBox);
        Toggle.IsTerminalFocused.value = suiteTerminalFocus;
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void enterSnapsTheBoxToThePromptOnItsBottomRow() {
        MenuScrollBar bar = terminal.scrollBox.scrollBar;
        assertTrue(bar.isNeeded(), "30 lines overflow four rows");
        assertEquals(0, bar.scrollOffset, "the history opens at its top");
        terminal.keyPress(Keys.ENTER, 0, false);
        terminal.draw(camera);
        assertEquals(bar.maximumScrollOffset(), bar.scrollOffset, EPSILON);
        HyperWord prompt = terminal.scrollBox.text.getLastWord();
        assertEquals(0, prompt.y, EPSILON, "the prompt sits on the bottom row");
        assertFalse(prompt.culled);
    }

    @Test
    void theWheelOverTheBoxScrollsItInFramebufferPixels() {
        terminal.keyPress(Keys.ENTER, 0, false);
        terminal.draw(camera);
        MenuScrollBar bar = terminal.scrollBox.scrollBar;
        float bottom = bar.scrollOffset;
        moveTo(ROWS_X, MIDDLE_Y);
        assertSame(terminal.scrollBox, pointer.regionUnderCursor(trap, PointerRegion::claimsWheel));
        trap.scrollCallback(1.0);
        pointer.paintUpdate(trap, 1f);
        assertEquals(bottom - 3 * ROW, bar.scrollOffset, EPSILON, "a notch away from the user goes back three rows");
        moveTo(ROWS_X, BOX_HEIGHT + ROW);
        assertNull(pointer.regionUnderCursor(trap, PointerRegion::claimsWheel), "above the box");
    }

    @Test
    void aPressOnTheBarPagesAndAClickOnTheRowsRunsTheWordAndFocusesTheTerminal() {
        terminal.history.addWordClick(CLICKABLE_TEXT, Color.WHITE, () -> clicks[0]++);
        terminal.keyPress(Keys.ENTER, 0, false);
        terminal.draw(camera);
        MenuScrollBar bar = terminal.scrollBox.scrollBar;
        float bottom = bar.scrollOffset;
        moveTo(BAR_X, MIDDLE_Y);
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_PRESS, 0);
        assertSame(terminal.scrollBox, pointer.pressHolder);
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_RELEASE, 0);
        assertEquals(bottom - bar.pageLength(), bar.scrollOffset, EPSILON, "the track above the thumb pages up");

        terminal.scrollBox.scrollToEndPending = true;
        terminal.draw(camera);
        HyperWord word = terminal.scrollBox.text.words.stream()
                .filter(candidate -> CLICKABLE_TEXT.equals(candidate.charSequence.toString().trim())).findFirst().orElseThrow();
        Toggle.IsMainFocused.value = true;
        moveTo(word.xScreenOffset + word.drawnWidth / 2, word.yScreenOffset + word.rowHeight / 2);
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_PRESS, 0);
        assertSame(terminal.scrollBox, pointer.pressHolder, "the box takes a press on its rows");
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_RELEASE, 0);
        assertEquals(1, clicks[0], "the box ran the clicked word's action");
        assertFalse(Toggle.IsMainFocused.value, "the click focused the terminal");
    }

    @Test
    void aHiddenTerminalLeavesTheWheelToTheCamera() {
        Toggle.IsTerminalFocused.value = false;
        moveTo(ROWS_X, MIDDLE_Y);
        assertNull(pointer.regionUnderCursor(trap, PointerRegion::claimsWheel));
        float start = orbitCamera.distance;
        trap.scrollCallback(1.0);
        pointer.paintUpdate(trap, 1f);
        assertTrue(orbitCamera.distance < start, "the camera zoomed");
        assertEquals(0, terminal.scrollBox.scrollBar.scrollOffset);
    }

    @Test
    void anAppendedStringKeepsItsDynamicWordsDynamic() {
        HyperString info = new HyperString();
        info.addWord("static");
        Supplier<ColorText<?>> live = () -> new ColorText<Float>(DYNAMIC_TEXT);
        info.addDynamicWord(live);
        HyperString rows = new HyperString();
        rows.addHyperString(info);
        assertSame(live, rows.getLastWord().wordAction, "the dynamic word still asks its supplier");
    }

    /**
     * Move the cursor to a framebuffer point, y up, as the window reports it.
     *
     * @param framebufferX framebuffer x
     * @param framebufferY framebuffer y, y up
     */
    private void moveTo(float framebufferX, float framebufferY) {
        pointer.moveOrDrag(trap, 0L, framebufferX / CONTENT_SCALE,
                WINDOW_HEIGHT - framebufferY / CONTENT_SCALE);
    }
}
