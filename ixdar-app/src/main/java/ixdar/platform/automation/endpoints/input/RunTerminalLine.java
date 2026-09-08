package ixdar.platform.automation.endpoints.input;

import java.io.IOException;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.graphics.render.Clock;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.Toggle;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.automation.InputSettle;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.Keys;
import ixdar.scenes.main.PaneTypes;

@AutomationRouteAnnotation(path = "input/terminal", method = APIMethod.POST)
public class RunTerminalLine extends AutomationEndpoint implements AutomationRoute {
    public static final String LINE = "line";
    public static final String SETTLE = "settle";
    public static final String RESPONSE = "response";
    public static final String SETTLED = "settled";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String COMMAND = "terminal";

    /**
     * {@code POST /input/terminal}: focus the scene terminal, type a line through the real key
     * path, press enter, and return the history lines the command produced.
     *
     * @param body JSON body with {@code line} (the command to run) and {@code settle}
     *             (frames to wait for once the command has run; default 2)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok", "line", "response": [...]  , "settled"}}, or an error object when the
     *         line is blank or no terminal and key handler are active
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String line = body.has(LINE) && !body.get(LINE).isJsonNull()
                ? body.get(LINE).getAsString().trim()
                : "";
        int settleFrames = body.has(SETTLE)
                ? body.get(SETTLE).getAsInt()
                : InputSettle.DEFAULT_FRAMES;
        if (line.isEmpty()) {
            return failure("a terminal line is required, e.g. rings list");
        }
        long[] appliedDuringFrame = new long[1];
        JsonObject result;
        try {
            result = runtime.runOnMainThread(() -> {
                Terminal terminal = Terminal.current;
                KeyGuy keys = runtime.activeKeys();
                JsonObject applied = new JsonObject();
                if (terminal == null || keys == null) {
                    applied.addProperty(OK, false);
                    applied.addProperty(ERROR, "No active terminal");
                    return applied;
                }
                appliedDuringFrame[0] = Clock.framesRendered();
                Toggle.setPanelFocus(PaneTypes.Terminal);
                List<String> before = runtime.hyperStringLines(terminal.history);
                typeLine(keys, line);
                keys.keyCallback(0L, Keys.ENTER, 0, Keys.ACTION_PRESS, 0);
                keys.keyCallback(0L, Keys.ENTER, 0, Keys.ACTION_RELEASE, 0);
                List<String> after = runtime.hyperStringLines(terminal.history);
                int last = after.size();
                while (last > 0 && after.get(last - 1).isBlank()) {
                    last--;
                }
                JsonArray response = new JsonArray();
                for (int index = Math.min(before.size(), last); index < last; index++) {
                    response.add(after.get(index));
                }
                JsonObject payload = new JsonObject();
                payload.addProperty(LINE, line);
                runtime.recordAbstractAction(COMMAND, payload);
                applied.addProperty(OK, true);
                applied.addProperty(LINE, line);
                applied.add(RESPONSE, response);
                return applied;
            });
        } catch (Exception e) {
            return failure(e.getMessage());
        }
        if (result.has(OK) && result.get(OK).getAsBoolean()) {
            result.addProperty(SETTLED, InputSettle.awaitFrames(appliedDuringFrame[0], settleFrames));
        }
        return result;
    }

    /**
     * Feed a line to the focused terminal the way a keyboard would: characters as char events,
     * spaces as a space key, because {@code Terminal.type} drops blank character input.
     *
     * @param keys active key handler
     * @param line text to enter on the command line
     */
    private void typeLine(KeyGuy keys, String line) {
        for (int index = 0; index < line.length(); index++) {
            char typed = line.charAt(index);
            if (typed == ' ') {
                keys.keyCallback(0L, Keys.SPACE, 0, Keys.ACTION_PRESS, 0);
                keys.keyCallback(0L, Keys.SPACE, 0, Keys.ACTION_RELEASE, 0);
            } else {
                keys.charCallback(0L, typed);
            }
        }
    }

    /**
     * Build the error envelope this route answers with.
     *
     * @param message what went wrong
     * @return {@code {"ok": false, "error": message}}
     */
    private JsonObject failure(String message) {
        JsonObject error = new JsonObject();
        error.addProperty(OK, false);
        error.addProperty(ERROR, message);
        return error;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(COMMAND)
                .description("Run one line in the scene terminal and return the history lines it produced.")
                .param(LINE, RouteParamType.STRING, true, "",
                        "Command line to type and enter, arguments included.", "rings list")
                .param(SETTLE, RouteParamType.INT, false, String.valueOf(InputSettle.DEFAULT_FRAMES),
                        "Frames to wait for after the command runs; 0 returns at once.", "0")
                .responseHint("{ok, line, response, settled}")
                .build();
    }
}
