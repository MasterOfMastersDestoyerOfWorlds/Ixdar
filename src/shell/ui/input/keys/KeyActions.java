package shell.ui.input.keys;

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
    DrawMetroDiagram(GLFW_KEY_M),
    DrawCutMatch(GLFW_KEY_B),
    DrawKnotGradient(GLFW_KEY_Y),
    ColorRandomization(GLFW_KEY_C),
    PrintScreen(true, GLFW_KEY_P),
    GenerateManifoldTests(true, GLFW_KEY_G),
    Save(true, GLFW_KEY_S),
    Find(true, GLFW_KEY_F),
    EditManifold(true, GLFW_KEY_E),
    NegativeCutMatchViewTool(true, GLFW_KEY_N);

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
            if (pressedKeys.contains(GLFW_KEY_LEFT_CONTROL)) {
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

}
