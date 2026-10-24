package ixdar.automation;

import java.util.List;
import java.util.concurrent.Callable;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import ixdar.automation.endpoints.ui.State;
import ixdar.platform.Clock;
import ixdar.scenes.Scene;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

/**
 * Blocks an automation input route until the scene has drawn the frames its effect needs, so a
 * caller can screenshot straight after a click without guessing a sleep.
 */
public final class InputSettle {

    public static final int DEFAULT_FRAMES = 2;

    public static final long TIMEOUT_MILLIS = 5000L;

    public static final String SETTLED = "settled";

    public static final String CONTROL = "control";

    public static final String PREVIOUS_TOOL = "previousTool";

    private static final long POLL_MILLIS = 1L;

    private InputSettle() {
    }

    /**
     * Wait until the renderer has completed {@code frames} frames after the one the input was
     * applied during. The applying frame itself does not count: it was drawn before the input ran.
     *
     * @param appliedDuringFrame {@link Clock#framesRendered()} sampled on the render thread while
     *        the input was being delivered
     * @param frames how many drawn frames past the input to wait for; zero or less returns at once
     * @return {@code true} when the frames arrived, {@code false} on the
     *         {@value #TIMEOUT_MILLIS}ms timeout
     */
    public static boolean awaitFrames(long appliedDuringFrame, int frames) {
        if (frames <= 0) {
            return true;
        }
        long target = appliedDuringFrame + 1 + frames;
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (Clock.framesRendered() < target) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Deliver a key or click on the render thread, wait for it to be drawn, then add to its reply
     * the control row it fired, the active tool, the one before a switch, and its summary.
     *
     * @param runtime the runtime whose scene takes the input
     * @param frames  drawn frames to wait for after the input, as {@link #awaitFrames} counts them
     * @param input   delivers the input on the render thread and answers the route's reply
     * @return the reply, or a failure when no scene is shown or the input refused
     */
    public static JsonObject settledInput(AutomationRuntime runtime, int frames, Callable<JsonObject> input) {
        try {
            JsonObject reply = runtime.runOnMainThread(() -> {
                Scene scene = runtime.canvas;
                if (scene == null) {
                    return AutomationEndpoint.failure("no scene is active");
                }
                String toolBefore = scene.activeTool == null ? "" : scene.activeTool.toolName();
                scene.keys.lastFiredControl = null;
                JsonObject applied = input.call();
                if (applied.get(AutomationEndpoint.OK).getAsBoolean()) {
                    ControlHint fired = scene.keys.lastFiredControl;
                    applied.add(CONTROL, fired == null ? JsonNull.INSTANCE : State.controls(List.of(fired)).get(0));
                    applied.addProperty(State.ACTIVE_TOOL, toolBefore);
                }
                return applied;
            });
            if (!reply.get(AutomationEndpoint.OK).getAsBoolean()) {
                return reply;
            }
            reply.addProperty(SETTLED, awaitFrames(Clock.framesRendered(), frames));
            return runtime.runOnMainThread(() -> {
                Tool after = runtime.canvas == null ? null : runtime.canvas.activeTool;
                String toolBefore = reply.get(State.ACTIVE_TOOL).getAsString();
                String toolAfter = after == null ? "" : after.toolName();
                if (!toolAfter.equals(toolBefore)) {
                    reply.addProperty(PREVIOUS_TOOL, toolBefore);
                    reply.addProperty(State.ACTIVE_TOOL, toolAfter);
                }
                if (after != null) {
                    reply.add(State.TOOL, State.toolSummary(after));
                }
                return reply;
            });
        } catch (Exception e) {
            return AutomationEndpoint.failure(e.getMessage());
        }
    }
}
