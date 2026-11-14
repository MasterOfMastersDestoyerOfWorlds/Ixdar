package ixdar.gui.ui;

import ixdar.graphics.render.color.Palette;
import ixdar.gui.Pane;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.Clock;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

/**
 * The editor's info pane: the frame rate, the active tool's name and info, then the controls
 * listing, scrolling together in the pane's box. A click in it focuses it.
 */
public class InfoPane extends Pane {

    /** The scene whose active tool and controls the pane lists. */
    public final Scene scene;

    /**
     * Build an empty info pane over {@code scene}, shown while the scene is active.
     *
     * @param viewId view id of the pane's bounds
     * @param scene  the scene whose active tool and controls are listed
     */
    public InfoPane(String viewId, Scene scene) {
        super(viewId, () -> scene.active, Toggle.IsInfoFocused);
        this.scene = scene;
    }

    /**
     * The rows stacked from the top: the frame rate and the active tool's name, the tool's
     * {@link Tool#info}, then the scene's controls, one row per key under each section's heading.
     *
     * @return the pane's rows, wrapped to its width
     */
    @Override
    public HyperString rows() {
        Tool tool = scene.activeTool;
        HyperString rows = new HyperString();
        rows.addWord("FPS:" + Clock.fps());
        rows.newLine();
        rows.addWord("Tool: " + tool.displayName());
        rows.newLine();
        rows.addHyperString(tool.info());
        rows.newLine();
        rows.newLine();
        Palette palette = Scene.activePalette();
        rows.addLine("Controls:", palette.headingText);
        for (int section = 0; section < scene.controls.size(); section++) {
            if (scene.controls.get(section).isEmpty()) {
                continue;
            }
            rows.addLine(scene.controlSectionName(section), palette.headingText);
            for (ControlHint hint : scene.controls.get(section)) {
                rows.addWord(hint.key, palette.mutedText);
                rows.addLine(hint.description, palette.text);
            }
        }
        rows.wrap();
        return rows;
    }
}
