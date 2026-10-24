package ixdar.automation.endpoints.ui;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.joml.Vector3f;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.command.Subsystem;
import ixdar.audio.AudioSystem;
import ixdar.automation.AutomationEndpoint;
import ixdar.automation.endpoints.mesh.Fingerprint;
import ixdar.canvas.IxdarWindow;
import ixdar.game.City;
import ixdar.game.CityNetwork;
import ixdar.geometry.grid2d.IrregularQuadGrid;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshRepairReport;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.tsp.PointSetPath;
import ixdar.graphics.cameras.Camera;
import ixdar.gui.ui.ErrorToasts;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.menu.MenuItem;
import ixdar.gui.ui.menu.MenuScrollBar;
import ixdar.gui.ui.menu.MenuScrollBox;
import ixdar.gui.ui.menu.model.SceneModelMenu;
import ixdar.gui.ui.text.HyperString;
import ixdar.gui.ui.text.HyperWord;
import ixdar.models.ModelCollection;
import ixdar.platform.Clock;
import ixdar.platform.Platforms;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;
import ixdar.tools.ControlHint;
import ixdar.tools.Draft;
import ixdar.tools.EditHistory;
import ixdar.tools.Item;
import ixdar.tools.Preview;
import ixdar.tools.Tool;
import ixdar.tools.regions.RegionLayer;

/**
 * The ui-state snapshot, the one place it is written as JSON: the common header (window,
 * framebuffer, frames, focus, scene id), then each dataset the scene shows written by its data
 * type, the active tool in the shape every tool shares, and every pane's scroll box alike.
 */
@AutomationRouteAnnotation(path = "ui/state", method = APIMethod.GET)
public class State extends AutomationEndpoint implements AutomationRoute {

    public static final String ACTIVE = "active";

    public static final String ACTIVE_TOOL = "activeTool";

    public static final String LABEL = "label";

    public static final String NAME = "name";

    public static final String EDGE_COUNT = "edgeCount";

    public static final String FINGERPRINT = "fingerprint";

    public static final String VERTEX_COUNT = "vertexCount";

    public static final String XPX = "xPx";

    public static final String YPX = "yPx";

    public static final String WIDTH = "width";

    public static final String HEIGHT = "height";

    public static final String VALID = "valid";

    public static final String TRADE = "trade";

    public static final String TOOL = "tool";

    public static final String DESCRIPTION = "description";

    public static final String MODE = "mode";

    public static final String CONTROLS = "controls";

    public static final String SCROLL_OFFSET_Y = "scrollOffsetY";

    public static final String DETAIL = "detail";

    public static final String IDLE_ROUTE_STATE = "IDLE";

    public static final String SCROLL_BOX = "scroll_box";

    /**
     * A packed position list as a JSON array, each value at single precision.
     *
     * @param packed the values, packed xyz for positions
     * @return the array
     */
    private static JsonArray floats(float[] packed) {
        JsonArray array = new JsonArray();
        for (float value : packed) {
            array.add(value);
        }
        return array;
    }

    /**
     * An item in the one shape every tool and shared set reports: label, location, fingerprint,
     * size, length, detail, saved, deleted, and its named numbers, points and flags as one
     * {@code values} object.
     *
     * @param item the item
     * @return the item's object
     */
    private static JsonObject itemState(Item item) {
        JsonObject state = new JsonObject();
        state.addProperty(LABEL, item.label);
        state.add("location", floats(item.location));
        state.addProperty(FINGERPRINT, item.fingerprint);
        state.addProperty("size", item.size);
        state.addProperty("length", item.length);
        state.addProperty(DETAIL, item.detail);
        state.addProperty("saved", item.saved);
        state.addProperty("deleted", item.deleted);
        JsonObject values = new JsonObject();
        for (Map.Entry<String, Double> number : item.numbers.entrySet()) {
            values.addProperty(number.getKey(), number.getValue());
        }
        for (Map.Entry<String, float[]> point : item.points.entrySet()) {
            values.add(point.getKey(), floats(point.getValue()));
        }
        for (Map.Entry<String, Boolean> flag : item.flags.entrySet()) {
            values.addProperty(flag.getKey(), flag.getValue());
        }
        state.add("values", values);
        return state;
    }

