package ixdar.automation.endpoints.ui;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.audio.AudioSystem;
import ixdar.canvas.IxdarWindow;
import ixdar.game.City;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.SurfaceRing;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.geometry.grid2d.IrregularQuadGrid;
import ixdar.platform.Clock;
import ixdar.gui.ui.ErrorToasts;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.menu.MenuItem;
import ixdar.gui.ui.menu.MenuScrollBox;
import ixdar.gui.ui.text.HyperString;
import ixdar.models.ModelCollection;
import ixdar.platform.Platforms;
import ixdar.scenes.main.Toggle;
import ixdar.automation.AutomationEndpoint;
import ixdar.scenes.anatomy.IrregularGridScene;
import ixdar.scenes.main.MainScene;
import ixdar.scenes.menu.MenuScene;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.scenes.model.ModelScene;
import ixdar.scenes.ring.RingScene;
import ixdar.scenes.trade.TradeScene;
import ixdar.tools.ControlHint;
import ixdar.tools.ring.RingTool;
import ixdar.tools.trade.RoutePlanningTool;

@AutomationRouteAnnotation(path = "ui/state", method = APIMethod.GET)
public class State extends AutomationEndpoint implements AutomationRoute {
    public static final String TRADE = "trade";
    public static final String ACTIVE = "active";
    public static final String ACTIVE_TOOL = "activeTool";
    public static final String XPX = "xPx";
    public static final String YPX = "yPx";
    public static final String EDGECOUNT = "edgeCount";
    public static final String TOOLTIP = "TOOLTIP";
    public static final String LABEL = "label";
    public static final String NAME = "name";

    public static final String FINGERPRINT = "fingerprint";

    public static final String CLOSED = "closed";
    public static final String VERTEXCOUNT = "vertexCount";
    public static final String KEY = "key";
    public static final String REGION_BOTTOM = "BOTTOM";

    public static final String WAYPOINTCOUNT = "waypointCount";

    public static final String LENGTH = "length";

    public static final String SEEDLENGTH = "seedLength";

    public static final String CENTROID = "centroid";

    public static final String PIN = "pin";

    public static final String AUTHORED_ANCHORS = "authoredAnchors";

    public static final String AUTHORED_ANCHOR_COUNT = "authoredAnchorCount";

    public static final String BASE_NORMAL = "baseNormal";

    public static final String MINIMUM_INTERIOR_ANGLE = "minimumInteriorAngleDegrees";

    public static final String SOURCE = "source";

