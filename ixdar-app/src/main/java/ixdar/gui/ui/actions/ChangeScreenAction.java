package ixdar.gui.ui.actions;

import ixdar.gui.ui.menu.Menu;
import ixdar.gui.ui.menu.MenuBox;

/**
 * Menu action that swaps the active {@link MenuBox} screen to a target {@link Menu}.
 */
public class ChangeScreenAction implements Action {
    Menu screen;

    /**
     * Bind this action to the menu it should switch to.
     *
     * @param screen the menu to display when {@link #perform()} is invoked
     */
    public ChangeScreenAction(Menu screen) {
        this.screen = screen;
    }

    /**
     * Load the bound screen into the active {@link MenuBox}.
     */
    @Override
    public void perform() {
        MenuBox.load(screen);
    }
}