package ixdar.gui.ui.menu;

import ixdar.graphics.render.color.Palette;
import ixdar.gui.ui.actions.Action;
import ixdar.gui.ui.text.HyperString;
import ixdar.scenes.Scene;

/**
 * One clickable row in a {@link Menu}: a heading label paired with the
 * {@link Action} fired when the row is selected.
 */
public class MenuItem {

    /** What a click on the row does; null for a disabled row, which a click leaves alone. */
    public Action action;
    String heading;
    String subHeading;
    String fileName;
    HyperString label;

    /**
     * Build a menu item, its label in the theme's muted text when it has an action and its disabled colour when not. The
     * label's words carry the action, though the {@link MenuBox} runs it for a click on the button.
     *
     * @param string the heading text shown to the user
     * @param action the action fired by {@link #performAction()}; null for a
     *               disabled row
     */
    public MenuItem(String string, Action action) {
        heading = string;
        subHeading = "";
        String labelText = subHeading.isEmpty() ? heading : heading + ": " + subHeading;
        label = new HyperString();
        Palette palette = Scene.activePalette();
        label.addWordClick(labelText, action == null ? palette.disabled : palette.mutedText, action);
        this.action = action;
    }

    /**
     * Get the rendered label used to draw this item in the menu box.
     *
     * @return the cached {@link HyperString} label
     */
    public HyperString itemString() {
        return label;
    }

    /**
     * Fire the action bound to this menu item.
     */
    public void performAction() {
        action.perform();
    }

    /**
     * Get the raw heading text (without any color/styling).
     *
     * @return the heading string passed to the constructor
     */
    public String getHeading() {
        return heading;
    }

}