    /**
     * Serialise a packed coordinate triple as a JSON array.
     *
     * @param packed packed xyz
     * @return the three coordinates as a JSON array
     */
    private static JsonArray floatArray(float[] packed) {
        JsonArray array = new JsonArray();
        for (float value : packed) {
            array.add(value);
        }
        return array;
    }

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("timestamp", Instant.now().toString());
        root.addProperty("windowWidth", Platforms.get().getWindowWidth());
        root.addProperty("windowHeight", Platforms.get().getWindowHeight());
        root.addProperty(
                "framebufferWidth",
                Platforms.get().getFrameBufferWidth());
        root.addProperty(
                "framebufferHeight",
                Platforms.get().getFrameBufferHeight());
        root.addProperty("menuVisible", MenuBox.menuVisible);
        root.addProperty("framesRendered", Clock.framesRendered());
        root.addProperty("terminalFocused", Toggle.IsTerminalFocused.value);
        root.addProperty("sceneMenuVisible",
                runtime.canvas instanceof ModelScene menuScene
                        && menuScene.sceneModelMenu != null
                        && menuScene.sceneModelMenu.isVisible());
        String sceneId = IxdarWindow.getCanvasId();
        root.addProperty("sceneId", sceneId == null ? "" : sceneId);
        root.addProperty(
                "sceneClass",
                runtime.canvas == null ? "" : runtime.canvas.getClass().getSimpleName());
        String mode = runtime.canvas instanceof TradeScene
                ? TRADE
                : (runtime.canvas instanceof MainScene ? "main" : "menu");
        root.addProperty("mode", mode);
        JsonObject trade = new JsonObject();
        if (runtime.canvas instanceof TradeScene tradeScene && tradeScene.activeTool != null) {
            trade.addProperty(ACTIVE, true);
            trade.addProperty(ACTIVE_TOOL, tradeScene.activeTool.displayName());
            if (tradeScene.activeTool == tradeScene.routePlanningTool) {
                RoutePlanningTool routeTool = tradeScene.routePlanningTool;
                trade.addProperty(
                        "routeMode",
                        routeTool.getCurrentMode().name());
                trade.addProperty(
                        "routeState",
                        routeTool.getOperationState().name());
                trade.addProperty(
                        "selectedCityA",
                        routeTool.getSelectedCityAName());
                trade.addProperty(
                        "selectedCityB",
                        routeTool.getSelectedCityBName());
                trade.addProperty(
                        "hasRoute",
                        routeTool.getCurrentRoute() != null);
                trade.addProperty(
                        "routeSegmentCount",
                        routeTool.getCurrentRoute() == null
                                ? 0
                                : routeTool.getCurrentRoute().manifoldSegments.size());
                trade.addProperty("canUndo", routeTool.canUndoOperation());
                trade.addProperty("canRedo", routeTool.canRedoOperation());
                trade.addProperty(
                        "inPreview",
                        routeTool.getOperationState() == RoutePlanningTool.OperationState.PREVIEW);
            }
            trade.addProperty(
                    "headquartersCity",
                    tradeScene.network.headquartersCity == null
                            ? ""
                            : tradeScene.network.headquartersCity.name);
            if (tradeScene.network != null &&
                    tradeScene.network.grid != null) {
                trade.addProperty(
                        "gridType",
                        tradeScene.network.grid.getClass().getSimpleName());
                if (tradeScene.network.grid instanceof IrregularQuadGrid) {
                    IrregularQuadGrid irregularGrid = (IrregularQuadGrid) tradeScene.network.grid;
                    trade.addProperty("gridSeed", irregularGrid.seed());
                    trade.addProperty(
                            "gridRelaxIterations",
                            irregularGrid.relaxIterations());
                    trade.addProperty("gridRows", irregularGrid.rows());
                    trade.addProperty("gridCols", irregularGrid.cols());
                    trade.addProperty(
                            "gridAnchorCount",
                            irregularGrid.anchorCount());
                }
            }
            JsonArray tradeCities = new JsonArray();
            if (tradeScene.network != null &&
                    tradeScene.network.cities != null) {
                for (City city : tradeScene.network.cities) {
                    JsonObject cityJson = new JsonObject();
                    cityJson.addProperty(NAME, city.name);
                    cityJson.addProperty("xWorld", city.getX());
                    cityJson.addProperty("yWorld", city.getY());
                    cityJson.addProperty(
                            XPX,
                            tradeScene.mapCamera.pointTransformX(city.getX()));
                    cityJson.addProperty(
                            YPX,
                            tradeScene.mapCamera.pointTransformY(city.getY()));
                    tradeCities.add(cityJson);
                }
            }
            trade.add("cities", tradeCities);
        } else {
            trade.addProperty(ACTIVE, false);
        }
        root.add(TRADE, trade);
        if (runtime.canvas instanceof IrregularGridScene) {
            IrregularGridScene irregularScene = (IrregularGridScene) runtime.canvas;
            JsonObject irregular = new JsonObject();
            irregular.addProperty("seed", irregularScene.getSeed());
            irregular.addProperty(
                    "relaxIterations",
                    irregularScene.getRelaxIters());
            irregular.addProperty("jitter", irregularScene.getJitter());
            irregular.addProperty(
                    "primalPointCount",
                    irregularScene.getPrimalPointCount());
            irregular.addProperty(
                    "dualPointCount",
                    irregularScene.getDualPointCount());
            irregular.addProperty(EDGECOUNT, irregularScene.getEdgeCount());
            irregular.addProperty(
                    "horizontalEdgeMean",
                    irregularScene.getHorizontalEdgeMean());
            irregular.addProperty(
                    "verticalEdgeMean",
                    irregularScene.getVerticalEdgeMean());
            irregular.addProperty(
                    "horizontalEdgeStdDev",
                    irregularScene.getHorizontalEdgeStdDev());
            irregular.addProperty(
                    "verticalEdgeStdDev",
                    irregularScene.getVerticalEdgeStdDev());
            root.add("irregularGrid", irregular);
        }

