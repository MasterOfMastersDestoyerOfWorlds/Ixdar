package shell.ui;

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

    public KeyGuy(Camera camera) {
        this.camera = camera;
    }

    public KeyGuy(Main main, JFrame frame, String fileName, Camera camera) {
        this.main = main;
        this.camera = camera;
        JRootPane rootPane = main.getRootPane();

        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK),
                "printScreen");
        rootPane.getActionMap().put("printScreen",
                new PrintScreenAction(frame));

        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK),
                "saveNew");
        rootPane.getActionMap().put("saveNew",
                new SaveAction(frame, fileName));

        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK),
                "generateManifoldTests");
        rootPane.getActionMap().put("generateManifoldTests",
                new GenerateManifoldTestsAction(frame, fileName));

        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK),
                "findManifold");
        rootPane.getActionMap().put("findManifold",
                new FindManifoldAction(frame));

        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK),
                "editCutMatch");
        rootPane.getActionMap().put("editCutMatch",
                new EditManifoldAction(frame));

        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK),
                "negativeCutMatchView");
        rootPane.getActionMap().put("negativeCutMatchView",
                new NegativeCutMatchViewAction(frame));
    }

    @Override
    public void keyPressed(KeyEvent e) {

        pressedKeys.add(e.getKeyCode());
        if (main != null) {
            main.repaint();
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    @Override
    public void keyReleased(KeyEvent e) {
        pressedKeys.remove(e.getKeyCode());
        if (main != null) {
            Tool tool = Main.tool;
            if (e.getKeyCode() == KeyEvent.VK_C) {
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
            if (e.getKeyCode() == KeyEvent.VK_B) {
                Toggle.drawCutMatch.toggle();
            }
            if (e.getKeyCode() == KeyEvent.VK_Y) {
                Toggle.drawKnotGradient.toggle();
            }
            if (e.getKeyCode() == KeyEvent.VK_M) {
                if (Main.metroDrawLayer != -1) {
                    Main.metroDrawLayer = -1;
                } else {
                    Main.metroDrawLayer = Main.shell.cutEngine.totalLayers;
                }
            }
            if (e.getKeyCode() == KeyEvent.VK_CLOSE_BRACKET || e.getKeyCode() == KeyEvent.VK_UP) {
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
            if (e.getKeyCode() == KeyEvent.VK_OPEN_BRACKET || e.getKeyCode() == KeyEvent.VK_DOWN) {
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
            if (e.getKeyCode() == KeyEvent.VK_O) {
                Toggle.drawMainPath.toggle();
            }
            if (e.getKeyCode() == KeyEvent.VK_U) {
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
            if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                Main.tool.confirm();
            }
            if (e.getKeyCode() == KeyEvent.VK_R) {
                camera.reset();
                Main.tool.reset();
            }
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                if (Main.tool.toolType() == ToolType.Free) {
                    System.exit(0);
                }
                Main.tool = Main.freeTool;
                main.repaint();
            }
        } else {
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                System.exit(0);
            }
        }
    }

    long REPRESS_TIME = 360;
    long lastPressTime;

    public void paintUpdate(double SHIFT_MOD) {
        camera.setShiftMod(SHIFT_MOD);
        if (!pressedKeys.isEmpty()) {
            boolean moved = false;
            for (Iterator<Integer> it = pressedKeys.iterator(); it.hasNext();) {
                switch (it.next()) {
                    case KeyEvent.VK_W:
                        camera.move(CameraMoveDirection.FORWARD);
                        moved = true;
                        break;
                    case KeyEvent.VK_A:
                        camera.move(CameraMoveDirection.LEFT);
                        moved = true;
                        break;
                    case KeyEvent.VK_S:
                        camera.move(CameraMoveDirection.BACKWARD);
                        moved = true;
                        break;
                    case KeyEvent.VK_D:
                        camera.move(CameraMoveDirection.RIGHT);
                        moved = true;
                        break;
                    case KeyEvent.VK_EQUALS:
                        camera.zoom(true);
                        moved = true;
                        break;
                    case KeyEvent.VK_MINUS:
                        camera.zoom(false);
                        moved = true;
                        break;
                }
            }

            if (moved && main != null) {
                Point mouseLocation = MouseInfo.getPointerInfo().getLocation();
                Point frameLocation = Main.frame.getRootPane().getLocationOnScreen();
                Main.tool.calculateHover((int) (mouseLocation.getX() - frameLocation.getX()),
                        (int) (mouseLocation.getY() - frameLocation.getY()));
            }
        }

        if (main != null) {
            long timeSinceLastPress = System.currentTimeMillis() - lastPressTime;
            if (!pressedKeys.isEmpty() && timeSinceLastPress > REPRESS_TIME / SHIFT_MOD) {
                lastPressTime = System.currentTimeMillis();
                for (Iterator<Integer> it = pressedKeys.iterator(); it.hasNext();) {
                    switch (it.next()) {
                        case KeyEvent.VK_LEFT:
                            Main.tool.leftArrow();
                            break;
                        case KeyEvent.VK_RIGHT:
                            Main.tool.rightArrow();
                            break;
                    }
                }
            }

            if (!pressedKeys.isEmpty()) {
                main.repaint();
            }
        }
    }
}
