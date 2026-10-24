package ixdar.tools;

import java.util.ArrayList;
import java.util.List;

import ixdar.graphics.cameras.Camera2D;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.input.KeyNames;
import ixdar.input.PointerDispatcher;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;

/**
 * One tool a {@link Scene} hosts. The active tool owns the scene's tool keys and, through the
 * {@link PointerDispatcher}, its pointer. What every tool reports has one shape here: last row and
 * error, mode, history, draft, preview and items.
 */
public abstract class Tool {

    /** One-line summary of the last thing the tool did, shown under the status line. */
    public String lastRow = "";

    /** Why the last action failed, or empty when it did not. */
    public String lastError = "";

    /** Labels of the ways of working the tool switches between, empty for a tool with one. */
    public final String[] modes;

    /** Index into {@link #modes} of the current one. */
    public int mode;

    /**
     * Tools this one owns and hands its own interaction to, in place of its default one, over the
     * state they share; the scene never hosts them. Empty for a tool with none.
     */
    public final List<Tool> subTools = new ArrayList<>();

    /** The one of {@link #subTools} taking the tool's input, or {@code null} while it takes it. */
    public Tool activeSubTool;

    /**
     * A tool working in the first of {@code modes}.
     *
     * @param modes labels of the tool's modes in switching order, none for a tool with one
     */
    protected Tool(String... modes) {
        this.modes = modes;
    }

    /**
     * Hand the tool's input to one of its {@link #subTools}, or with {@code null} take it back: the
     * sub-tool leaving deactivates and the one coming activates.
     *
     * @param subTool one of {@link #subTools}, or {@code null} for the tool's default interaction
     */
    public void switchSubTool(Tool subTool) {
        if (subTool == activeSubTool) {
            return;
        }
        if (activeSubTool != null) {
            activeSubTool.deactivate();
        }
        activeSubTool = subTool;
        if (subTool != null) {
            subTool.activate();
        }
    }

    /**
     * Name the tool-switch hint, the log and the terminal's {@code ct} command call the tool by,
     * the id its {@code ToolAnnotation} registers it under.
     *
     * @return a short lower-case name
     */
    public abstract String toolName();

    /** Become the active tool: take the mouse and start showing what the tool shows. */
    public abstract void activate();

    /** Hand the mouse back for another tool, keeping every edit and selection for later. */
    public abstract void deactivate();

    /** One frame, active or not; an inactive tool keeps only what must stay in step. */
    public void perFrame() {
    }

    /**
     * Append the tool's own key and mouse hints, which the scene's key handler dispatches ahead of
     * the scene's own.
     *
     * @param controls the scene's control list, rebuilt whenever the active tool changes
     */
    public abstract void addControls(List<ControlHint> controls);

    /**
     * The edits undo and redo step through; a tool with undo narrows the type to its snapshots.
     *
     * @return the history, or {@code null} for a tool that keeps none
     */
    public EditHistory<?> history() {
        return null;
    }

    /**
     * What the tool is building and has not committed yet.
     *
     * @return the draft, or {@code null} when nothing is being built
     */
    public Draft draft() {
        return null;
    }

    /**
     * What the tool shows under the cursor before a click commits it.
     *
     * @return the preview, or {@code null} for a tool that previews nothing
     */
    public Preview preview() {
        return null;
    }

    /**
     * The item under the cursor, one the tool has made or one it works over.
     *
     * @return the hovered item, or {@code null} when the cursor is over none
     */
    public Item hovered() {
        return null;
    }

    /**
     * The things the tool has made, in the order it lists them.
     *
     * @return the items, empty for a tool that makes none
     */
    public List<? extends Item> items() {
        return List.of();
    }

    /**
     * The name the info pane and the trade state report show for the tool.
     *
     * @return a title-case name, {@link #toolName} unless the tool has a longer one
     */
    public String displayName() {
        return toolName();
    }

    /**
     * The terse alias the terminal's {@code ct} command also accepts.
     *
     * @return a short lower-case alias, {@link #toolName} unless the tool has a shorter one
     */
    public String shortName() {
        return toolName();
    }

    /**
     * One sentence on what the tool does, for the terminal's tool listing.
     *
     * @return the description, empty for a tool with none
     */
    public String desc() {
        return "";
    }

    /**
     * The info pane's tool section: what the tool has hovered and selected.
     *
     * @return the text, current as of the tool's last hover or selection change
     */
    public HyperString info() {
        return new HyperString();
    }

    /**
     * Whether the scene applies {@code toggle} while this tool is active.
     *
     * @param toggle the toggle to test
     * @return the toggle's value, or false for a toggle the tool switches off
     */
    public boolean canUseToggle(Toggle toggle) {
        return toggle.value;
    }

    /**
     * The active tool's first chance at Esc with the menu closed; the scene returns to its default
     * tool only when the tool leaves it.
     *
     * @return true when the tool consumed the key
     */
    public boolean escapePressed() {
        return false;
    }

    /**
     * What {@link #escapePressed} does when it consumes the key, for the controls menu.
     *
     * @return a short phrase, or empty when the tool never consumes Esc
     */
    public String escapeDescription() {
        return "";
    }

    /**
     * Whether the active tool draws its own view in place of the scene's, so the scene's view, its
     * overlay text and its own key hints step aside and {@link #addControls} lists every key.
     *
     * @return false for a tool that works over the scene's view
     */
    public boolean replacesView() {
        return false;
    }

    /**
     * Frame everything the tool shows, its home view, on F or a double-click on empty space as
     * {@link Scene#frameAll} binds them.
     *
     * @return false for a tool over the scene's view, which the scene then frames itself
     */
    public boolean frameAll() {
        return false;
    }

    /** Draw the tool's view in place of the scene's; only called while {@link #replacesView}. */
    public void drawView() {
    }

    /**
     * Draw the tool's highlights and previews over a 2D scene's view, after the scene's map.
     *
     * @param camera        the scene's 2D camera, in its main view
     * @param lineThickness the scene's base line thickness in pixels
     */
    public void drawOverlay(Camera2D camera, float lineThickness) {
    }

    /**
     * The cursor moved over a 2D scene's view with no press held.
     *
     * @param x framebuffer x of the cursor
     * @param y framebuffer y of the cursor, y up
     */
    public void onHover(float x, float y) {
    }

    /** The cursor left a 2D scene's view: drop what the hover showed. */
    public void clearHover() {
    }

    /**
     * A click a 2D scene's mouse handler resolved on its view, offered to the active tool.
     *
     * @param x framebuffer x of the click
     * @param y framebuffer y of the click, y up
     * @return true when the tool acted on it
     */
    public boolean onClick(float x, float y) {
        return false;
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
    public boolean onPress(float x, float y, int mods) {
        return false;
    }

    /**
     * The cursor moved with a press {@link #onPress} took still held, wherever it now is.
     *
     * @param x framebuffer x of the cursor
     * @param y framebuffer y of the cursor, y up
     */
    public void onDrag(float x, float y) {
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
    public boolean onRelease(float x, float y, boolean click) {
        return false;
    }
}
