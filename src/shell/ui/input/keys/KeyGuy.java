package shell.ui.input.keys;

import java.awt.Color;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;

import shell.Main;
import shell.Toggle;
import shell.cameras.Camera;
import shell.cameras.CameraMoveDirection;
import shell.file.FileManagement;
import shell.shell.Shell;
import shell.ui.actions.EditManifoldAction;
import shell.ui.actions.FindManifoldAction;
import shell.ui.actions.GenerateManifoldTestsAction;
import shell.ui.actions.NegativeCutMatchViewAction;
import shell.ui.actions.PrintScreenAction;
import shell.ui.actions.SaveAction;
import shell.ui.tools.Tool;
import shell.ui.tools.ToolType;

public class KeyGuy implements KeyListener {

    public final Set<Integer> pressedKeys = new HashSet<>();
    public Main main;
    public Camera camera;

    static PrintScreenAction printScreenAction;
    static SaveAction saveAction;
    static GenerateManifoldTestsAction generateManifoldTests;
    static FindManifoldAction findManifoldAction;
    static EditManifoldAction editManifoldAction;
    static NegativeCutMatchViewAction negativeCutMatchViewAction;

    boolean controlMask;

    public KeyGuy(Camera camera) {
        this.camera = camera;
    }

    public KeyGuy(Main main, JFrame frame, String fileName, Camera camera) {
        this.main = main;
        this.camera = camera;
        printScreenAction = new PrintScreenAction(frame);
        saveAction = new SaveAction(frame, fileName);
        generateManifoldTests = new GenerateManifoldTestsAction(frame, fileName);
        findManifoldAction = new FindManifoldAction(frame);
        editManifoldAction = new EditManifoldAction(frame);
        negativeCutMatchViewAction = new NegativeCutMatchViewAction(frame);
    }

    @Override
    public void keyPressed(KeyEvent e) {
        boolean firstPress = !pressedKeys.contains(e.getKeyCode());
        pressedKeys.add(e.getKeyCode());
        if (e.getKeyCode() == KeyEvent.VK_CONTROL) {
            controlMask = true;
        }
        if (controlMask && firstPress) {
            if (KeyActions.PrintScreen.keyPressed(pressedKeys)) {
                printScreenAction.actionPerformed(null);
            }
            if (KeyActions.Save.keyPressed(pressedKeys)) {
                saveAction.actionPerformed(null);
            }
            if (KeyActions.GenerateManifoldTests.keyPressed(pressedKeys)) {
                generateManifoldTests.actionPerformed(null);
            }
            if (KeyActions.Find.keyPressed(pressedKeys)) {
                findManifoldAction.actionPerformed(null);
            }
            if (KeyActions.EditManifold.keyPressed(pressedKeys)) {
                editManifoldAction.actionPerformed(null);
            }
            if (KeyActions.NegativeCutMatchViewTool.keyPressed(pressedKeys)) {
                negativeCutMatchViewAction.actionPerformed(null);
            }
        }
        if (main != null) {
            main.repaint();
        }

    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (main != null) {
            Tool tool = Main.tool;
            if (KeyActions.ColorRandomization.keyPressed(pressedKeys)) {
                Random colorSeed = new Random();
                Main.stickyColor = new Color(colorSeed.nextFloat(), colorSeed.nextFloat(), colorSeed.nextFloat());
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
            if (KeyActions.Exit.keyPressed(pressedKeys)) {
                if (Main.tool.toolType() == ToolType.Free) {
                    System.exit(0);
                }
                Main.tool = Main.freeTool;
                main.repaint();
            }
        } else {
            if (KeyActions.Exit.keyPressed(pressedKeys)) {
                System.exit(0);
            }
        }
        if (e.getKeyCode() == KeyEvent.VK_CONTROL) {
            controlMask = false;
        }
        pressedKeys.remove(e.getKeyCode());
    }

    long REPRESS_TIME = 360;
    long lastPressTime;

    public void paintUpdate(double SHIFT_MOD) {
        camera.setShiftMod(SHIFT_MOD);
        if (!pressedKeys.isEmpty()) {
            boolean moved = false;
            if (KeyActions.MoveUp.keyPressed(pressedKeys)) {
                camera.move(CameraMoveDirection.FORWARD);
                moved = true;
            }
            if (KeyActions.MoveLeft.keyPressed(pressedKeys)) {
                camera.move(CameraMoveDirection.LEFT);
                moved = true;
            }
            if (KeyActions.MoveDown.keyPressed(pressedKeys)) {
                camera.move(CameraMoveDirection.BACKWARD);
                moved = true;
            }
            if (KeyActions.MoveRight.keyPressed(pressedKeys)) {
                camera.move(CameraMoveDirection.RIGHT);
                moved = true;
            }
            if (KeyActions.ZoomIn.keyPressed(pressedKeys)) {
                camera.zoom(true);
                moved = true;
            }
            if (KeyActions.ZoomOut.keyPressed(pressedKeys)) {

                camera.zoom(false);
                moved = true;
            }
            if (moved && main != null) {
                Point mouseLocation = MouseInfo.getPointerInfo().getLocation();
                Point frameLocation = Main.frame.getRootPane().getLocationOnScreen();
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

            if (!pressedKeys.isEmpty()) {
                main.repaint();
            }
        }
    }
}
