package ixdar.automation.endpoints.input;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.platform.Clock;
import ixdar.automation.AutomationEndpoint;
import ixdar.automation.InputSettle;
import ixdar.input.MouseTrap;

@AutomationRouteAnnotation(path = "input/hover", method = APIMethod.POST)
public class InjectHover extends AutomationEndpoint implements AutomationRoute {
    public static final String X = "x";
    public static final String Y = "y";
    public static final String NORMALIZED = "normalized";
    public static final String PERSISTENT = "persistent";
    public static final String SETTLE = "settle";
    public static final String SETTLED = "settled";
    public static final String COMMAND = "hover";
    /**
     * {@code POST /input/hover}: move the cursor without clicking, then wait for the hover to be
     * drawn, optionally pinning it with the mouse handler's automation hover lock so it is
     * re-hovered every frame until real motion moves it. Recorded as an abstract {@code "hover"}
     * action.
     *
     * @param body JSON body with {@code x}, {@code y} (floats, default 0),
     *             {@code normalized} (boolean; when true, {@code x}/{@code y} are
     *             fractions of window size), {@code persistent} (boolean,
     *             default true) controlling whether the hover lock is set or
     *             cleared, and {@code settle} (frames to wait for; default 2)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true, "settled": true, "event": {xPx, yPx, xNorm, yNorm, persistent}}}
     *         on success, or an error object when no mouse handler is active
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            float x = body.has(X) ? body.get(X).getAsFloat() : 0f;
            float y = body.has(Y) ? body.get(Y).getAsFloat() : 0f;
            boolean normalized = body.has(NORMALIZED) && body.get(NORMALIZED).getAsBoolean();
            boolean persistent = !body.has(PERSISTENT) ||
                    body.get(PERSISTENT).getAsBoolean();
            int settleFrames = body.has(SETTLE)
                    ? body.get(SETTLE).getAsInt()
                    : InputSettle.DEFAULT_FRAMES;
            long[] appliedDuringFrame = new long[1];
            try {
                JsonObject settledResult = runtime.runOnMainThread(() -> {
                    MouseTrap mouse = runtime.activeMouse();
                    if (mouse == null) {
                        return failure("No active mouse handler");
                    }
                    appliedDuringFrame[0] = Clock.framesRendered();
                    float xPos = normalized ? denormalizeX(x) : x;
                    float yPos = normalized ? denormalizeY(y) : y;
                    mouse.automationInputInProgress = true;
                    try {
                        if (persistent) {
                            mouse.setAutomationHoverLock(xPos, yPos);
                        } else {
                            mouse.clearAutomationHoverLock();
                            mouse.mousePos(xPos, yPos);
                        }
                    } finally {
                        mouse.automationInputInProgress = false;
                    }
                    JsonObject payload = new JsonObject();
                    payload.addProperty("xPx", xPos);
                    payload.addProperty("yPx", yPos);
                    payload.addProperty("xNorm", normalizeX(xPos));
                    payload.addProperty("yNorm", normalizeY(yPos));
                    payload.addProperty(PERSISTENT, persistent);
                    runtime.recorder.recordAbstract(COMMAND, payload);
                    JsonObject result = success();
                    result.add("event", payload);
                    return result;
                });
                if (settledResult.has(OK) && settledResult.get(OK).getAsBoolean()) {
                    settledResult.addProperty(SETTLED,
                            InputSettle.awaitFrames(appliedDuringFrame[0], settleFrames));
                }
                return settledResult;
            } catch (Exception e) {
                return failure(e.getMessage());
            }
        } catch (Exception e) {
            return failure(e.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(COMMAND)
                .description("Move the cursor without clicking, then wait for the hover to be drawn.")
                .param(X, RouteParamType.FLOAT, false, String.valueOf(0),
                        "Target X coordinate.", "0.5")
                .param(Y, RouteParamType.FLOAT, false, String.valueOf(0),
                        "Target Y coordinate.", "0.25")
                .param(NORMALIZED, RouteParamType.BOOL, false, String.valueOf(false),
                        "Treat X/Y as fractions of window size rather than pixels.", "true")
                .param(PERSISTENT, RouteParamType.BOOL, false, String.valueOf(true),
                        "Hold the hover lock so real mouse motion cannot dislodge it; false clears it.", "false")
                .param(SETTLE, RouteParamType.INT, false, String.valueOf(InputSettle.DEFAULT_FRAMES),
                        "Frames to wait for after the hover, so a screenshot needs no sleep; 0 returns at once.", "0")
                .responseHint("{ok, settled, event:{xPx, yPx, xNorm, yNorm, persistent}}")
                .build();
    }
}
