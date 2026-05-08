package ixdar.gui.ui.menu;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.actions.Action;

/**
 * One clickable row in a {@link Menu}: a heading label paired with the
 * {@link Action} fired when the row is selected.
 */
public class MenuItem {

    String heading;
    String subHeading;
    String fileName;
    Action action;
    HyperString label;

    /**
     * Build a menu item. The {@link HyperString} label is rendered with
     * {@code Color.BLUE_WHITE}.
     *
     * @param string the heading text shown to the user
     * @param action the action fired by {@link #performAction()}; may be null
     *               for placeholder rows
     */
    public MenuItem(String string, Action action) {
        heading = string;
        subHeading = "";
        String labelText = subHeading.isEmpty() ? heading : heading + ": " + subHeading;
        label = new HyperString();
        label.addWord(labelText, Color.BLUE_WHITE);
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
