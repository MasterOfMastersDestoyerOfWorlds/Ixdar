package ixdar.platform.automation.endpoints.record;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(id = "RecordStatus", path = "record/status", method = APIMethod.GET)
public class Status extends AutomationEndpoint implements AutomationRoute {
    /**
     * {@code GET /record/status}: snapshot of the recorder.
     *
     * @param body request body (unused)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return recorder status (see {@link ixdar.platform.automation.AutomationRecorder#status()})
     *         with {@code recording}, event counts, start timestamp, and last saved
     *         file path
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        return runtime.recorder().status();
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Snapshot of the recorder: recording flag, event counts, start time, saved file.")
                .responseHint("{recording, rawEventCount, abstractActionCount, startedAtIso, lastSavedFile}")
                .build();
    }
}
