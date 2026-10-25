package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.automation.AutomationRuntime;
import ixdar.automation.endpoints.input.InjectClick;
import ixdar.automation.endpoints.ui.SelectionBounds;
import ixdar.automation.endpoints.ui.State;
import ixdar.automation.endpoints.ui.tools.FireControl;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.terminal.commands.SelectCommand;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.lwjgl.DefaultBuffer;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;
import ixdar.tools.regions.RegionLayer;
import ixdar.tools.regions.RingRegionTool;

/**
 * The ring scene driven as automation drives it, on the cut cylinder of {@link RingRegionsTest}:
 * action ids, firing by id, {@code sel} selecting and framing regions, and a shift-click adding one.
 */
class RegionControlAutomationTest {

    private static final int STAND_IN_PLATFORM_ID = 7718;

    private static final int WINDOW_SIZE = 64;

    private static final int LOW_RING_ROW = 2;

    private static final int HIGH_RING_ROW = 5;

    private static final String GET_PLATFORM_ID = "getPlatformID";

    private static final String SELECT = SelectCommand.cmd + " ";

    private static final String OK = "ok";

    private static final String SAVE_RINGS = "save-rings";

    private static final String PART = "tail";

    private static final float CENTRE_TOLERANCE = 1e-5f;

    private static final float[] BOTTOM_POINT = { 1f, 0f, 0.5f };

    private static final float[] TOP_POINT = { 1f, 0f, 3.5f };

    private static final float CLICK_PIXELS = 32f;

    private Platform suitePlatform;

    private GL suiteGl;

    private Scene runtimeCanvasBefore;

    private long renderThreadBefore;

    private MeshTopology cylinder;

    private RingScene scene;

    /** The terminal {@code sel} lines run in, over {@link #scene}. */
    private Terminal terminal;

    private Terminal terminalBefore;

    private boolean terminalFocusBefore;

    /** Active face the surface's id pass reports under any pixel, as a click would read it. */
    private final int[] faceUnderCursor = { -1 };

    /** Active face the coarse id pass sees over the lower half of the view. */
    private final int[] faceSeenBelow = { -1 };

    /** Active face the coarse id pass sees over the upper half of the view. */
    private final int[] faceSeenAbove = { -1 };

