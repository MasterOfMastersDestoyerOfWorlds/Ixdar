package ixdar.gui.ui.actions;

import ixdar.scenes.Scene;
import ixdar.scenes.trade.TradeScene;

/**
 * Action to start the trade game's campaign at its first level.
 */
public class StartNewGameAction implements Action {
    @Override
    public void perform() {
        TradeScene.startNewGame(Scene.instance);
    }
}
