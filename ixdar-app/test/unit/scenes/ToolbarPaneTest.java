package unit.scenes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.gui.ui.ToolbarPane;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.scenes.Scene;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ControlHint;
import unit.GlStandIn;

/**
 * The toolbar lists the active tool's name and every key of its control section, follows a tool
 * switch, and is hidden while its scene turns it off, as a multiview capture does for its cells.
 */
class ToolbarPaneTest {

    private static final int STAND_IN_PLATFORM_ID = 7719;

    private Platform suitePlatform;

    private GL suiteGl;

    private RingScene scene;

    /**
     * A platform whose window takes no callbacks and a GL that does nothing, then a ring scene on
     * its default tool with the toolbar on, as {@code ModelScene} leaves it.
     */
    @BeforeEach
    void openRingScene() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platform window = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getPlatformID")) {
                        return STAND_IN_PLATFORM_ID;
                    }
                    Class<?> type = method.getReturnType();
                    return type == int.class ? 0 : type == float.class ? 0f
                            : type == boolean.class ? false : null;
                });
        Platforms.init(window, GlStandIn.create());
        scene = new RingScene();
        scene.initPoints();
        scene.setTools();
        scene.switchTool(scene.defaultTool);
        scene.showToolbar = true;
        scene.active = true;
    }

    /**
     * Put the suite's platform back.
     */
    @AfterEach
    void restoreSuitePlatform() {
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void theBarListsTheActiveToolsKeysAndFollowsASwitch() {
        String orbit = assertListsActiveTool();
        scene.switchTool(scene.ringTool);
        String ring = assertListsActiveTool();
        assertNotEquals(orbit, ring, "the bar changed with the tool");
    }

    @Test
    void theBarHidesWhileTheSceneTurnsItOffAndComesBack() {
        ToolbarPane bar = scene.toolbarPane;
        assertTrue(bar.shown());
        scene.showToolbar = false;
        assertFalse(bar.shown(), "a multiview cell shows the model alone");
        scene.showToolbar = true;
        assertTrue(bar.shown(), "the bar is back once the capture puts the flag back");
    }

    /**
     * Assert the bar names the active tool and lists every key of the tool's control section with
     * what it does.
     *
     * @return the bar's text
     */
    private String assertListsActiveTool() {
        String text = String.join("\n", scene.toolbarPane.rows().plainLines());
        String tool = scene.activeTool.displayName().toUpperCase(Locale.ROOT);
        assertTrue(text.startsWith(tool), "the bar opens on " + tool + ": " + text);
        List<ControlHint> hints = scene.controls.get(Scene.TOOL_SECTION);
        assertFalse(hints.isEmpty(), tool + " has keys to list");
        for (ControlHint hint : hints) {
            assertTrue(text.contains(hint.key + ": " + hint.description),
                    "the bar lists " + hint.key + ": " + text);
        }
        return text;
    }
}
