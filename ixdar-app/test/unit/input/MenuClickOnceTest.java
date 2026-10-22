package unit.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.MOUSE_BUTTON_LEFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.cameras.Camera3D;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.menu.MenuItem;
import ixdar.gui.ui.text.HyperWord;
import ixdar.input.MouseTrap;
import ixdar.input.PointerDispatcher;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.scenes.menu.MenuScene;
import unit.GlStandIn;

/**
 * At the 1.25 display scale a click on a main-menu button runs its action exactly once, whether
 * it lands on the label's words, which carry the action too, or on the button's padding; a disabled
 * button's words are not clickable.
 */
class MenuClickOnceTest {

    private static final int WINDOW_WIDTH = 1024;

    private static final int WINDOW_HEIGHT = 1024;

    private static final int FRAMEBUFFER_WIDTH = 1280;

    private static final int FRAMEBUFFER_HEIGHT = 1280;

    private static final float CONTENT_SCALE = (float) FRAMEBUFFER_WIDTH / WINDOW_WIDTH;

    private static final float PADDING_INSET = 4f;

    private final int[] runs = new int[1];

    private Platform suitePlatform;

    private GL suiteGl;

    private ArrayList<MenuItem> suiteItems;

    private MenuItem enabled;

    private MenuItem disabled;

    private MenuBox menu;

    private MouseTrap trap;

    private PointerDispatcher pointer;

    /**
     * Install a 1.25-scaled platform with a GL stand-in, and draw a menu of one enabled and one
     * disabled button in a menu scene whose mouse trap takes what the menu leaves.
     */
    @BeforeEach
    void installScaledMenu() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
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
        MenuScene scene = new MenuScene();
        scene.active = true;
        menu = new MenuBox(scene);
        scene.menu = menu;
        suiteItems = MenuBox.menuItems;
        enabled = new MenuItem("Start New Game", () -> runs[0]++);
        disabled = new MenuItem("Continue", null);
        MenuBox.menuItems = new ArrayList<>(List.of(enabled, disabled));
        MouseTrap.hyperStrings = new ArrayList<>();
        menu.draw(new Camera2D(1, 1, 1f, 0, 0, null));
        trap = new MouseTrap(new Camera3D(new Vector3f(), 0f, 0f), scene);
        trap.active = true;
        scene.mouse = trap;
        pointer = PointerDispatcher.current();
    }

    /**
     * Drop the menu's subscription and text, and put the suite's menu and platform back.
     */
    @AfterEach
    void restoreSuitePlatform() {
        pointer.unsubscribe(menu);
        MenuBox.menuItems = suiteItems;
        MouseTrap.hyperStrings = new ArrayList<>();
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void aClickOnTheLabelTextRunsTheActionOnce() {
        HyperWord word = enabled.itemString().words.get(1);
        assertTrue(word.clickable);
        clickFramebuffer(word.xScreenOffset + word.drawnWidth / 2, word.yScreenOffset + word.rowHeight / 2);
        assertEquals(1, runs[0]);
    }

    @Test
    void aClickOnTheButtonPaddingRunsTheActionOnce() {
        HyperWord button = button(0);
        clickFramebuffer(button.xScreenOffset + PADDING_INSET, button.yScreenOffset + button.rowHeight / 2);
        assertEquals(1, runs[0]);
    }

    @Test
    void aDisabledButtonsWordsAreNotClickable() {
        for (HyperWord word : disabled.itemString().words) {
            assertFalse(word.clickable);
        }
        HyperWord button = button(1);
        clickFramebuffer(button.xScreenOffset + button.drawnWidth / 2,
                button.yScreenOffset + button.rowHeight / 2);
        assertEquals(0, runs[0]);
    }

    /**
     * A button's rectangle as the menu's item box reports it, on its row's first word.
     *
     * @param row the item's row
     * @return the row's first word, placed on the button
     */
    private HyperWord button(int row) {
        for (HyperWord word : menu.itemBox.text.getLine(row)) {
            if (!word.newLine) {
                return word;
            }
        }
        throw new AssertionError("row " + row + " has no words");
    }

    private void clickFramebuffer(float framebufferX, float framebufferY) {
        pointer.moveOrDrag(trap, 0L, framebufferX / CONTENT_SCALE,
                WINDOW_HEIGHT - framebufferY / CONTENT_SCALE);
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_PRESS, 0);
        pointer.mouseButton(trap, MOUSE_BUTTON_LEFT, ACTION_RELEASE, 0);
    }
}
