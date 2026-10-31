package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.automation.AutomationRuntime;
import ixdar.automation.endpoints.input.InjectClick;
import ixdar.automation.endpoints.input.InjectHover;
import ixdar.automation.endpoints.ui.SelectionBounds;
import ixdar.automation.endpoints.ui.State;
import ixdar.automation.endpoints.ui.tools.FireControl;
import ixdar.geometry.mesh.data.labels.MeshLabels;
import ixdar.geometry.mesh.data.paths.query.SurfacePicker;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.MeshOverlayRuntime;
import ixdar.gui.ui.text.HyperString;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.terminal.commands.RingDeleteCommand;
import ixdar.gui.terminal.commands.SelectCommand;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.lwjgl.DefaultBuffer;
import ixdar.platform.input.Keys;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;
import ixdar.tools.regions.RegionLayer;
import ixdar.tools.regions.RingRegionTool;
import ixdar.tools.ring.RingTool;

/**
 * The ring scene driven as automation drives it, on the cut cylinder of {@link RingRegionsTest}:
 * action ids, firing by id, {@code sel} selecting and framing regions, and a shift-click adding one.
 */
class RegionControlAutomationTest {

    private static final int STAND_IN_PLATFORM_ID = 7718;

    private static final int WINDOW_SIZE = 64;

    private static final int LOW_RING_ROW = 2;

    private static final int HIGH_RING_ROW = 5;

    private static final String LOW_RING = "ring_02";

    private static final String HIGH_RING = "ring_05";

    private static final String REGIONS = "regions";

    private static final String GET_PLATFORM_ID = "getPlatformID";

    private static final String SELECT = SelectCommand.cmd + " ";

    private static final String OK = "ok";

    private static final String SAVE_RINGS = "save-rings";

    private static final String PART = "tail";

    private static final String TOGGLE_NUMBERS = RingScene.TOGGLE_NUMBERS_ACTION;

    private static final String TOGGLE_LABELS = RingScene.TOGGLE_LABELS_ACTION;

    private static final String TOGGLE_REGION_COLOURS = "toggle-region-colours";

    private static final float SEEN_PIXEL_X = 20f;

    private static final float SEEN_PIXEL_FROM_TOP = 10f;

    private static final float BAND_MIDDLE_Z = 1.75f;

    private static final float ROW_HEIGHT = 0.5f;

    private static final float ORBIT_ELEVATION = 1.2f;

    private static final float OUTSIDE_DISTANCE = 6f;

    private static final float INSIDE_DISTANCE = 0.75f;

    private static final float NEAR_PLANE = 0.01f;

    private static final float FAR_PLANE = 100f;

    private static final double RING_TOLERANCE = 1e-3;

    private static final float DEPTH_LIFT = 0.125f;

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

    /**
     * Vertex the tools' cursor pick lands on, at its position, or -1 for the scene's own pick, which
     * the stand-in GPU misses.
     */
    private final int[] cursorOnVertex = { -1 };

    /** The region colours the surface's runtime was last handed. */
    private Vector4f[] drawnColours = new Vector4f[0];

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

            @Override
            public boolean pickCursor(HalfEdgeMeshRuntime runtime, MeshTopology surface) {
                if (cursorOnVertex[0] < 0) {
                    return super.pickCursor(runtime, surface);
                }
                Vector3f position = new Vector3f();
                surface.vertexPosition(cursorOnVertex[0], position);
                cursorVertexId = cursorOnVertex[0];
                cursorFaceId = surface.vertexFaceAt(cursorVertexId, 0);
                cursorPoint[0] = position.x;
                cursorPoint[1] = position.y;
                cursorPoint[2] = position.z;
                return true;
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

        };
        scene.meshRuntime.upload(cylinder);
        scene.ringMarksByLabel.put(LOW_RING, RingRegionsUpdateTest.rowMask(cylinder, LOW_RING_ROW, false));
        scene.ringMarksByLabel.put(HIGH_RING, RingRegionsUpdateTest.rowMask(cylinder, HIGH_RING_ROW, false));
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

