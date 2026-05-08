package ixdar.platform.input;

import static ixdar.platform.input.Keys.*;

import java.util.Set;

public enum KeyActions {
    CycleToolLeft(LEFT),
    CycleToolRight(RIGHT),
    ZoomIn(EQUAL),
    ZoomOut(MINUS),
    MoveRight(D),
    MoveLeft(A),
    MoveUp(W),
    MoveDown(S),
    DoubleSpeed(LEFT_SHIFT),
    DrawOriginal(O),
    Confirm(ENTER),
    Back(ESCAPE),
    Reset(R),
    UpdateFile(U),
    IncreaseKnotLayer(RIGHT_BRACKET, UP),
    DecreaseKnotLayer(LEFT_BRACKET, DOWN),
    DrawGridLines(G),
    DrawMetroDiagram(M),
    DrawCutMatch(B),
    DrawKnotGradient(Y),
    ColorRandomization(C),
    PrintScreen(true, P),
    GenerateManifoldTests(true, G),
    Save(true, S),
    SaveAs(true, Q),
    Find(true, F),
    Compare(true, C),
    KnotSurfaceView(true, V),
    KnotAnimTool(true, A),
    EditManifold(true, E),
    NegativeCutMatchViewTool(true, N),
    NeighborViewTool(true, B),
    ControlMask(LEFT_CONTROL, RIGHT_CONTROL),
    ShiftMask(LEFT_SHIFT, RIGHT_SHIFT);

    public final String keyBindingsFileLocation = "./src/main/resources/res/keyBindings.txt";

    Integer[] keys;
    boolean controlMask;

    KeyActions(Integer... defaultKeyPresses) {
        controlMask = false;
        keys = defaultKeyPresses;
    }

    KeyActions(boolean control, Integer... defaultKeyPresses) {
        controlMask = control;
        keys = defaultKeyPresses;
    }

    /**
     * Test whether this action is currently triggered. For control-masked actions (e.g.
     * {@link #Save}), the {@link #ControlMask} chord must also be down. For non-control actions,
     * having any control key held suppresses the trigger so terminal shortcuts don't bleed into
     * single-key bindings.
     *
     * @param pressedKeys live set of pressed key codes
     * @return true if the binding is satisfied this frame
     */
    public boolean keyPressed(Set<Integer> pressedKeys) {
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
     * Display name for special keys that don't render well as a single ASCII character (arrows,
     * modifiers, escape, enter). Falls through to {@code (char) keyCode}.
     *
     * @param keyCode key code from {@code Keys}
     * @return human-readable key name
     */
    public static String extraNames(int keyCode) {
        switch (keyCode) {
        case ESCAPE:
            return "Escape";
        case LEFT_CONTROL:
        case RIGHT_CONTROL:
            return "Ctrl";
        case LEFT_SHIFT:
        case RIGHT_SHIFT:
            return "Shift";
        case LEFT:
            return "Left";
        case RIGHT:
            return "Right";
        case UP:
            return "Up";
        case DOWN:
            return "Down";
        case ENTER:
            return "Enter";
        default:
            return "" + (char) keyCode;
        }
    }

    /**
     * Human-readable binding label, e.g. {@code "Save => Ctrl + S"}.
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
