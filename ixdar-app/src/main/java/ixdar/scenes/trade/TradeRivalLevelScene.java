package ixdar.scenes.trade;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.game.TradeLevel;

/**
 * The trade game opened straight on the rival level, the market war the tutorials lead up to.
 */
@SceneAnnotation(id = "trade-rival-level")
public class TradeRivalLevelScene extends TradeScene {

    /**
     * Open the campaign at the rival level.
     */
    public TradeRivalLevelScene() {
        super(TradeLevel.campaign(), TradeLevel.RIVAL_LEVEL_INDEX, null);
    }
}
