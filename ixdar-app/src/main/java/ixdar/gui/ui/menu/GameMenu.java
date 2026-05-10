package ixdar.gui.ui.menu;

import java.util.ArrayList;
import ixdar.gui.ui.actions.StartNewGameAction;

import ixdar.gui.ui.actions.ChangeScreenAction;

/**
 * The main menu for the trade game mode.
 * Provides options to start a new game, continue a saved game, or return to the debug menu.
 */
public class GameMenu implements Menu {

    public ArrayList<MenuItem> menuItems;
    private Menu debugMenu;

    /**
     * Build the game menu. Holds a reference to the debug menu so the user can
     * jump back to it via the "Debug Menu" item or the {@link #back()} button.
     *
     * @param debugMenu the debug-mode main menu to return to
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
        menuItems.add(new MenuItem("Debug Menu", new ChangeScreenAction(debugMenu)));
    }

    /**
     * Return the cached list of game-menu items (Start New Game, Continue,
     * Settings, Debug Menu).
     *
     * @return the menu's items in display order
     */
    @Override
    public ArrayList<MenuItem> loadMenu() {
        return menuItems;
    }

    /**
     * Back action: switch the active {@link MenuBox} screen to the debug menu.
     */
    @Override
    public void back() {
        // Return to debug menu
        MenuBox.load(debugMenu);
    }
}
