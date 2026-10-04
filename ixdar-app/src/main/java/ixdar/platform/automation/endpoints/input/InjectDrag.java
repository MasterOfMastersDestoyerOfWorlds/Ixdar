package ixdar.platform.automation.endpoints.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;

import java.io.IOException;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.graphics.render.Clock;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.automation.InputSettle;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.PointerDispatcher;

/**
 * {@code POST /input/drag}: a left-button drag from one point to another in steps, a frame
 * drawn after each, so a tool that follows the cursor per frame sees the whole path.
 */
@AutomationRouteAnnotation(path = "input/drag", method = APIMethod.POST)
public class InjectDrag extends AutomationEndpoint implements AutomationRoute {
    public static final String FROM_X = "fromX";
    public static final String FROM_Y = "fromY";
    public static final String TO_X = "toX";
    public static final String TO_Y = "toY";
    public static final String STEPS = "steps";
    public static final String SETTLE = "settle";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String COMMAND = "drag";
    public static final int DEFAULT_STEPS = 8;

    /**
     * Rest the cursor on the start and let a frame pick under it, press, move to the end in
     * {@code steps} equal moves with a frame after each, then release there.
     *
     * @param body JSON body with {@code fromX}, {@code fromY}, {@code toX}, {@code toY} in window
     *             pixels, {@code steps} (default 8) and {@code settle} (frames after the release)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true, "steps": n, "settled": true}}, or an error object when no mouse
     *         handler is active
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        float fromX = body.has(FROM_X) ? body.get(FROM_X).getAsFloat() : 0f;
        float fromY = body.has(FROM_Y) ? body.get(FROM_Y).getAsFloat() : 0f;
        float toX = body.has(TO_X) ? body.get(TO_X).getAsFloat() : fromX;
        float toY = body.has(TO_Y) ? body.get(TO_Y).getAsFloat() : fromY;
        int steps = Math.max(1, body.has(STEPS) ? body.get(STEPS).getAsInt() : DEFAULT_STEPS);
        int settleFrames = body.has(SETTLE) ? body.get(SETTLE).getAsInt()
                : InputSettle.DEFAULT_FRAMES;
        JsonObject result = new JsonObject();
        try {
            if (!deliver(mouse -> mouse.mousePos(fromX, fromY), 1)
                    || !deliver(mouse -> PointerDispatcher.current().mouseButton(mouse, 0, ACTION_PRESS, 0), 1)) {
                result.addProperty(OK, false);
                result.addProperty(ERROR, "No active mouse handler");
                return result;
            }
            for (int step = 1; step <= steps; step++) {
                float along = (float) step / steps;
                float x = fromX + along * (toX - fromX);
                float y = fromY + along * (toY - fromY);
                deliver(mouse -> PointerDispatcher.current().mouseDragged(mouse, x, y), 1);
            }
            deliver(mouse -> PointerDispatcher.current().mouseButton(mouse, 0, ACTION_RELEASE, 0),
                    settleFrames);
            JsonObject payload = new JsonObject();
            payload.addProperty(FROM_X, fromX);
            payload.addProperty(FROM_Y, fromY);
            payload.addProperty(TO_X, toX);
            payload.addProperty(TO_Y, toY);
            payload.addProperty(STEPS, steps);
            runtime.recordAbstractAction(COMMAND, payload);
            result.addProperty(OK, true);
            result.addProperty(STEPS, steps);
            result.addProperty("settled", true);
            return result;
        } catch (Exception failure) {
            result.addProperty(OK, false);
            result.addProperty(ERROR, failure.getMessage());
            return result;
        }
    }

    /**
     * Run one mouse event on the main thread, then wait for frames to be drawn after it.
     *
     * @return false when there is no active mouse handler to deliver to
     */
    private boolean deliver(Consumer<MouseTrap> event, int frames) throws Exception {
        long[] appliedDuringFrame = new long[1];
        JsonObject delivered = runtime.runOnMainThread(() -> {
            MouseTrap mouse = runtime.activeMouse();
            JsonObject applied = new JsonObject();
            applied.addProperty(OK, mouse != null);
            if (mouse != null) {
                appliedDuringFrame[0] = Clock.framesRendered();
                event.accept(mouse);
            }
            return applied;
        });
        if (!delivered.get(OK).getAsBoolean()) {
            return false;
        }
        InputSettle.awaitFrames(appliedDuringFrame[0], frames);
        return true;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(COMMAND)
                .description("Drag with the left button from one window point to another, a "
                        + "frame drawn after every step.")
                .param(FROM_X, RouteParamType.FLOAT, true, "", "Start X in window pixels.", "310")
                .param(FROM_Y, RouteParamType.FLOAT, true, "", "Start Y in window pixels.", "500")
                .param(TO_X, RouteParamType.FLOAT, true, "", "End X in window pixels.", "330")
                .param(TO_Y, RouteParamType.FLOAT, true, "", "End Y in window pixels.", "470")
                .param(STEPS, RouteParamType.INT, false, String.valueOf(DEFAULT_STEPS),
                        "Equal moves the drag is split into.", "12")
                .param(SETTLE, RouteParamType.INT, false,
                        String.valueOf(InputSettle.DEFAULT_FRAMES),
                        "Frames to wait for after the release.", "2")
                .responseHint("{ok, steps, settled}")
                .build();
    }
}