    /**
     * Each region's number hangs on a fixed point of its own region, the face deepest from its
     * rings, and stays there while the camera moves: the band between the two rings is labelled
     * at its middle row.
     */
    @Test
    void theRegionNumbersRowAnchorsEachNumberDeepInsideItsRegionWhereverTheCameraIs() throws Exception {
        RingRegions regions = scene.regionLayer.regions;
        scene.updateScene();
        assertEquals(0, scene.shownLabels.size(), "numbers drawn before the row turned them on");
        JsonObject body = new JsonObject();
        body.addProperty(FireControl.ACTION, TOGGLE_NUMBERS);
        assertTrue(control(body).get(OK).getAsBoolean());
        scene.updateScene();

        assertEquals(regions.regionCount, scene.shownLabels.size());
        float[] anchors = new float[RingRegions.TRIANGLE_CORNERS * regions.regionCount];
        for (HyperString label : scene.shownLabels) {
            int region = (Integer) label.getData();
            assertEquals(RegionLayer.REGION_NUMBER_PREFIX + region, label.plainLines().get(0));
            assertSame(scene.camera, label.anchorCamera, "a region number is not anchored in 3D");
            assertEquals(region, regions.regionByActiveFace[regions.nearestActiveFace(label.anchor.x,
                    label.anchor.y, label.anchor.z)], "R" + region + " sits outside its region");
            anchors[RingRegions.TRIANGLE_CORNERS * region] = label.anchor.x;
            anchors[RingRegions.TRIANGLE_CORNERS * region + 1] = label.anchor.y;
            anchors[RingRegions.TRIANGLE_CORNERS * region + 2] = label.anchor.z;
        }
        int between = regions.regionByActiveFace[regions.nearestActiveFace(1f, 0f, BAND_MIDDLE_Z)];
        assertEquals(BAND_MIDDLE_Z, anchors[RingRegions.TRIANGLE_CORNERS * between + 2], ROW_HEIGHT,
                "the band's number is not at its middle row");

        scene.camera.setOrbit(1f, 1f, OUTSIDE_DISTANCE);
        scene.updateScene();
        scene.updateScene();
        for (HyperString label : scene.shownLabels) {
            int base = RingRegions.TRIANGLE_CORNERS * (Integer) label.getData();
            assertEquals(new Vector3f(anchors[base], anchors[base + 1], anchors[base + 2]), label.anchor,
                    "a region number moved with the camera");
        }
        assertTrue(control(body).get(OK).getAsBoolean());
        scene.updateScene();
        assertEquals(0, scene.shownLabels.size(), "the row did not turn the numbers off");
    }

    /**
     * A named region's label is its part name, centred on the region, and joins its number when
     * both are shown, so one label per region never overlaps another on the same region.
     */
    @Test
    void aNamedRegionDrawsOneLabelWithItsNumberAndPartName() throws Exception {
        RingRegions regions = scene.regionLayer.regions;
        int below = regions.regionByActiveFace[regions.nearestActiveFace(BOTTOM_POINT[0], BOTTOM_POINT[1],
                BOTTOM_POINT[2])];
        scene.regionLayer.partLabels.add(PART, BOTTOM_POINT.clone());
        scene.regionLayer.labelsChanged();
        scene.updateScene();
        assertEquals(0, scene.shownLabels.size(), "a part name drawn before the labels row turned them on");
        JsonObject labels = new JsonObject();
        labels.addProperty(FireControl.ACTION, TOGGLE_LABELS);
        assertTrue(control(labels).get(OK).getAsBoolean());
        scene.updateScene();
        assertEquals(1, scene.shownLabels.size());
        HyperString named = scene.shownLabels.get(0);
        assertEquals(PART, named.plainLines().get(0));
        assertEquals(below, named.getData());
        assertEquals(below, regions.regionByActiveFace[regions.nearestActiveFace(named.anchor.x,
                named.anchor.y, named.anchor.z)], "the name left its region");
        assertEquals(scene.palette.background, named.getWord(0).borderColor,
                "a label draws in its own bordered style");

        JsonObject body = new JsonObject();
        body.addProperty(FireControl.ACTION, TOGGLE_NUMBERS);
        assertTrue(control(body).get(OK).getAsBoolean());
        scene.updateScene();
        assertEquals(regions.regionCount, scene.shownLabels.size(), "one label per region");
        for (HyperString label : scene.shownLabels) {
            int region = (Integer) label.getData();
            assertEquals(RegionLayer.REGION_NUMBER_PREFIX + region + (region == below ? " " + PART : ""),
                    label.plainLines().get(0));
        }
    }

