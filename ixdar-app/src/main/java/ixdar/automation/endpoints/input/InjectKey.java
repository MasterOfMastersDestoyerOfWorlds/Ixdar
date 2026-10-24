package ixdar.automation.endpoints.input;

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
import ixdar.gui.terminal.Terminal;
import ixdar.input.KeyGuy;
import ixdar.platform.input.KeyActions;
import ixdar.platform.input.KeyNames;
import ixdar.platform.input.Keys;
import ixdar.tools.Tool;

@AutomationRouteAnnotation(path = "input/key", method = APIMethod.POST)
public class InjectKey extends AutomationEndpoint implements AutomationRoute {
    public static final String KEY = "key";
    public static final String ACTION = "action";
    public static final String MODS = "mods";
    public static final String SETTLE = "settle";
    public static final String KEY_CODE = "keyCode";
    public static final String CONSUMED = "consumed";
    public static final String HANDLED = "handled";
    public static final String REASON = "reason";

    /**
     * {@code POST /input/key}: deliver a named key event to the active key handler and report
     * whether anything took it. Recorded as an abstract {@code "key"} action.
     *
     * @param body JSON body with {@code key} (a name such as {@code ESCAPE}, {@code GRAVE} or
     *             {@code CTRL+SHIFT+S}), {@code action} ({@code tap}, {@code press},
     *             {@code release}, {@code repeat}; default {@code tap}) and {@code settle}
     *             (frames to wait for after the input; default 2)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return the key, whether anything took it and why not, and once it settled the active tool,
     *         the control row it fired and the tool's shared state; an error object when the key
     *         name is unparseable or no key handler is active
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String spec = body.has(KEY) && !body.get(KEY).isJsonNull()
                ? body.get(KEY).getAsString()
                : "";
        String action = body.has(ACTION) && !body.get(ACTION).isJsonNull()
                ? body.get(ACTION).getAsString()
                : KeyNames.ACTION_TAP;
        int settleFrames = body.has(SETTLE)
                ? body.get(SETTLE).getAsInt()
                : InputSettle.DEFAULT_FRAMES;
        int keyCode;
        int mods;
        List<Integer> modifierKeys;
        boolean tap;
        int actionCode;
        try {
            keyCode = KeyNames.keyCode(spec);
            mods = KeyNames.modifierMask(spec);
            modifierKeys = KeyNames.modifierKeyCodes(spec);
            tap = KeyNames.ACTION_TAP.equalsIgnoreCase(action.trim());
            actionCode = tap ? Keys.ACTION_PRESS : KeyNames.actionCode(action);
        } catch (IllegalArgumentException badSpec) {
            return failure(badSpec.getMessage());
        }
        return InputSettle.settledInput(runtime, settleFrames, () -> {
                KeyGuy keys = runtime.activeKeys();
                int consumedBefore = KeyActions.keysConsumed;
                deliver(keys, keyCode, mods, modifierKeys, actionCode, tap);
                JsonObject payload = new JsonObject();
                payload.addProperty(KEY, spec);
                payload.addProperty(KEY_CODE, keyCode);
                payload.addProperty(MODS, mods);
                payload.addProperty(ACTION, action);
                runtime.recordAbstractAction(KEY, payload);
                JsonObject applied = success();
                applied.addProperty(KEY, spec);
                applied.addProperty(KEY_CODE, keyCode);
                applied.addProperty(MODS, mods);
                applied.addProperty(ACTION, action);
                applied.addProperty(CONSUMED, KeyActions.keysConsumed > consumedBefore);
                // The dispatcher's own answer: a control row fired, or the focused terminal took the key.
                boolean terminalTook = keys.lastFiredControl == null && KeyGuy.terminalOwnsKey(keyCode)
                        && Terminal.current != null;
                applied.addProperty(HANDLED, keys.lastFiredControl != null || terminalTook);
                Tool tool = keys.canvas.activeTool;
                if (terminalTook) {
                    applied.addProperty(REASON, "the focused terminal took " + spec
                            + ", not the scene; ~ gives the keys back to the scene");
                } else if (keys.lastFiredControl == null) {
                    applied.addProperty(REASON, "nothing took " + spec + ": no control row of the scene "
                            + (tool == null ? "" : "or of its active tool '" + tool.toolName() + "' ")
                            + "is bound to it with these modifiers");
                }
                return applied;
            });
    }

    /**
     * Drive the handler's callbacks for one key spec, holding the named modifier keys down around
     * the key itself so handlers that read pressed keys and handlers that read the mask agree.
     *
     * @param keys active key handler
     * @param keyCode code of the key named by the spec
     * @param mods GLFW modifier mask the spec asks for
     * @param modifierKeys physical modifier keys the spec asks to be held
     * @param actionCode GLFW action delivered for the key itself
     * @param tap whether the key is released again immediately
     */
    private void deliver(KeyGuy keys, int keyCode, int mods, List<Integer> modifierKeys,
            int actionCode, boolean tap) {
        boolean holding = actionCode != Keys.ACTION_RELEASE;
        if (holding) {
            for (int modifierKey : modifierKeys) {
                keys.keyCallback(0L, modifierKey, 0, Keys.ACTION_PRESS, mods);
            }
        }
        keys.keyCallback(0L, keyCode, 0, actionCode, mods);
        if (tap) {
            keys.keyCallback(0L, keyCode, 0, Keys.ACTION_RELEASE, mods);
        }
        if (tap || !holding) {
            for (int index = modifierKeys.size() - 1; index >= 0; index--) {
                keys.keyCallback(0L, modifierKeys.get(index), 0, Keys.ACTION_RELEASE, 0);
            }
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(KEY)
                .description("Deliver a named key event to the active key handler and report what took it: "
                        + "the control row it fired, or that nothing did, and the active tool's state after.")
                .param(KEY, RouteParamType.STRING, true, "",
                        "Key name, with optional modifiers: ESCAPE, GRAVE, RIGHT_BRACKET, P, SHIFT+P, CTRL+SHIFT+S. "
                                + "Raw GLFW codes are rejected.",
                        "SHIFT+P")
                .param(ACTION, RouteParamType.STRING, false, KeyNames.ACTION_TAP,
                        "Event action: tap (press then release), press, release or repeat.", "press")
                .param(SETTLE, RouteParamType.INT, false, String.valueOf(InputSettle.DEFAULT_FRAMES),
                        "Frames to wait for after the key, so a screenshot needs no sleep; 0 returns at once.", "0")
                .responseHint("{ok, key, keyCode, mods, action, consumed, handled, reason?, settled, "
                        + "activeTool, previousTool?, control:{key, description, keyCode, controlHeld, "
                        + "shiftHeld}|null, tool?:{name, lastRow, error, undoDepth?, redoDepth?, nextUndo?, "
                        + "nextRedo?, mode?, modes?}}")
                .build();
    }
}
