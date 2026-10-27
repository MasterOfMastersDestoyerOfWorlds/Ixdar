package unit.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.game.TradeGame;
import ixdar.game.TradeLevel;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import unit.GlStandIn;

/**
 * Undoing route operations one by one and redoing every one brings back the loop, city for city.
 */
class RoutePlanningUndoRedoTest {

    private static final int FUEL_TUTORIAL = 1;

    private Platform suitePlatform;

    private GL suiteGl;

    /**
     * Pair the suite's platform with a GL that does nothing, since cities build their draw shapes.
     */
    @BeforeEach
    void installGlStandIn() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platforms.init(suitePlatform, GlStandIn.create());
    }

    /**
     * Put the suite's GL back.
     */
    @AfterEach
    void restoreSuiteGl() {
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void undoAllThenRedoAllRestoresTheLoopCityForCity() {
        TradeGame game = new TradeGame(TradeLevel.campaign().get(FUEL_TUTORIAL));
        int startingOperations = game.operations.size();
        for (int city = 2; city < game.cityCount; city++) {
            game.grow(0, city);
        }
        int grown = game.operations.size() - startingOperations;
        assertTrue(grown > 0, "at least one town joins the loop");
        int[] full = game.loopCities.get(0).clone();
        int[] sizes = new int[grown + 1];
        for (int undo = grown; undo >= 0; undo--) {
            sizes[undo] = game.loopCities.get(0).length;
            if (undo > 0) {
                game.undo();
            }
        }
        assertEquals(2, game.loopCities.get(0).length, "undoing every grow leaves the starting loop");
        for (int redo = 1; redo <= grown; redo++) {
            game.redo();
            assertEquals(sizes[redo], game.loopCities.get(0).length);
        }
        assertEquals(full.length, game.loopCities.get(0).length);
        for (int k = 0; k < full.length; k++) {
            assertEquals(full[k], game.loopCities.get(0)[k], "redo rebuilds the same loop in the same order");
        }
    }
}
