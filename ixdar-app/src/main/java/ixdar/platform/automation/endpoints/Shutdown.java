package ixdar.platform.automation.endpoints;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.canvas.IxdarWindow;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "shutdown", method = APIMethod.POST)
public class Shutdown extends AutomationEndpoint implements AutomationRoute {
    public static final int NUM_150 = 150;
    public static final int NUM_1200 = 1200;
    /**
     * {@code POST /shutdown}: acknowledge immediately, then asynchronously close the
     * canvas (or stop the runtime), request window close, and finally call
     * {@link System#exit(int)} after a short grace period.
     *
     * @param body request body (unused)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true, "accepted": true}}
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("accepted", true);
        Thread shutdownThread = new Thread(
                () -> {
                    try {
                        Thread.sleep(NUM_150);
                        runtime.runOnMainThread(() -> {
                            if (runtime.canvas != null) {
                                runtime.canvas.shutdown();
                            } else {
                                runtime.stop();
                            }
                            IxdarWindow.requestClose();
                            return new JsonObject();
                        });
                        Thread.sleep(NUM_1200);
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

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Acknowledge, then asynchronously close the canvas and exit the process.")
                .responseHint("{ok, accepted}")
                .build();
    }
}
