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
 * Fire one control row of any hosted tool by its key label, the way pressing its key or clicking
 * its row in the controls menu does, switching to the tool first as its switch key would.
 */
@AutomationRouteAnnotation(path = "ui/tools/control", method = APIMethod.POST)
public class FireControl extends AutomationEndpoint implements AutomationRoute {

    public static final String TOOL = "tool";

    public static final String KEY = "key";

    public static final String SETTLE = "settle";

    public static final String CANDIDATES = "candidates";

    public static final String COMMAND = "control";

    /**
     * {@code POST /ui/tools/control}: find the tool's row whose key label matches, switch to the
     * tool when it is not active, fire the row through the key dispatcher and report the outcome.
     *
     * @param body JSON body with {@code tool} (a tool name; default the active tool), {@code key}
     *             (the row's key label as the tools listing shows it, ignoring case) and
     *             {@code settle} (frames to wait for after; default 2)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return the key and input outcome {@code key} and {@code click} reply with, or a refusal
     *         naming the tools or the fireable rows when the tool or the label matches none
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String toolName = body.has(TOOL) ? body.get(TOOL).getAsString() : "";
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
            if (tool == null) {
                JsonObject refusal = failure("no tool named '" + toolName + "' in this scene");
                refusal.add(CANDIDATES, toolNames);
                return refusal;
            }
            List<ControlHint> rows = new ArrayList<>();
            tool.addControls(rows);
            ControlHint row = null;
            JsonArray fireable = new JsonArray();
            for (ControlHint hint : rows) {
                if (hint.action == null) {
                    continue;
                }
                fireable.add(hint.toString());
                if (row == null && hint.key.equalsIgnoreCase(label)) {
                    row = hint;
                }
            }
            if (row == null) {
                JsonObject refusal = failure("the " + tool.toolName() + " has no control row with key '" + label
                        + "'; fire one of its candidates by its key label");
                refusal.add(CANDIDATES, fireable);
                return refusal;
            }
            if (tool != scene.activeTool) {
                int switchIndex = scene.switchedTools.indexOf(tool);
                if (switchIndex < 0) {
                    scene.switchTool(tool);
                } else {
                    scene.keys.fire(scene.toolSwitchHints.get(switchIndex));
                }
            }
            scene.keys.fire(row);
            JsonObject payload = new JsonObject();
            payload.addProperty(TOOL, tool.toolName());
            payload.addProperty(KEY, row.key);
            runtime.recordAbstractAction(COMMAND, payload);
            JsonObject applied = success();
            applied.addProperty(KEY, row.key);
            return applied;
        });
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(COMMAND)
                .description("Fire a tool's control row by its key label, active tool or not, switching to the "
                        + "tool first as its switch key would, and report the outcome as key and click do.")
                .param(TOOL, RouteParamType.STRING, false, "",
                        "Tool name as the tools listing gives it; the active tool when left out.", "region tool")
                .param(KEY, RouteParamType.STRING, true, "",
                        "The row's key label as the tools listing shows it, ignoring case.", "shift+H")
                .param(SETTLE, RouteParamType.INT, false, String.valueOf(InputSettle.DEFAULT_FRAMES),
                        "Frames to wait for after the row fired; 0 returns at once.", "0")
                .responseHint("{ok, key, control, settled, activeTool, previousTool?, tool:{name, lastRow, "
                        + "error, undo heads, mode?}}, or {ok:false, error, candidates}")
                .build();
    }
}
