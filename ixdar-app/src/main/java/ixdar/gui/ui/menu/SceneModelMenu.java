package ixdar.gui.ui.menu;

import java.util.List;

import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.Drawing;
import ixdar.platform.Platforms;
import ixdar.platform.input.PointerDispatcher;
import ixdar.platform.input.PointerRegion;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.model.ModelChoice;
import ixdar.scenes.model.ModelScene;

/**
 * The right-side model menu of every {@link ModelScene}: a MODELS box, Recompute, then a CONTROLS
 * box, each scrolling on its own; while shown it takes the wheel and pointer over its strip.
 */
public final class SceneModelMenu implements PointerRegion {

    public static final String MODELS_HEADER = "MODELS";

    public static final String CONTROLS_HEADER = "CONTROLS";

    public static final String RECOMPUTE_LABEL = "Recompute";

    public static final String CURRENT_MARKER = "> ";

    public static final String OTHER_MARKER = "  ";

    public static final String KEY_SEP = "  ";

    public static final String VIEW_MODELS_BOX = "SCENE_MENU_MODELS";

    public static final String VIEW_CONTROLS_BOX = "SCENE_MENU_CONTROLS";

    public static final float MAXIMUM_CONTROLS_BOX_SHARE_OF_STRIP = 0.4f;

    public static final int RECOMPUTE_BLANK_AND_CONTROLS_TITLE_ROWS = 3;

    /** The scrolling list of models, or of the open collection's members. */
    public final MenuScrollBox modelsBox;

    /** The scrolling list of the scene's key controls. */
    public final MenuScrollBox controlsBox;

    /** The whole right-side strip the menu covers, framebuffer pixels, y up. */
    public final Bounds strip;

    /** The Recompute row and the CONTROLS title between the boxes, as last drawn. */
    public HyperString betweenBoxes = new HyperString();

    private final ModelScene scene;

    private final SceneCollectionMenu collectionSection;

    private boolean visible;

    private boolean centreCurrentModel;

