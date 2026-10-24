package ixdar.automation.endpoints.ui.tools;

import java.io.IOException;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.automation.AutomationEndpoint;
import ixdar.automation.endpoints.ui.State;
import ixdar.scenes.Scene;
import ixdar.tools.Tool;

/**
 * What the active scene can do: every tool it hosts, active or not, in the shape ui-state writes
 * for the active tool, with the key that switches to it; {@link FireControl} drives any of their
 * control rows.
 */
@AutomationRouteAnnotation(path = "ui/tools", method = APIMethod.GET)
public class Tools extends AutomationEndpoint implements AutomationRoute {

    public static final String TOOLS = "tools";

    /**
     * {@code GET /ui/tools}: list the active scene's tools, each as {@link State#toolState} writes
     * it, its controls among them, plus whether it is active and its switch key.
     *
     * @param body unused
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {ok, sceneClass, activeTool, defaultTool, tools:[...]}}, or an error object
     *         when no scene is bound
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            return runtime.runOnMainThread(() -> {
                Scene scene = runtime.canvas;
                if (scene == null) {
                    return failure("no scene is active");
                }
                JsonObject result = success();
                result.addProperty("sceneClass", scene.getClass().getSimpleName());
                result.addProperty(State.ACTIVE_TOOL, scene.activeTool == null ? "" : scene.activeTool.toolName());
                result.addProperty("defaultTool", scene.defaultTool == null ? "" : scene.defaultTool.toolName());
                JsonArray tools = new JsonArray();
                for (Tool tool : scene.tools) {
                    JsonObject listed = State.toolState(tool);
                    listed.addProperty(State.ACTIVE, tool == scene.activeTool);
                    int switchIndex = scene.switchedTools.indexOf(tool);
                    listed.add("switchKey", switchIndex < 0 ? JsonNull.INSTANCE
                            : State.controls(List.of(scene.toolSwitchHints.get(switchIndex))).get(0));
                    tools.add(listed);
                }
                result.add(TOOLS, tools);
                return result;
            });
        } catch (Exception e) {
            return failure(e.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(TOOLS)
                .description("List every tool the active scene hosts, active or not: switch key, description, "
                        + "modes and full controls with their modifiers, which `control` fires by key label.")
                .responseHint("{ok, sceneClass, activeTool, defaultTool, tools:[{the ui-state tool object "
                        + "(name, displayName, shortName, description, lastRow, error, undo heads, mode, modes, "
                        + "controls, draft, preview, hovered, items), active, switchKey:{key, description, "
                        + "keyCode, controlHeld, shiftHeld}|null}]}")
                .build();
    }
}