    /**
     * A string anchored on a world point asks the camera's visibility answer, with its depth lift,
     * and is not shown while that answer says the point is hidden. The stand-in font has no
     * metrics, so where it lays out is checked live, not here.
     */
    @Test
    void aWorldAnchoredStringLaysOutWhereItsPointIsSeenAndHidesWhereItIsNot() {
        HyperString number = new HyperString();
        number.addWord("7", scene.palette.text, scene.palette.background,
                RingScene.LABEL_BORDER_PIXELS);
        number.anchorCamera = scene.camera;
        number.anchor.set(1f, 2f, 3f);
        boolean[] seen = { true };
        float[] askedLift = { -1f };
        scene.camera.pointVisibility = (camera, x, y, z, depthLift, pixel) -> {
            pixel[0] = SEEN_PIXEL_X;
            pixel[1] = SEEN_PIXEL_FROM_TOP;
            askedLift[0] = depthLift;
            return seen[0];
        };
        number.anchorDepthLift = DEPTH_LIFT;

        number.layoutAtAnchor(scene.camera2D, RingScene.LABEL_ROW_PIXELS);
        assertTrue(number.shown);
        assertEquals(DEPTH_LIFT, askedLift[0], "the anchor's depth lift is what the hiding test gets");

        seen[0] = false;
        number.layoutAtAnchor(scene.camera2D, RingScene.LABEL_ROW_PIXELS);
        assertTrue(!number.shown, "a hidden anchor left the string shown");
    }

    /**
     * A ring round the tube puts its number on a loop point on the side facing a camera outside,
     * and shows none from inside the tube or while the surface hides every loop point.
     */
    @Test
    void aRingNumberSitsOnTheLoopFacingTheCameraAndHidesFromInsideAndBehind() {
        scene.meshRuntime = new MeshOverlayRuntime();
        scene.meshRuntime.upload(cylinder);
        scene.ringTool.invalidateRings();
        scene.ringTool.uploadOverlay();
        assertTrue(!scene.ringTool.ringNumbers.isEmpty(), "the rings have no numbers");
        Vector3f eye = new Vector3f();
        boolean[] surfaceHidesAll = { false };
        // The near half of the tube is what a depth test would show: points on the eye's side.
        scene.camera.pointVisibility = (camera, x, y, z, depthLift, pixel) -> camera.projectToPixels(
                new Matrix4f(), x, y, z, pixel) && !surfaceHidesAll[0] && x * eye.x + y * eye.y > 0f;
        scene.camera.setTarget(new Vector3f(0f, 0f, 1f));

        scene.camera.setOrbit(0f, ORBIT_ELEVATION, OUTSIDE_DISTANCE);
        scene.camera.updateProjection(false, NEAR_PLANE, FAR_PLANE);
        eye.set(scene.camera.position);
        assertTrue(Math.hypot(eye.x, eye.y) > 1.0, "the camera is not outside the tube: " + eye);
        scene.ringTool.placeRingNumbers(scene.camera);
        for (HyperString number : scene.ringTool.ringNumbers) {
            assertTrue(number.shown, "a ring facing the camera shows no number");
            assertEquals(1.0, Math.hypot(number.anchor.x, number.anchor.y), RING_TOLERANCE,
                    "the number is not on its loop: " + number.anchor);
            assertTrue(number.anchor.x * eye.x + number.anchor.y * eye.y > 0f,
                    "the number sits on the far side of the tube: " + number.anchor);
        }

        surfaceHidesAll[0] = true;
        scene.camera.setOrbit(0f, ORBIT_ELEVATION, OUTSIDE_DISTANCE + 1f);
        scene.ringTool.placeRingNumbers(scene.camera);
        for (HyperString number : scene.ringTool.ringNumbers) {
            assertTrue(!number.shown, "a ring the surface hides still shows its number");
        }

        surfaceHidesAll[0] = false;
        scene.camera.setOrbit(0f, ORBIT_ELEVATION, INSIDE_DISTANCE);
        eye.set(scene.camera.position);
        assertTrue(Math.hypot(eye.x, eye.y) < 1.0, "the camera is not inside the tube: " + eye);
        scene.ringTool.placeRingNumbers(scene.camera);
        for (HyperString number : scene.ringTool.ringNumbers) {
            assertTrue(!number.shown, "a ring seen from inside the tube shows its number");
        }
    }