        if (runtime.canvas instanceof MeshNodeViewerScene) {
            MeshNodeViewerScene meshScene = (MeshNodeViewerScene) runtime.canvas;
            JsonObject mesh = new JsonObject();
            mesh.addProperty(VERTEXCOUNT, meshScene.getMeshVertexCount());
            mesh.addProperty(EDGECOUNT, meshScene.getMeshEdgeCount());
            mesh.addProperty("faceCount", meshScene.getMeshFaceCount());
            mesh.addProperty(
                    "boundaryEdgeCount",
                    meshScene.getMeshBoundaryEdgeCount());
            mesh.addProperty(
                    "degenerateFaceCount",
                    meshScene.getMeshDegenerateFaceCount());
            mesh.addProperty(
                    "eulerCharacteristic",
                    meshScene.getMeshEulerCharacteristic());
            mesh.addProperty(CLOSED, meshScene.isMeshClosed());
            mesh.addProperty("repairReport", meshScene.getMeshRepairReport());
            mesh.addProperty("radius", meshScene.getMeshRadius());
            mesh.addProperty("shaderMode", meshScene.getShaderModeName());
            mesh.addProperty("texturedDraw", meshScene.hasTexturedDraw());
            mesh.addProperty("slots", meshScene.bundleSlotNames());
            mesh.add("center", runtime.vector3Array(meshScene.getMeshCenter()));
            mesh.add(
                    "boundingBoxMin",
                    runtime.vector3Array(meshScene.getBoundingBoxMin()));
            mesh.add(
                    "boundingBoxMax",
                    runtime.vector3Array(meshScene.getBoundingBoxMax()));
            JsonArray edgeMarks = new JsonArray();
            for (Map.Entry<String, boolean[]> entry
                    : meshScene.graphEdgeMarks(meshScene.graph).entrySet()) {
                JsonObject mark = new JsonObject();
                mark.addProperty(LABEL, entry.getKey());
                mark.addProperty(EDGECOUNT, markedEdgeCount(entry.getValue()));
                mark.addProperty(FINGERPRINT,
                        EdgeMarks.fingerprint(meshScene.getMesh(), entry.getValue()));
                edgeMarks.add(mark);
            }
            mesh.add("edgeMarks", edgeMarks);
            root.add("mesh", mesh);
        }

        if (runtime.canvas instanceof RingScene ringScene && ringScene.ringWaypointCount > 0) {
            JsonObject ringJson = new JsonObject();
            ringJson.addProperty(WAYPOINTCOUNT, ringScene.ringWaypointCount);
            ringJson.addProperty("points", SurfaceWaypoints.format(ringScene.ringWaypointsXyz,
                    ringScene.ringWaypointCount));
            SurfaceRing closedRing = ringScene.ring;
            ringJson.addProperty(CLOSED, closedRing != null);
            if (closedRing != null) {
                ringJson.addProperty(EDGECOUNT, closedRing.markedEdgeCount);
                ringJson.addProperty(LENGTH, closedRing.length);
                ringJson.addProperty(SEEDLENGTH, closedRing.seedLength);
                JsonArray centroid = new JsonArray();
                centroid.add(closedRing.centroidX);
                centroid.add(closedRing.centroidY);
                centroid.add(closedRing.centroidZ);
                ringJson.add(CENTROID, centroid);
                MeshTopology ringMesh = ringScene.halfEdgeSurface();
                if (ringMesh != null) {
                    ringJson.addProperty(FINGERPRINT,
                            EdgeMarks.fingerprint(ringMesh, closedRing.markedByEdgeId));
                }
            }
            root.add("ring", ringJson);
        }

