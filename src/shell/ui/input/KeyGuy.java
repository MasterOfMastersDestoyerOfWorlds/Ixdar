package shell.ui.input;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.JFrame;

import shell.Toggle;
import shell.cameras.Camera;
import shell.file.FileManagement;
import shell.shell.Shell;
import shell.terminal.commands.ColorCommand;
import shell.terminal.commands.ExitCommand;
import shell.terminal.commands.ResetCommand;
import shell.terminal.commands.ResetCommand.ResetOption;
import shell.ui.Canvas3D;
import shell.ui.IxdarWindow;
import shell.ui.actions.GenerateManifoldTestsAction;
import shell.ui.actions.SaveDialog;
import shell.ui.main.Main;
import shell.ui.tools.EditManifoldTool;
import shell.ui.tools.FindManifoldTool;
import shell.ui.tools.NegativeCutMatchViewTool;
import shell.ui.tools.Tool;

public class KeyGuy {

    public final Set<Integer> pressedKeys = new HashSet<>();
    public Main main;
    public Camera camera;

    static GenerateManifoldTestsAction generateManifoldTests;

    boolean controlMask;
    private Canvas3D canvas;
    JFrame frame;
    SaveDialog dialog;
    NegativeCutMatchViewTool negativeCutMatchViewTool = new NegativeCutMatchViewTool();
    FindManifoldTool findManifoldTool;
    EditManifoldTool editCutMatchTool;
    public boolean active = true;

    public KeyGuy(Camera camera, Canvas3D canvas) {
        this.camera = camera;
        this.canvas = canvas;

    }

    public KeyGuy(Main main, String fileName, Camera camera) {
        this.main = main;
        this.camera = camera;
        dialog = new SaveDialog(frame, fileName);

        generateManifoldTests = new GenerateManifoldTestsAction(frame, fileName);
    }

    private void keyPressed(int key, int mods) {
        if (!active) {
            return;
        }
        boolean firstPress = !pressedKeys.contains(key);
        pressedKeys.add(key);

        if (KeyActions.ControlMask.keyPressed(pressedKeys)) {
            controlMask = true;
        }
        if (controlMask && firstPress) {
            if (KeyActions.PrintScreen.keyPressed(pressedKeys)) {
                System.out.println("Printing Screenshot");
                if (canvas != null) {
                    int numInFolder = new File("./img").list().length;
                    canvas.printScreen("./img/snap" + numInFolder + ".png");
                    return;
                } else {
                    BufferedImage img = new BufferedImage((int) IxdarWindow.getWidth(), (int) IxdarWindow.getHeight(),
                            BufferedImage.TYPE_INT_RGB);
                    canvas.paintGL();
                    int numInFolder = new File("./img").list().length;
                    File outputfile = new File("./img/snap" + numInFolder + ".png");
                    try {
                        ImageIO.write(img, "png", outputfile);
                    } catch (IOException ex) {
                        System.out.println(ex.getMessage());
                    }
                    return;
                }
            }
            if (KeyActions.Save.keyPressed(pressedKeys)) {
                String newFilename = dialog.showDialog();

                if ((newFilename != null) && (newFilename.length() > 0)) {
                    System.out.println("Saving to file: " + newFilename);
                }
            }
            if (KeyActions.GenerateManifoldTests.keyPressed(pressedKeys)) {
                generateManifoldTests.actionPerformed(null);
            }
            if (KeyActions.Find.keyPressed(pressedKeys)) {
                findManifoldTool.reset();
                findManifoldTool.state = FindManifoldTool.States.FindStart;
                Main.tool = findManifoldTool;
                Main.knotDrawLayer = Main.shell.cutEngine.totalLayers;
                Main.updateKnotsDisplayed();
            }
            if (KeyActions.EditManifold.keyPressed(pressedKeys)) {
                if (Toggle.manifold.value && Toggle.drawCutMatch.value) {
                    editCutMatchTool.reset();
                    Main.tool = editCutMatchTool;
                }

            }
            if (KeyActions.NegativeCutMatchViewTool.keyPressed(pressedKeys) && negativeCutMatchViewTool != null) {
                negativeCutMatchViewTool.reset();
                negativeCutMatchViewTool.initSegmentMap();
                Main.tool = negativeCutMatchViewTool;
            }

        } else if (Toggle.isTerminalFocused.value) {
            Main.terminal.keyPress(key, mods);
        }
        if (KeyActions.Back.keyPressed(pressedKeys)) {
            ExitCommand.run();
        }
    }

