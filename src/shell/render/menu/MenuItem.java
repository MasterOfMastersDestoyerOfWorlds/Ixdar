package shell.render.menu;

public class MenuItem {

    String heading;
    String subHeading;
    String fileName;
    MenuAction action;

    public interface MenuAction {
        public void perform();
    }

    public MenuItem(String string, MenuAction action) {
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
