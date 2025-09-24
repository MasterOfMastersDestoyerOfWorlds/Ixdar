package shell.ui.menu;

import shell.render.color.Color;
import shell.render.text.HyperString;
import shell.ui.actions.Action;

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