    /**
     * A ring scene over the cylinder with its two rings and the region tool active, its regions and
     * pick buffer built, bound to the automation runtime.
     */
    @BeforeEach
    void installScene() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platform window = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getWindowWidth":
                        case "getFrameBufferWidth":
                        case "getWindowHeight":
                        case "getFrameBufferHeight":
                            return WINDOW_SIZE;
                        case GET_PLATFORM_ID:
                            return STAND_IN_PLATFORM_ID;
                        case "allocateFloats":
                            return new DefaultBuffer((Integer) arguments[0]);
                        default:
                            return method.getReturnType() == int.class ? 0
                                    : method.getReturnType() == String.class ? "" : null;
                    }
                });
        GL quietGl = (GL) Proxy.newProxyInstance(GL.class.getClassLoader(),
                new Class<?>[] {GL.class}, (proxy, method, arguments) -> {
                    if (GET_PLATFORM_ID.equals(method.getName())) {
                        return STAND_IN_PLATFORM_ID;
                    }
                    Class<?> type = method.getReturnType();
                    return type == int.class ? 1 : type == boolean.class ? false
                            : type == float.class ? 0f : type == String.class ? "" : null;
                });
        Platforms.init(window, quietGl);
        cylinder = RingRegionsTest.cappedCylinder();
        scene = new RingScene() {
            @Override
            public MeshTopology getMesh() {
                return cylinder;
            }

            @Override
            public MeshTopology halfEdgeSurface() {
                return cylinder;
            }
        };
        scene.initPoints();
        scene.setTools();
        scene.switchTool(scene.defaultTool);
        scene.meshRuntime = new HalfEdgeMeshRuntime() {
            @Override
            public int faceIndexAtPixel(Camera3D camera, int framebufferX, int framebufferY) {
                return faceUnderCursor[0];
            }

            @Override
            public int[] faceIndexGrid(Camera3D camera, int columns, int rows) {
                int[] grid = new int[columns * rows];
                Arrays.fill(grid, 0, grid.length / 2, faceSeenBelow[0]);
                Arrays.fill(grid, grid.length / 2, grid.length, faceSeenAbove[0]);
                return grid;
            }
        };
        scene.meshRuntime.upload(cylinder);
        scene.ringMarksByLabel.put("ring_02", RingRegionsUpdateTest.rowMask(cylinder, LOW_RING_ROW, false));
        scene.ringMarksByLabel.put("ring_05", RingRegionsUpdateTest.rowMask(cylinder, HIGH_RING_ROW, false));
        scene.switchTool(scene.regionTool);
        scene.mouse.active = true;
        scene.updateScene();
        scene.updateScene();
        AutomationRuntime runtime = AutomationRuntime.get();
        runtimeCanvasBefore = runtime.canvas;
        renderThreadBefore = runtime.renderThreadId;
        runtime.canvas = scene;
        terminalBefore = Terminal.current;
        terminalFocusBefore = Toggle.IsTerminalFocused.value;
        terminal = new Terminal(".", scene);
    }

    /** Put the runtime, the terminal and the suite's platform back. */
    @AfterEach
    void restoreSuiteState() {
        Terminal.current = terminalBefore;
        Toggle.IsTerminalFocused.value = terminalFocusBefore;
        AutomationRuntime runtime = AutomationRuntime.get();
        runtime.canvas = runtimeCanvasBefore;
        runtime.renderThreadId = renderThreadBefore;
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void everyRowThatFiresHasAnActionIdThatMeansOneThingInItsScope() {
        Map<String, List<String>> idsByScope = new HashMap<>();
        for (Tool tool : scene.tools) {
            scene.switchTool(tool);
            List<ControlHint> rows = new ArrayList<>(scene.controls.get(Scene.TOOL_SECTION));
            rows.addAll(scene.controls.get(Scene.SCENE_SECTION));
            Map<String, String> descriptionById = new HashMap<>();
            List<String> ids = new ArrayList<>();
            for (ControlHint row : rows) {
                if (row.action == null) {
                    assertEquals("", row.actionName, "a display-only row has an action id: " + row);
                    continue;
                }
                assertTrue(!row.actionName.isEmpty(), tool.toolName() + ": no action id for " + row);
                // Two rows share an id only as one action under two keys, as redo is on ctrl+shift+Z
                // and ctrl+Y: the same description.
                String earlier = descriptionById.putIfAbsent(row.actionName, row.description);
                assertTrue(earlier == null || earlier.equals(row.description), tool.toolName() + ": '"
                        + row.actionName + "' is both '" + earlier + "' and '" + row.description + "'");
                ids.add(row.actionName);
            }
            idsByScope.put(tool.toolName(), ids);
        }
        assertTrue(idsByScope.get(RingRegionTool.TOOL_NAME).containsAll(
                List.of("hide-selected", "isolate-selected", "name-part", SAVE_RINGS)),
                idsByScope.get(RingRegionTool.TOOL_NAME).toString());
        assertTrue(idsByScope.get(scene.ringTool.toolName()).containsAll(List.of("bisect", "undo",
                "switch-mode", SAVE_RINGS)), idsByScope.get(scene.ringTool.toolName()).toString());
    }

    @Test
    void theSaveRowFiresByItsExactIdAndAPrefixIsRefused() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty(FireControl.ACTION, SAVE_RINGS);
        JsonObject reply = control(body);
        assertTrue(reply.get(OK).getAsBoolean(), reply.toString());
        assertEquals(SAVE_RINGS, reply.get(FireControl.ACTION).getAsString());
        assertEquals("save rings", reply.getAsJsonObject(FireControl.COMMAND).get("description").getAsString());
        body.addProperty(FireControl.ACTION, "save");
        JsonObject prefix = control(body);
        assertTrue(!prefix.get(OK).getAsBoolean() && prefix.getAsJsonArray(FireControl.CANDIDATES).toString()
                .contains(SAVE_RINGS), prefix.toString());
    }

    @Test
    void selectPicksRegionsByNumberFromAnotherToolAndFramesThem() {
        RingRegions regions = scene.regionLayer.regions;
        int top = regions.regionByActiveFace[regions.nearestActiveFace(TOP_POINT[0], TOP_POINT[1], TOP_POINT[2])];
        scene.switchTool(scene.orbitTool);
        terminal.run(SELECT + top);
        assertSame(scene.regionTool, scene.activeTool, "sel did not switch to the region tool");
        boolean[] expected = new boolean[regions.regionCount];
        expected[top] = true;
        assertArrayEquals(expected, scene.regionTool.selectedRegions, scene.regionTool.lastError);
        SelectionBounds bounds = new SelectionBounds();
        bounds.regionFaces(regions, expected);
        Vector3f centre = new Vector3f();
        bounds.sphere(centre);
        assertTrue(centre.distance(scene.camera.orbitTarget) < CENTRE_TOLERANCE,
                "the camera aims at " + scene.camera.orbitTarget + ", not the region's centre " + centre);
        assertTrue(scene.regionTool.lastRow.startsWith("selected and framed"), scene.regionTool.lastRow);

        terminal.run(SELECT + regions.regionCount);
        assertTrue(scene.regionTool.lastError.contains("no region or part '" + regions.regionCount + "'"),
                scene.regionTool.lastError);
        assertArrayEquals(expected, scene.regionTool.selectedRegions, "a refused number changed the selection");
    }

    @Test
    void selectByPartNamePicksEveryRegionItLabels() {
        RingRegions regions = scene.regionLayer.regions;
        scene.regionLayer.partLabels.add(PART, TOP_POINT.clone());
        scene.regionLayer.partLabels.add(PART, BOTTOM_POINT.clone());
        scene.regionLayer.labelsChanged();
        scene.updateScene();
        terminal.run(SELECT + PART);
        boolean[] expected = new boolean[regions.regionCount];
        expected[regions.regionByActiveFace[regions.nearestActiveFace(TOP_POINT[0], TOP_POINT[1], TOP_POINT[2])]] = true;
        expected[regions.regionByActiveFace[regions.nearestActiveFace(BOTTOM_POINT[0], BOTTOM_POINT[1],
                BOTTOM_POINT[2])]] = true;
        assertArrayEquals(expected, scene.regionTool.selectedRegions, scene.regionTool.lastError);

        terminal.run(SELECT + "claw");
        assertTrue(scene.regionTool.lastError.contains("no region or part 'claw'")
                && scene.regionTool.lastError.contains(PART), scene.regionTool.lastError);
    }

    @Test
    void aClickWithShiftAddsARegionToTheSelection() throws Exception {
        RingRegions regions = scene.regionLayer.regions;
        int bottomFace = regions.nearestActiveFace(BOTTOM_POINT[0], BOTTOM_POINT[1], BOTTOM_POINT[2]);
        int topFace = regions.nearestActiveFace(TOP_POINT[0], TOP_POINT[1], TOP_POINT[2]);
        faceUnderCursor[0] = bottomFace;
        JsonObject reply = click("");
        assertTrue(reply.get(OK).getAsBoolean(), reply.toString());
        scene.updateScene();
        assertEquals(1, scene.regionTool.selectedCount(), scene.regionTool.lastError);
        faceUnderCursor[0] = topFace;
        click("shift");
        scene.updateScene();
        assertEquals(2, scene.regionTool.selectedCount(), scene.regionTool.lastError);
        assertTrue(scene.regionTool.selectedRegions[regions.regionByActiveFace[bottomFace]]
                && scene.regionTool.selectedRegions[regions.regionByActiveFace[topFace]]);
        click("");
        scene.updateScene();
        assertEquals(1, scene.regionTool.selectedCount(), "shift stayed held after the shift-click");
    }

    @Test
    void theRegionNumbersRowDrawsEachSeenRegionsNumberInsideTheViewOfIt() throws Exception {
        RingRegions regions = scene.regionLayer.regions;
        faceSeenBelow[0] = regions.nearestActiveFace(BOTTOM_POINT[0], BOTTOM_POINT[1], BOTTOM_POINT[2]);
        faceSeenAbove[0] = regions.nearestActiveFace(TOP_POINT[0], TOP_POINT[1], TOP_POINT[2]);
        int below = regions.regionByActiveFace[faceSeenBelow[0]];
        int above = regions.regionByActiveFace[faceSeenAbove[0]];
        scene.updateScene();
        assertEquals(0, scene.regionLayer.regionNumberText.length, "numbers drawn before the row turned them on");
        JsonObject body = new JsonObject();
        body.addProperty(FireControl.ACTION, "toggle-region-numbers");
        assertTrue(control(body).get(OK).getAsBoolean());
        scene.updateScene();

        String[] text = scene.regionLayer.regionNumberText;
        float[] xy = scene.regionLayer.regionNumberXy;
        assertEquals(regions.regionCount, text.length);
        assertEquals(RegionLayer.REGION_NUMBER_PREFIX + below, text[below]);
        assertEquals(RegionLayer.REGION_NUMBER_PREFIX + above, text[above]);
        assertTrue(xy[2 * below + 1] < WINDOW_SIZE / 2f && xy[2 * above + 1] > WINDOW_SIZE / 2f,
                "a number left the half of the view its region fills: " + Arrays.toString(xy));
        for (int region = 0; region < regions.regionCount; region++) {
            assertTrue(region == below || region == above || text[region] == null,
                    "region " + region + " is numbered but not seen");
        }
        assertTrue(control(body).get(OK).getAsBoolean());
        scene.updateScene();
        assertEquals(0, scene.regionLayer.regionNumberText.length, "the row did not turn the numbers off");
    }

    @Test
    void uiStateExportsTheRegionAndRingDatasetsOnceAndTheItemsAsHandles() throws Exception {
        JsonObject state = new State() {
            {
                runtime = AutomationRuntime.get();
            }
        }.endpointHandler(new JsonObject());
        RingRegions regions = scene.regionLayer.regions;
        JsonArray rows = state.getAsJsonObject("regions").getAsJsonArray("rows");
        assertEquals(regions.regionCount, rows.size());
        for (int region = 0; region < regions.regionCount; region++) {
            JsonObject row = rows.get(region).getAsJsonObject();
            assertEquals(RingRegionTool.regionTag(region), row.get(State.LABEL).getAsString());
            assertEquals(regions.regionArea[region], row.get(State.AREA).getAsDouble(), 1e-12);
            assertEquals(regions.boundingRingsByRegion[region].length, row.getAsJsonArray("boundingRings").size());
            assertEquals(regions.regionCreaseShare[region], row.get(State.CREASE_SHARE).getAsDouble(), 1e-12);
            assertEquals(regions.regionCreaseInterior[region], row.get(State.CREASE_INTERIOR).getAsBoolean());
            assertEquals(3, row.getAsJsonArray(State.CREASE_POINT).size());
        }
        JsonArray rings = state.getAsJsonArray("rings");
        assertEquals(scene.ringTool.confirmedRings.size(), rings.size());
        for (int ring = 0; ring < rings.size(); ring++) {
            JsonObject row = rings.get(ring).getAsJsonObject();
            assertEquals(scene.ringTool.confirmedRings.get(ring).serial, row.get("serial").getAsInt());
            assertEquals(scene.ringTool.confirmedRings.get(ring).mode.label, row.get(State.MODE).getAsString());
        }
        JsonObject regionTool = state.getAsJsonObject("tool");
        JsonObject handle = regionTool.getAsJsonArray("items").get(0).getAsJsonObject();
        assertTrue(!handle.has("values") && !handle.has(State.KIND)
                && !handle.get(State.DETAIL).getAsString().contains(State.AREA),
                "a region handle still carries the region dataset: " + handle);
    }

    private JsonObject control(JsonObject body) throws Exception {
        body.addProperty(FireControl.SETTLE, 0);
        return new FireControl() {
            {
                runtime = AutomationRuntime.get();
                runtime.renderThreadId = Thread.currentThread().getId();
            }
        }.endpointHandler(body);
    }

    private JsonObject click(String modifiers) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty(InjectClick.X, CLICK_PIXELS);
        body.addProperty(InjectClick.Y, CLICK_PIXELS);
        body.addProperty(InjectClick.MODS, modifiers);
        body.addProperty(InjectClick.SETTLE, 0);
        return new InjectClick() {
            {
                runtime = AutomationRuntime.get();
                runtime.renderThreadId = Thread.currentThread().getId();
            }
        }.endpointHandler(body);
    }
}
