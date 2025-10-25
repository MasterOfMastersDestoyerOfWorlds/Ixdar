package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.ACTION_REPEAT;
import static ixdar.platform.input.Keys.LEFT_CONTROL;

import java.util.HashSet;
import java.util.Set;

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

public class KeyGuy {

    public final Set<Integer> pressedKeys = new HashSet<>();
    public MainScene main;
    public Camera camera;

    boolean controlMask;
    boolean shiftMask;
    public boolean active = true;
    public Canvas3D canvas;

    public KeyGuy(Camera camera, Canvas3D canvas) {
        this.camera = camera;
        this.canvas = canvas;
    }

    public KeyGuy(MainScene main, String fileName, Camera camera, Canvas3D canvas2) {
        this.main = main;
        this.camera = camera;
        this.canvas = canvas2;
    }

    private void keyPressed(int key, int mods, boolean repeated) {
        if (!active) {
            return;
        }
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
        if (Toggle.IsTerminalFocused.value) {
            MainScene.terminal.keyPress(key, mods, controlMask);
        }
        if (KeyActions.Back.keyPressed(pressedKeys) && MainScene.active) {
            Terminal.runNoArgs(ExitCommand.class);
        }
    }

    public void keyReleased(int key, int mask) {
        if (!active) {
            return;
        }
        if (main != null && MainScene.active) {

        } else if (canvas.active) {
            if (KeyActions.Back.keyPressed(pressedKeys)) {
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

    long REPRESS_TIME = 360;
    long lastPressTime;

    public void paintUpdate(float SHIFT_MOD) {
        if (!active || Toggle.IsTerminalFocused.value) {
            return;
        }
        camera.setShiftMod(SHIFT_MOD);
        if (!pressedKeys.isEmpty()) {
            if (KeyActions.MoveUp.keyPressed(pressedKeys)) {
                camera.move(Camera.Direction.FORWARD);
            }
            if (KeyActions.MoveLeft.keyPressed(pressedKeys)) {
                camera.move(Camera.Direction.LEFT);
            }
            if (KeyActions.MoveDown.keyPressed(pressedKeys)) {
                camera.move(Camera.Direction.BACKWARD);
            }
            if (KeyActions.MoveRight.keyPressed(pressedKeys)) {
                camera.move(Camera.Direction.RIGHT);
            }
            if (KeyActions.ZoomIn.keyPressed(pressedKeys) && !KeyActions.ZoomOut.keyPressed(pressedKeys)) {
                camera.onScroll(true, (float) Clock.deltaTime());
            }
            if (KeyActions.ZoomOut.keyPressed(pressedKeys) && !KeyActions.ZoomIn.keyPressed(pressedKeys)) {
                camera.onScroll(false, (float) Clock.deltaTime());
            }
        }

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

    public void charCallback(long window, int codepoint) {
        Platforms.init(canvas.platform.getPlatformID());
        String currentText = "" + (char) codepoint;
        if (Toggle.IsTerminalFocused.value) {
            MainScene.terminal.type(currentText);
        }
    }

}
