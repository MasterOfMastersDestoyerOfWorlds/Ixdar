package shell.platform.input;

import static shell.platform.input.Keys.*;

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

    public final String keyBindingsFileLocation = "./src/main/resources/res/keyBindings.txt";

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

    public static void loadKeyBindingsFile() {

    }

    public static void updateKeyBindingsFile() {

    }

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

    @Override
    public String toString() {

        String name = extraNames(this.keys[0]) + "";
        return this.name() + " => " + (this.controlMask ? "Ctrl + " : "")
                + name.substring(0, 1).toUpperCase() + name.substring(1);
    }

}