        if (runtime.canvas instanceof RingScene toolScene && toolScene.activeTool != null) {
            RingTool tool = toolScene.ringTool;
            JsonObject toolJson = new JsonObject();
            toolJson.addProperty(ACTIVE, tool.active);
            toolJson.addProperty(ACTIVE_TOOL, toolScene.activeTool.toolName());
            toolJson.addProperty("previewValid", tool.previewValid);
            toolJson.addProperty("previewAnchorCount", tool.previewAnchorCount);
            toolJson.addProperty("previewGirdleEdgeCount", tool.previewGirdleEdgeCount);
            toolJson.addProperty(LENGTH, tool.previewLength);
            toolJson.addProperty("previewMillis", tool.previewMillis);
            toolJson.addProperty("newDraftMode", tool.newDraftMode.label);
            toolJson.addProperty("tolerance", tool.previewTolerance);
            toolJson.addProperty("deviation", tool.previewDeviation);
            toolJson.addProperty("anchorCapReached", tool.previewAnchorCapReached);
            toolJson.addProperty("geodesicsTraced", tool.previewGeodesicCount);
            toolJson.addProperty("axisFromSkeleton", tool.axisFromSkeleton);
            toolJson.addProperty("hoveredRing", tool.hoveredRing);
            toolJson.addProperty("hoveredGraphRing",
                    tool.hoveredGraphRing == null ? "" : tool.hoveredGraphRing);
            toolJson.addProperty("unsavedRingCount", tool.unsavedRingCount());
            toolJson.addProperty("undoDepth", tool.history.undoDepth);
            toolJson.addProperty("redoDepth", tool.history.redoDepth());
            toolJson.addProperty("nextUndo", tool.history.nextUndoName());
            toolJson.addProperty("nextRedo", tool.history.nextRedoName());
            toolJson.add("previewHitPoint", floatArray(tool.previewHitPoint));
            toolJson.add("previewAnchors", floatArray(tool.previewSpline == null ? new float[0]
                    : tool.previewSpline.anchorXyz));
            toolJson.add("previewPlaneNormal", floatArray(tool.previewPlaneNormal));
            toolJson.add("previewLimbAxis", floatArray(tool.previewLimbAxis));
            toolJson.addProperty(ERROR, tool.lastError);
            toolJson.addProperty("lastRow", tool.lastRow);
            MeshTopology toolSurface = toolScene.halfEdgeSurface();
            JsonObject draft = new JsonObject();
            SurfaceSpline draftRing = tool.draft;
            draft.addProperty("exists", draftRing != null);
            if (draftRing != null) {
                int authoredAnchors = draftRing.authoredCount();
                float[] supporting = new float[3 * (draftRing.anchorCount - authoredAnchors)];
                int cursor = 0;
                for (int anchor = 0; anchor < draftRing.anchorCount; anchor++) {
                    if (!draftRing.anchorAuthored[anchor]) {
                        System.arraycopy(draftRing.anchorXyz, 3 * anchor, supporting,
                                3 * cursor++, 3);
                    }
                }
                draft.add(AUTHORED_ANCHORS,
                        floatArray(tool.positionsOf(tool.draftAuthoredVertexId)));
                draft.add("supportingAnchors", floatArray(supporting));
                draft.addProperty(AUTHORED_ANCHOR_COUNT, authoredAnchors);
                draft.addProperty("supportingAnchorCount",
                        draftRing.anchorCount - authoredAnchors);
                draft.addProperty("selectedAnchor", tool.selectedIndex());
                draft.addProperty("hoveredAnchor", tool.hoveredAnchor);
                draft.addProperty("dragging", tool.draggingAnchor);
                draft.addProperty("reopenedRing", tool.draftSourceRing);
                draft.addProperty("convertedFrom",
                        tool.draftSourceLabel == null ? "" : tool.draftSourceLabel);
                draft.add(BASE_NORMAL, floatArray(tool.draftBaseNormal));
                draft.addProperty(EDGECOUNT, draftRing.markedEdgeCount);
                draft.addProperty(LENGTH, draftRing.length);
                draft.addProperty("millis", tool.draftMillis);
                draft.addProperty(MINIMUM_INTERIOR_ANGLE, draftRing.minimumInteriorAngleDegrees);
                if (toolSurface != null) {
                    draft.addProperty(FINGERPRINT,
                            EdgeMarks.fingerprint(toolSurface, draftRing.markedByEdgeId));
                }
            }
            toolJson.add("draft", draft);
            JsonArray confirmed = new JsonArray();
            for (int index = 0; index < tool.confirmedRings.size(); index++) {
                SurfaceSpline ring = tool.confirmedRings.get(index);
                JsonObject row = new JsonObject();
                row.addProperty("number", tool.drawnRingNumber(index));
                String statementId = index < tool.confirmedStatementIds.size()
                        ? tool.confirmedStatementIds.get(index) : null;
                row.addProperty("statement", statementId == null ? "" : statementId);
                row.addProperty("unsaved", index < tool.confirmedRingUnsaved.size()
                        && tool.confirmedRingUnsaved.get(index));
                String sourceLabel = index < tool.confirmedSourceLabel.size()
                        ? tool.confirmedSourceLabel.get(index) : null;
                row.addProperty(SOURCE, sourceLabel == null ? "" : sourceLabel);
                row.addProperty("deleted", index < tool.confirmedRingDeleted.size()
                        && tool.confirmedRingDeleted.get(index));
                row.addProperty(EDGECOUNT, ring.markedEdgeCount);
                row.addProperty(LENGTH, ring.length);
                row.addProperty("anchorCount", ring.anchorCount);
                row.addProperty("meanRadius", ring.meanRadius);
                row.addProperty("traceDepth", ring.traceDepth);
                row.addProperty(MINIMUM_INTERIOR_ANGLE,
                        ring.minimumInteriorAngleDegrees);
                row.addProperty("unresolvedGaps", ring.unresolvedGaps);
                JsonArray centroid = new JsonArray();
                centroid.add(ring.centroidX);
                centroid.add(ring.centroidY);
                centroid.add(ring.centroidZ);
                row.add(CENTROID, centroid);
                row.add("anchors", floatArray(ring.anchorXyz));
                row.addProperty(AUTHORED_ANCHOR_COUNT, ring.authoredCount());
                row.add(AUTHORED_ANCHORS,
                        floatArray(tool.positionsOf(tool.confirmedAuthoredVertexId.get(index))));
                row.add(BASE_NORMAL, floatArray(tool.confirmedBaseNormal.get(index)));
                row.add("sharpestCorner", floatArray(ring.sharpestCornerXyz));
                if (toolSurface != null) {
                    row.addProperty(FINGERPRINT,
                            EdgeMarks.fingerprint(toolSurface, ring.markedByEdgeId));
                }
                confirmed.add(row);
            }
            toolJson.add("confirmedRings", confirmed);
            JsonArray drawn = new JsonArray();
            for (int ring = 0; ring < tool.drawnRingLabel.length; ring++) {
                JsonObject label = new JsonObject();
                label.addProperty(LABEL, tool.drawnRingLabel[ring]);
                if (ring < tool.drawnRingColors.length) {
                    label.addProperty("color", tool.drawnRingColors[ring].getName());
                }
                drawn.add(label);
            }
            toolJson.add("drawnRings", drawn);
            root.add("ringTool", toolJson);
            ErrorToasts toasts = toolScene.errorToasts;
            JsonArray toastRows = new JsonArray();
            for (int toast = 0; toast < toasts.messages.size(); toast++) {
                JsonObject row = new JsonObject();
                row.addProperty(SOURCE, toasts.sources.get(toast));
                row.addProperty("message", toasts.messages.get(toast));
                row.addProperty("opacity", toasts.colors.get(toast).toVector4f().w);
                toastRows.add(row);
            }
            root.add("errorToasts", toastRows);
        }

