package ixdar.gui.ui.actions;

import ixdar.gui.ui.menu.Menu;
import ixdar.gui.ui.menu.MenuBox;

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