    /**
     * Items as a JSON array, each in the shape {@link #itemState} writes.
     *
     * @param items the items
     * @return the array
     */
    private static JsonArray itemStates(List<? extends Item> items) {
        JsonArray array = new JsonArray();
        for (Item item : items) {
            array.add(itemState(item));
        }
        return array;
    }

    /**
     * What every tool's last input left, read from the {@link Tool} base: its name, last row and
     * error, undo and redo heads when it keeps a history, and its mode with the options.
     *
     * @param tool the tool
     * @return the tool's summary object
     */
    public static JsonObject toolSummary(Tool tool) {
        JsonObject state = new JsonObject();
        state.addProperty(NAME, tool.toolName());
        state.addProperty("lastRow", tool.lastRow);
        state.addProperty(ERROR, tool.lastError);
        EditHistory<?> history = tool.history();
        if (history != null) {
            state.addProperty("undoDepth", history.undoDepth);
            state.addProperty("redoDepth", history.redoDepth());
            state.addProperty("nextUndo", history.nextUndoName());
            state.addProperty("nextRedo", history.nextRedoName());
        }
        if (tool.modes.length > 0) {
            state.addProperty(MODE, tool.modes[tool.mode]);
            JsonArray modes = new JsonArray();
            for (String option : tool.modes) {
                modes.add(option);
            }
            state.add("modes", modes);
        }
        return state;
    }

    /**
     * A tool in the one shape every tool shares, the one ui-state and the tools listing write:
     * names, description, last row and error, undo heads, mode, controls, draft, preview, hovered
     * item and items, read from the {@link Tool} base and its accessors.
     *
     * @param tool the tool
     * @return the tool's object
     */
    public static JsonObject toolState(Tool tool) {
        JsonObject state = toolSummary(tool);
        state.addProperty("displayName", tool.displayName());
        state.addProperty("shortName", tool.shortName());
        state.addProperty(DESCRIPTION, tool.desc());
        List<ControlHint> hints = new ArrayList<>();
        tool.addControls(hints);
        state.add(CONTROLS, controls(hints));
        Draft draft = tool.draft();
        JsonObject draftState = draft == null ? new JsonObject() : itemState(draft);
        draftState.addProperty("exists", draft != null);
        if (draft != null) {
            draftState.add("points", floats(draft.points));
            JsonArray pointLabels = new JsonArray();
            for (String pointLabel : draft.pointLabels) {
                pointLabels.add(pointLabel);
            }
            draftState.add("pointLabels", pointLabels);
            draftState.addProperty(VALID, draft.valid);
            draftState.addProperty("selectedPoint", draft.selectedPoint);
            draftState.addProperty("hoveredPoint", draft.hoveredPoint);
            if (draft.reopened != null) {
                draftState.add("reopened", itemState(draft.reopened));
            }
        }
        state.add("draft", draftState);
        Preview preview = tool.preview();
        if (preview != null) {
            JsonObject previewState = new JsonObject();
            previewState.add("hitPoint", floats(preview.hitPoint));
            previewState.addProperty(VALID, preview.valid);
            previewState.addProperty("millis", preview.millis);
            previewState.addProperty(DETAIL, preview.detail);
            state.add("preview", previewState);
        }
        Item hovered = tool.hovered();
        if (hovered != null) {
            state.add("hovered", itemState(hovered));
        }
        state.add("items", itemStates(tool.items()));
        return state;
    }

    /**
     * Control hints as ui-state lists them: key label, description, key code, Control and Shift held.
     *
     * @param hints the hints
     * @return one object per hint
     */
    public static JsonArray controls(List<ControlHint> hints) {
        JsonArray controls = new JsonArray();
        for (ControlHint hint : hints) {
            JsonObject control = new JsonObject();
            control.addProperty("key", hint.key);
            control.addProperty(DESCRIPTION, hint.description);
            control.addProperty("keyCode", hint.keyCode);
            control.addProperty("controlHeld", hint.controlHeld);
            control.addProperty("shiftHeld", hint.shiftHeld);
            controls.add(control);
        }
        return controls;
    }