        if (runtime.canvas instanceof ModelScene modelScene && modelScene.modelCollection != null) {
            ModelCollection modelCollection = modelScene.modelCollection;
            JsonObject collection = new JsonObject();
            collection.addProperty(NAME, modelCollection.name);
            collection.addProperty("directory", modelCollection.directory.toString());
            collection.addProperty("manifest", modelCollection.manifestPath.toString());
            collection.addProperty("memberCount", modelCollection.memberCount());
            collection.addProperty("keptCount", modelCollection.keptCount());
            collection.addProperty("memberIndex", modelCollection.index());
            int currentMember = modelCollection.index();
            collection.addProperty("member",
                    currentMember < 0 ? "" : modelCollection.memberNames[currentMember]);
            JsonArray collectionMembers = new JsonArray();
            for (int member = 0; member < modelCollection.memberCount(); member++) {
                JsonObject memberJson = new JsonObject();
                memberJson.addProperty(NAME, modelCollection.memberNames[member]);
                memberJson.addProperty("path", modelCollection.memberPaths[member]);
                memberJson.addProperty("keep", modelCollection.memberKeep[member]);
                memberJson.addProperty(VERTEXCOUNT, modelCollection.memberVertexCount[member]);
                memberJson.addProperty("triangleCount",
                        modelCollection.memberTriangleCount[member]);
                memberJson.addProperty("settings",
                        ModelCollection.settingsSummary(modelCollection.memberSettings[member]));
                collectionMembers.add(memberJson);
            }
            collection.add("members", collectionMembers);
            root.add("collection", collection);
        }