    public void keyReleased(int key, int mask) {
        if (!active) {
            return;
        }
        if (main != null && Main.active) {
            Tool tool = Main.tool;
            if (tool.canUseToggle(Toggle.isMainFocused)) {
                if (KeyActions.ColorRandomization.keyPressed(pressedKeys)) {
                    ColorCommand.run();
                }
                if (KeyActions.DrawCutMatch.keyPressed(pressedKeys)) {
                    Toggle.drawCutMatch.toggle();
                }
                if (KeyActions.DrawKnotGradient.keyPressed(pressedKeys)) {
                    Toggle.drawKnotGradient.toggle();
                }
                if (KeyActions.DrawMetroDiagram.keyPressed(pressedKeys)) {
                    Main.setDrawLevelMetro();
                }
                if (KeyActions.IncreaseKnotLayer.keyPressed(pressedKeys)) {
                    if (tool.canUseToggle(Toggle.canSwitchLayer)) {
                        if (tool.canUseToggle(Toggle.manifold) && tool.canUseToggle(Toggle.drawCutMatch)) {
                            Main.manifoldIdx++;
                            if (Main.manifoldIdx >= Main.manifolds.size()) {
                                Main.manifoldIdx = 0;
                            }
                        } else {
                            Main.knotDrawLayer++;
                            if (Main.knotDrawLayer > Main.shell.cutEngine.totalLayers) {
                                Main.knotDrawLayer = Main.shell.cutEngine.totalLayers;
                            }
                            if (Main.knotDrawLayer < 1) {
                                Main.knotDrawLayer = 1;
                            }
                            Main.updateKnotsDisplayed();
                        }
                    }
                }
                if (KeyActions.DecreaseKnotLayer.keyPressed(pressedKeys)) {
                    if (tool.canUseToggle(Toggle.canSwitchLayer)) {
                        if (tool.canUseToggle(Toggle.manifold) && tool.canUseToggle(Toggle.drawCutMatch)) {
                            Main.manifoldIdx--;
                            if (Main.manifoldIdx < 0) {
                                Main.manifoldIdx = Main.manifolds.size() - 1;
                            }
                        } else {
                            if (Main.knotDrawLayer == -1) {
                                Main.knotDrawLayer = Main.shell.cutEngine.totalLayers;
                            } else {
                                Main.knotDrawLayer--;
                                if (Main.knotDrawLayer < 1) {
                                    Main.knotDrawLayer = 1;
                                }
                            }
                            Main.updateKnotsDisplayed();
                        }
                    }
                }
                if (KeyActions.DrawOriginal.keyPressed(pressedKeys)) {
                    Toggle.drawMainPath.toggle();
                }
                if (KeyActions.UpdateFile.keyPressed(pressedKeys)) {
                    if (Main.subPaths.size() == 1) {
                        Shell ans = Main.subPaths.get(0);
                        if (Main.orgShell.getLength() > ans.getLength()) {
                            FileManagement.appendAns(Main.file, ans);
                            Main.orgShell = ans;
                        }
                        if (tool.canUseToggle(Toggle.manifold)) {
                            FileManagement.appendCutAns(Main.file, Main.manifolds);
                        }
                    }
                }
                if (KeyActions.Confirm.keyPressed(pressedKeys)) {
                    Main.tool.confirm();
                }
                if (KeyActions.Reset.keyPressed(pressedKeys)) {
                    ResetCommand.run(ResetOption.All);
                }
            }
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
        if (!active || Toggle.isTerminalFocused.value) {
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
            long timeSinceLastPress = System.currentTimeMillis()
                    - lastPressTime;
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
                keyPressed(key, mods);
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
        if (Toggle.isTerminalFocused.value) {
            Main.terminal.type(currentText);
        }
    }

}
