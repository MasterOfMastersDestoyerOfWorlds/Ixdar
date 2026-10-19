package unit.scenes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ixdar.gui.terminal.Terminal;
import ixdar.gui.terminal.commands.TerminalCommand;
import ixdar.gui.ui.PaneTypes;
import ixdar.gui.ui.text.HyperWord;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.Keys;
import ixdar.scenes.Scene;
import ixdar.scenes.main.MainScene;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.menu.MenuScene;
import ixdar.tools.ControlHint;
import unit.GlStandIn;

/**
 * The knot editor's terminal commands: run against their own editor, refused elsewhere, cleared
 * after throwing, sg writing into the terminal's directory, and R versus {@code rst camera}.
 */
class MainSceneTerminalCommandTest {

    private static final int STAND_IN_PLATFORM_ID = 7719;

    private static final String EXIT_COMMAND = "ex";

    private static final String THROWING_COMMAND = "throws";

    private static final String THROWN_MESSAGE = "command failed";

    private static final String LINES = "lines";

    private static final String WORKING_DIRECTORY = ".";

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
        MainScene editor = new MainScene(LINES, menu);
        editor.setTools();
        editor.switchTool(editor.defaultTool);
        editor.terminal.run(EXIT_COMMAND);
        assertSame(menu, editor.successor, "the editor switched to its back scene");
    }

    @Test
    void exitTypedIntoAnotherScenesTerminalIsRefused() {
        MenuScene menu = new MenuScene();
        Scene.instance = menu;
        Terminal terminal = new Terminal(WORKING_DIRECTORY, menu);
        assertDoesNotThrow(() -> terminal.run(EXIT_COMMAND));
        String printed = printed(terminal);
        assertTrue(printed.contains(EXIT_COMMAND + " is a MainScene command"),
                "the refusal is printed: " + printed);
        assertNull(menu.successor, "no scene switch");
    }

    @Test
    void aCommandThatThrowsLeavesAnEmptyCommandLineAndPrintsItsError() {
        MenuScene menu = new MenuScene();
        Scene.instance = menu;
        Terminal terminal = new Terminal(WORKING_DIRECTORY, menu);
        Terminal.commandMap.put(THROWING_COMMAND, new TerminalCommand() {
            @Override
            public String fullName() {
                return THROWING_COMMAND;
            }

            @Override
            public String shortName() {
                return THROWING_COMMAND;
            }

            @Override
            public String desc() {
                return THROWING_COMMAND;
            }

            @Override
            public String usage() {
                return THROWING_COMMAND;
            }

            @Override
            public int argLength() {
                return 0;
            }

            @Override
            public String[] run(String[] args, int startIdx, Terminal running) {
                throw new IllegalStateException(THROWN_MESSAGE);
            }
        });
        try {
            enter(terminal, THROWING_COMMAND);
            enter(terminal, EXIT_COMMAND);
        } finally {
            Terminal.commandMap.remove(THROWING_COMMAND);
        }
        String printed = printed(terminal);
        assertTrue(printed.contains("EXCEPTION: java.lang.IllegalStateException: " + THROWN_MESSAGE),
                "the thrown exception is printed: " + printed);
        assertTrue(printed.contains(EXIT_COMMAND + " is a MainScene command"),
                "the next line ran on its own: " + printed);
        assertFalse(printed.contains(THROWING_COMMAND + EXIT_COMMAND), "nothing glued: " + printed);
    }

    @Test
    void subgraphWritesItsFileInsideTheTerminalsDirectory(@TempDir Path solutions) throws Exception {
        MenuScene menu = new MenuScene();
        Scene.instance = menu;
        MainScene editor = new MainScene(LINES, menu);
        Path linesFolder = Files.createDirectory(solutions.resolve(LINES));
        editor.terminal.directory = linesFolder.toString();
        editor.terminal.run("sg 1-2");
        assertTrue(Files.isRegularFile(linesFolder.resolve(LINES + "_1-2.ix")),
                "the subgraph is " + LINES + "/" + LINES + "_1-2.ix: " + printed(editor.terminal));
        try (Stream<Path> written = Files.list(solutions)) {
            assertEquals(List.of(linesFolder), written.toList(), "nothing written beside the folder");
        }
    }

    /**
     * Type a line into the terminal and press Enter, as the keyboard does.
     *
     * @param terminal terminal receiving the keys
     * @param line     command line, without spaces
     */
    private static void enter(Terminal terminal, String line) {
        terminal.type(line);
        terminal.keyPress(Keys.ENTER, 0, false);
    }

    /**
     * The terminal's history as one string.
     *
     * @param terminal terminal to read
     * @return every history word concatenated
     */
    private static String printed(Terminal terminal) {
        StringBuilder printed = new StringBuilder();
        for (HyperWord word : terminal.history.words) {
            printed.append(word);
        }
        return printed.toString();
    }

    @Test
    void theResetRowReturnsToTheFreeToolAndResetsTheToggles() throws Exception {
        MainScene editor = editorInNeighborViewWithCutMatchAndGradientOff();
        ControlHint resetRow = null;
        for (ControlHint hint : editor.controls) {
            if (hint.keyCode == Keys.R && !hint.controlHeld) {
                resetRow = hint;
            }
        }
        assertNotNull(resetRow, "the editor lists an R row");
        resetRow.action.perform();
        assertSame(editor.freeTool, editor.activeTool, "R switched back to the free tool");
        assertTrue(Toggle.DrawCutMatch.value, "R reset the cut-match toggle");
        assertTrue(Toggle.DrawKnotGradient.value, "R reset the gradient toggle");
    }

    @Test
    void resetCameraTypedIntoTheTerminalLeavesTheToolAndTheToggles() throws Exception {
        MainScene editor = editorInNeighborViewWithCutMatchAndGradientOff();
        editor.terminal.run("rst camera");
        assertSame(editor.neighborViewTool, editor.activeTool, "the tool is unchanged");
        assertFalse(Toggle.DrawCutMatch.value, "the cut-match toggle is unchanged");
        assertFalse(Toggle.DrawKnotGradient.value, "the gradient toggle is unchanged");
    }

    /**
     * An editor switched to Neighbor View, as ctrl+B leaves it, with the knot view focused and two
     * toggles that start on turned off.
     *
     * @return the editor
     * @throws Exception when the tour file does not load
     */
    private MainScene editorInNeighborViewWithCutMatchAndGradientOff() throws Exception {
        MenuScene menu = new MenuScene();
        Scene.instance = menu;
        MainScene editor = new MainScene(LINES, menu);
        editor.setTools();
        editor.switchTool(editor.defaultTool);
        editor.switchTool(editor.neighborViewTool);
        Toggle.setPanelFocus(PaneTypes.KnotView);
        Toggle.DrawCutMatch.value = false;
        Toggle.DrawKnotGradient.value = false;
        return editor;
    }
}
