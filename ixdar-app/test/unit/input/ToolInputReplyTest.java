package unit.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.automation.AutomationRuntime;
import ixdar.automation.endpoints.input.InjectClick;
import ixdar.automation.endpoints.input.InjectKey;
import ixdar.automation.endpoints.ui.tools.FireControl;
import ixdar.automation.endpoints.ui.tools.Tools;
import ixdar.geometry.mesh.data.rings.RingSegmentMode;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.terminal.commands.TerminalCommand;
import ixdar.gui.ui.PaneTypes;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.input.Keys;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.menu.MenuScene;
import ixdar.tools.ControlHint;
import ixdar.tools.EditHistory;
import ixdar.tools.Tool;
import ixdar.tools.connection.ConnectionTool;
import ixdar.tools.regions.RingRegionTool;
import ixdar.tools.ring.RingTool;
import unit.GlStandIn;

/**
 * Key and click replies say which tool took the input, the control row it fired or that nothing
 * did, and the tool's shared state; the tools listing shows every hosted tool, active or not.
 */
class ToolInputReplyTest {

    private static final String PEN = "pen";

    private static final String HAND_BACK = "handback";

    private static final String TERMINAL_DIRECTORY = ".";

    private static final String TERMINAL_REASON = "terminal";

    private static final String DREW = "drew a stroke";

    private static final String CLICKED = "clicked the canvas";

    private static final String HIDE = "hide the stroke";

    private static final String OK = "ok";

    private static final String KEY = "key";

    private static final String SETTLE = "settle";

    private static final String ACTIVE_TOOL = "activeTool";

    private static final String PREVIOUS_TOOL = "previousTool";

    private static final String CONTROL = "control";

    private static final String TOOL = "tool";

    private static final String LAST_ROW = "lastRow";

    private static final String DESCRIPTION = "description";

    private static final String NAME = "name";

    private static final String SHIFT_HELD = "shiftHeld";

    private static final String CONTROL_HELD = "controlHeld";

    private static final String CROSSHATCH = "crosshatch";

    private static final String PLAIN = "plain";

    private static final String DRAW_KEY = "P";

    private static final String UNBOUND_KEY = "J";

    private static final String BRUSH = "brush";

    private static final String SWITCH_KEY = "switchKey";

    private static final String HIDE_KEY = "shift+H";

    private static final String UNHOSTED_TOOL = "easel";

    private static final String ERROR = "error";

    private static final String BRUSH_SWITCH = "ctrl+W";

    private static final String NEXT_UNDO = "nextUndo";


    private static final String GEODESIC = RingSegmentMode.GEODESIC.label;

    private static final String CREASE = RingSegmentMode.CREASE.label;

    private static final int WINDOW_PIXELS = 800;

    private static final float PINNED_PIXELS = 20f;

    private static final float CLICK_PIXELS = 100f;

    private final EditHistory<String> penHistory = new EditHistory<>();

    private Platform suitePlatform;

    private GL suiteGl;

    private boolean terminalFocusBefore;

    private Terminal terminalBefore;

    private boolean menuVisibleBefore;

    private Scene canvasBefore;

    private Scene runtimeCanvasBefore;

    private long renderThreadBefore;

    private MenuScene scene;

    private Tool pen;

    private Tool brush;