    /**
     * A text element: its type, region and scroll, its plain lines and each drawn word's screen
     * offset and size.
     *
     * @param type          element type tag
     * @param region        named layout region holding the text
     * @param text          the text, or {@code null} for empty lines and words
     * @param scrollOffsetY the region's vertical scroll
     * @return the element
     */
    private static JsonObject textElement(String type, String region, HyperString text,
            float scrollOffsetY) {
        JsonObject element = new JsonObject();
        element.addProperty("type", type);
        element.addProperty("region", region);
        element.addProperty(SCROLL_OFFSET_Y, scrollOffsetY);
        JsonArray lines = new JsonArray();
        JsonArray words = new JsonArray();
        if (text != null) {
            for (String line : text.plainLines()) {
                lines.add(line);
            }
            for (HyperWord drawn : text.words) {
                if (drawn.newLine) {
                    continue;
                }
                JsonObject word = new JsonObject();
                word.addProperty("text", drawn.toString());
                word.addProperty("x", drawn.xScreenOffset);
                word.addProperty("y", drawn.yScreenOffset);
                word.addProperty(WIDTH, drawn.drawnWidth);
                word.addProperty(HEIGHT, drawn.rowHeight);
                words.add(word);
            }
        }
        element.add("lines", lines);
        element.add("words", words);
        return element;
    }

