package shell.render.menu.action;

import shell.render.menu.MenuBox;
import shell.render.menu.Menu;
import shell.render.menu.MenuItem.MenuAction;

public class ChangeScreenAction implements MenuAction {
    Menu screen;

    public ChangeScreenAction(Menu screen) {
        this.screen = screen;
    }

    public void perform() {
        MenuBox.load(screen);
    }
}