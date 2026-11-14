package ixdar.gui;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.ColorBox;
import ixdar.gui.ui.menu.MenuScrollBox;
import ixdar.gui.ui.text.HyperString;
import ixdar.input.PointerDispatcher;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;

/**
 * A rectangle of the window that shows rows of text over the theme's background: the info pane,
 * the terminal and the toolbar. The pane owns its view id, so drawing it moves the camera there
 * itself; a subclass only says what its rows are.
 */
public abstract class Pane {

    /**
     * The box the pane's rows are drawn and scrolled in, holding them as last drawn. Its bounds are
     * the pane's view, under the pane's view id.
     */
    public final MenuScrollBox scrollBox;

    /** The focus toggle a click in the pane turns on, or {@code null} for a pane that takes none. */
    public final Toggle focus;

    private final BooleanSupplier shown;

    /** Box behind the rows, built on the first draw on the render thread. */
    private ColorBox background;

    /**
     * Build a pane with no layout yet; {@link #place} gives it one.
     *
     * @param viewId view id of the pane's bounds
     * @param shown  whether the pane is on screen this frame, so it draws and takes the wheel
     * @param focus  focus toggle a click in the pane turns on, or {@code null} for none
     */
    protected Pane(String viewId, BooleanSupplier shown, Toggle focus) {
        this.shown = shown;
        this.focus = focus;
        this.scrollBox = new MenuScrollBox(viewId, shown,
                focus == null ? null : () -> Toggle.setPanelFocus(focus));
    }

    /**
     * The id of the pane's view in the camera's views.
     *
     * @return the view id
     */
    public String viewId() {
        return scrollBox.bounds.id;
    }

    /**
     * Whether the pane is drawn this frame.
     *
     * @return what the pane's shown supplier says
     */
    public boolean shown() {
        return shown.getAsBoolean();
    }

    /**
     * Lay the pane out and add its bounds to {@code views} under its view id. A pane with a
     * {@link #focus} also takes the wheel and clicks; one without lets them through.
     *
     * @param views  the camera's views the pane's bounds join
     * @param layout sets the bounds from the window on every recalculation
     */
    public void place(Map<String, Bounds> views, Consumer<Bounds> layout) {
        Bounds bounds = scrollBox.bounds;
        bounds.setUpdateCallback(layout);
        bounds.recalc();
        views.put(viewId(), bounds);
        if (focus != null) {
            PointerDispatcher.current().subscribe(bounds, scrollBox);
        }
    }

    /**
     * Draw the pane while it is {@link #shown}: move {@code camera} to the pane's view, fill it
     * with the theme's background, then draw this frame's {@link #rows} at the box's scroll.
     *
     * @param camera 2D camera whose views hold the pane's bounds; left on a part of the pane
     */
    public final void draw(Camera2D camera) {
        if (!shown()) {
            return;
        }
        camera.updateView(viewId());
        if (background == null) {
            background = new ColorBox();
        }
        background.draw(Scene.activePalette().background, camera);
        scrollBox.text = rows();
        scrollBox.draw(camera);
    }

    /**
     * The pane's text rows.
     *
     * @return the text the pane shows
     */
    public abstract HyperString rows();
}