    /**
     * Add a scroll bar's limit, its framebuffer rectangle, y up, and its thumb to an element.
     *
     * @param element the element
     * @param bar     the bar
     */
    private static void addScrollBar(JsonObject element, MenuScrollBar bar) {
        element.addProperty("maximumScrollOffsetY", bar.maximumScrollOffset());
        element.addProperty(XPX, bar.bounds.offsetX);
        element.addProperty(YPX, bar.bounds.offsetY);
        element.addProperty(WIDTH, bar.bounds.viewWidth);
        element.addProperty(HEIGHT, bar.bounds.viewHeight);
        element.addProperty("scrollBarShown", bar.isNeeded());
        element.addProperty("scrollBarThumbStart", bar.thumbStart());
        element.addProperty("scrollBarThumbLength", bar.thumbLength());
    }

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        Scene scene = runtime.canvas;
        List<MenuScrollBox> panes = scene == null ? List.of() : scene.panes();
        JsonObject root = new JsonObject();
        root.addProperty("timestamp", Instant.now().toString());
        root.addProperty("windowWidth", Platforms.get().getWindowWidth());
        root.addProperty("windowHeight", Platforms.get().getWindowHeight());
        root.addProperty("framebufferWidth", Platforms.get().getFrameBufferWidth());
        root.addProperty("framebufferHeight", Platforms.get().getFrameBufferHeight());
        root.addProperty("menuVisible", MenuBox.menuVisible);
        root.addProperty("framesRendered", Clock.framesRendered());
        root.addProperty("terminalFocused", Toggle.IsTerminalFocused.value);
        boolean sceneMenuVisible = false;
        for (MenuScrollBox box : panes) {
            sceneMenuVisible |= box.bounds.id.equals(SceneModelMenu.VIEW_MODELS_BOX);
        }
        root.addProperty("sceneMenuVisible", sceneMenuVisible);
        String sceneId = IxdarWindow.getCanvasId();
        root.addProperty("sceneId", sceneId == null ? "" : sceneId);
        root.addProperty("sceneClass", scene == null ? "" : scene.getClass().getSimpleName());
        CityNetwork network = scene == null ? null : (CityNetwork) scene.dataset(Subsystem.TRADE);
        PointSetPath knot = scene == null ? null : (PointSetPath) scene.dataset(Subsystem.KNOT);
        IrregularQuadGrid grid = scene == null ? null : (IrregularQuadGrid) scene.dataset(Subsystem.GRID);
        root.addProperty(MODE, network != null ? TRADE : knot != null ? "main" : "menu");
        JsonObject trade = new JsonObject();
        trade.addProperty(ACTIVE, network != null && scene.activeTool != null);
        if (network != null && scene.activeTool != null) {
            Tool tool = scene.activeTool;
            trade.addProperty(ACTIVE_TOOL, tool.displayName());
            EditHistory<?> history = tool.history();
            if (history != null) {
                // The route tool's state under the trade block's older names, read off the shape
                // every tool shares: its draft is the operation, labelled by its state.
                Draft draft = tool.draft();
                trade.addProperty("routeMode", tool.modes[tool.mode]);
                trade.addProperty("routeState", draft == null ? IDLE_ROUTE_STATE : draft.label);
                trade.addProperty("selectedCityA",
                        draft == null || draft.pointLabels.length < 1 ? "" : draft.pointLabels[0]);
                trade.addProperty("selectedCityB",
                        draft == null || draft.pointLabels.length < 2 ? "" : draft.pointLabels[1]);
                trade.addProperty("hasRoute", !tool.items().isEmpty());
                trade.addProperty("routeSegmentCount", tool.items().size());
                trade.addProperty("canUndo", history.undoDepth > 0);
                trade.addProperty("canRedo", history.redoDepth() > 0);
                trade.addProperty("inPreview", draft != null && draft.valid);
            }
            City headquarters = network.headquartersCity;
            trade.addProperty("headquartersCity", headquarters == null ? "" : headquarters.name);
            if (network.grid != null) {
                trade.addProperty("gridType", network.grid.getClass().getSimpleName());
            }
            if (grid != null) {
                trade.addProperty("gridSeed", grid.seed());
                trade.addProperty("gridRelaxIterations", grid.relaxIterations());
                trade.addProperty("gridRows", grid.rows());
                trade.addProperty("gridCols", grid.cols());
                trade.addProperty("gridAnchorCount", grid.anchorCount());
            }
            Camera camera = scene.mouse.camera;
            JsonArray cities = new JsonArray();
            for (City city : network.cities) {
                JsonObject cityState = new JsonObject();
                cityState.addProperty(NAME, city.name);
                cityState.addProperty("xWorld", city.getX());
                cityState.addProperty("yWorld", city.getY());
                cityState.addProperty(XPX, camera.pointTransformX(city.getX()));
                cityState.addProperty(YPX, camera.pointTransformY(city.getY()));
                cities.add(cityState);
            }
            trade.add("cities", cities);
        }
        root.add(TRADE, trade);
        if (grid != null) {
            JsonObject irregular = new JsonObject();
            irregular.addProperty("seed", grid.seed());
            irregular.addProperty("relaxIterations", grid.relaxIterations());
            irregular.addProperty("jitter", grid.jitterRatio());
            irregular.addProperty("primalPointCount", grid.anchorCount());
            irregular.addProperty("dualPointCount", grid.dualPointCount());
            irregular.addProperty(EDGE_COUNT, grid.edgeCount());
            irregular.addProperty("horizontalEdgeMean", grid.horizontalEdgeMean());
            irregular.addProperty("verticalEdgeMean", grid.verticalEdgeMean());
            irregular.addProperty("horizontalEdgeStdDev", grid.horizontalEdgeStdDev());
            irregular.addProperty("verticalEdgeStdDev", grid.verticalEdgeStdDev());
            root.add("irregularGrid", irregular);
        }
        GeometryBundle bundle = scene == null ? null : (GeometryBundle) scene.dataset(Subsystem.MESH);
        if (bundle != null && bundle.mesh() != null) {
            MeshTopology shown = bundle.mesh();
            JsonObject mesh = new JsonObject();
            mesh.addProperty(VERTEX_COUNT, shown.vertexCount());
            mesh.addProperty(EDGE_COUNT, shown.edgeCount());
            mesh.addProperty("faceCount", shown.faceCount());
            int boundaryEdges = shown.boundaryEdgeCount();
            mesh.addProperty("boundaryEdgeCount", boundaryEdges);
            mesh.addProperty("degenerateFaceCount", shown.degenerateFaceCount());
            mesh.addProperty("eulerCharacteristic",
                    shown.vertexCount() - shown.edgeCount() + shown.faceCount());
            mesh.addProperty("closed", boundaryEdges == 0);
            Map<String, Object> slots = bundle.slots();
            mesh.addProperty("repairReport", slots.get(MeshRepairReport.SLOT)
                    instanceof MeshRepairReport report ? report.toText() : "");
            mesh.addProperty("radius", shown.radius());
            mesh.addProperty("shaderMode", scene.shaderModeName());
            mesh.addProperty("texturedDraw", scene.texturedDraw());
            mesh.addProperty("slots", String.join(",", new TreeSet<>(slots.keySet())));
            mesh.add("center", runtime.vector3Array(shown.center(new Vector3f())));
            mesh.add("boundingBoxMin", runtime.vector3Array(shown.boundsMin(new Vector3f())));
            mesh.add("boundingBoxMax", runtime.vector3Array(shown.boundsMax(new Vector3f())));
            mesh.add("edgeMarks", Fingerprint.edgeMarks(bundle));
            root.add("mesh", mesh);
        }
        RegionLayer regionLayer = scene == null ? null : (RegionLayer) scene.dataset(Subsystem.RINGS);
        if (regionLayer != null && regionLayer.scene.activeTool != null) {
            JsonObject ringTool = toolState(regionLayer.scene.ringTool);
            ringTool.addProperty(ACTIVE_TOOL, regionLayer.scene.activeTool.toolName());
            root.add("ringTool", ringTool);
            root.add("rings", itemStates(regionLayer.rings()));
            ErrorToasts toasts = regionLayer.scene.errorToasts;
            JsonArray toastRows = new JsonArray();
            for (int toast = 0; toast < toasts.messages.size(); toast++) {
                JsonObject row = new JsonObject();
                row.addProperty("source", toasts.sources.get(toast));
                row.addProperty("message", toasts.messages.get(toast));
                row.addProperty("opacity", toasts.colors.get(toast).toVector4f().w);
                toastRows.add(row);
            }
            root.add("errorToasts", toastRows);
            JsonObject regions = new JsonObject();
            regions.addProperty("regionCount",
                    regionLayer.regions == null ? 0 : regionLayer.regions.regionCount);
            regions.addProperty("visible", regionLayer.visible);
            regions.addProperty("absorbSlivers", regionLayer.absorbSlivers);
            regions.addProperty("hiddenRingCount", regionLayer.hiddenRingLabels.size());
            regions.addProperty("summary", regionLayer.summary);
            root.add("regions", regions);
        }
        ModelCollection modelCollection = scene == null ? null
                : (ModelCollection) scene.dataset(Subsystem.MODEL);
        if (modelCollection != null && modelCollection.hasManifest()) {
            JsonObject collection = new JsonObject();
            collection.addProperty(NAME, modelCollection.name);
            collection.addProperty("directory", modelCollection.directory.toString());
            collection.addProperty("manifest", modelCollection.manifestPath.toString());
            collection.addProperty("memberCount", modelCollection.memberCount());
            collection.addProperty("keptCount", modelCollection.keptCount());
            collection.addProperty("memberIndex", modelCollection.index());
            int currentMember = modelCollection.index();
            collection.addProperty("member",
                    currentMember < 0 ? "" : modelCollection.members[currentMember].displayName);
            JsonArray members = new JsonArray();
            for (int member = 0; member < modelCollection.memberCount(); member++) {
                JsonObject memberState = new JsonObject();
                memberState.addProperty(NAME, modelCollection.members[member].displayName);
                memberState.addProperty("path", modelCollection.members[member].path);
                memberState.addProperty("kind", modelCollection.members[member].kind.name());
                memberState.addProperty("keep", modelCollection.memberKeep[member]);
                memberState.addProperty(VERTEX_COUNT, modelCollection.memberVertexCount[member]);
                memberState.addProperty("triangleCount", modelCollection.memberTriangleCount[member]);
                memberState.addProperty("settings",
                        ModelCollection.settingsSummary(modelCollection.memberSettings[member]));
                members.add(memberState);
            }
            collection.add("members", members);
            root.add("collection", collection);
        }
        if (knot != null && scene.activeTool != null) {
            JsonObject knotEditor = new JsonObject();
            knotEditor.addProperty(ACTIVE_TOOL, scene.activeTool.toolName());
            knotEditor.addProperty("pointCount", knot.ps == null ? 0 : knot.ps.size());
            JsonObject toggles = new JsonObject();
            for (Toggle toggle : Toggle.values()) {
                toggles.addProperty(toggle.shortName, toggle.value);
            }
            knotEditor.add("toggles", toggles);
            root.add("knotEditor", knotEditor);
        }
        if (scene != null && scene.activeTool != null) {
            root.add(TOOL, toolState(scene.activeTool));
        }

