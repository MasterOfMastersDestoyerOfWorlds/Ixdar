package shell.ui.input;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

import java.awt.MouseInfo;
import java.awt.Point;
import java.util.HashSet;
import java.util.Set;

import javax.swing.JFrame;

import shell.Toggle;
import shell.cameras.Camera;
import shell.file.FileManagement;
import shell.terminal.Terminal;
import shell.terminal.commands.ChangeToolCommand;
import shell.terminal.commands.ColorCommand;
import shell.terminal.commands.ExitCommand;
import shell.terminal.commands.ManifoldTestCommand;
import shell.terminal.commands.ResetCommand;
import shell.terminal.commands.ResetCommand.ResetOption;
import shell.terminal.commands.ScreenShotCommand;
import shell.terminal.commands.UpdateCommand;
import shell.ui.Canvas3D;
import shell.ui.IxdarWindow;
import shell.ui.main.Main;
import shell.ui.tools.CompareManifoldTool;
import shell.ui.tools.EditManifoldTool;
import shell.ui.tools.FindManifoldTool;
import shell.ui.tools.KnotAnimationTool;
import shell.ui.tools.KnotSurfaceViewTool;
import shell.ui.tools.NegativeCutMatchViewTool;
import shell.ui.tools.Tool;

public class KeyGuy {

    public final Set<Integer> pressedKeys = new HashSet<>();
    public Main main;
    public Camera camera;

    boolean controlMask;
    boolean shiftMask;
    JFrame frame;
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
                if (KeyActions.PrintScreen.keyPressed(pressedKeys)) {
                    Terminal.runNoArgs(ScreenShotCommand.class);
                }
                if (KeyActions.Save.keyPressed(pressedKeys)) {
                    if (Main.file == null && Main.tempFile != null) {

                    } else if (Main.file != null) {

                    }
                }
                if (KeyActions.SaveAs.keyPressed(pressedKeys)) {

                }
                if (KeyActions.GenerateManifoldTests.keyPressed(pressedKeys)) {
                    ManifoldTestCommand.run(Main.file.getName(),
                            FileManagement.solutionsFolder + "/" + Main.file.getName().replace(".ix", ""));
                }
                if (KeyActions.Find.keyPressed(pressedKeys)) {
                    ChangeToolCommand.run(FindManifoldTool.class);
                }
                if (KeyActions.KnotSurfaceView.keyPressed(pressedKeys)) {
                    ChangeToolCommand.run(KnotSurfaceViewTool.class);
                }
                if (KeyActions.KnotAnimTool.keyPressed(pressedKeys)) {
                    ChangeToolCommand.run(KnotAnimationTool.class);
                }
                if (KeyActions.Compare.keyPressed(pressedKeys)) {
                    ChangeToolCommand.run(CompareManifoldTool.class);
                }
                if (KeyActions.EditManifold.keyPressed(pressedKeys)) {
                    if (Toggle.Manifold.value && Toggle.DrawCutMatch.value) {
                        ChangeToolCommand.run(EditManifoldTool.class);
                    }
                }
                if (KeyActions.NegativeCutMatchViewTool.keyPressed(pressedKeys)) {
                    ChangeToolCommand.run(NegativeCutMatchViewTool.class);
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
        if (key == GLFW_KEY_LEFT_CONTROL) {
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
            boolean moved = false;
            if (KeyActions.MoveUp.keyPressed(pressedKeys)) {
                camera.move(Camera.Direction.FORWARD);
                moved = true;
            }
            if (KeyActions.MoveLeft.keyPressed(pressedKeys)) {
                camera.move(Camera.Direction.LEFT);
                moved = true;
            }
            if (KeyActions.MoveDown.keyPressed(pressedKeys)) {
                camera.move(Camera.Direction.BACKWARD);
                moved = true;
            }
            if (KeyActions.MoveRight.keyPressed(pressedKeys)) {
                camera.move(Camera.Direction.RIGHT);
                moved = true;
            }
            if (KeyActions.ZoomIn.keyPressed(pressedKeys) && !KeyActions.ZoomOut.keyPressed(pressedKeys)) {
                camera.zoom(true);
                moved = true;
            }
            if (KeyActions.ZoomOut.keyPressed(pressedKeys) && !KeyActions.ZoomIn.keyPressed(pressedKeys)) {

                camera.zoom(false);
                moved = true;
            }
            if (moved && main != null) {
                Point mouseLocation = MouseInfo.getPointerInfo().getLocation();
                Point frameLocation = IxdarWindow.getLocationOnScreen();
                Main.tool.calculateHover((int) (mouseLocation.getX() - frameLocation.getX()),
                        (int) (mouseLocation.getY() - frameLocation.getY()));
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
        case GLFW_PRESS:
            keyPressed(key, mods, false);
            break;
        case GLFW_REPEAT:
            keyPressed(key, mods, true);
            break;
        case GLFW_RELEASE:
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
