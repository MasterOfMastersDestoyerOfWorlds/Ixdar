package unit.scenes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.text.HyperWord;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.scenes.Scene;
import ixdar.scenes.main.MainScene;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.menu.MenuScene;
import unit.GlStandIn;

/**
 * A TSP command runs against the editor that owns the terminal it is typed into, and typed into
 * another scene's terminal it prints a refusal instead of throwing.
 */
class MainSceneTerminalCommandTest {

    private static final int STAND_IN_PLATFORM_ID = 7719;

    private static final String EXIT_COMMAND = "ex";

    private static final int WINDOW_WIDTH = 800;

    private static final int WINDOW_HEIGHT = 600;

    private static final Set<String> WINDOW_METHODS = Set.of("setTitle", "loadTexture", "loadSourceAsync",
            "loadShaderSourceAsync", "setKeyCallback", "setCharCallback", "setCursorPosCallback",
            "setMouseButtonCallback", "setScrollCallback", "setCursorMode", "requestRepaint", "automation");

    private final Toggle[] toggles = Toggle.values();

    private boolean[] togglesBefore;

    private Platform suitePlatform;

    private GL suiteGl;

    private Scene canvasBefore;

    /**
     * The suite's platform for files, with a fixed window size and no window behind it, paired with
     * a GL that does nothing; the editor's constructor sets toggles, so they are kept to restore.
     */
    @BeforeEach
    void installStandIns() {
        togglesBefore = new boolean[toggles.length];
        for (int index = 0; index < toggles.length; index++) {
            togglesBefore[index] = toggles[index].value;
        }
        canvasBefore = Scene.instance;
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platform files = suitePlatform;
        Platform window = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    String name = method.getName();
                    if (name.equals("getPlatformID")) {
                        return STAND_IN_PLATFORM_ID;
                    }
                    if (name.equals("getWindowWidth")) {
                        return WINDOW_WIDTH;
                    }
                    if (name.equals("getWindowHeight")) {
                        return WINDOW_HEIGHT;
                    }
                    if (WINDOW_METHODS.contains(name)) {
                        return null;
                    }
                    try {
                        return method.invoke(files, arguments);
                    } catch (InvocationTargetException thrown) {
                        throw thrown.getCause();
                    }
                });
        Platforms.init(window, GlStandIn.create());
    }

    /**
     * Put the suite's platform, the toggles and the current scene back.
     */
    @AfterEach
    void restoreSharedState() {
        Platforms.init(suitePlatform, suiteGl);
        for (int index = 0; index < toggles.length; index++) {
            toggles[index].value = togglesBefore[index];
        }
        Scene.instance = canvasBefore;
    }

    @Test
    void exitTypedIntoTheEditorsTerminalLeavesForItsBackScene() throws Exception {
        MenuScene menu = new MenuScene();
        Scene.instance = menu;
        MainScene editor = new MainScene("lines", menu);
        editor.terminal.run(EXIT_COMMAND);
        assertSame(menu, editor.successor, "the editor switched to its back scene");
    }

    @Test
    void exitTypedIntoAnotherScenesTerminalIsRefused() {
        MenuScene menu = new MenuScene();
        Scene.instance = menu;
        Terminal terminal = new Terminal(".", menu);
        assertDoesNotThrow(() -> terminal.run(EXIT_COMMAND));
        StringBuilder printed = new StringBuilder();
        for (HyperWord word : terminal.history.words) {
            printed.append(word);
        }
        assertTrue(printed.toString().contains(EXIT_COMMAND + " is a MainScene command"),
                "the refusal is printed: " + printed);
        assertNull(menu.successor, "no scene switch");
    }
}
