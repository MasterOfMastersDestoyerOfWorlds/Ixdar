package ixdar.automation.endpoints.ui.tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.automation.InputSettle;
import ixdar.scenes.Scene;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

/**
 * Fire one control row by its action id, a tool's or the scene's own, the way pressing its key
 * or clicking its row in the controls menu does, switching to the tool first as its switch key
 * would.
 */
@AutomationRouteAnnotation(path = "ui/tools/control", method = APIMethod.POST)
public class FireControl extends AutomationEndpoint implements AutomationRoute {

    public static final String TOOL = "tool";

    public static final String ACTION = "action";

    public static final String KEY = "key";

    public static final String SETTLE = "settle";

    public static final String CANDIDATES = "candidates";

    public static final String COMMAND = "control";

    /**
     * {@code POST /ui/tools/control}: find the row among the tool's section, then the scene's own,
     * whose action id is exactly the one asked for, switch to the tool when it is not active, fire
     * the row and report the outcome.
     *
     * @param body JSON body with {@code tool} (a tool name; default the active tool),
     *             {@code action} (the row's action id as the tools listing gives it), or
     *             {@code key} (its key label, ignoring case), and {@code settle} (frames to wait
     *             for after; default 2)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return the key and input outcome {@code key} and {@code click} reply with, or a refusal
     *         naming the tools or the fireable rows when the tool or the row matches none
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String toolName = body.has(TOOL) ? body.get(TOOL).getAsString() : "";
        String actionName = body.has(ACTION) ? body.get(ACTION).getAsString().trim() : "";
        String label = body.has(KEY) ? body.get(KEY).getAsString() : "";
        int settleFrames = body.has(SETTLE) ? body.get(SETTLE).getAsInt() : InputSettle.DEFAULT_FRAMES;
        return InputSettle.settledInput(runtime, settleFrames, () -> {
            Scene scene = runtime.canvas;
            Tool tool = toolName.isEmpty() ? scene.activeTool : null;
            JsonArray toolNames = new JsonArray();
            for (Tool hosted : scene.tools) {
                toolNames.add(hosted.toolName());
                if (hosted.toolName().equals(toolName)) {
                    tool = hosted;
                }
            }
            if (tool == null && !toolName.isEmpty()) {
                JsonObject refusal = failure("no tool named '" + toolName + "' in this scene");
                refusal.add(CANDIDATES, toolNames);
                return refusal;
            }
            // The tool's section, as it stands once the tool is active, then the scene's own, in the
            // order the key handler fires them.
            List<ControlHint> rows = new ArrayList<>();
            if (tool == scene.activeTool) {
                rows.addAll(scene.controls.get(Scene.TOOL_SECTION));
            } else {
                tool.addControls(rows);
            }
            rows.addAll(scene.controls.get(Scene.SCENE_SECTION));
            ControlHint row = null;
            List<String> fireable = new ArrayList<>();
            for (ControlHint hint : rows) {
                if (hint.action == null) {
                    continue;
                }
                String candidate = actionName.isEmpty() ? hint.toString() : hint.actionName;
                if (!fireable.contains(candidate)) {
                    fireable.add(candidate);
                }
                boolean matches = actionName.isEmpty() ? hint.key.equalsIgnoreCase(label)
                        : hint.actionName.equals(actionName);
                row = row == null && matches ? hint : row;
            }
            if (row == null) {
                String scope = tool == null ? "the scene" : "the " + tool.toolName() + " or the scene";
                String asked = actionName.isEmpty() ? "with key '" + label + "'" : "with action '" + actionName + "'";
                JsonObject refusal = failure(actionName.isEmpty() && label.isEmpty()
                        ? "name the row to fire by its action, as the tools listing gives it"
                        : scope + " has no control row " + asked + "; fire one of its candidates");
                JsonArray candidates = new JsonArray();
                for (String candidate : fireable) {
                    candidates.add(candidate);
                }
                refusal.add(CANDIDATES, candidates);
                return refusal;
            }
            if (tool != null && tool != scene.activeTool) {
                int switchIndex = scene.switchedTools.indexOf(tool);
                if (switchIndex < 0) {
                    scene.switchTool(tool);
                } else {
                    scene.keys.fire(scene.toolSwitchHints.get(switchIndex));
                }
            }
            scene.keys.fire(row);
            JsonObject payload = new JsonObject();
            payload.addProperty(TOOL, tool == null ? "" : tool.toolName());
            payload.addProperty(ACTION, row.actionName);
            runtime.recordAbstractAction(COMMAND, payload);
            JsonObject applied = success();
            applied.addProperty(ACTION, row.actionName);
            applied.addProperty(KEY, row.key);
            return applied;
        });
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(COMMAND)
                .description("Fire a control row by its action id, a tool's, active or not, or the scene's own, "
                        + "switching to the tool first as its switch key would, and report the outcome as key and "
                        + "click do.")
                .param(TOOL, RouteParamType.STRING, false, "",
                        "Tool name as the tools listing gives it; the active tool when left out.", "region tool")
                .param(ACTION, RouteParamType.STRING, false, "",
                        "The row's action id exactly as the tools listing gives it.", "save-rings")
                .param(KEY, RouteParamType.STRING, false, "",
                        "The row's key label instead of its action id, ignoring case.", "shift+H")
                .param(SETTLE, RouteParamType.INT, false, String.valueOf(InputSettle.DEFAULT_FRAMES),
                        "Frames to wait for after the row fired; 0 returns at once.", "0")
                .responseHint("{ok, action, key, control, settled, activeTool, previousTool?, tool:{name, lastRow, "
                        + "error, undo heads, mode?}}, or {ok:false, error, candidates}")
                .build();
    }
}
