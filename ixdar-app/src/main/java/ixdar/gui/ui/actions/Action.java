package ixdar.gui.ui.actions;

public interface Action {
    String name = "None";

    /**
     * Run this action's effect (e.g. switch screens, load a file, start a game).
     * Invoked when the menu item bound to this action is clicked.
     */
    public void perform();
}