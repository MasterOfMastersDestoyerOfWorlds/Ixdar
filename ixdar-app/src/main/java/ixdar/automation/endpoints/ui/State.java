package ixdar.automation.endpoints.ui;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.joml.Vector2f;
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
import ixdar.game.trade.TradeGame;
import ixdar.game.trade.map.City;
import ixdar.game.trade.map.Place;
import ixdar.game.trade.routes.GrowTarget;
import ixdar.geometry.grid2d.IrregularQuadGrid;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.labels.MeshLabels;
import ixdar.geometry.mesh.data.ops.MeshRepairReport;
import ixdar.geometry.mesh.data.paths.query.SurfaceConnection;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.geometry.tsp.PointSetPath;
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
import ixdar.scenes.trade.TradeScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Draft;
import ixdar.tools.EditHistory;
import ixdar.tools.Item;
import ixdar.tools.Preview;
import ixdar.tools.Tool;
import ixdar.tools.regions.RegionLayer;
import ixdar.tools.regions.RingRegionTool;
import ixdar.tools.ring.ConfirmedRing;
import ixdar.tools.ring.RingTool;

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

    public static final String KIND = "kind";

    public static final String DELETED = "deleted";

    public static final String SOURCE = "source";

    public static final String REGION = "region";

    public static final String RING = "ring";

    public static final String FACE_COUNT = "faceCount";

    public static final String AREA = "area";

    public static final String CREASE_SHARE = "creaseShare";

    public static final String INTERIOR_CREASE_SHARE = "interiorCreaseShare";

    public static final String PEAK_CURVATURE = "peakCurvature";

    public static final String PEAK_OVER_SATURATION = "peakOverSaturation";

    public static final String CREASE_POINT = "creasePoint";

    public static final String CREASE_INTERIOR = "creaseInterior";

    public static final String IDLE_ROUTE_STATE = "IDLE";

    public static final String SCROLL_BOX = "scroll_box";
    public static final String ERROR_TOASTS = "errorToasts";

    /**
     * A scene's shown refusals, newest first, each with the tool that raised it and how faded it is.
     *
     * @param toasts the scene's error toasts
     * @return one row per shown refusal
     */
    private static JsonArray toastRows(ErrorToasts toasts) {
        JsonArray rows = new JsonArray();
        for (int toast = 0; toast < toasts.messages.size(); toast++) {
            JsonObject row = new JsonObject();
            row.addProperty(SOURCE, toasts.sources.get(toast));
            row.addProperty("message", toasts.messages.get(toast));
            row.addProperty("opacity", toasts.colors.get(toast).toVector4f().w);
            rows.add(row);
        }
        return rows;
    }

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
     * An item handle in the one shape every tool reports: label, location, fingerprint, size,
     * length, detail, saved and deleted.
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
        state.addProperty(DELETED, item.deleted);
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
     * error, undo and redo heads when it keeps a history, its mode with the options, and the
     * sub-tool taking its input among its sub-tools, the tool's own name while none does.
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
        if (!tool.subTools.isEmpty()) {
            state.addProperty("subTool", tool.activeSubTool == null ? tool.toolName()
                    : tool.activeSubTool.toolName());
            JsonArray subTools = new JsonArray();
            for (Tool subTool : tool.subTools) {
                subTools.add(subTool.toolName());
            }
            state.add("subTools", subTools);
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
     * Control hints as ui-state lists them: action id, key label, description, key code, Control
     * and Shift held.
     *
     * @param hints the hints
     * @return one object per hint
     */
    public static JsonArray controls(List<ControlHint> hints) {
        JsonArray controls = new JsonArray();
        for (ControlHint hint : hints) {
            JsonObject control = new JsonObject();
            control.addProperty("action", hint.actionName);
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
        element.addProperty(REGION, region);
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
        TradeGame tradeGame = scene == null ? null : (TradeGame) scene.dataset(Subsystem.TRADE);
        PointSetPath knot = scene == null ? null : (PointSetPath) scene.dataset(Subsystem.KNOT);
        IrregularQuadGrid grid = scene == null ? null : (IrregularQuadGrid) scene.dataset(Subsystem.GRID);
        root.addProperty(MODE, tradeGame != null ? TRADE : knot != null ? "main" : "menu");
        JsonObject trade = new JsonObject();
        trade.addProperty(ACTIVE, tradeGame != null && scene.activeTool != null);
        if (tradeGame != null && scene.activeTool != null && scene instanceof TradeScene game) {
            Tool tool = scene.activeTool;
            trade.addProperty(ACTIVE_TOOL, tool.displayName());
            EditHistory<?> history = tool.history();
            Draft draft = tool.draft();
            trade.addProperty("routeState", draft == null ? IDLE_ROUTE_STATE : draft.label);
            trade.addProperty("pendingPlace", draft == null ? "" : draft.pointLabels[0]);
            trade.addProperty("routeSegmentCount", tool.items().size());
            trade.addProperty("canUndo", history != null && history.undoDepth > 0);
            trade.addProperty("canRedo", history != null && history.redoDepth() > 0);
            trade.addProperty("layer", game.layer);
            trade.addProperty("deepestLayer", tradeGame.network.deepestLayer());
            trade.addProperty("level", tradeGame.level.title);
            trade.addProperty("lap", tradeGame.lap);
            trade.addProperty("gold", tradeGame.player.gold);
            trade.addProperty("rivalGold", tradeGame.rival == null ? 0 : tradeGame.rival.gold);
            trade.addProperty("share", tradeGame.share(tradeGame.player));
            trade.addProperty("outcome", tradeGame.outcome.name());
            trade.addProperty("loops", tradeGame.player.loops.size());
            trade.addProperty("selectedPlace", game.selectedPlace == null ? "" : game.selectedPlace.name);
            trade.addProperty("hoveredPlace", game.hoveredPlace == null ? "" : game.hoveredPlace.name);
            trade.addProperty("mapMode", game.mapMode.name());
            trade.add(ERROR_TOASTS, toastRows(game.errorToasts));
            JsonArray targets = new JsonArray();
            for (GrowTarget target : game.growTool.targets()) {
                targets.add(target.place().name);
            }
            trade.add("growTargets", targets);
            JsonArray places = new JsonArray();
            for (Place place : tradeGame.map.places) {
                JsonObject placeState = new JsonObject();
                Vector2f pixel = game.placePixel(place);
                placeState.addProperty(NAME, place.name);
                placeState.addProperty("market", place.hasMarket());
                placeState.addProperty(XPX, pixel.x);
                placeState.addProperty(YPX, pixel.y);
                placeState.addProperty("facility",
                        place instanceof City city ? city.facility(tradeGame.player).name() : "");
                places.add(placeState);
            }
            trade.add("places", places);
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
            mesh.addProperty(FACE_COUNT, shown.faceCount());
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
            // The confirmed rings, by the number drawn beside each as their items are labelled.
            RingTool rings = regionLayer.scene.ringTool;
            JsonArray ringRows = new JsonArray();
            for (int index = 0; index < rings.confirmedRings.size(); index++) {
                ConfirmedRing ring = rings.confirmedRings.get(index);
                JsonObject row = new JsonObject();
                row.addProperty(LABEL, Integer.toString(rings.drawnRingNumber(index)));
                row.addProperty("liveLabel", rings.liveLabel(index));
                row.addProperty("bisector", ring.isBisector());
                row.addProperty(MODE, ring.mode.label);
                row.addProperty("serial", ring.serial);
                row.addProperty("statement", ring.statementId == null ? "" : ring.statementId);
                row.addProperty(SOURCE, ring.sourceLabel == null ? "" : ring.sourceLabel);
                JsonArray walls = new JsonArray();
                for (int wall : ring.isBisector() ? ring.bisectorEnds : new int[0]) {
                    walls.add(wall);
                }
                row.add("wallSerials", walls);
                row.addProperty("anchorCount", ring.spline.anchorCount);
                row.addProperty("authoredCount", ring.spline.authoredCount());
                row.addProperty("unsaved", ring.unsaved);
                row.addProperty(DELETED, ring.deleted);
                ringRows.add(row);
            }
            root.add("rings", ringRows);
            SurfaceConnection connection = regionLayer.scene.connectionTool.connection;
            if (connection != null) {
                JsonObject joined = new JsonObject();
                joined.addProperty("connected", connection.connected);
                joined.addProperty(ERROR, connection.failure);
                joined.addProperty("pathLength", connection.pathLength);
                joined.addProperty("pathFaceCount", connection.pathActiveFace.length);
                joined.addProperty("pathFilledFaceCount", connection.pathFilledFaceCount);
                joined.addProperty("narrowestGirth", Double.isInfinite(connection.narrowestGirth) ? -1.0
                        : connection.narrowestGirth);
                joined.addProperty("widestGirth", connection.widestGirth);
                joined.add("narrowestPoint", floats(connection.narrowestPoint));
                joined.addProperty("girthsCrossingWalls", connection.girthsCrossingWalls);
                joined.addProperty("pathMillis", connection.pathMillis);
                joined.addProperty("girthMillis", connection.girthMillis);
                root.add("connection", joined);
            }
            root.add(ERROR_TOASTS, toastRows(regionLayer.scene.errorToasts));
            JsonObject regions = new JsonObject();
            regions.addProperty("regionCount",
                    regionLayer.regions == null ? 0 : regionLayer.regions.regionCount);
            regions.addProperty("visible", regionLayer.visible);
            regions.addProperty("absorbSlivers", regionLayer.absorbSlivers);
            regions.addProperty("hiddenRingCount", regionLayer.hiddenRingLabels.size());
            regions.addProperty("summary", regionLayer.summary);
            // Each region by its number, as its item's tag names it: area, bounding rings with
            // the side the region lies on, part label and flatness.
            RingRegions built = regionLayer.regions;
            JsonArray regionRows = new JsonArray();
            for (int region = 0; built != null && region < built.regionCount; region++) {
                JsonObject row = new JsonObject();
                row.addProperty(REGION, region);
                row.addProperty(LABEL, RingRegionTool.regionTag(region));
                row.addProperty(FACE_COUNT, built.regionFaceCount[region]);
                row.addProperty(AREA, built.regionArea[region]);
                row.addProperty("sliver", built.isSliver(region));
                JsonArray bounding = new JsonArray();
                for (int ring : built.boundingRingsByRegion[region]) {
                    JsonObject side = new JsonObject();
                    side.addProperty(RING, built.ringLabels[ring]);
                    byte sideOfRing = built.sideByRingRegion[ring][region];
                    side.addProperty("side", sideOfRing == RingRegions.SIDE_NONE ? "both"
                            : RingRegions.SIDE_NAMES[sideOfRing]);
                    bounding.add(side);
                }
                row.add("boundingRings", bounding);
                int[] labelByRegion = regionLayer.partLabels.labelByRegion;
                int part = region < labelByRegion.length ? labelByRegion[region] : MeshLabels.UNLABELLED;
                row.addProperty("part", part == MeshLabels.UNLABELLED ? ""
                        : regionLayer.partLabels.names.get(part));
                if (built.flatnessCreases != null && region < built.regionCreaseShare.length) {
                    double saturation = built.flatnessCreases.saturation;
                    row.addProperty(CREASE_SHARE, built.regionCreaseShare[region]);
                    row.addProperty(INTERIOR_CREASE_SHARE, built.regionInteriorCreaseShare[region]);
                    row.addProperty(PEAK_CURVATURE, built.regionPeakCurvature[region]);
                    row.addProperty(PEAK_OVER_SATURATION, saturation > 0.0
                            ? built.regionPeakCurvature[region] / saturation : 0.0);
                    row.add(CREASE_POINT, floats(Arrays.copyOfRange(built.regionCreasePoint,
                            RingRegions.TRIANGLE_CORNERS * region,
                            RingRegions.TRIANGLE_CORNERS * region + RingRegions.TRIANGLE_CORNERS)));
                    row.addProperty(CREASE_INTERIOR, built.regionCreaseInterior[region]);
                }
                regionRows.add(row);
            }
            regions.add("rows", regionRows);
            // Each ring that splits nothing, by label, with a point on the handle it cannot cut.
            JsonArray joined = new JsonArray();
            for (int ring = 0; built != null && ring < built.ringLabels.length; ring++) {
                if (!built.ringSplitsNothing[ring]) {
                    continue;
                }
                JsonObject row = new JsonObject();
                row.addProperty(RING, built.ringLabels[ring]);
                boolean measured = RingRegions.TRIANGLE_CORNERS * ring < built.ringJoinPoint.length
                        && !Float.isNaN(built.ringJoinPoint[RingRegions.TRIANGLE_CORNERS * ring]);
                row.add("joinedAt", measured ? floats(Arrays.copyOfRange(built.ringJoinPoint,
                        RingRegions.TRIANGLE_CORNERS * ring,
                        RingRegions.TRIANGLE_CORNERS * ring + RingRegions.TRIANGLE_CORNERS)) : new JsonArray());
                row.addProperty("joinPathLength", measured ? built.ringJoinPathLength[ring] : 0.0);
                joined.add(row);
            }
            regions.add("splitsNothing", joined);
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
                memberState.addProperty(KIND, modelCollection.members[member].kind.name());
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
            textElements.add(textElement(tradeGame != null ? "trade_tooltip" : "tooltip",
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
        JsonArray controlSections = new JsonArray();
        for (int section = 0; scene != null && section < scene.controls.size(); section++) {
            JsonObject listed = new JsonObject();
            listed.addProperty("section", scene.controlSectionName(section));
            listed.add(CONTROLS, controls(scene.controls.get(section)));
            controlSections.add(listed);
        }
        root.add(CONTROLS, controlSections);

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
                        + "mode, trade, irregularGrid?, mesh?, ringTool?, rings?:[{label, liveLabel, bisector, mode, "
                        + "serial, statement, source, wallSerials, anchorCount, authoredCount, unsaved, deleted}], "
                        + "connection?, errorToasts?, regions?:{regionCount, visible, absorbSlivers, "
                        + "hiddenRingCount, summary, rows:[{region, label, faceCount, area, sliver, "
                        + "boundingRings:[{ring, side}], part, creaseShare?, interiorCreaseShare?, peakCurvature?, "
                        + "peakOverSaturation?, creasePoint?, creaseInterior?}]}, "
                        + "collection?, knotEditor?, tool?, textElements, menuItems, menuScroll?, controls:[{section, controls}], audio}")
                .build();
    }
}
