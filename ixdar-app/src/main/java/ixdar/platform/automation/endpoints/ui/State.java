package ixdar.platform.automation.endpoints.ui;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.audio.AudioSystem;
import ixdar.canvas.IxdarWindow;
import ixdar.game.City;
import ixdar.geometry.point.IrregularQuadGrid;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.menu.MenuItem;
import ixdar.gui.ui.tools.RoutePlanningTool;
import ixdar.platform.Platforms;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.anatomy.IrregularGridScene;
import ixdar.scenes.main.MainScene;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.scenes.trade.TradeScene;

@AutomationRouteAnnotation(path = "ui/state", method = APIMethod.GET)
public class State extends AutomationEndpoint implements AutomationRoute {
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
        String sceneId = IxdarWindow.getCanvasId();
        root.addProperty("sceneId", sceneId == null ? "" : sceneId);
        root.addProperty(
                "sceneClass",
                runtime.canvas == null ? "" : runtime.canvas.getClass().getSimpleName());
        String mode = TradeScene.active
                ? "trade"
                : (MainScene.active ? "main" : "menu");
        root.addProperty("mode", mode);
        JsonObject trade = new JsonObject();
        if (TradeScene.active && TradeScene.instance != null) {
            trade.addProperty("active", true);
            if (TradeScene.instance.activeTool != null) {
                trade.addProperty(
                        "activeTool",
                        TradeScene.instance.activeTool.displayName());
            }
            if (TradeScene.instance.activeTool instanceof RoutePlanningTool) {
                RoutePlanningTool routeTool = (RoutePlanningTool) TradeScene.instance.activeTool;
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
                    TradeScene.instance.network.headquartersCity == null
                            ? ""
                            : TradeScene.instance.network.headquartersCity.name);
            if (TradeScene.instance.network != null &&
                    TradeScene.instance.network.grid != null) {
                trade.addProperty(
                        "gridType",
                        TradeScene.instance.network.grid.getClass().getSimpleName());
                if (TradeScene.instance.network.grid instanceof IrregularQuadGrid) {
                    IrregularQuadGrid irregularGrid = (IrregularQuadGrid) TradeScene.instance.network.grid;
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
            if (TradeScene.instance.network != null &&
                    TradeScene.instance.network.cities != null &&
                    TradeScene.camera != null) {
                for (City city : TradeScene.instance.network.cities) {
                    JsonObject cityJson = new JsonObject();
                    cityJson.addProperty("name", city.name);
                    cityJson.addProperty("xWorld", city.getX());
                    cityJson.addProperty("yWorld", city.getY());
                    cityJson.addProperty(
                            "xPx",
                            TradeScene.camera.pointTransformX(city.getX()));
                    cityJson.addProperty(
                            "yPx",
                            TradeScene.camera.pointTransformY(city.getY()));
                    tradeCities.add(cityJson);
                }
            }
            trade.add("cities", tradeCities);
        } else {
            trade.addProperty("active", false);
        }
        root.add("trade", trade);
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
            irregular.addProperty("edgeCount", irregularScene.getEdgeCount());
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
            mesh.addProperty("vertexCount", meshScene.getMeshVertexCount());
            mesh.addProperty("edgeCount", meshScene.getMeshEdgeCount());
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
            mesh.addProperty("closed", meshScene.isMeshClosed());
            mesh.addProperty("radius", meshScene.getMeshRadius());
            mesh.add("center", runtime.vector3Array(meshScene.getMeshCenter()));
            mesh.add(
                    "boundingBoxMin",
                    runtime.vector3Array(meshScene.getBoundingBoxMin()));
            mesh.add(
                    "boundingBoxMax",
                    runtime.vector3Array(meshScene.getBoundingBoxMax()));
            root.add("mesh", mesh);
        }

        JsonArray textElements = new JsonArray();
        if (MainScene.terminal != null) {
            textElements.add(
                    runtime.hyperStringElement(
                            "terminal",
                            "BOTTOM",
                            MainScene.terminal.getCachedInfo(),
                            MainScene.terminal.scrollOffsetY));
        }
        if (MainScene.info != null) {
            textElements.add(
                    runtime.hyperStringElement(
                            "info",
                            "RIGHT_TOP",
                            MainScene.info.getCachedInfo(),
                            MainScene.info.scrollOffsetY));
        }
        HyperString tooltip = MainScene.getToolTip();
        if (tooltip != null && MainScene.isToolTipVisible()) {
            textElements.add(
                    runtime.hyperStringElement("tooltip", "TOOLTIP", tooltip, 0));
        }
        HyperString tradeTooltip = TradeScene.getToolTip();
        if (tradeTooltip != null && TradeScene.isToolTipVisible()) {
            textElements.add(
                    runtime.hyperStringElement("trade_tooltip", "TOOLTIP", tradeTooltip, 0));
        }
        root.add("textElements", textElements);

        JsonArray menuItems = new JsonArray();
        if (runtime.canvas != null && runtime.canvas.menu != null) {
            for (MenuBox.MenuItemBounds itemBounds : runtime.canvas.menu.getMenuItemBounds()) {
                JsonObject menuItem = new JsonObject();
                menuItem.addProperty("label", itemBounds.label);
                JsonObject bounds = new JsonObject();
                bounds.addProperty("xPx", itemBounds.left);
                bounds.addProperty("yPx", itemBounds.bottom);
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
                menuItem.addProperty("label", item.getHeading());
                menuItems.add(menuItem);
            }
        }
        root.add("menuItems", menuItems);

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
}
