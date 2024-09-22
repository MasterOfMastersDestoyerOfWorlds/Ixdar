package shell.ui.input.keys;

import java.awt.event.KeyEvent;
import java.util.Set;

public enum KeyActions {
    CycleToolLeft(KeyEvent.VK_LEFT),
    CycleToolRight(KeyEvent.VK_RIGHT),
    ZoomIn(KeyEvent.VK_EQUALS),
    ZoomOut(KeyEvent.VK_MINUS),
    MoveRight(KeyEvent.VK_D),
    MoveLeft(KeyEvent.VK_A),
    MoveUp(KeyEvent.VK_W),
    MoveDown(KeyEvent.VK_S),
    DoubleSpeed(KeyEvent.VK_SHIFT),
    DrawOriginal(KeyEvent.VK_O),
    Confirm(KeyEvent.VK_ENTER),
    Exit(KeyEvent.VK_ESCAPE),
    Reset(KeyEvent.VK_R),
    UpdateFile(KeyEvent.VK_U),
    IncreaseKnotLayer(KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_UP),
    DecreaseKnotLayer(KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_DOWN),
    DrawMetroDiagram(KeyEvent.VK_M),
    DrawCutMatch(KeyEvent.VK_B),
    DrawKnotGradient(KeyEvent.VK_Y),
    ColorRandomization(KeyEvent.VK_C),
    PrintScreen(true, KeyEvent.VK_P),
    GenerateManifoldTests(true, KeyEvent.VK_G),
    Save(true, KeyEvent.VK_S),
    Find(true, KeyEvent.VK_F),
    EditManifold(true, KeyEvent.VK_E),
    NegativeCutMatchViewTool(true, KeyEvent.VK_N);

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
            if (pressedKeys.contains(KeyEvent.VK_CONTROL)) {
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