    @Test
    void uiStateExportsTheRegionAndRingDatasetsOnceAndTheItemsAsHandles() throws Exception {
        JsonObject state = new State() {
            {
                runtime = AutomationRuntime.get();
            }
        }.endpointHandler(new JsonObject());
        RingRegions regions = scene.regionLayer.regions;
        JsonArray rows = state.getAsJsonObject(REGIONS).getAsJsonArray("rows");
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

    /**
     * TOOL-49: rings deleted by label in the ring tool, by live label and by drawn number, show in
     * the regions dataset while the ring tool stays active, and Ctrl+Z brings the ring back.
     */
    @Test
    void ringsDeletedByLabelInTheRingToolKeepTheRegionsDatasetCurrent() throws Exception {
        RingTool rings = scene.ringTool;
        assertEquals(2, rings.confirmedRings.size(), "the graph's rings were not adopted: " + rings.lastError);
        assertEquals(3, regionCount(state()));

        terminal.run(RingDeleteCommand.cmd + " " + HIGH_RING);
        assertSame(rings, scene.activeTool, "rd did not switch to the ring tool");
        assertTrue(rings.confirmedRings.get(rings.ringOfSourceLabel(HIGH_RING)).deleted, rings.lastError);
        scene.updateScene();
        JsonObject state = state();
        assertEquals(2, regionCount(state), "the regions trail the ring tool's edit");
        assertFalse(state.getAsJsonObject(REGIONS).toString().contains(HIGH_RING),
                "a region is still bounded by the deleted ring");

        int low = rings.ringOfSourceLabel(LOW_RING);
        terminal.run(RingDeleteCommand.cmd + " " + rings.drawnRingNumber(low));
        scene.updateScene();
        assertEquals(1, regionCount(state()), "the ring deleted by its drawn number still splits");
        assertTrue(rings.undo(), rings.lastError);
        scene.updateScene();
        assertEquals(2, regionCount(state()), "undo did not bring the ring back into the regions");

        terminal.run(RingDeleteCommand.cmd + " 7");
        assertTrue(rings.lastError.contains("no live ring '7'"), rings.lastError);
        assertFalse(rings.confirmedRings.get(low).deleted, "a refused list deleted a ring");
    }

    /** TOOL-49: a toggle's reply says the state it switched to, not an earlier row's outcome. */
    @Test
    void aToggleRowsReplySaysWhatItSwitchedTo() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty(FireControl.ACTION, TOGGLE_LABELS);
        JsonObject reply = control(body);
        assertEquals("labels shown: " + (scene.showLabels ? "on" : "off"),
                reply.getAsJsonObject(State.TOOL).get("lastRow").getAsString());
    }

