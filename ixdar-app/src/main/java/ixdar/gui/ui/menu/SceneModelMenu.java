package ixdar.gui.ui.menu;

import java.util.List;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.Drawing;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.model.ModelChoice;
import ixdar.scenes.model.ModelScene;

/**
 * The right-side ESC menu shared by every {@link ModelScene}: a clickable model dropdown with a
 * Recompute action and a Controls section for the scene's key bindings. The {@link HyperString}
 * is rebuilt every frame so the current-model highlight stays live and drawing re-registers its
 * clickable words for that frame's mouse dispatch.
 */
public final class SceneModelMenu {

    /** Header above the model list. */
    public static final String MODELS_HEADER = "MODELS";

    /** Header above the controls list. */
    public static final String CONTROLS_HEADER = "CONTROLS";

    /** Label of the recompute action row. */
    public static final String RECOMPUTE_LABEL = "Recompute";

    /** Marker prefixed to the currently loaded model. */
    public static final String CURRENT_MARKER = "> ";

    /** Marker prefixed to non-current models, keeping columns aligned. */
    public static final String OTHER_MARKER = "  ";

    /** Separator between a control's key and its description. */
    public static final String KEY_SEP = "  ";

    private final ModelScene scene;

    private boolean visible;

    /**
     * Bind the menu to the scene it drives.
     *
     * @param scene scene whose models and controls this menu shows
     */
    public SceneModelMenu(ModelScene scene) {
        this.scene = scene;
    }

    /**
     * Whether the menu is currently shown.
     *
     * @return {@code true} if visible
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Flip the menu between shown and hidden (bound to ESC).
     */
    public void toggle() {
        visible = !visible;
    }

    /**
     * Render the menu into the current camera view (a right-side strip set up by the caller),
     * top-down from the first row.
     *
     * @param camera 2D camera whose active view bounds the menu is drawn into
     */
    public void draw(Camera2D camera) {
        HyperString hyper = build();
        Drawing.getDrawing().font.drawHyperStringRows(hyper, 0, 0, Drawing.FONT_HEIGHT_PIXELS, camera);
    }

    private HyperString build() {
        HyperString hyper = new HyperString();
        hyper.addLine(MODELS_HEADER, Color.AMBER);

        ModelChoice current = scene.currentModel();
        String currentPath = current == null ? null : current.path;
        List<ModelChoice> models = scene.availableModels();
        if (models.isEmpty()) {
            hyper.addLine("(no models found)", Color.LIGHT_GRAY);
        }
        for (ModelChoice choice : models) {
            boolean isCurrent = currentPath != null && currentPath.equals(choice.path);
            String marker = isCurrent ? CURRENT_MARKER : OTHER_MARKER;
            Color color = isCurrent ? Color.BRIGHT_GREEN : Color.COMMAND;
            hyper.addWordClick(marker + choice.displayName, color,
                    () -> scene.requestModelLoad(choice.path));
            hyper.newLine();
        }

        hyper.newLine();
        hyper.addWordClick(RECOMPUTE_LABEL, Color.SKY_BLUE, () -> {
            ModelChoice reload = scene.currentModel();
            if (reload != null) {
                scene.requestModelLoad(reload.path);
            }
        });
        hyper.newLine();

        hyper.newLine();
        hyper.addLine(CONTROLS_HEADER, Color.AMBER);
        for (ControlHint hint : scene.controls()) {
            String row = hint.key + KEY_SEP + hint.description;
            if (hint.action == null) {
                hyper.addWord(row, Color.LIGHT_GRAY);
            } else {
                hyper.addWordClick(row, Color.BLUE_WHITE, hint.action);
            }
            hyper.newLine();
        }
        return hyper;
    }
}
