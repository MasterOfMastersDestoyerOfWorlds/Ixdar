package shell.ui.input.keys;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.JFrame;

import shell.Main;
import shell.Toggle;
import shell.cameras.Camera;
import shell.file.FileManagement;
import shell.render.color.Color;
import shell.render.color.ColorRGB;
import shell.shell.Shell;
import shell.ui.Canvas3D;
import shell.ui.IxdarWindow;
import shell.ui.actions.GenerateManifoldTestsAction;
import shell.ui.actions.SaveDialog;
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
    NegativeCutMatchViewTool negativeCutMatchViewTool;
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
        negativeCutMatchViewTool = new NegativeCutMatchViewTool();
    }

    private void keyPressed(int key, int mods) {
        if (!active) {
            return;
        }
        boolean firstPress = !pressedKeys.contains(key);
        pressedKeys.add(key);
        if (key == KeyEvent.VK_CONTROL) {
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
                Main.metroDrawLayer = Main.shell.cutEngine.totalLayers;
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
        }
    }

    public void keyReleased(int key, int mask) {
        if (!active) {
            return;
        }
        if (main != null && Main.active) {
            Tool tool = Main.tool;
            if (KeyActions.ColorRandomization.keyPressed(pressedKeys)) {
                Random colorSeed = new Random();
                Main.stickyColor = new ColorRGB(colorSeed.nextFloat(), colorSeed.nextFloat(), colorSeed.nextFloat());
                if (tool.canUseToggle(Toggle.drawMetroDiagram)) {
                    Main.metroColors = new ArrayList<>();
                    int totalLayers = Main.shell.cutEngine.totalLayers;
                    float startHue = colorSeed.nextFloat();
                    float step = 1.0f / ((float) totalLayers);
                    for (int i = 0; i <= totalLayers; i++) {
                        Main.metroColors.add(Color.getHSBColor((startHue + step * i) % 1.0f, 1.0f, 1.0f));
                    }
                }
                float startHue = colorSeed.nextFloat();
                float step = 1.0f / ((float) Main.shell.cutEngine.flatKnots.size());
                for (int i = 0; i < Main.knotGradientColors.size(); i++) {
                    Main.knotGradientColors.set(i, Color.getHSBColor((startHue + step * i) % 1.0f, 1.0f, 1.0f));
                }
            }
            if (KeyActions.DrawCutMatch.keyPressed(pressedKeys)) {
                Toggle.drawCutMatch.toggle();
            }
            if (KeyActions.DrawKnotGradient.keyPressed(pressedKeys)) {
                Toggle.drawKnotGradient.toggle();
            }
            if (KeyActions.DrawMetroDiagram.keyPressed(pressedKeys)) {
                if (Main.metroDrawLayer != -1) {
                    Main.metroDrawLayer = -1;
                } else {
                    Main.metroDrawLayer = Main.shell.cutEngine.totalLayers;
                }
            }
            if (KeyActions.IncreaseKnotLayer.keyPressed(pressedKeys)) {
                if (tool.canUseToggle(Toggle.canSwitchLayer)) {
                    if (tool.canUseToggle(Toggle.manifold) && tool.canUseToggle(Toggle.drawCutMatch)) {
                        Main.manifoldIdx++;
                        if (Main.manifoldIdx >= Main.manifolds.size()) {
                            Main.manifoldIdx = 0;
                        }
                    } else {
                        Main.metroDrawLayer++;
                        if (Main.metroDrawLayer > Main.shell.cutEngine.totalLayers) {
                            Main.metroDrawLayer = Main.shell.cutEngine.totalLayers;
                        }
                        if (Main.metroDrawLayer < 1) {
                            Main.metroDrawLayer = 1;
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
                        if (Main.metroDrawLayer == -1) {
                            Main.metroDrawLayer = Main.shell.cutEngine.totalLayers;
                        } else {
                            Main.metroDrawLayer--;
                            if (Main.metroDrawLayer < 1) {
                                Main.metroDrawLayer = 1;
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
                camera.reset();
                Main.tool.reset();
            }
            if (KeyActions.Back.keyPressed(pressedKeys)) {
                if (Main.tool.toolType() == Tool.Type.Free) {
                    Canvas3D.activate(true);
                    Main.activate(false);
                }
                Main.tool = Main.freeTool;
            }
        } else if (Canvas3D.active) {
            if (KeyActions.Back.keyPressed(pressedKeys)) {
                Canvas3D.menu.back();
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
        if (!active) {
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

}
