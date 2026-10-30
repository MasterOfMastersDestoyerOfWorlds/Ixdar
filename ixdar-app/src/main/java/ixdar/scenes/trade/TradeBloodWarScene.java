package ixdar.scenes.trade;

import ixdar.annotations.scene.SceneAnnotation;

/**
 * The trade game opened straight on the rival level, the market war, for its launch entry.
 */
@SceneAnnotation(id = "trade-blood-war")
public class TradeBloodWarScene extends TradeScene {
    public static final int BLOOD_WAR_LEVEL = 4;

    /**
     * Open the campaign at the rival level.
     */
    public TradeBloodWarScene() {
        super(BLOOD_WAR_LEVEL);
    }
}
