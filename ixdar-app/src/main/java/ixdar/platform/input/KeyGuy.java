package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.ACTION_REPEAT;
import static ixdar.platform.input.Keys.LEFT_CONTROL;
import static ixdar.platform.input.Keys.LEFT_SHIFT;
import static ixdar.platform.input.Keys.RIGHT_CONTROL;
import static ixdar.platform.input.Keys.RIGHT_SHIFT;
import java.util.HashSet;

import java.util.HashMap;
import java.util.Set;

import java.util.Map;

import ixdar.graphics.cameras.Camera;
import ixdar.platform.Clock;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.Platforms;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.menu.MenuScene;
import ixdar.platform.AutomationBackend;
import ixdar.tools.ControlHint;

public class KeyGuy extends Camera2DInputController {
    public static final String KEY = "key";

    /**
     * Running count of key events some binding matched. Automation samples it
     * around an injected key to report whether anything consumed that key.
     */
    public static int keysConsumed;

    public static final long REPEAT_MILLIS = 360;

    public final Set<Integer> pressedKeys = new HashSet<>();
    public Camera camera;
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
     * Key handler shared by every scene: modifier masks, terminal forwarding, and the menu-back and
     * camera-reset releases; scenes add their own shortcuts in a subclass.
     *
     * @param camera camera the controller drives
     * @param canvas owning canvas (for platform-id resolution)
     */
    public KeyGuy(Camera camera, Scene canvas) {
        this.camera = camera;
        this.canvas = canvas;
    }

    /**
     * Record a high-level key action with the platform's automation backend, if it has one.
     *
     * @param action    action type tag
     * @param keyValues alternating payload keys and values
     */
    public static void recordAbstractAction(String action, Object... keyValues) {
        AutomationBackend automation =Platforms.get().automation();
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
     * Record that the key event being dispatched matched a binding. Called only
     * from the dispatch primitives ({@link KeyActions#keyPressed}, a control hint,
     * the terminal), never per binding.
     */
    public static void markKeyConsumed() {
        keysConsumed++;
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
        if (!terminalOwnsKey(key)) {
            sceneKeyPressed(key, mods, firstPress);
        }
        if (Toggle.IsTerminalFocused.value && Terminal.current != null) {
            Terminal.current.keyPress(key, mods, controlMask);
            markKeyConsumed();
        }
    }

    /**
     * Fire the scene's own shortcuts for a key the terminal does not own, after {@link #pressedKeys}
     * and the modifier masks are updated and before the terminal sees the key. The shared handler
     * binds none.
     *
     * @param key        pressed key code
     * @param mods       modifier-key bitmask
     * @param firstPress whether the key was up before this event, {@code false} on a repeat
     */
    public void sceneKeyPressed(int key, int mods, boolean firstPress) {
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
                markKeyConsumed();
                lastRepeatMillis = System.currentTimeMillis();
                return;
            }
        }
    }

    /**
     * Handle a key-up: the scene's release shortcuts through {@link #sceneKeyReleased}, then
     * {@code key} leaves {@link #pressedKeys} and each modifier mask holds only while one of its
     * keys is down.
     *
     * @param key  key code that was released
     * @param mask modifier-key bitmask
     */
    public void keyReleased(int key, int mask) {
        if (!active) {
            return;
        }
        recordAbstractAction("key_release", KEY, key, "mask", mask);
        if (canvas.active && !terminalOwnsKey(key)) {
            sceneKeyReleased();
        }
        pressedKeys.remove(key);
        controlMask = pressedKeys.contains(LEFT_CONTROL) || pressedKeys.contains(RIGHT_CONTROL);
        shiftMask = pressedKeys.contains(LEFT_SHIFT) || pressedKeys.contains(RIGHT_SHIFT);
    }

    /**
     * Fire the release shortcuts of an active scene for a key the terminal does not own, while
     * {@link #pressedKeys} still holds the key: Escape steps the menu back, R resets the camera.
     */
    public void sceneKeyReleased() {
        if (KeyActions.Back.keyPressed(pressedKeys) && canvas instanceof MenuScene menuScene
                && menuScene.menu != null) {
            menuScene.menu.back();
        }
        if (KeyActions.Reset.keyPressed(pressedKeys)) {
            camera.reset();
        }
    }

    /**
     * Per-frame: skip while terminal is focused (so typing into terminal doesn't
     * move the camera), forward camera movement keys to
     * {@link Camera2DInputController#apply}, then refire the held repeating control rows once
     * {@link #REPEAT_MILLIS}, scaled down by the speed multiplier, has passed since a row fired.
     *
     * @param SHIFT_MOD speed multiplier (typically 1 or 2)
     */
    public void paintUpdate(float SHIFT_MOD) {
        if (!active || Toggle.IsTerminalFocused.value) {
            return;
        }
        super.apply(camera, pressedKeys, SHIFT_MOD, Clock.deltaTime());
        long now = System.currentTimeMillis();
        if (now - lastRepeatMillis <= REPEAT_MILLIS / SHIFT_MOD) {
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
