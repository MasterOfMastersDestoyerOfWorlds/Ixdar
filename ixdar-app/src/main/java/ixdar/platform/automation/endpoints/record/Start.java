package ixdar.platform.automation.endpoints.record;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(id = "RecordStart", path = "record/start", method = APIMethod.POST)
public class Start extends AutomationEndpoint implements AutomationRoute {
    /**
     * {@code POST /record/start}: begin a new recording session, clearing any
     * previously buffered events.
     *
     * @param body request body (unused)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return recorder status (see {@link ixdar.platform.automation.AutomationRecorder#status()})
     *         with an additional {@code "ok": true} flag
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        runtime.recorder().start();
        JsonObject result = runtime.recorder().status();
        result.addProperty("ok", true);
        return result;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Begin a new recording session, clearing any previously buffered events.")
                .responseHint("{recording, rawEventCount, abstractActionCount, startedAtIso, lastSavedFile, ok}")
                .build();
    }
}
