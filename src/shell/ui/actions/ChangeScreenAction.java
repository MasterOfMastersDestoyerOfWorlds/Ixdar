package shell.ui.actions;

import shell.ui.menu.Menu;
import shell.ui.menu.MenuBox;

public class ChangeScreenAction implements Action {
    Menu screen;

    public ChangeScreenAction(Menu screen) {
        this.screen = screen;
    }

    @Override
    public void perform() {
        MenuBox.load(screen);
    }
}