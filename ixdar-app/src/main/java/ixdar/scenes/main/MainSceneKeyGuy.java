package ixdar.scenes.main;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.terminal.commands.ChangeToolCommand;
import ixdar.gui.terminal.commands.ColorCommand;
import ixdar.gui.terminal.commands.ExitCommand;
import ixdar.gui.terminal.commands.ResetCommand;
import ixdar.gui.terminal.commands.ResetCommand.ResetOption;
import ixdar.gui.terminal.commands.UpdateCommand;
import ixdar.gui.ui.tools.NegativeCutMatchViewTool;
import ixdar.gui.ui.tools.NeighborViewTool;
import ixdar.gui.ui.tools.Tool;
import ixdar.platform.Toggle;
import ixdar.platform.input.KeyActions;
import ixdar.platform.input.KeyGuy;

/**
 * The tour editor's key handler: the tool-switch chords, the view toggles that act on
 * {@link MainScene#tool}, Escape stepping the tool back, and left/right cycling the tool.
 */
public class MainSceneKeyGuy extends KeyGuy {

    public static final long REPRESS_MILLIS = 360;

    public long lastCyclePressTime;

    /**
     * Bind the tour editor's shortcuts to its 2D camera and backing canvas.
     *
     * @param camera camera the controller drives
     * @param canvas backing canvas, whose menu input is suspended while the editor is active
     */
    public MainSceneKeyGuy(Camera camera, Canvas3D canvas) {
        super(camera, canvas);
    }

    /**
     * On a first press, fire the tool-switch chords with Control held, or the view toggles while
     * the active tool lets the main pane take keys; Escape, repeats included, steps the tool back.
     *
     * @param firstPress whether the key was up before this event, {@code false} on a repeat
     */
    @Override
    public void sceneKeyPressed(boolean firstPress) {
        if (firstPress) {
            if (controlMask) {
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
        if (KeyActions.Back.keyPressed(pressedKeys)) {
            Terminal.runNoArgs(ExitCommand.class);
        }
    }

    /**
     * Per-frame: the shared camera movement, then cycle the active tool on left/right, at most
     * once per {@link #REPRESS_MILLIS} scaled down by the speed multiplier.
     *
     * @param shiftMod speed multiplier (typically 1 or 2)
     */
    @Override
    public void paintUpdate(float shiftMod) {
        super.paintUpdate(shiftMod);
        if (!active || Toggle.IsTerminalFocused.value) {
            return;
        }
        long timeSinceLastPress = System.currentTimeMillis() - lastCyclePressTime;
        if (!pressedKeys.isEmpty() && timeSinceLastPress > REPRESS_MILLIS / shiftMod) {
            lastCyclePressTime = System.currentTimeMillis();
            if (KeyActions.CycleToolLeft.keyPressed(pressedKeys)) {
                MainScene.tool.cycleLeft();
            }
            if (KeyActions.CycleToolRight.keyPressed(pressedKeys)) {
                MainScene.tool.cycleRight();
            }
        }
    }
}
