package ixdar.scenes.trade;

import ixdar.annotations.scene.SceneAnnotation;

/**
 * The trade game opened straight on the generated level, whose cities and towns the map generator
 * places, for its launch entry.
 */
@SceneAnnotation(id = "trade-generated")
public class TradeGeneratedScene extends TradeScene {
    public static final int GENERATED_LEVEL = 5;

    /**
     * Open the campaign at the generated level.
     */
    public TradeGeneratedScene() {
        super(GENERATED_LEVEL);
    }
}
