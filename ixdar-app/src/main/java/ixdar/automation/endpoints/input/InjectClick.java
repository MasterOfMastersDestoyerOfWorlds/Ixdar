package ixdar.automation.endpoints.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;

import java.io.IOException;
import java.util.List;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.automation.InputSettle;
import ixdar.automation.endpoints.ui.DrawnWords;
import ixdar.input.KeyGuy;
import ixdar.input.MouseTrap;
import ixdar.input.PointerDispatcher;
import ixdar.platform.input.KeyNames;

@AutomationRouteAnnotation(path = "input/click", method = APIMethod.POST)
public class InjectClick extends AutomationEndpoint implements AutomationRoute {
    public static final String X = "x";
    public static final String Y = "y";
    public static final String NORMALIZED = "normalized";
    public static final String BUTTON = "button";
    public static final String SETTLE = "settle";
    public static final String COUNT = "count";
    public static final String MODS = "mods";
    public static final String COMMAND = "click";

    /**
     * {@code POST /input/click}: move the cursor to a target position, issue a press/release pair
     * on the active mouse handler, then wait for the click to be drawn. Recorded as an abstract
     * {@code "click"} action. Given a {@code label}, the target is that drawn label's pixel.
     *
     * @param body JSON body with {@code label} (drawn text to click instead of a point, with
     *             {@code within} and {@code index} narrowing it, see
     *             {@link DrawnWords#labelTarget}), {@code x}, {@code y} (floats, default 0),
     *             {@code normalized} (boolean; when true, {@code x}/{@code y} are
     *             treated as fractions of window size), {@code button}
     *             (GLFW button code, default 0), {@code count} (press/release pairs
     *             back to back, 2 for a double-click; default 1), {@code mods} (modifier
     *             words held for the click, such as {@code shift}) and {@code settle}
     *             (frames to wait for after the click; default 2)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return the event and, once it settled, the active tool, the controls-menu row it fired and
     *         the tool's shared state; an error object when no mouse handler is active, a modifier
     *         is unknown or the label refuses
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String label = body.has(DrawnWords.LABEL) ? body.get(DrawnWords.LABEL).getAsString() : "";
        String within = body.has(DrawnWords.WITHIN) ? body.get(DrawnWords.WITHIN).getAsString() : "";
        int index = body.has(DrawnWords.INDEX) ? body.get(DrawnWords.INDEX).getAsInt()
                : DrawnWords.NO_INDEX;
        float x = body.has(X) ? body.get(X).getAsFloat() : 0f;
        float y = body.has(Y) ? body.get(Y).getAsFloat() : 0f;
        boolean normalized = body.has(NORMALIZED) && body.get(NORMALIZED).getAsBoolean();
        int button = body.has(BUTTON) ? body.get(BUTTON).getAsInt() : 0;
        int count = body.has(COUNT) ? body.get(COUNT).getAsInt() : 1;
        int settleFrames = body.has(SETTLE)
                ? body.get(SETTLE).getAsInt()
                : InputSettle.DEFAULT_FRAMES;
        String held = body.has(MODS) ? body.get(MODS).getAsString() : "";
        List<String> modifiers;
        try {
            modifiers = KeyNames.modifiers(held, false);
        } catch (IllegalArgumentException badModifier) {
            return failure(badModifier.getMessage());
        }
        int mask = KeyNames.modifierMask(modifiers);
        List<Integer> modifierKeys = KeyNames.modifierKeyCodes(modifiers);
        return InputSettle.settledInput(runtime, settleFrames, () -> {
                MouseTrap mouse = runtime.activeMouse();
                KeyGuy keys = runtime.activeKeys();
                JsonObject target = label.isEmpty() ? null
                        : DrawnWords.labelTarget(label, within, index);
                if (target != null && !target.get(OK).getAsBoolean()) {
                    return target;
                }
                float xPos = target != null ? target.get(X).getAsFloat() : normalized ? denormalizeX(x) : x;
                float yPos = target != null ? target.get(Y).getAsFloat() : normalized ? denormalizeY(y) : y;
                mouse.automationInputInProgress = true;
                // The modifiers go down on the key handler as held keys do, so a tool reading the
                // pressed keys and one reading the button's mask both see them.
                for (int modifierKey : modifierKeys) {
                    keys.keyCallback(0L, modifierKey, 0, ACTION_PRESS, mask);
                }
                try {
                    // A pinned hover follows the cursor to the click, as a real cursor stays where
                    // it clicked; left behind, it would pull a tool's next-frame pick back to it.
                    if (mouse.automationHoverLocked) {
                        mouse.setAutomationHoverLock(xPos, yPos);
                    }
                    mouse.mousePos(xPos, yPos);
                    PointerDispatcher pointer = PointerDispatcher.current();
                    for (int click = 0; click < count; click++) {
                        pointer.mouseButton(mouse, button, ACTION_PRESS, mask);
                        pointer.mouseButton(mouse, button, ACTION_RELEASE, mask);
                    }
                } finally {
                    mouse.automationInputInProgress = false;
                    for (int modifier = modifierKeys.size() - 1; modifier >= 0; modifier--) {
                        keys.keyCallback(0L, modifierKeys.get(modifier), 0, ACTION_RELEASE, 0);
                    }
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("xPx", xPos);
                payload.addProperty("yPx", yPos);
                payload.addProperty("xNorm", normalizeX(xPos));
                payload.addProperty("yNorm", normalizeY(yPos));
                payload.addProperty(BUTTON, button);
                payload.addProperty(MODS, held);
                if (target != null) {
                    payload.addProperty(DrawnWords.LABEL, target.get(DrawnWords.LABEL).getAsString());
                    payload.addProperty(DrawnWords.VIEW, target.get(DrawnWords.VIEW).getAsString());
                }
                runtime.recordAbstractAction(COMMAND, payload);
                JsonObject applied = success();
                applied.add("event", payload);
                return applied;
            });
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(COMMAND)
                .description("Click at a point, or on a drawn label by its text, on the active mouse "
                        + "handler, then wait for the click to be drawn.")
                .param(DrawnWords.LABEL, RouteParamType.STRING, false, "",
                        "Click the one visible clickable drawn label with this text instead of x/y; "
                                + "refuses, listing candidates, when absent, out of view or ambiguous.",
                        "Start New Game")
                .param(DrawnWords.WITHIN, RouteParamType.STRING, false, "",
                        "Only labels drawn in this view id, as words lists them.", "TERMINAL")
                .param(DrawnWords.INDEX, RouteParamType.INT, false, String.valueOf(DrawnWords.NO_INDEX),
                        "Which of several visible clickable label matches, in drawing order.",
                        String.valueOf(1))
                .param(X, RouteParamType.FLOAT, false, String.valueOf(0),
                        "Target X coordinate.", "0.5")
                .param(Y, RouteParamType.FLOAT, false, String.valueOf(0),
                        "Target Y coordinate.", "0.25")
                .param(NORMALIZED, RouteParamType.BOOL, false, "false",
                        "Treat X/Y as fractions of window size rather than pixels.", "true")
                .param(BUTTON, RouteParamType.INT, false, String.valueOf(0),
                        "GLFW mouse button code.", "1")
                .param(COUNT, RouteParamType.INT, false, String.valueOf(1),
                        "Press/release pairs delivered back to back; 2 is a double-click.", "2")
                .param(MODS, RouteParamType.STRING, false, "",
                        "Modifier keys held down for the click and released after, +-separated: shift, ctrl, "
                                + "alt.",
                        "shift")
                .param(SETTLE, RouteParamType.INT, false, String.valueOf(InputSettle.DEFAULT_FRAMES),
                        "Frames to wait for after the click, so a screenshot needs no sleep; 0 returns at once.", "0")
                .responseHint("{ok, settled, event:{xPx, yPx, xNorm, yNorm, button, mods, label?, view?}, "
                        + "activeTool, previousTool?, control (the controls-menu row clicked, else null), "
                        + "tool?:{name, lastRow, error, undo/redo heads, mode?}}, "
                        + "or {ok:false, error, candidates} when a label refuses")
                .build();
    }
}