        if (runtime.canvas instanceof MainScene editor && editor.activeTool != null) {
            JsonObject knotEditor = new JsonObject();
            knotEditor.addProperty(ACTIVE_TOOL, editor.activeTool.toolName());
            JsonObject toggles = new JsonObject();
            for (Toggle toggle : Toggle.values()) {
                toggles.addProperty(toggle.shortName, toggle.value);
            }
            knotEditor.add("toggles", toggles);
            root.add("knotEditor", knotEditor);
        }

        JsonArray textElements = new JsonArray();
        if (runtime.canvas != null && runtime.canvas.sceneTerminal != null) {
            textElements.add(
                    runtime.hyperStringElement(
                            "scene_terminal",
                            REGION_BOTTOM,
                            runtime.canvas.sceneTerminal.history,
                            runtime.canvas.sceneTerminal.scrollOffsetY));
        }
        if (runtime.canvas instanceof ModelScene menuScene && menuScene.sceneModelMenu != null
                && menuScene.sceneModelMenu.isVisible()) {
            for (MenuScrollBox box : new MenuScrollBox[] {menuScene.sceneModelMenu.modelsBox,
                    menuScene.sceneModelMenu.controlsBox}) {
                JsonObject element = runtime.hyperStringElement("scene_menu_box", box.bounds.id,
                        box.text, box.scrollBar.scrollOffset);
                element.addProperty("maximumScrollOffsetY", box.scrollBar.maximumScrollOffset());
                element.addProperty("drawnScrollOffsetY", box.drawnScrollOffsetY());
                element.addProperty(XPX, box.bounds.offsetX);
                element.addProperty(YPX, box.bounds.offsetY);
                element.addProperty("width", box.bounds.viewWidth);
                element.addProperty("height", box.bounds.viewHeight);
                element.addProperty("rowsUsed", box.rowsUsed);
                element.addProperty("scrollBarShown", box.scrollBar.isNeeded());
                element.addProperty("scrollBarThumbStart", box.scrollBar.thumbStart());
                element.addProperty("scrollBarThumbLength", box.scrollBar.thumbLength());
                textElements.add(element);
            }
        }
        if (runtime.canvas instanceof MainScene editor) {
            textElements.add(
                    runtime.hyperStringElement(
                            "terminal",
                            REGION_BOTTOM,
                            editor.terminal.getCachedInfo(),
                            editor.terminal.scrollOffsetY));
            textElements.add(
                    runtime.hyperStringElement(
                            "info",
                            "RIGHT_TOP",
                            editor.info.getCachedInfo(),
                            editor.info.scrollOffsetY));
        }
        HyperString tooltip = runtime.canvas == null ? null : runtime.canvas.toolTip;
        if (tooltip != null && runtime.canvas.showToolTip) {
            textElements.add(
                    runtime.hyperStringElement(runtime.canvas instanceof TradeScene ? "trade_tooltip" : "tooltip",
                            TOOLTIP, tooltip, 0));
        }
        root.add("textElements", textElements);

