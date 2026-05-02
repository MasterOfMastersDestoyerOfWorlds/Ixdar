package ixdar.platform.automation.endpoints;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.canvas.IxdarWindow;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "shutdown", method = APIMethod.POST)
public class Shutdown extends AutomationEndpoint implements AutomationRoute {
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("accepted", true);
        Thread shutdownThread = new Thread(
                () -> {
                    try {
                        Thread.sleep(150);
                        runtime.runOnMainThread(() -> {
                            if (runtime.canvas != null) {
                                runtime.canvas.shutdown();
                            } else {
                                runtime.stop();
                            }
                            IxdarWindow.requestClose();
                            return new JsonObject();
                        });
                        Thread.sleep(1200);
                        System.exit(0);
                    } catch (Exception ignored) {
                        // Shutdown is best-effort.
                    }
                },
                "automation-shutdown");
        shutdownThread.setDaemon(true);
        shutdownThread.start();
        return result;
    }
}
