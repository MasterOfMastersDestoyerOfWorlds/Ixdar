package ixdar.gui.ui;

import java.util.Locale;

import ixdar.graphics.render.color.Palette;
import ixdar.gui.Pane;
import ixdar.gui.ui.text.HyperString;
import ixdar.gui.ui.text.HyperWord;
import ixdar.scenes.Scene;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

/**
 * The toolbar along the bottom of a scene: the active tool's name, its mode when it has sub-tools,
 * then its controls, each key with what it does, wrapped to the window's width. It takes no focus
 * and no clicks.
 */
public class ToolbarPane extends Pane {

    public static final String VIEW_SCENE_TOOLBAR = "SCENE_TOOLBAR";

    /** The scene whose active tool the bar lists. */
    public final Scene scene;

    /**
     * Build the bar over {@code scene}, shown while the scene has {@link Scene#showToolbar} on, is
     * active, and its active tool leaves the 3D view in place.
     *
     * @param scene the scene whose active tool is listed
     */
    public ToolbarPane(Scene scene) {
        super(VIEW_SCENE_TOOLBAR, () -> scene.showToolbar && scene.active && scene.activeTool != null
                && !scene.activeTool.replacesView(), null);
        this.scene = scene;
    }

    /**
     * The tool's name and mode in the theme's highlight, then each of the scene's tool-section
     * controls, wrapped to the bar's width with each key kept on the row of what it does.
     *
     * @return the bar's rows
     */
    @Override
    public HyperString rows() {
        Tool tool = scene.activeTool;
        Palette palette = Scene.activePalette();
        HyperString bar = new HyperString();
        String title = tool.displayName() + (tool.activeSubTool == null ? ""
                : ": " + tool.activeSubTool.displayName());
        bar.addWord(title.toUpperCase(Locale.ROOT), palette.highlight);
        for (ControlHint hint : scene.controls.get(Scene.TOOL_SECTION)) {
            HyperWord key = bar.addWord(ControlHint.KEY_DESCRIPTION_GAP + hint.key + ":", palette.linkText);
            bar.joinWord(key, hint.description, palette.text);
        }
        bar.wrap();
        return bar;
    }
}