    /**
     * CRAW-83: the numbers row and the labels row are the ring scene's own, on N and P, and fire
     * from every tool over the scene's view and the ring tool's bisector, no tool row taking their
     * keys; the rows they replace are gone and both start off.
     */
    @Test
    void theNumbersAndLabelsRowsAreSceneRowsThatFireFromEveryToolAndStartOff() throws Exception {
        assertFalse(new RingScene().showNumbers || new RingScene().showLabels,
                "a fresh scene shows numbers or labels");
        List<Tool> modes = new ArrayList<>(scene.tools);
        modes.removeIf(Tool::replacesView);
        modes.add(scene.ringTool.bisector);
        Map<String, Integer> keyByAction = Map.of(TOGGLE_NUMBERS, Keys.N, TOGGLE_LABELS, Keys.P);
        for (Tool mode : modes) {
            if (mode == scene.ringTool.bisector) {
                scene.switchTool(scene.ringTool);
                scene.ringTool.switchSubTool(mode);
            } else {
                scene.switchTool(mode);
            }
            for (List<ControlHint> section : scene.controls) {
                for (ControlHint row : section) {
                    assertFalse(List.of("toggle-ring-numbers", "toggle-region-numbers", "toggle-part-names",
                            "toggle-colour-by-part").contains(row.actionName), mode.toolName() + " still has "
                                    + row.actionName);
                }
            }
            for (Map.Entry<String, Integer> action : keyByAction.entrySet()) {
                assertTrue(scene.controls.get(Scene.SCENE_SECTION).stream()
                        .anyMatch(row -> row.actionName.equals(action.getKey())),
                        action.getKey() + " is not a scene row in " + mode.toolName());
                boolean numbersBefore = scene.showNumbers;
                boolean labelsBefore = scene.showLabels;
                scene.keys.handleSceneKeys(action.getValue(), 0);
                assertEquals(action.getKey(), scene.keys.lastFiredControl.actionName,
                        "in " + mode.toolName() + " the key fired another row");
                JsonObject body = new JsonObject();
                body.addProperty(FireControl.ACTION, action.getKey());
                assertTrue(control(body).get(OK).getAsBoolean(), mode.toolName());
                assertTrue(numbersBefore == scene.showNumbers && labelsBefore == scene.showLabels,
                        mode.toolName() + ": the key then the id did not flip " + action.getKey() + " and back");
            }
        }
    }

    /**
     * CRAW-83: G shows the region colours in the ring tool while the labels are off and the part
     * colours while they are on, whatever the region tool was left showing; with labels on, the
     * parts are coloured in every tool.
     */
    @Test
    void partColouringFollowsTheLabelsRowAndGShowsRegionColoursWithLabelsOff() throws Exception {
        scene.meshRuntime = new HalfEdgeMeshRuntime() {
            @Override
            public void setFaceGroups(MeshTopology mesh, int[] groupByActiveFace, Vector4f[] colourByGroup,
                    String[] tagByGroup) {
                drawnColours = colourByGroup.clone();
                super.setFaceGroups(mesh, groupByActiveFace, colourByGroup, tagByGroup);
            }
        };
        scene.meshRuntime.upload(cylinder);
        RegionLayer layer = scene.regionLayer;
        layer.partLabels.add(PART, TOP_POINT.clone());
        layer.labelsChanged();
        RingRegions regions = layer.regions;
        Vector4f[] regionColours = new Vector4f[regions.regionCount];
        Vector4f[] partColours = new Vector4f[regions.regionCount];
        Palette palette = scene.palette;
        for (int region = 0; region < regions.regionCount; region++) {
            regionColours[region] = regions.isSliver(region) ? palette.warning.toVector4f()
                    : Palette.shadedEntry(palette.region, regions.colouring.colourByNode[region]).toVector4f();
            int label = layer.partLabels.labelByRegion[region];
            partColours[region] = label == MeshLabels.UNLABELLED ? palette.surface.toVector4f()
                    : Palette.shadedEntry(palette.categories, layer.partLabels.colouring.colourByNode[label])
                            .toVector4f();
        }
        assertFalse(Arrays.equals(regionColours, partColours), "the fixture cannot tell the colourings apart");
        JsonObject colours = new JsonObject();
        colours.addProperty(FireControl.ACTION, TOGGLE_REGION_COLOURS);
        JsonObject labels = new JsonObject();
        labels.addProperty(FireControl.ACTION, TOGGLE_LABELS);

        scene.updateScene();
        assertArrayEquals(regionColours, drawnColours, "the region tool coloured by part with labels off");
        scene.switchTool(scene.ringTool);
        assertTrue(control(colours).get(OK).getAsBoolean());
        scene.updateScene();
        assertArrayEquals(regionColours, drawnColours, "G in the ring tool coloured by part with labels off");
        assertTrue(control(labels).get(OK).getAsBoolean());
        scene.updateScene();
        assertArrayEquals(partColours, drawnColours, "labels on did not colour the parts");
        assertTrue(control(labels).get(OK).getAsBoolean());
        scene.updateScene();
        assertArrayEquals(regionColours, drawnColours, "labels off did not bring the region colours back");

        assertTrue(control(colours).get(OK).getAsBoolean());
        scene.switchTool(scene.orbitTool);
        scene.updateScene();
        assertFalse(layer.coloured(), "the orbit tool coloured the surface with G and labels off");
        assertTrue(control(labels).get(OK).getAsBoolean());
        scene.updateScene();
        assertTrue(layer.coloured(), "labels on left the orbit tool's surface plain");
        assertArrayEquals(partColours, drawnColours, "labels on did not colour the parts in the orbit tool");
    }