        JsonArray menuItems = new JsonArray();
        if (runtime.canvas instanceof MenuScene menuScene && menuScene.menu != null) {
            for (MenuBox.MenuItemBounds itemBounds : menuScene.menu.getMenuItemBounds()) {
                JsonObject menuItem = new JsonObject();
                menuItem.addProperty(LABEL, itemBounds.label);
                menuItem.addProperty("enabled", itemBounds.enabled);
                JsonObject bounds = new JsonObject();
                bounds.addProperty(XPX, itemBounds.left);
                bounds.addProperty(YPX, itemBounds.bottom);
                bounds.addProperty("widthPx", itemBounds.width);
                bounds.addProperty("heightPx", itemBounds.height);
                bounds.addProperty("centerXPx", itemBounds.centerX);
                bounds.addProperty("centerYPx", itemBounds.centerY);
                menuItem.add("bounds", bounds);
                menuItems.add(menuItem);
            }
        } else if (MenuBox.menuItems != null) {
            for (MenuItem item : MenuBox.menuItems) {
                JsonObject menuItem = new JsonObject();
                menuItem.addProperty(LABEL, item.getHeading());
                menuItems.add(menuItem);
            }
        }
        root.add("menuItems", menuItems);

        JsonArray controls = new JsonArray();
        if (runtime.canvas != null) {
            for (ControlHint hint : runtime.canvas.controls) {
                JsonObject control = new JsonObject();
                control.addProperty(KEY, hint.key);
                control.addProperty("description", hint.description);
                control.addProperty("keyCode", hint.keyCode);
                control.addProperty("controlHeld", hint.controlHeld);
                controls.add(control);
            }
        }
        root.add("controls", controls);

        JsonObject audio = new JsonObject();
        AudioSystem audioSystem = AudioSystem.get();
        audio.addProperty("available", audioSystem.isAvailable());
        audio.addProperty("menuMusicPlaying", audioSystem.isMenuMusicPlaying());
        audio.addProperty(
                "menuMusicSourceCount",
                audioSystem.getMenuMusicSourceCount());
        audio.addProperty("lastSfxPlayed", audioSystem.getLastSfxPlayed());
        JsonObject sfxCounts = new JsonObject();
        for (Map.Entry<String, Integer> entry : audioSystem
                .getSfxPlayCountSnapshot()
                .entrySet()) {
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

    /**
     * Marked entries in a per-edge mask.
     *
     * @param marksByEdgeId edge-id-indexed mask
     * @return how many edges the mask marks
     */
    private static int markedEdgeCount(boolean[] marksByEdgeId) {
        int marked = 0;
        for (boolean mark : marksByEdgeId) {
            if (mark) {
                marked++;
            }
        }
        return marked;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Snapshot the full UI state: window, frames, scene, trade, mesh, text, menu, and audio.")
                .responseHint("{timestamp, windowWidth, windowHeight, framebufferWidth, framebufferHeight, "
                        + "menuVisible, framesRendered, terminalFocused, sceneMenuVisible, sceneId, sceneClass, "
                        + "mode, trade, irregularGrid?, mesh?, ring?, collection?, knotEditor?, textElements, menuItems, "
                        + "controls, audio}")
                .build();
    }
}
