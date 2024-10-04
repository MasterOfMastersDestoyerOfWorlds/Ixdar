package shell.ui.menu;

import shell.ui.actions.Action;

public class MenuItem {

    String heading;
    String subHeading;
    String fileName;
    Action action;

    public MenuItem(String string, Action action) {
        heading = string;
        subHeading = "";
        this.action = action;
    }

    public String itemString() {
        if (subHeading.isEmpty()) {
            return heading;
        }
        return heading + ": " + subHeading;
    }

    public void performAction() {
        action.perform();
    }

}
