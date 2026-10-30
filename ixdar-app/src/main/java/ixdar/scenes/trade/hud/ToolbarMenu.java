package ixdar.scenes.trade.hud;

/**
 * The toolbar's three menus: the route tools with undo and redo, how the map is shown, and the game's own
 * commands.
 */
public enum ToolbarMenu {
    TOOLS("Tools"),
    VIEW("View"),
    GAME("Game");

    public final String label;

    ToolbarMenu(String label) {
        this.label = label;
    }
}
