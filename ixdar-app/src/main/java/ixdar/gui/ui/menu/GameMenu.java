package ixdar.gui.ui.menu;

import java.util.ArrayList;

import ixdar.gui.ui.actions.StartNewGameAction;

/**
 * The main menu for the trade game mode.
 * Provides options to start a new game, continue a saved game, or return to the debug menu.
 */
public class GameMenu implements Menu {

    public ArrayList<MenuItem> menuItems;
    private Menu debugMenu;

    /**
     * TODO: document {@code GameMenu}.
     *
     * @param debugMenu TODO: describe
     */
    public GameMenu(Menu debugMenu) {
        this.debugMenu = debugMenu;
        initMenu();
    }

    private void initMenu() {
        menuItems = new ArrayList<>();
        menuItems.add(new MenuItem("Start New Game", new StartNewGameAction()));
        menuItems.add(new MenuItem("Continue", null)); // Placeholder for TRADE-18
        menuItems.add(new MenuItem("Settings", null)); // Placeholder
        menuItems.add(new MenuItem("Debug Menu", new ixdar.gui.ui.actions.ChangeScreenAction(debugMenu)));
    }

    /**
     * TODO: document {@code loadMenu}.
     *
     * @return TODO: describe
     */
    @Override
    public ArrayList<MenuItem> loadMenu() {
        return menuItems;
    }

    /**
     * TODO: document {@code back}.
     */
    @Override
    public void back() {
        // Return to debug menu
        MenuBox.load(debugMenu);
    }
}
