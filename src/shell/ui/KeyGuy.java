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
import shell.file.FileManagement;
import shell.shell.Shell;
import shell.ui.actions.EditManifoldAction;
import shell.ui.actions.FindManifoldAction;
import shell.ui.actions.GenerateManifoldTestsAction;
import shell.ui.actions.PrintScreenAction;
import shell.ui.actions.SaveAction;
import shell.ui.tools.Tool;

public class KeyGuy implements KeyListener {

    public final Set<Integer> pressedKeys = new HashSet<>();
    public Main main;

    public KeyGuy(Main main, JFrame frame, String fileName) {
        this.main = main;
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
    }

    @Override
    public void keyPressed(KeyEvent e) {
        pressedKeys.add(e.getKeyCode());

        main.repaint();
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    @Override
    public void keyReleased(KeyEvent e) {
        pressedKeys.remove(e.getKeyCode());
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
            Main.calculateSubPaths();
        }
        if (e.getKeyCode() == KeyEvent.VK_R) {
            Camera camera = Main.camera;
            camera.ScaleFactor = camera.InitialScale;
            camera.PanX = camera.defaultPanX;
            camera.PanY = camera.defaultPanY;
            Main.tool.reset();
        }
        if (e.getKeyCode() == KeyEvent.VK_LEFT) {
            Main.tool.leftArrow();
        }
        if (e.getKeyCode() == KeyEvent.VK_RIGHT) {
            Main.tool.rightArrow();
        }
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            Main.tool = Main.freeTool;
            main.repaint();
        }
    }

    public void paintUpdate(double SHIFT_MOD) {
        if (!pressedKeys.isEmpty()) {
            Camera camera = Main.camera;
            for (Iterator<Integer> it = pressedKeys.iterator(); it.hasNext();) {
                switch (it.next()) {
                    case KeyEvent.VK_W:
                        camera.PanY += camera.PAN_SPEED * SHIFT_MOD;
                        break;
                    case KeyEvent.VK_A:
                        camera.PanX += camera.PAN_SPEED * SHIFT_MOD;
                        break;
                    case KeyEvent.VK_S:
                        camera.PanY -= camera.PAN_SPEED * SHIFT_MOD;
                        break;
                    case KeyEvent.VK_D:
                        camera.PanX -= camera.PAN_SPEED * SHIFT_MOD;
                        break;
                    case KeyEvent.VK_EQUALS:
                        camera.scale(camera.ZOOM_SPEED * SHIFT_MOD);
                        break;
                    case KeyEvent.VK_MINUS:
                        camera.scale(-(camera.ZOOM_SPEED * SHIFT_MOD));
                        break;
                }
            }

            Point mouseLocation = MouseInfo.getPointerInfo().getLocation();
            Point frameLocation = Main.frame.getRootPane().getLocationOnScreen();
            Main.tool.calculateHover((int) (mouseLocation.getX() - frameLocation.getX()),
                    (int) (mouseLocation.getY() - frameLocation.getY()));
        }

        if (!pressedKeys.isEmpty()) {
            main.repaint();
        }
    }
}