    /**
     * CRAW-83: with the numbers on, the ring the ring sub-tool or the bisector hovers has its number
     * set aside to draw beside the cursor while the others stay on their loops; leaving the ring,
     * or a tool that hovers no ring, puts it back.
     */
    @Test
    void theHoveredRingsNumberMovesToTheCursorInRingModeAndReturnsWhenLeft() throws Exception {
        scene.meshRuntime = new MeshOverlayRuntime();
        scene.meshRuntime.upload(cylinder);
        RingTool rings = scene.ringTool;
        rings.invalidateRings();
        scene.switchTool(rings);
        JsonObject numbers = new JsonObject();
        numbers.addProperty(FireControl.ACTION, TOGGLE_NUMBERS);
        assertTrue(control(numbers).get(OK).getAsBoolean());
        int low = rings.ringOfSourceLabel(LOW_RING);
        int high = rings.ringOfSourceLabel(HIGH_RING);
        scene.updateScene();
        assertEquals(2, rings.ringNumbers.size(), rings.lastError);
        assertEquals(null, scene.hoveredRingNumber, "a number left its loop with the cursor off every ring");
        assertTrue(scene.shownLabels.containsAll(rings.ringNumbers));

        for (Tool mode : List.of(rings.closedRing, rings.bisector)) {
            rings.switchSubTool(mode);
            for (int ring : new int[] { low, high }) {
                int edgeId = rings.confirmedRings.get(ring).spline.marks.markedEdgeIds[0];
                cursorOnVertex[0] = cylinder.halfEdgeVertex(cylinder.edgeHalfEdge(edgeId));
                scene.updateScene();
                HyperString hovered = scene.hoveredRingNumber;
                assertTrue(hovered != null && hovered.getData().equals(rings.drawnRingNumber(ring)),
                        mode.toolName() + ": ring " + rings.drawnRingNumber(ring) + "'s number did not go to the "
                                + "cursor: " + hovered);
                assertFalse(scene.shownLabels.contains(hovered), "the hovered number is also on its loop");
                assertEquals(1, rings.ringNumbers.stream().filter(scene.shownLabels::contains).count(),
                        "the other ring's number left its loop");
            }
            cursorOnVertex[0] = -1;
            scene.updateScene();
            assertEquals(null, scene.hoveredRingNumber, mode.toolName() + ": leaving the ring kept its number");
            assertTrue(scene.shownLabels.containsAll(rings.ringNumbers), "leaving did not put the number back");
        }
        int edgeId = rings.confirmedRings.get(low).spline.marks.markedEdgeIds[0];
        cursorOnVertex[0] = cylinder.halfEdgeVertex(cylinder.edgeHalfEdge(edgeId));
        scene.switchTool(scene.orbitTool);
        scene.updateScene();
        assertEquals(null, scene.hoveredRingNumber, "the orbit tool moved a ring's number");
    }

