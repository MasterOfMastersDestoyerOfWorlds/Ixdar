package ixdar.gui.ui.menu;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.actions.Action;

public class MenuItem {

    String heading;
    String subHeading;
    String fileName;
    Action action;
    HyperString label;

    /**
     * TODO: document {@code MenuItem}.
     *
     * @param string TODO: describe
     * @param action TODO: describe
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
     * TODO: document {@code itemString}.
     *
     * @return TODO: describe
     */
    public HyperString itemString() {
        return label;
    }

    /**
     * TODO: document {@code performAction}.
     */
    public void performAction() {
        action.perform();
    }

    /**
     * TODO: document {@code getHeading}.
     *
     * @return TODO: describe
     */
    public String getHeading() {
        return heading;
    }

}
