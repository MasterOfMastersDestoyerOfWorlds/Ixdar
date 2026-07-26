package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.ACTION_REPEAT;
import static ixdar.platform.input.Keys.LEFT_CONTROL;
import java.util.HashSet;

import java.util.HashMap;
import java.util.Set;

import java.util.Map;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Clock;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.terminal.commands.ChangeToolCommand;
import ixdar.gui.terminal.commands.ColorCommand;
import ixdar.gui.terminal.commands.ExitCommand;
import ixdar.gui.terminal.commands.ResetCommand;
import ixdar.gui.terminal.commands.UpdateCommand;
import ixdar.gui.terminal.commands.ResetCommand.ResetOption;
import ixdar.gui.ui.tools.NegativeCutMatchViewTool;
import ixdar.gui.ui.tools.NeighborViewTool;
import ixdar.gui.ui.tools.Tool;
import ixdar.platform.Platforms;
import ixdar.platform.Toggle;
import ixdar.scenes.main.MainScene;

public class KeyGuy extends Camera2DInputController{
    public static final String KEY = "key";

    private static Object automationRuntime;
    private static boolean automationChecked;

    public final Set<Integer> pressedKeys = new HashSet<>();
    public MainScene main;
    public Camera camera;
    public boolean active = true;
    public Canvas3D canvas;

    boolean controlMask;
    boolean shiftMask;

    long REPRESS_TIME = 360;
    long lastPressTime;

    /**
     * Lightweight constructor used by scenes that don't have a {@code MainScene} (e.g.
     * {@code TradeScene}, dungeon viewer).
     *
     * @param camera camera the controller drives
     * @param canvas owning canvas (for platform-id resolution)
     */
    public KeyGuy(Camera camera, Canvas3D canvas) {
        this.camera = camera;
        this.canvas = canvas;
    }

    /**
     * Constructor used by {@code MainScene} so the handler can dispatch tool / terminal
     * shortcuts.
     *
     * @param main owning main scene
     * @param fileName scene-loaded file name (currently unused but retained for symmetry)
     * @param camera camera the controller drives
     * @param canvas2 owning canvas
     */
    public KeyGuy(MainScene main, String fileName, Camera camera, Canvas3D canvas2) {
        this.main = main;
        this.camera = camera;
        this.canvas = canvas2;
    }

    private static Object getAutomationRuntime() {
        if (!automationChecked) {
            automationChecked = true;
            try {
                Class<?> cls = Class.forName(
                        String.join(".", "ixdar", "platform", "automation", "endpoints", "AutomationRuntime"));
                automationRuntime = cls.getMethod("get").invoke(null);
            } catch (Throwable ignored) {}
        }
        return automationRuntime;
    }

