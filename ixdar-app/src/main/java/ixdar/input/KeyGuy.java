package ixdar.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.ACTION_REPEAT;
import static ixdar.platform.input.Keys.LEFT_CONTROL;
import static ixdar.platform.input.Keys.LEFT_SHIFT;
import static ixdar.platform.input.Keys.RIGHT_CONTROL;
import static ixdar.platform.input.Keys.RIGHT_SHIFT;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import ixdar.graphics.cameras.CameraController;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.AutomationBackend;
import ixdar.platform.Platforms;
import ixdar.platform.input.KeyActions;
import ixdar.platform.input.KeyNames;
import ixdar.platform.input.Keys;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;
import ixdar.tools.ControlHint;

/**
 * Every scene's key handler: a first press fires the first of the scene's control rows bound to
 * it, held keys drive the scene's camera controller, and while the terminal has focus its keys
 * are the terminal's alone.
 */
public class KeyGuy {
    public static final String KEY = "key";

    public static final long REPEAT_MILLIS = 360;

    public final Set<Integer> pressedKeys = new HashSet<>();
    public boolean active = true;
    public Scene canvas;
    public boolean shiftMask;
    public boolean controlMask;

    /**
     * Whether a control row that does not need Control stays silent while Control is held, as the
     * tour editor's single-key toggles always have; otherwise it fires with Control held too.
     */
    public boolean controlSilencesPlainKeys;

    /** When a held {@link ControlHint#repeatsWhileHeld repeating} row last fired. */
    public long lastRepeatMillis;

    /**
     * Key handler for a scene, firing the rows of its {@link Scene#controls}.
     *
     * @param canvas owning scene, whose controls the keys fire and whose camera they drive
     */
    public KeyGuy(Scene canvas) {
        this.canvas = canvas;
    }

    /**
     * Record a high-level key action with the platform's automation backend, if it has one.
     *
     * @param action    action type tag
     * @param keyValues alternating payload keys and values
     */
    public static void recordAbstractAction(String action, Object... keyValues) {
        AutomationBackend automation = Platforms.get().automation();
        if (automation == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            payload.put((String) keyValues[i], keyValues[i + 1]);
        }
        automation.recordAbstractActionMap(action, payload);
    }

    /**
     * Whether a key belongs to the focused scene terminal and must not reach any scene binding.
     * The terminal toggle key ({@code ~}) never does, so it can still close the terminal.
     *
     * @param key key code
     * @return {@code true} while the terminal has focus and {@code key} is not the toggle key
     */
    public static boolean terminalOwnsKey(int key) {
        return Toggle.IsTerminalFocused.value && key != Keys.GRAVE;
    }

    private void keyPressed(int key, int mods, boolean repeated) {
        if (!active) {
            return;
        }
        recordAbstractAction("key_press", KEY, key, "mods", mods, "repeated", String.valueOf(repeated));
        boolean firstPress = !pressedKeys.contains(key);
        pressedKeys.add(key);

        if (KeyActions.ControlMask.keyPressed(pressedKeys)) {
            controlMask = true;
        }
        if (KeyActions.ShiftMask.keyPressed(pressedKeys)) {
            shiftMask = true;
        }
        if (firstPress) {
            handleSceneKeys(key, mods);
        }
        if (Toggle.IsTerminalFocused.value && Terminal.current != null) {
            Terminal.current.keyPress(key, mods, controlMask);
            KeyActions.markKeyConsumed();
        }
    }

