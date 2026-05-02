package ixdar.platform.automation.endpoints.record;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "record/status", method = APIMethod.GET)
public class Status extends AutomationEndpoint implements AutomationRoute {
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        return runtime.recorder().status();
    }
}
