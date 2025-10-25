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

    public MenuItem(String string, Action action) {
        heading = string;
        subHeading = "";
        String labelText = subHeading.isEmpty() ? heading : heading + ": " + subHeading;
        label = new HyperString();
        label.addWord(labelText, Color.BLUE_WHITE);
        this.action = action;
    }

    public HyperString itemString() {
        return label;
    }

    public void performAction() {
        action.perform();
    }

}
