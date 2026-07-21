package ixdar.platform.automation.endpoints.input;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.KeyGuy;

@AutomationRouteAnnotation(path = "input/key", method = APIMethod.POST)
public class InjectKey extends AutomationEndpoint implements AutomationRoute {
    public static final String KEY = "key";
    public static final String ACTION = "action";
    public static final String MODS = "mods";
    public static final String SCANCODE = "scancode";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    /**
     * {@code POST /input/key}: synthesize a single GLFW key event on the active key
     * handler. Recorded as an abstract {@code "key"} action.
     *
     * @param body JSON body with {@code key} (GLFW key code, default 0),
     *             {@code action} (press/release/repeat, default 1 = press),
     *             {@code mods} (modifier bitmask, default 0), and {@code scancode}
     *             (default 0)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true}} on success, or an error object when no key
     *         handler is active
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        int key = body.has(KEY) ? body.get(KEY).getAsInt() : 0;
        int action = body.has(ACTION) ? body.get(ACTION).getAsInt() : 1;
        int mods = body.has(MODS) ? body.get(MODS).getAsInt() : 0;
        int scancode = body.has(SCANCODE)
                ? body.get(SCANCODE).getAsInt()
                : 0;
        try {
            return runtime.runOnMainThread(() -> {
                KeyGuy keys = runtime.activeKeys();
                JsonObject result = new JsonObject();
                if (keys == null) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "No active key handler");
                    return result;
                }
                keys.keyCallback(0L, key, scancode, action, mods);
                JsonObject payload = new JsonObject();
                payload.addProperty(KEY, key);
                payload.addProperty(ACTION, action);
                payload.addProperty(MODS, mods);
                payload.addProperty(SCANCODE, scancode);
                runtime.recorder.recordAbstract(KEY, payload);
                result.addProperty(OK, true);
                return result;
            });
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty(OK, false);
            error.addProperty(ERROR, e.getMessage());
            return error;
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(KEY)
                .description("Synthesize a single GLFW key event on the active key handler.")
                .param(KEY, RouteParamType.INT, false, String.valueOf(0),
                        "GLFW key code.", "65")
                .param(ACTION, RouteParamType.INT, false, String.valueOf(1),
                        "Event action: 1 press, 0 release, 2 repeat.", "0")
                .param(MODS, RouteParamType.INT, false, String.valueOf(0),
                        "Modifier-key bitmask.", "8")
                .param(SCANCODE, RouteParamType.INT, false, String.valueOf(0),
                        "Platform scancode.", "24")
                .responseHint("{ok}")
                .build();
    }
}
