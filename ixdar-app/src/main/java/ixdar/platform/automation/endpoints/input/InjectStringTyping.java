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
import ixdar.platform.input.Keys;

@AutomationRouteAnnotation(path = "input/type", method = APIMethod.POST)
public class InjectStringTyping extends AutomationEndpoint implements AutomationRoute {
    public static final String TEXT = "text";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String COMMAND = "type";
    /**
     * {@code POST /input/type}: synthesize a sequence of character events on the
     * active key handler, one {@code charCallback} per character. A space is sent as a
     * {@code SPACE} key press/release instead, because {@code Terminal.type} ignores blank
     * character input (a real keyboard delivers the space through its key event). Recorded as an
     * abstract {@code "type"} action.
     *
     * @param body JSON body with {@code text} (string, default {@code ""})
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true}} on success, or an error object when no key
     *         handler is active or main-thread dispatch fails
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            String text = body.has(TEXT)
                    ? body.get(TEXT).getAsString()
                    : "";
            try {
                return runtime.runOnMainThread(() -> {
                    KeyGuy keys = runtime.activeKeys();
                    JsonObject result = new JsonObject();
                    if (keys == null) {
                        result.addProperty(OK, false);
                        result.addProperty(ERROR, "No active key handler");
                        return result;
                    }
                    for (int i = 0; i < text.length(); i++) {
                        char typed = text.charAt(i);
                        if (typed == ' ') {
                            keys.keyCallback(0L, Keys.SPACE, 0, Keys.ACTION_PRESS, 0);
                            keys.keyCallback(0L, Keys.SPACE, 0, Keys.ACTION_RELEASE, 0);
                        } else {
                            keys.charCallback(0L, typed);
                        }
                    }
                    JsonObject payload = new JsonObject();
                    payload.addProperty(TEXT, text);
                    runtime.recorder.recordAbstract(COMMAND, payload);
                    result.addProperty(OK, true);
                    return result;
                });
            } catch (Exception e) {
                JsonObject error = new JsonObject();
                error.addProperty(OK, false);
                error.addProperty(ERROR, e.getMessage());
                return error;
            }
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
                .commandName(COMMAND)
                .description("Synthesize character events on the active key handler, one per character of the text.")
                .param(TEXT, RouteParamType.STRING, false, "",
                        "Text to type, delivered character by character.", "hello world")
                .responseHint("{ok}")
                .build();
    }
}