    static void recordAbstractAction(String action, Object... keyValues) {
        Object rt = getAutomationRuntime();
        if (rt == null) return;
        try {
            Map<String, Object> payload = new HashMap<>();
            for (int i = 0; i < keyValues.length; i += 2) {
                payload.put((String) keyValues[i], keyValues[i + 1]);
            }
            rt.getClass().getMethod("recordAbstractActionMap", String.class, Map.class).invoke(rt, action, payload);
        } catch (Throwable ignored) {}
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
            if (controlMask) {
                if (KeyActions.Save.keyPressed(pressedKeys)) {
                    if (MainScene.file == null && MainScene.tempFile != null) {

                    } else if (MainScene.file != null) {

                    }
                }
                if (KeyActions.SaveAs.keyPressed(pressedKeys)) {

                }
                if (KeyActions.NegativeCutMatchViewTool.keyPressed(pressedKeys)) {
                    ChangeToolCommand.run(NegativeCutMatchViewTool.class);
                }
                if (KeyActions.NeighborViewTool.keyPressed(pressedKeys)) {
                    ChangeToolCommand.run(NeighborViewTool.class);
                }
            } else {
                Tool tool = MainScene.tool;
                if (tool != null && tool.canUseToggle(Toggle.IsMainFocused)) {
                    if (KeyActions.ColorRandomization.keyPressed(pressedKeys)) {
                        Terminal.runNoArgs(ColorCommand.class);
                    }
                    if (KeyActions.DrawCutMatch.keyPressed(pressedKeys)) {
                        Toggle.DrawCutMatch.toggle();
                    }
                    if (KeyActions.DrawKnotGradient.keyPressed(pressedKeys)) {
                        Toggle.DrawKnotGradient.toggle();
                    }
                    if (KeyActions.DrawMetroDiagram.keyPressed(pressedKeys)) {
                        MainScene.setDrawLevelMetro();
                    }
                    if (KeyActions.IncreaseKnotLayer.keyPressed(pressedKeys)) {
                        tool.increaseViewLayer();
                    }
                    if (KeyActions.DecreaseKnotLayer.keyPressed(pressedKeys)) {
                        tool.decreaseViewLayer();
                    }
                    if (KeyActions.DrawOriginal.keyPressed(pressedKeys)) {
                        Toggle.DrawMainPath.toggle();
                    }
                    if (KeyActions.DrawGridLines.keyPressed(pressedKeys)) {
                        Toggle.DrawGridLines.toggle();
                    }
                    if (KeyActions.UpdateFile.keyPressed(pressedKeys)) {
                        Terminal.runNoArgs(UpdateCommand.class);
                    }
                    if (KeyActions.Confirm.keyPressed(pressedKeys)) {
                        MainScene.tool.confirm();
                    }
                    if (KeyActions.Reset.keyPressed(pressedKeys)) {
                        ResetCommand.run(ResetOption.Camera);
                    }
                }
            }
        }
        if (Toggle.IsTerminalFocused.value && Terminal.current != null) {
            Terminal.current.keyPress(key, mods, controlMask);
        }
        if (KeyActions.Back.keyPressed(pressedKeys) && MainScene.active) {
            Terminal.runNoArgs(ExitCommand.class);
        }
    }

    /**
     * Handle a key-up: fire menu / camera shortcuts when a non-main scene is active, clear the
     * control-mask flag on left-control release, then drop {@code key} from {@link #pressedKeys}.
     *
     * @param key key code that was released
     * @param mask modifier-key bitmask
     */
    public void keyReleased(int key, int mask) {
        if (!active) {
            return;
        }
        recordAbstractAction("key_release", KEY, key, "mask", mask);
        if (main != null && MainScene.active) {

        } else if (canvas.active) {
            if (KeyActions.Back.keyPressed(pressedKeys) && canvas.menu != null) {
                canvas.menu.back();
            }
            if (KeyActions.Reset.keyPressed(pressedKeys)) {
                camera.reset();
            }
        }
        if (key == LEFT_CONTROL) {
            controlMask = false;
        }
        pressedKeys.remove(key);
    }

    /**
     * Per-frame: skip while terminal is focused (so typing into terminal doesn't move the
     * camera), forward camera movement keys to {@link Camera2DInputController#apply}, then
     * cycle the active tool on left/right with debouncing.
     *
     * @param SHIFT_MOD speed multiplier (typically 1 or 2)
     */
    public void paintUpdate(float SHIFT_MOD) {
        if (!active || Toggle.IsTerminalFocused.value) {
            return;
        }
        super.apply(camera, pressedKeys, SHIFT_MOD, Clock.deltaTime());

        if (main != null) {
            long timeSinceLastPress = System.currentTimeMillis() - lastPressTime;
            if (!pressedKeys.isEmpty() && timeSinceLastPress > REPRESS_TIME / SHIFT_MOD) {
                lastPressTime = System.currentTimeMillis();
                if (KeyActions.CycleToolLeft.keyPressed(pressedKeys)) {
                    MainScene.tool.cycleLeft();
                }
                if (KeyActions.CycleToolRight.keyPressed(pressedKeys)) {
                    MainScene.tool.cycleRight();
                }
            }
        }
    }

    /**
     * Platform key-event entry point: rebinds {@link Platforms} to the owning canvas, then
     * dispatches to {@code keyPressed} (with {@code repeated = true} for {@code ACTION_REPEAT})
     * or {@link #keyReleased}.
     *
     * @param window platform window handle
     * @param key key code (see {@code Keys})
     * @param scancode raw scancode (GLFW; 0 on web)
     * @param action {@code ACTION_PRESS} / {@code ACTION_REPEAT} / {@code ACTION_RELEASE}
     * @param mods modifier-key bitmask
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
     * Platform char-event entry point: when terminal focus is on, forwards typed characters
     * into {@code MainScene.terminal}.
     *
     * @param window platform window handle
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