    /**
     * Fire the first of the scene's control rows bound to {@code key} with its modifiers held, or
     * none while the focused terminal owns the key. Scenes bind keys by adding controls.
     *
     * @param key  pressed key code
     * @param mods modifier-key bitmask
     */
    public void handleSceneKeys(int key, int mods) {
        if (terminalOwnsKey(key)) {
            return;
        }
        boolean control = (mods & KeyNames.MOD_CONTROL) != 0;
        for (ControlHint hint : canvas.controls) {
            boolean modifierHeld = (hint.controlHeld ? control : !(control && controlSilencesPlainKeys))
                    && (!hint.shiftHeld || (mods & KeyNames.MOD_SHIFT) != 0);
            if (hint.keyCode == key && hint.keyCode != ControlHint.NO_KEY && hint.action != null
                    && modifierHeld) {
                hint.action.perform();
                KeyActions.markKeyConsumed();
                lastRepeatMillis = System.currentTimeMillis();
                return;
            }
        }
    }

    /**
     * Handle a key-up: {@code key} leaves {@link #pressedKeys} and each modifier mask holds only
     * while one of its keys is down. No row fires on a release.
     *
     * @param key  key code that was released
     * @param mask modifier-key bitmask
     */
    public void keyReleased(int key, int mask) {
        if (!active) {
            return;
        }
        recordAbstractAction("key_release", KEY, key, "mask", mask);
        pressedKeys.remove(key);
        controlMask = pressedKeys.contains(LEFT_CONTROL) || pressedKeys.contains(RIGHT_CONTROL);
        shiftMask = pressedKeys.contains(LEFT_SHIFT) || pressedKeys.contains(RIGHT_SHIFT);
    }

    /**
     * Per-frame, unless the terminal has focus: the held keys drive the scene's camera controller,
     * then the held repeating control rows refire once {@link #REPEAT_MILLIS}, scaled down by the
     * speed multiplier, has passed since a row fired.
     *
     * @param shiftMod speed multiplier (typically 1 or 2)
     */
    public void paintUpdate(float shiftMod) {
        if (!active || Toggle.IsTerminalFocused.value) {
            return;
        }
        CameraController controller = canvas.mouse == null ? null : canvas.mouse.controller;
        if (controller != null) {
            controller.keysHeld(pressedKeys, shiftMod);
        }
        long now = System.currentTimeMillis();
        if (now - lastRepeatMillis <= REPEAT_MILLIS / shiftMod) {
            return;
        }
        for (ControlHint hint : canvas.controls) {
            if (hint.repeatsWhileHeld && pressedKeys.contains(hint.keyCode)
                    && !(controlMask && controlSilencesPlainKeys)) {
                lastRepeatMillis = now;
                hint.action.perform();
            }
        }
    }

    /**
     * Platform key-event entry point: rebinds {@link Platforms} to the owning
     * canvas, then dispatches to {@code keyPressed} (with {@code repeated = true}
     * for {@code ACTION_REPEAT}) or {@link #keyReleased}.
     *
     * @param window   platform window handle
     * @param key      key code (see {@code Keys})
     * @param scancode raw scancode (GLFW; 0 on web)
     * @param action   {@code ACTION_PRESS} / {@code ACTION_REPEAT} /
     *                 {@code ACTION_RELEASE}
     * @param mods     modifier-key bitmask
     */
    public void keyCallback(long window, int key, int scancode, int action, int mods) {
        Platforms.init(canvas.platform.getPlatformID());
        switch (action) {
        case ACTION_PRESS:
            keyPressed(key, mods, false);
            break;
        case ACTION_REPEAT:
            keyPressed(key, mods, true);
            break;
        case ACTION_RELEASE:
            keyReleased(key, mods);
            break;
        default:
            break;
        }
    }

    /**
     * Platform char-event entry point: when terminal focus is on, forwards typed
     * characters into {@link Terminal#current}.
     *
     * @param window    platform window handle
     * @param codepoint Unicode code point of typed character
     */
    public void charCallback(long window, int codepoint) {
        Platforms.init(canvas.platform.getPlatformID());
        String currentText = "" + (char) codepoint;
        recordAbstractAction("char_input", "text", currentText, "codepoint", codepoint);
        if (codepoint == '`' || codepoint == '~') {
            return;
        }
        if (Toggle.IsTerminalFocused.value && Terminal.current != null) {
            Terminal.current.type(currentText);
        }
    }

}