    /**
     * TOOL-49: on a torus, the one ring round its tube splits nothing, and the regions dataset
     * names a point on the far side, where its two sides stay joined.
     */
    @Test
    void aRingThatSplitsNothingIsListedWithAPointWhereItsSidesStayJoined() throws Exception {
        cylinder = RingRegionsTest.torus();
        scene.meshRuntime.upload(cylinder);
        scene.ringMarksByLabel.clear();
        scene.ringMarksByLabel.put(LOW_RING, RingRegionsTest.meridian(cylinder, 0));
        scene.ringTool.graphRingsChanged();
        scene.updateScene();
        scene.updateScene();

        JsonArray joined = state().getAsJsonObject(REGIONS).getAsJsonArray("splitsNothing");
        assertEquals(1, joined.size(), joined.toString());
        JsonObject ring = joined.get(0).getAsJsonObject();
        assertEquals(LOW_RING, ring.get(State.RING).getAsString());
        assertTrue(ring.getAsJsonArray("joinedAt").get(0).getAsFloat() < -1f,
                "the join is not on the torus's far side: " + ring);
    }

    /**
     * TOOL-49: a hover's reply names the surface under the cursor as pick does, with the crease
     * strength the ring tool's crease mode follows there, whichever tool is active.
     */
    @Test
    void aHoverRepliesWithTheSurfacePointAndItsCreaseStrength() throws Exception {
        Vector3f centre = new Vector3f(BOTTOM_POINT[0], BOTTOM_POINT[1], BOTTOM_POINT[2]);
        scene.camera.frame(centre, 1f, Camera3D.DEFAULT_FRAME_PADDING, scene.camera.aspectRatio());
        scene.updateScene();
        float[] pixel = new float[BOTTOM_POINT.length];
        assertTrue(scene.projectPoint(centre.x, centre.y, centre.z, pixel, new SurfacePicker()),
                "the framed point is behind the camera");
        JsonObject body = new JsonObject();
        body.addProperty(InjectHover.X, pixel[0]);
        body.addProperty(InjectHover.Y, pixel[1]);
        body.addProperty(InjectHover.SETTLE, 0);
        JsonObject reply = new InjectHover() {
            {
                runtime = AutomationRuntime.get();
                runtime.renderThreadId = Thread.currentThread().getId();
            }
        }.endpointHandler(body);

        JsonObject surface = reply.getAsJsonObject(InjectHover.SURFACE);
        assertTrue(surface != null && surface.get("hit").getAsBoolean(), reply.toString());
        int faceId = surface.get("faceId").getAsInt();
        float[] strength = scene.ringTool.creases.valleyStrength;
        double mean = 0.0;
        for (int corner = 0; corner < cylinder.faceVertexCount(faceId); corner++) {
            mean += strength[cylinder.faceVertexAt(faceId, corner)] / cylinder.faceVertexCount(faceId);
        }
        assertEquals(mean, surface.get("creaseStrength").getAsDouble(), 1e-9, surface.toString());
        assertTrue(surface.has("inCrease") && surface.has("point") && surface.has("region"), surface.toString());
    }

    private JsonObject state() throws Exception {
        return new State() {
            {
                runtime = AutomationRuntime.get();
            }
        }.endpointHandler(new JsonObject());
    }

    private static int regionCount(JsonObject state) {
        return state.getAsJsonObject(REGIONS).get("regionCount").getAsInt();
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