    /**
     * Bind the menu to the scene it drives and subscribe its boxes, then its whole strip, as
     * pointer regions, so the boxes win where they overlap the strip.
     *
     * @param scene scene whose models and controls this menu shows
     */
    public SceneModelMenu(ModelScene scene) {
        this.scene = scene;
        this.collectionSection = new SceneCollectionMenu(scene);
        modelsBox = new MenuScrollBox(VIEW_MODELS_BOX, this::isVisible);
        controlsBox = new MenuScrollBox(VIEW_CONTROLS_BOX, this::isVisible);
        modelsBox.bounds.setUpdateCallback(bounds -> layout());
        controlsBox.bounds.setUpdateCallback(bounds -> layout());
        strip = new Bounds(0, 0, 0, 0,
                bounds -> bounds.update(
                        Platforms.get().getFrameBufferWidth() - ModelScene.MENU_PANEL_WIDTH, 0,
                        ModelScene.MENU_PANEL_WIDTH, Platforms.get().getFrameBufferHeight()),
                ModelScene.VIEW_SCENE_MENU);
        strip.recalc();
        PointerDispatcher pointer = PointerDispatcher.current();
        pointer.subscribe(modelsBox.bounds, modelsBox);
        pointer.subscribe(controlsBox.bounds, controlsBox);
        pointer.subscribe(strip, this);
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
     * Flip the menu between shown and hidden (Ctrl+I; Esc closes it); opening it centres the current model
     * in the models box.
     */
    public void toggle() {
        visible = !visible;
        centreCurrentModel = visible;
    }

    /**
     * Place the controls box at the bottom of the right-side strip and the models box directly
     * under the MODELS title, both whole lines tall so an unscrolled box shows no cut row.
     */
    public void layout() {
        float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
        float stripTop = Platforms.get().getFrameBufferHeight();
        float stripX = Platforms.get().getFrameBufferWidth() - ModelScene.MENU_PANEL_WIDTH;
        float boxRoom = Math.max(0,
                stripTop - (RECOMPUTE_BLANK_AND_CONTROLS_TITLE_ROWS + 1) * rowHeight);
        int controlLines = controlsBox.rowsUsed == 0 ? scene.controls().size()
                : controlsBox.rowsUsed;
        controlLines = Math.min(controlLines,
                (int) (boxRoom * MAXIMUM_CONTROLS_BOX_SHARE_OF_STRIP / rowHeight));
        float controlsHeight = controlLines * rowHeight;
        controlsBox.bounds.update(stripX, 0, ModelScene.MENU_PANEL_WIDTH, controlsHeight);
        float modelsHeight = (float) Math.floor((boxRoom - controlsHeight) / rowHeight) * rowHeight;
        modelsBox.bounds.update(stripX, stripTop - rowHeight - modelsHeight,
                ModelScene.MENU_PANEL_WIDTH, modelsHeight);
    }

    /**
     * Render the titles and both boxes into the right-side strip, leaving the camera's view on
     * the last box drawn. The models box holds the open collection's section, or every model with
     * the loaded one highlighted and, on the first draw after opening, centred.
     *
     * @param camera 2D camera whose view is moved to each part in turn
     */
    public void draw(Camera2D camera) {
        controlsBox.clearRows();
        for (ControlHint hint : scene.controls()) {
            controlsBox.addRow(hint.key + KEY_SEP + hint.description,
                    hint.action == null ? Color.LIGHT_GRAY : Color.BLUE_WHITE, hint.action);
        }
        layout();
        controlsBox.layout(camera);

        modelsBox.clearRows();
        int currentModelRow = -1;
        collectionSection.append(modelsBox);
        if (scene.modelCollection == null) {
            ModelChoice current = scene.currentModel();
            String currentPath = current == null ? null : current.path;
            List<ModelChoice> choices = scene.availableModels();
            if (choices.isEmpty()) {
                modelsBox.addRow("(no models found)", Color.LIGHT_GRAY, null);
            }
            for (ModelChoice choice : choices) {
                boolean isCurrent = currentPath != null && currentPath.equals(choice.path);
                int row = modelsBox.addRow(
                        (isCurrent ? CURRENT_MARKER : OTHER_MARKER) + choice.displayName,
                        isCurrent ? Color.BRIGHT_GREEN : Color.COMMAND,
                        () -> scene.requestModelLoad(choice.path));
                if (isCurrent) {
                    currentModelRow = row;
                }
            }
        }
        layout();
        if (centreCurrentModel) {
            centreCurrentModel = false;
            if (currentModelRow >= 0) {
                modelsBox.centreRow(currentModelRow);
            }
        }
        HyperString modelsTitle = new HyperString();
        modelsTitle.addLine(MODELS_HEADER, Color.AMBER);
        drawFixedRows(modelsTitle, modelsBox.bounds.offsetY + modelsBox.bounds.viewHeight, 1,
                camera);
        modelsBox.draw(camera);

        betweenBoxes = new HyperString();
        betweenBoxes.addWordClick(RECOMPUTE_LABEL, Color.SKY_BLUE, () -> {
            ModelChoice reload = scene.currentModel();
            if (reload != null) {
                scene.requestModelLoad(reload.path);
            }
        });
        betweenBoxes.newLine();
        betweenBoxes.newLine();
        betweenBoxes.addLine(CONTROLS_HEADER, Color.AMBER);
        drawFixedRows(betweenBoxes, controlsBox.bounds.viewHeight,
                RECOMPUTE_BLANK_AND_CONTROLS_TITLE_ROWS, camera);
        controlsBox.draw(camera);
    }

    /**
     * Nothing scrolls the strip as a whole; it only keeps the wheel over its titles from zooming.
     *
     * @param scrollUp unused
     * @param deltaSeconds unused
     */
    @Override
    public void onScroll(boolean scrollUp, double deltaSeconds) {
    }

    /**
     * The open menu takes the wheel over its whole strip, titles included; a closed menu leaves
     * the wheel to the orbit.
     *
     * @return whether the menu is shown
     */
    @Override
    public boolean claimsWheel() {
        return visible;
    }

    /**
     * The open menu takes presses over its whole strip, titles included, so nothing under it, the
     * orbit or a scene tool, sees them; a closed menu takes none.
     *
     * @return whether the menu is shown
     */
    @Override
    public boolean claimsPointer() {
        return visible;
    }

    /**
     * A click on the strip outside both boxes runs the Recompute row when it lands there.
     *
     * @param x framebuffer x of the release
     * @param y framebuffer y of the release, y up
     * @param click whether the press and release make a click
     */
    @Override
    public void onRelease(float x, float y, boolean click) {
        if (click) {
            betweenBoxes.click(x, y);
        }
    }

    /**
     * Draw unscrolled rows into a strip-wide view {@code rowCount} rows tall.
     *
     * @param rows text to draw
     * @param bottomY framebuffer y of the view's bottom edge
     * @param rowCount rows the view holds
     * @param camera 2D camera whose view is moved there
     */
    private void drawFixedRows(HyperString rows, float bottomY, int rowCount, Camera2D camera) {
        float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
        camera.updateView(Platforms.get().getFrameBufferWidth() - ModelScene.MENU_PANEL_WIDTH,
                (int) bottomY, ModelScene.MENU_PANEL_WIDTH, (int) (rowCount * rowHeight));
        Drawing.getDrawing().font.drawHyperStringRows(rows, 0, 0, rowHeight, camera);
    }
}