        JsonArray textElements = new JsonArray();
        JsonArray menuItems = new JsonArray();
        boolean menuShown = false;
        for (MenuScrollBox box : panes) {
            JsonObject element = textElement(SCROLL_BOX, box.bounds.id, box.text,
                    box.scrollBar.scrollOffset);
            element.addProperty("drawnScrollOffsetY", box.drawnScrollOffsetY());
            element.addProperty("rowsUsed", box.rowsUsed);
            addScrollBar(element, box.scrollBar);
            textElements.add(element);
            if (!box.bounds.id.equals(MenuBox.VIEW_MENU_ITEMS)) {
                continue;
            }
            // The main menu's rows: each row's words sit on its button's rectangle.
            menuShown = true;
            List<String> labels = box.text.plainLines();
            for (int row = 0; row < labels.size(); row++) {
                for (HyperWord word : box.text.getLine(row)) {
                    if (word.newLine) {
                        continue;
                    }
                    JsonObject menuItem = new JsonObject();
                    menuItem.addProperty(LABEL, labels.get(row));
                    menuItem.addProperty("enabled", word.clickAction != null);
                    JsonObject bounds = new JsonObject();
                    bounds.addProperty(XPX, word.xScreenOffset);
                    bounds.addProperty(YPX, word.yScreenOffset);
                    bounds.addProperty("widthPx", word.drawnWidth);
                    bounds.addProperty("heightPx", word.rowHeight);
                    bounds.addProperty("centerXPx", word.xScreenOffset + word.drawnWidth / 2);
                    bounds.addProperty("centerYPx", word.yScreenOffset + word.rowHeight / 2);
                    menuItem.add("bounds", bounds);
                    menuItems.add(menuItem);
                    break;
                }
            }
            JsonObject menuScroll = new JsonObject();
            menuScroll.addProperty(SCROLL_OFFSET_Y, box.scrollBar.scrollOffset);
            addScrollBar(menuScroll, box.scrollBar);
            root.add("menuScroll", menuScroll);
        }
        if (scene != null && scene.toolTip != null && scene.showToolTip) {
            textElements.add(textElement(network != null ? "trade_tooltip" : "tooltip",
                    Scene.VIEW_TOOLTIP, scene.toolTip, 0));
        }
        root.add("textElements", textElements);
        if (!menuShown && MenuBox.menuItems != null) {
            for (MenuItem item : MenuBox.menuItems) {
                JsonObject menuItem = new JsonObject();
                menuItem.addProperty(LABEL, item.getHeading());
                menuItems.add(menuItem);
            }
        }
        root.add("menuItems", menuItems);
        root.add(CONTROLS, scene == null ? new JsonArray() : controls(scene.controls));

