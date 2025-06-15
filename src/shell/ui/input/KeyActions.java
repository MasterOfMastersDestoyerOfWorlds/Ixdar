package shell.ui.input;

import static org.lwjgl.glfw.GLFW.*;

import java.util.Set;

public enum KeyActions {
    CycleToolLeft(GLFW_KEY_LEFT),
    CycleToolRight(GLFW_KEY_RIGHT),
    ZoomIn(GLFW_KEY_EQUAL),
    ZoomOut(GLFW_KEY_MINUS),
    MoveRight(GLFW_KEY_D),
    MoveLeft(GLFW_KEY_A),
    MoveUp(GLFW_KEY_W),
    MoveDown(GLFW_KEY_S),
    DoubleSpeed(GLFW_KEY_LEFT_SHIFT),
    DrawOriginal(GLFW_KEY_O),
    Confirm(GLFW_KEY_ENTER),
    Back(GLFW_KEY_ESCAPE),
    Reset(GLFW_KEY_R),
    UpdateFile(GLFW_KEY_U),
    IncreaseKnotLayer(GLFW_KEY_RIGHT_BRACKET, GLFW_KEY_UP),
    DecreaseKnotLayer(GLFW_KEY_LEFT_BRACKET, GLFW_KEY_DOWN),
    DrawGridLines(GLFW_KEY_G),
    DrawMetroDiagram(GLFW_KEY_M),
    DrawCutMatch(GLFW_KEY_B),
    DrawKnotGradient(GLFW_KEY_Y),
    ColorRandomization(GLFW_KEY_C),
    PrintScreen(true, GLFW_KEY_P),
    GenerateManifoldTests(true, GLFW_KEY_G),
    Save(true, GLFW_KEY_S),
    SaveAs(true, GLFW_KEY_Q),
    Find(true, GLFW_KEY_F),
    Compare(true, GLFW_KEY_C),
    KnotSurfaceView(true, GLFW_KEY_V),
    KnotAnimTool(true, GLFW_KEY_A),
    EditManifold(true, GLFW_KEY_E),
    NegativeCutMatchViewTool(true, GLFW_KEY_N),
    NeighborViewTool(true, GLFW_KEY_B),
    ControlMask(GLFW_KEY_LEFT_CONTROL, GLFW_KEY_RIGHT_CONTROL),
    ShiftMask(GLFW_KEY_LEFT_SHIFT, GLFW_KEY_RIGHT_SHIFT);

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

    public final String keyBindingsFileLocation = "./src/res/keyBindings.txt";

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
            case GLFW_KEY_ESCAPE:
                return "Escape";
            case GLFW_KEY_LEFT_CONTROL:
            case GLFW_KEY_RIGHT_CONTROL:
                return "Ctrl";
            case GLFW_KEY_LEFT_SHIFT:
            case GLFW_KEY_RIGHT_SHIFT:
                return "Shift";
            case GLFW_KEY_LEFT:
                return "Left";
            case GLFW_KEY_RIGHT:
                return "Right";
            case GLFW_KEY_UP:
                return "Up";
            case GLFW_KEY_DOWN:
                return "Down";
            case GLFW_KEY_ENTER:
                return "Enter";
            default:
                return glfwGetKeyName(keyCode, 0);
        }
    }

    @Override
    public String toString() {

        String name = extraNames(this.keys[0]) + "";
        return this.name() + " => " + (this.controlMask ? "Ctrl + " : "")
                + name.substring(0, 1).toUpperCase() + name.substring(1);
    }

}
