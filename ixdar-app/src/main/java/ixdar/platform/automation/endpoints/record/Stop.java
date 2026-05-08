package ixdar.platform.automation.endpoints.record;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "record/stop", method = APIMethod.POST)
public class Stop extends AutomationEndpoint implements AutomationRoute {
    public static final String PATH = "path";
    /**
     * {@code POST /record/stop}: end the active recording session and write the
     * captured events to disk.
     *
     * @param body JSON body with optional {@code path} (output file path; blank
     *             falls back to the recorder's default {@code recordings/automation/}
     *             location)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return recorder status with {@code saved} and (on success) the absolute
     *         {@code file} path that was written, or an error object on failure
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            String path = body.has(PATH) ? body.get(PATH).getAsString() : "";
            return runtime.recorder().stop(path);
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.getMessage());
            return err;
        }
    }
}