    /**
     * A scene hosting a pen with undo on ctrl+Q, a two-mode brush on ctrl+W and a ring tool with no
     * switch key, the pen active, bound to the automation runtime.
     */
    @BeforeEach
    void hostTools() {
        terminalFocusBefore = Toggle.IsTerminalFocused.value;
        terminalBefore = Terminal.current;
        menuVisibleBefore = MenuBox.menuVisible;
        canvasBefore = Scene.instance;
        AutomationRuntime runtime = AutomationRuntime.get();
        runtimeCanvasBefore = runtime.canvas;
        renderThreadBefore = runtime.renderThreadId;
        Toggle.IsTerminalFocused.value = false;
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        GL standIn = GlStandIn.create();
        int platformId = standIn.getPlatformID();
        Platform window = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getPlatformID":
                            return platformId;
                        case "getWindowWidth":
                        case "getWindowHeight":
                        case "getFrameBufferWidth":
                        case "getFrameBufferHeight":
                            return WINDOW_PIXELS;
                        case "setPlatformID":
                            return null;
                        default:
                            // The suite's platform, so the shaders cached for the stand-in's id
                            // keep working buffers for the tests that run after this one.
                            return method.invoke(suitePlatform, arguments);
                    }
                });
        Platforms.init(window, standIn);
        scene = new MenuScene();
        pen = new Tool() {
            @Override
            public String toolName() {
                return PEN;
            }

            @Override
            public void activate() {
            }

            @Override
            public void deactivate() {
            }

            @Override
            public void addControls(List<ControlHint> controls) {
                controls.add(new ControlHint(Keys.P, DRAW_KEY, DREW, () -> {
                    lastRow = DREW;
                    penHistory.push(DREW, "", "");
                }));
            }

            @Override
            public EditHistory<?> history() {
                return penHistory;
            }

            @Override
            public boolean onPress(float x, float y, int mods) {
                return true;
            }

            @Override
            public boolean onRelease(float x, float y, boolean click) {
                lastRow = CLICKED;
                return true;
            }
        };
        brush = new Tool(PLAIN, CROSSHATCH) {
            @Override
            public String toolName() {
                return BRUSH;
            }

            @Override
            public void activate() {
            }

            @Override
            public void deactivate() {
            }

            @Override
            public void addControls(List<ControlHint> controls) {
                controls.add(new ControlHint(Keys.H, false, true, HIDE_KEY, HIDE, () -> lastRow = HIDE));
            }
        };
        scene.addToolSwitch(pen, new ControlHint(Keys.Q, true, "ctrl+Q", "draw", () -> scene.switchTool(pen)));
        scene.addToolSwitch(brush, new ControlHint(Keys.W, true, BRUSH_SWITCH, BRUSH,
                () -> scene.switchTool(brush)));
        scene.tools.add(new RingTool(null));
        scene.defaultTool = pen;
        scene.switchTool(pen);
        scene.mouse.active = true;
        runtime.canvas = scene;
    }

    /**
     * Put the runtime, the terminal focus, the menu flag and the current scene back.
     */
    @AfterEach
    void restoreSharedState() {
        AutomationRuntime runtime = AutomationRuntime.get();
        runtime.canvas = runtimeCanvasBefore;
        runtime.renderThreadId = renderThreadBefore;
        Platforms.init(suitePlatform, suiteGl);
        Terminal.current = terminalBefore;
        Toggle.IsTerminalFocused.value = terminalFocusBefore;
        MenuBox.menuVisible = menuVisibleBefore;
        Scene.instance = canvasBefore;
    }

    @Test
    void aHandledKeyNamesItsControlAndTheToolsRowAndUndoHead() throws Exception {
        JsonObject reply = key(DRAW_KEY);
        assertTrue(reply.get(OK).getAsBoolean(), reply.toString());
        assertTrue(reply.get(InjectKey.HANDLED).getAsBoolean());
        assertEquals(PEN, reply.get(ACTIVE_TOOL).getAsString());
        assertFalse(reply.has(PREVIOUS_TOOL), "the key switched no tool");
        assertEquals(DREW, reply.getAsJsonObject(CONTROL).get(DESCRIPTION).getAsString());
        JsonObject tool = reply.getAsJsonObject(TOOL);
        assertEquals(DREW, tool.get(LAST_ROW).getAsString());
        assertEquals(DREW, tool.get(NEXT_UNDO).getAsString());
        assertEquals(1, tool.get("undoDepth").getAsInt());
        assertEquals("", tool.get(ERROR).getAsString());
    }

    @Test
    void aKeyNothingTakesSaysSo() throws Exception {
        JsonObject reply = key(UNBOUND_KEY);
        assertFalse(reply.get(InjectKey.HANDLED).getAsBoolean(), reply.toString());
        assertTrue(reply.get(CONTROL).isJsonNull());
        String reason = reply.get(InjectKey.REASON).getAsString();
        assertTrue(reason.contains(UNBOUND_KEY) && reason.contains(PEN), reason);
    }

    @Test
    void aKeyTheFocusedTerminalOwnsSaysTheTerminalTookIt() throws Exception {
        Toggle.IsTerminalFocused.value = true;
        Terminal.current = new Terminal(TERMINAL_DIRECTORY, scene);
        JsonObject reply = key(DRAW_KEY);
        assertTrue(reply.get(InjectKey.HANDLED).getAsBoolean(), reply.toString());
        assertTrue(reply.get(CONTROL).isJsonNull(), reply.toString());
        assertTrue(reply.get(InjectKey.REASON).getAsString().contains(TERMINAL_REASON),
                reply.toString());
        assertEquals("", reply.getAsJsonObject(TOOL).get(LAST_ROW).getAsString(), "the pen drew");
    }

    @Test
    void anEnterWhoseLineHandsTheKeysBackStillSaysTheTerminalTookIt() throws Exception {
        Toggle.IsTerminalFocused.value = true;
        Terminal.current = new Terminal(TERMINAL_DIRECTORY, scene);
        Terminal.commandMap.put(HAND_BACK, new TerminalCommand() {
            @Override
            public String fullName() {
                return HAND_BACK;
            }

            @Override
            public String shortName() {
                return HAND_BACK;
            }

            @Override
            public String desc() {
                return HAND_BACK;
            }

            @Override
            public String usage() {
                return HAND_BACK;
            }

            @Override
            public int argLength() {
                return 0;
            }

            @Override
            public String[] run(String[] args, int startIdx, Terminal terminal) {
                Toggle.setPanelFocus(PaneTypes.KnotView);
                return null;
            }
        });
        try {
            Terminal.current.type(HAND_BACK);
            JsonObject reply = key("ENTER");
            assertFalse(Toggle.IsTerminalFocused.value, "the line did not hand the keys back");
            assertTrue(reply.get(InjectKey.HANDLED).getAsBoolean(), reply.toString());
            assertTrue(reply.get(InjectKey.REASON).getAsString().contains(TERMINAL_REASON),
                    reply.toString());
        } finally {
            Terminal.commandMap.remove(HAND_BACK);
        }
    }

    @Test
    void aToolSwitchingKeyNamesBothToolsAndTheNewToolsMode() throws Exception {
        JsonObject reply = key("CTRL+W");
        assertEquals(BRUSH, reply.get(ACTIVE_TOOL).getAsString(), reply.toString());
        assertEquals(PEN, reply.get(PREVIOUS_TOOL).getAsString());
        JsonObject control = reply.getAsJsonObject(CONTROL);
        assertEquals(BRUSH_SWITCH, control.get(KEY).getAsString());
        assertTrue(control.get(CONTROL_HELD).getAsBoolean());
        JsonObject tool = reply.getAsJsonObject(TOOL);
        assertEquals(BRUSH, tool.get(NAME).getAsString());
        assertEquals(PLAIN, tool.get("mode").getAsString());
        assertFalse(tool.has(NEXT_UNDO), "a tool without history has no undo heads");
    }

    @Test
    void aClickReportsTheToolThatTookItAndItsRow() throws Exception {
        JsonObject reply = click();
        assertTrue(reply.get(OK).getAsBoolean(), reply.toString());
        assertEquals(PEN, reply.get(ACTIVE_TOOL).getAsString());
        assertTrue(reply.get(CONTROL).isJsonNull(), "no controls-menu row was clicked");
        assertEquals(CLICKED, reply.getAsJsonObject(TOOL).get(LAST_ROW).getAsString());
    }

    @Test
    void aClickMovesAPinnedHoverToTheClickSoTheNextFramePicksThere() throws Exception {
        scene.mouse.setAutomationHoverLock(PINNED_PIXELS, PINNED_PIXELS);
        JsonObject reply = click();
        assertTrue(reply.get(OK).getAsBoolean(), reply.toString());
        scene.mouse.paintUpdate(1f);
        assertEquals((int) CLICK_PIXELS, scene.mouse.lastX, "the pinned hover pulled the cursor back");
        assertEquals((int) CLICK_PIXELS, scene.mouse.lastY);
    }

    @Test
    void theToolsListingShowsEveryToolWithItsSwitchKeyShiftAndRoutes() throws Exception {
        JsonObject listing = new Tools() {
            {
                runtime = AutomationRuntime.get();
                runtime.renderThreadId = Thread.currentThread().getId();
            }
        }.endpointHandler(new JsonObject());
        assertTrue(listing.get(OK).getAsBoolean(), listing.toString());
        assertEquals(PEN, listing.get(ACTIVE_TOOL).getAsString());
        JsonArray tools = listing.getAsJsonArray(Tools.TOOLS);
        assertEquals(3, tools.size());
        JsonObject inactive = tools.get(1).getAsJsonObject();
        assertEquals(BRUSH, inactive.get(NAME).getAsString());
        assertFalse(inactive.get("active").getAsBoolean());
        assertEquals(BRUSH_SWITCH, inactive.getAsJsonObject(SWITCH_KEY).get(KEY).getAsString());
        JsonArray modes = inactive.getAsJsonArray("modes");
        assertEquals(2, modes.size());
        assertEquals(PLAIN, modes.get(0).getAsString());
        assertEquals(CROSSHATCH, modes.get(1).getAsString());
        JsonObject hide = inactive.getAsJsonArray("controls").get(0).getAsJsonObject();
        assertEquals(HIDE, hide.get(DESCRIPTION).getAsString());
        assertTrue(hide.get(SHIFT_HELD).getAsBoolean(), "the shift flag was dropped");
        JsonObject ringTool = tools.get(2).getAsJsonObject();
        assertEquals(RingTool.TOOL_NAME, ringTool.get(NAME).getAsString());
        assertTrue(ringTool.get(SWITCH_KEY).isJsonNull());
        assertEquals(new RingTool(null).desc(), ringTool.get(DESCRIPTION).getAsString());
        assertTrue(ringTool.has("draft") && ringTool.has("items"), "not the ui-state tool shape");
    }

    @Test
    void firingAnInactiveToolsRowSwitchesToItFiresTheRowAndReportsTheOutcome() throws Exception {
        JsonObject reply = fire(BRUSH, "SHIFT+h");
        assertTrue(reply.get(OK).getAsBoolean(), reply.toString());
        assertEquals(BRUSH, reply.get(ACTIVE_TOOL).getAsString());
        assertEquals(PEN, reply.get(PREVIOUS_TOOL).getAsString(), "the brush's switch key was not used");
        assertEquals(HIDE, reply.getAsJsonObject(CONTROL).get(DESCRIPTION).getAsString());
        assertTrue(reply.getAsJsonObject(CONTROL).get(SHIFT_HELD).getAsBoolean());
        JsonObject tool = reply.getAsJsonObject(TOOL);
        assertEquals(BRUSH, tool.get(NAME).getAsString());
        assertEquals(HIDE, tool.get(LAST_ROW).getAsString());
    }

    @Test
    void anUnknownRowOrToolIsRefusedNamingTheCandidates() throws Exception {
        JsonObject unknownRow = fire(BRUSH, UNBOUND_KEY);
        assertFalse(unknownRow.get(OK).getAsBoolean(), unknownRow.toString());
        assertEquals(HIDE_KEY + ControlHint.KEY_DESCRIPTION_GAP + HIDE,
                unknownRow.getAsJsonArray(FireControl.CANDIDATES).get(0).getAsString());
        assertEquals(PEN, scene.activeTool.toolName(), "a refused row still switched tools");
        JsonObject unknownTool = fire(UNHOSTED_TOOL, DRAW_KEY);
        assertFalse(unknownTool.get(OK).getAsBoolean(), unknownTool.toString());
        assertEquals(3, unknownTool.getAsJsonArray(FireControl.CANDIDATES).size());
        assertTrue(unknownTool.get(ERROR).getAsString().contains(UNHOSTED_TOOL), unknownTool.toString());
    }

    @Test
    void theRingSceneToolsDescribeThemselvesWithoutKeysAndTheModeKeyNamesBothModes() {
        RingTool ringTool = new RingTool(null);
        for (Tool tool : List.of(ringTool, new RingRegionTool(null), new ConnectionTool(null))) {
            assertFalse(tool.desc().isEmpty(), tool.toolName());
            assertFalse(tool.desc().matches(".*(Ctrl|Shift|Esc|Enter|\\b[A-Z]:).*"), tool.desc());
        }
        assertTrue(ringTool.desc().contains(GEODESIC) && ringTool.desc().contains(CREASE));
        List<ControlHint> controls = new ArrayList<>();
        ringTool.addControls(controls);
        String modeHint = "";
        for (ControlHint hint : controls) {
            if (hint.keyCode == Keys.T) {
                modeHint = hint.description;
            }
        }
        assertTrue(modeHint.contains(GEODESIC) && modeHint.contains(CREASE), modeHint);
    }

    private JsonObject fire(String toolName, String label) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty(FireControl.TOOL, toolName);
        body.addProperty(KEY, label);
        body.addProperty(SETTLE, 0);
        return new FireControl() {
            {
                runtime = AutomationRuntime.get();
                runtime.renderThreadId = Thread.currentThread().getId();
            }
        }.endpointHandler(body);
    }

    private JsonObject click() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("x", CLICK_PIXELS);
        body.addProperty("y", CLICK_PIXELS);
        body.addProperty(SETTLE, 0);
        return new InjectClick() {
            {
                runtime = AutomationRuntime.get();
                runtime.renderThreadId = Thread.currentThread().getId();
            }
        }.endpointHandler(body);
    }

    private JsonObject key(String spec) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty(KEY, spec);
        body.addProperty(SETTLE, 0);
        return new InjectKey() {
            {
                runtime = AutomationRuntime.get();
                runtime.renderThreadId = Thread.currentThread().getId();
            }
        }.endpointHandler(body);
    }
}
