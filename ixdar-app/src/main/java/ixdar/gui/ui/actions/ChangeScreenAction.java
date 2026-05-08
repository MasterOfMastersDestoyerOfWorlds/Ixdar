package ixdar.gui.ui.actions;

import ixdar.gui.ui.menu.Menu;
import ixdar.gui.ui.menu.MenuBox;

public class ChangeScreenAction implements Action {
    Menu screen;

    /**
     * TODO: document {@code ChangeScreenAction}.
     *
     * @param screen TODO: describe
     */
    public ChangeScreenAction(Menu screen) {
        this.screen = screen;
    }

    /**
     * TODO: document {@code perform}.
     */
    @Override
    public void perform() {
        MenuBox.load(screen);
    }
}