package ixdar.platform.input;

import static ixdar.platform.input.Keys.*;

import java.util.Set;

public enum KeyActions {
    ZoomIn(EQUAL),
    ZoomOut(MINUS),
    MoveRight(D),
    MoveLeft(A),
    MoveUp(W),
    MoveDown(S),
    DoubleSpeed(LEFT_SHIFT),
    ControlMask(LEFT_CONTROL, RIGHT_CONTROL),
    ShiftMask(LEFT_SHIFT, RIGHT_SHIFT);

    /**
     * Running count of key events some binding matched. Automation samples it around an injected
     * key to report whether anything consumed that key.
     */
    public static int keysConsumed;

    public final String keyBindingsFileLocation = "./src/main/resources/res/keyBindings.txt";

    Integer[] keys;
    boolean controlMask;

    KeyActions(Keys... defaultKeyPresses) {
        controlMask = false;
        keys = new Integer[defaultKeyPresses.length];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = defaultKeyPresses[i].keyCode;
        }
    }

    /**
     * Test whether this action is currently triggered. For control-masked actions, the
     * {@link #ControlMask} chord must also be down. For non-control actions,
     * having any control key held suppresses the trigger so terminal shortcuts don't bleed into
     * single-key bindings.
     *
     * @param pressedKeys live set of pressed key codes
     * @return true if the binding is satisfied this frame
     */
    public boolean keyPressed(Set<Integer> pressedKeys) {
        boolean satisfied = matches(pressedKeys);
        if (satisfied && this != ControlMask && this != ShiftMask) {
            markKeyConsumed();
        }
        return satisfied;
    }

    /**
     * Record that the key event being dispatched matched a binding. Called only from the dispatch
     * primitives ({@link #keyPressed}, a control row, the terminal), never per binding.
     */
    public static void markKeyConsumed() {
        keysConsumed++;
    }

    /**
     * The binding test behind {@link #keyPressed(Set)}, without the consumption bookkeeping.
     *
     * @param pressedKeys live set of pressed key codes
     * @return true if the binding is satisfied this frame
     */
    private boolean matches(Set<Integer> pressedKeys) {
        if (controlMask) {
            if (KeyActions.ControlMask.keyPressed(pressedKeys)) {
                boolean flag = false;
                for (int i = 0; i < keys.length; i++) {
                    if (pressedKeys.contains(keys[i])) {
                        flag = true;
                        break;
                    }
                }
                return flag;
            } else {
                return false;
            }
        } else {
            if (this != KeyActions.ControlMask && KeyActions.ControlMask.keyPressed(pressedKeys)) {
                return false;
            }
            boolean flag = false;
            for (int i = 0; i < keys.length; i++) {
                if (pressedKeys.contains(keys[i])) {
                    flag = true;
                    break;
                }
            }
            return flag;
        }
    }

    /**
     * Reserved entry point for loading user-customized bindings from
     * {@link #keyBindingsFileLocation}; currently unimplemented.
     */
    public static void loadKeyBindingsFile() {

    }

    /**
     * Reserved entry point for persisting current bindings back to
     * {@link #keyBindingsFileLocation}; currently unimplemented.
     */
    public static void updateKeyBindingsFile() {

    }

    /**
     * A key code's name: its {@link Keys#label}, or the character for a code no key has.
     *
     * @param keyCode GLFW key code
     * @return human-readable key name
     */
    public static String extraNames(int keyCode) {
        Keys key = Keys.of(keyCode);
        return key == null ? String.valueOf((char) keyCode) : key.label;
    }

    /**
     * Human-readable binding label, e.g. {@code "Reset => R"}.
     *
     * @return the action name and its primary key (with {@code Ctrl +} prefix if control-masked)
     */
    @Override
    public String toString() {

        String name = extraNames(this.keys[0]) + "";
        return this.name() + " => " + (this.controlMask ? "Ctrl + " : "")
                + name.substring(0, 1).toUpperCase() + name.substring(1);
    }

}
