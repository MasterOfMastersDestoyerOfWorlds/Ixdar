package ixdar.gui.ui;

import java.util.List;
import java.util.function.BooleanSupplier;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.gui.ui.menu.MenuScrollBox;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.Clock;
import ixdar.scenes.main.Toggle;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

/**
 * The editor's info pane: the frame rate, the active tool's name and info, then the controls
 * listing, scrolling together in one {@link MenuScrollBox}.
 */
public class InfoPane {

    /**
     * The box the pane's rows scroll in, holding them as last drawn; the scene lays its bounds out
     * as the pane's view and subscribes it, and a click in it focuses the pane.
     */
    public final MenuScrollBox scrollBox;

    /**
     * Build an empty info pane whose box's bounds carry {@code viewId}.
     *
     * @param viewId view id of the pane's bounds
     * @param shown whether the pane's scene is on screen, so the box takes the wheel
     */
    public InfoPane(String viewId, BooleanSupplier shown) {
        this.scrollBox = new MenuScrollBox(viewId, shown, () -> Toggle.setPanelFocus(PaneTypes.Info));
    }

    /**
     * Render the info pane stacked from the top at the box's scroll: the frame rate and the active
     * tool's name, the tool's {@link Tool#info}, then the scene's controls listing.
     *
     * @param camera   2D camera whose view is moved to the pane's box
     * @param tool     the active tool, whose info is shown
     * @param controls the scene's controls, one row per key
     */
    public void draw(Camera2D camera, Tool tool, List<ControlHint> controls) {
        HyperString rows = new HyperString();
        rows.addWord("FPS:" + Clock.fps());
        rows.newLine();
        rows.addWord("Tool: " + tool.displayName());
        rows.newLine();
        rows.addHyperString(tool.info());
        rows.newLine();
        rows.newLine();
        rows.addLine("Controls:", Color.IXDAR);
        for (ControlHint hint : controls) {
            rows.addWord(hint.key, Color.BLUE_GREY);
            rows.addLine(hint.description, Color.WHITE);
        }
        rows.wrap();
        scrollBox.text = rows;
        scrollBox.draw(camera);
    }
}
