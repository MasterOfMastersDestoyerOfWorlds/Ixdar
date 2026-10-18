package ixdar.tools;

import java.util.List;

import ixdar.platform.input.KeyNames;
import ixdar.platform.input.PointerDispatcher;
import ixdar.scenes.Scene;

/**
 * One tool a {@link Scene} hosts. The one active tool owns the scene's tool keys and, through the
 * {@link PointerDispatcher}, its pointer; it keeps its state when another takes over.
 */
public interface EditTool {

    /**
     * Name the tool-switch hint and the log call the tool by.
     *
     * @return a short lower-case name
     */
    String toolName();

    /** Become the active tool: take the mouse and start showing what the tool shows. */
    void activate();

    /** Hand the mouse back for another tool, keeping every edit and selection for later. */
    void deactivate();

    /** One frame, active or not; an inactive tool keeps only what must stay in step. */
    void perFrame();

    /**
     * Append the tool's own key and mouse hints, which the scene's key handler dispatches ahead of
     * the scene's own.
     *
     * @param controls the scene's control list, rebuilt whenever the active tool changes
     */
    void addControls(List<ControlHint> controls);

    /**
     * The active tool's first chance at Esc with the menu closed; the scene returns to its default
     * tool only when the tool leaves it.
     *
     * @return true when the tool consumed the key
     */
    default boolean escapePressed() {
        return false;
    }

    /**
     * What {@link #escapePressed} does when it consumes the key, for the controls menu.
     *
     * @return a short phrase, or empty when the tool never consumes Esc
     */
    default String escapeDescription() {
        return "";
    }

    /**
     * Whether the active tool draws its own view in place of the scene's, so the scene's view, its
     * overlay text and its own key hints step aside and {@link #addControls} lists every key.
     *
     * @return false for a tool that works over the scene's view
     */
    default boolean replacesView() {
        return false;
    }

    /**
     * Frame everything the tool shows, its home view, on F or a double-click on empty space as
     * {@link Scene#frameAll} binds them.
     *
     * @return false for a tool over the scene's view, which the scene then frames itself
     */
    default boolean frameAll() {
        return false;
    }

    /** Draw the tool's view in place of the scene's; only called while {@link #replacesView}. */
    default void drawView() {
    }

    /**
     * A left press no UI pane took, offered to the active tool before the camera. Taking it keeps
     * the camera still: the drag reaches {@link #onDrag} instead.
     *
     * @param x    framebuffer x of the press
     * @param y    framebuffer y of the press, y up
     * @param mods modifier-key bitmask of the press, as {@link KeyNames} spells the bits
     * @return true to take the drag that follows; false leaves it to the camera
     */
    default boolean onPress(float x, float y, int mods) {
        return false;
    }

    /**
     * The cursor moved with a press {@link #onPress} took still held, wherever it now is.
     *
     * @param x framebuffer x of the cursor
     * @param y framebuffer y of the cursor, y up
     */
    default void onDrag(float x, float y) {
    }

    /**
     * The release of every press {@link #onPress} was offered, whether the tool or the camera took
     * its drag.
     *
     * @param x     framebuffer x of the release
     * @param y     framebuffer y of the release, y up
     * @param click true when the cursor stayed within the click threshold of the press
     * @return true when the tool acted on the click, so the overlay text's links do not
     */
    default boolean onRelease(float x, float y, boolean click) {
        return false;
    }

    /**
     * The wheel deltas summed since the last frame, offered to the active tool when no UI pane
     * under the cursor takes them.
     *
     * @param deltaX summed horizontal delta, positive toward the left
     * @param deltaY summed vertical delta, 1.0 per wheel notch, positive away from the user
     * @return true when the tool used them; false leaves the vertical one to the camera's zoom
     */
    default boolean onScroll(double deltaX, double deltaY) {
        return false;
    }
}
