package shell.platform.input;

import static shell.platform.input.Keys.ACTION_PRESS;
import static shell.platform.input.Keys.ACTION_RELEASE;
import static shell.platform.input.Keys.ACTION_REPEAT;
import static shell.platform.input.Keys.LEFT_CONTROL;

import java.util.HashSet;
import java.util.Set;

import shell.Toggle;
import shell.cameras.Camera;
import shell.render.Clock;
import shell.terminal.Terminal;
import shell.terminal.commands.ChangeToolCommand;
import shell.terminal.commands.ColorCommand;
import shell.terminal.commands.ExitCommand;
import shell.terminal.commands.ResetCommand;
import shell.terminal.commands.UpdateCommand;
import shell.terminal.commands.ResetCommand.ResetOption;
import shell.ui.Canvas3D;
import shell.ui.main.Main;
import shell.ui.tools.NegativeCutMatchViewTool;
import shell.ui.tools.NeighborViewTool;
import shell.ui.tools.Tool;

public class KeyGuy {

    public final Set<Integer> pressedKeys = new HashSet<>();
    public Main main;
    public Camera camera;

    boolean controlMask;
    boolean shiftMask;
    public boolean active = true;

    public KeyGuy(Camera camera, Canvas3D canvas) {
        this.camera = camera;
    }

    public KeyGuy(Main main, String fileName, Camera camera) {
        this.main = main;
        this.camera = camera;
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
                    if (Main.file == null && Main.tempFile != null) {

                    } else if (Main.file != null) {

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
                Tool tool = Main.tool;
                if (tool.canUseToggle(Toggle.IsMainFocused)) {
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
                        Main.setDrawLevelMetro();
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
                        Main.tool.confirm();
                    }
                    if (KeyActions.Reset.keyPressed(pressedKeys)) {
                        ResetCommand.run(ResetOption.Camera);
                    }
                }
            }
        }
        if (Toggle.IsTerminalFocused.value) {
            Main.terminal.keyPress(key, mods, controlMask);
        }
        if (KeyActions.Back.keyPressed(pressedKeys) && Main.active) {
            Terminal.runNoArgs(ExitCommand.class);
        }
    }

    public void keyReleased(int key, int mask) {
        if (!active) {
            return;
        }
        if (main != null && Main.active) {

        } else if (Canvas3D.active) {
            if (KeyActions.Back.keyPressed(pressedKeys)) {
                Canvas3D.menu.back();
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
                camera.onScroll(true, (float)Clock.deltaTime());
            }
            if (KeyActions.ZoomOut.keyPressed(pressedKeys) && !KeyActions.ZoomIn.keyPressed(pressedKeys)) {
                camera.onScroll(false, (float)Clock.deltaTime());
            }
        }

        if (main != null) {
            long timeSinceLastPress = System.currentTimeMillis() - lastPressTime;
            if (!pressedKeys.isEmpty() && timeSinceLastPress > REPRESS_TIME / SHIFT_MOD) {
                lastPressTime = System.currentTimeMillis();
                if (KeyActions.CycleToolLeft.keyPressed(pressedKeys)) {
                    Main.tool.cycleLeft();
                }
                if (KeyActions.CycleToolRight.keyPressed(pressedKeys)) {
                    Main.tool.cycleRight();
                }
            }
        }
    }

    public void keyCallback(long window, int key, int scancode, int action, int mods) {
        
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
        String currentText = "" + (char) codepoint;
        if (Toggle.IsTerminalFocused.value) {
            Main.terminal.type(currentText);
        }
    }

}