        JsonObject audio = new JsonObject();
        AudioSystem audioSystem = AudioSystem.get();
        audio.addProperty("available", audioSystem.isAvailable());
        audio.addProperty("menuMusicPlaying", audioSystem.isMenuMusicPlaying());
        audio.addProperty("menuMusicSourceCount", audioSystem.getMenuMusicSourceCount());
        audio.addProperty("lastSfxPlayed", audioSystem.getLastSfxPlayed());
        JsonObject sfxCounts = new JsonObject();
        for (Map.Entry<String, Integer> entry : audioSystem.getSfxPlayCountSnapshot().entrySet()) {
            sfxCounts.addProperty(entry.getKey(), entry.getValue());
        }
        audio.add("sfxPlayCountById", sfxCounts);
        JsonArray audioEvents = new JsonArray();
        for (String event : audioSystem.getEventLogSnapshot()) {
            audioEvents.add(event);
        }
        audio.add("eventLog", audioEvents);
        root.add("audio", audio);
        return root;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Snapshot the full UI state: window, frames, scene, trade, mesh, text, menu, and audio.")
                .responseHint("{timestamp, windowWidth, windowHeight, framebufferWidth, framebufferHeight, "
                        + "menuVisible, framesRendered, terminalFocused, sceneMenuVisible, sceneId, sceneClass, "
                        + "mode, trade, irregularGrid?, mesh?, ringTool?, rings?, errorToasts?, regions?, "
                        + "collection?, knotEditor?, tool?, textElements, menuItems, menuScroll?, controls, audio}")
                .build();
    }
}
