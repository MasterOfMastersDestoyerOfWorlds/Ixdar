package unit.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.game.TradeGame;
import ixdar.game.TradeLevel;
import ixdar.game.TradeMap;
import ixdar.game.TradeMarket;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import unit.GlStandIn;

/**
 * The trade MVP's rules: merit-order clearing, grid routing, the per-loop cost, route operations with
 * undo, starving towns, bankruptcy and the share goal.
 */
public class TradeGameTest {
    private static final double EPSILON = 1e-9;
    private static final int TUTORIAL = 0;

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

    /** Sellers fill demand cheapest first and the last seller needed sets the price. */
    @Test
    public void meritOrderSellsCheapestFirstAndTheLastSellerNeededSetsThePrice() {
        int[] sold = new int[2];
        double price = new TradeMarket().clear(60, 2.0, new int[] { 40, 50 }, new double[] { 1.30, 1.45 },
                new double[] { -1, -1 }, sold);
        assertEquals(40, sold[0]);
        assertEquals(20, sold[1]);
        assertEquals(1.45, price, EPSILON);
    }

    /** When supply falls short of demand the town pays its ceiling. */
    @Test
    public void aShortMarketPaysTheCeiling() {
        int[] sold = new int[2];
        double price = new TradeMarket().clear(100, 2.0, new int[] { 40, 50 }, new double[] { 1.30, 1.45 },
                new double[] { -1, -1 }, sold);
        assertEquals(40, sold[0]);
        assertEquals(50, sold[1]);
        assertEquals(2.0, price, EPSILON);
    }

    /** A seller at war goes first, its price is everyone's, and covering demand shuts the rival out. */
    @Test
    public void aPriceWarGoesFirstAndSetsEveryonesPrice() {
        int[] sold = new int[2];
        double price = new TradeMarket().clear(60, 2.0, new int[] { 40, 50 }, new double[] { 1.50, 1.20 },
                new double[] { 1.50, -1 }, sold);
        assertEquals(40, sold[0]);
        assertEquals(20, sold[1]);
        assertEquals(1.50, price, EPSILON);

        double war = new TradeMarket().clear(60, 2.0, new int[] { 70, 50 }, new double[] { 1.50, 1.20 },
                new double[] { 0.75, -1 }, sold);
        assertEquals(60, sold[0]);
        assertEquals(0, sold[1], "covering all of D at the war price leaves the rival nothing");
        assertEquals(0.75, war, EPSILON);
    }

    /** Every route steps cell to cell, avoids the moor, crosses only at bridges and prices off-road double. */
    @Test
    public void routesStepBetweenNeighboursAroundBlockedCellsAndPriceOffRoadDouble() {
        TradeGame game = new TradeGame(TradeLevel.campaign().get(TradeLevel.RIVAL_LEVEL_INDEX));
        TradeMap map = game.map;
        for (int a = 0; a < game.cityCount; a++) {
            for (int b = 0; b < game.cityCount; b++) {
                int[] path = game.legPath[a][b];
                int cost = 0;
                for (int k = 1; k < path.length; k++) {
                    int from = path[k - 1], to = path[k];
                    boolean neighbours = false;
                    for (int n : map.cellNeighbours[from]) {
                        neighbours |= n == to;
                    }
                    assertTrue(neighbours, "a route steps between neighbouring cells");
                    assertFalse(map.cellBlocked[to], "a route never enters the moor");
                    assertFalse(map.riverCrossings.contains(map.edgeKey(from, to)), "a route crosses only at bridges");
                    cost += map.isRoad(from, to) ? TradeMap.ON_ROAD_COST : TradeMap.OFF_ROAD_COST;
                }
                assertEquals(cost, game.legCost[a][b]);
                assertEquals(game.legCost[a][b], game.legCost[b][a]);
            }
        }
        assertFalse(map.roads.isEmpty(), "the level starts with roads");
        assertEquals(2, map.bridges.size());
    }

    /** A loop's cost per unit is harvest on burned and sold blood plus upkeep, over units sold. */
    @Test
    public void aLoopsCostPerUnitIsHarvestOnBurnedAndSoldPlusUpkeepOverSold() {
        TradeGame game = new TradeGame(TradeLevel.campaign().get(TradeLevel.RIVAL_LEVEL_INDEX));
        assertEquals(1, game.loopCities.size(), "the rival level starts with one loop");
        int burn = game.loopBurn.get(0);
        int cargo = game.loopCargo.get(0);
        int wagons = game.loopWagons.get(0);
        assertTrue(cargo > 0, "the starting loop sells");
        double expected = (TradeGame.HARVEST_COST * (burn * wagons + cargo) + TradeGame.STATION_UPKEEP * 3
                + TradeGame.WAGON_UPKEEP * wagons) / cargo;
        assertEquals(expected, game.loopUnitCost.get(0), EPSILON);
        int ashby = 1;
        assertEquals(expected, game.unitCost[TradeGame.PLAYER][ashby], EPSILON);
    }

    /** Pipe starts a loop, grow extends it, undo replays back, and a loop cannot pipe to itself. */
    @Test
    public void pipeGrowAndUndoEditTheLoopThroughKnots() {
        TradeGame game = new TradeGame(TradeLevel.campaign().get(TUTORIAL));
        assertTrue(game.loopCities.isEmpty());
        assertTrue(game.pipe(0, 1), game.lastRefusal);
        assertEquals(2, game.loopCities.get(0).length, "pipe from home starts an out-and-back loop");
        game.computeGrowTargets(0);
        assertFalse(game.growCity.isEmpty(), "grow offers the remaining towns");
        int target = game.growCity.get(0);
        assertTrue(game.grow(0, target), game.lastRefusal);
        assertEquals(3, game.loopCities.get(0).length);
        assertEquals(0, game.loopCities.get(0)[0], "the loop is listed from the home base");
        game.undo();
        assertEquals(2, game.loopCities.get(0).length, "undo replays the operations before the last");
        assertFalse(game.pipe(0, 1), "both cities already share a loop");
    }

    /**
     * Piping two loops joins them into one loop under a new knot, keeping their wagons, and collapsing
     * a chain either keeps one whole loop or is refused with the network unchanged.
     */
    @Test
    public void pipeJoinsTwoLoopsUnderANewKnotAndCollapseKeepsOneLoop() {
        TradeGame game = new TradeGame(TradeLevel.campaign().get(2));
        int cobham = 3, dorrin = 4, elmet = 5, farrow = 6, ashby = 1;
        assertTrue(game.pipe(cobham, dorrin), game.lastRefusal);
        assertEquals(2, game.loopCities.size(), "two lone towns start a second loop");
        assertTrue(game.pipe(ashby, cobham), game.lastRefusal);
        assertEquals(1, game.loopCities.size(), "piping the loops joins them");
        assertEquals(5, game.loopCities.get(0).length);
        assertEquals(2, game.loopWagons.get(0), "the joined loop keeps both loops' wagons");
        assertTrue(game.top(game.knotOf(0)).knotPoints.size() >= 2, "the join is a new level of the knot");

        assertTrue(game.pipe(elmet, farrow), game.lastRefusal);
        assertTrue(game.pipe(dorrin, elmet) || game.pipe(cobham, farrow), game.lastRefusal);
        assertEquals(1, game.loopCities.size());
        int before = game.loopCities.get(0).length;
        int operations = game.operations.size();
        boolean collapsed = game.collapse(0, farrow);
        assertEquals(1, game.loopCities.size(), "collapse leaves one loop");
        assertEquals(before, game.loopCities.get(0).length, "collapse keeps every city");
        assertEquals(collapsed ? operations + 1 : operations, game.operations.size());
    }

    /** A town left unserved loses a quarter of its shortfall and pays a higher ceiling next lap. */
    @Test
    public void unmetDemandShrinksTheTownAndRaisesItsCeiling() {
        TradeGame game = new TradeGame(TradeLevel.campaign().get(TUTORIAL));
        int city = 1;
        int before = game.demand[city];
        double ceiling = game.ceiling[city];
        game.endLap();
        assertEquals(before - (int) Math.ceil(before * TradeGame.DEMAND_SHRINK_FRACTION), game.demand[city]);
        assertEquals(ceiling * TradeGame.CEILING_RISE, game.ceiling[city], EPSILON);
    }

    /** Three laps below zero gold bankrupts the player and loses the level. */
    @Test
    public void threeLapsBelowZeroGoldIsBankruptcy() {
        TradeGame game = new TradeGame(TradeLevel.campaign().get(TradeLevel.RIVAL_LEVEL_INDEX));
        game.gold[TradeGame.PLAYER] = -100000;
        for (int lap = 0; lap < TradeGame.BANKRUPT_LAPS; lap++) {
            assertEquals(TradeGame.PLAYING, game.outcome);
            game.endLap();
        }
        assertTrue(game.bankrupt[TradeGame.PLAYER]);
        assertEquals(TradeGame.LOST, game.outcome);
    }

    /** Selling at least the share goal of all demand wins the rival level. */
    @Test
    public void holdingSixtyPercentOfDemandWinsTheRivalLevel() {
        TradeGame game = new TradeGame(TradeLevel.campaign().get(TradeLevel.RIVAL_LEVEL_INDEX));
        for (int city = 0; city < game.cityCount; city++) {
            if (!game.station[TradeGame.PLAYER][city]) {
                game.demand[city] = 0;
            }
        }
        game.endLap();
        assertEquals(TradeGame.WON, game.outcome, game.outcomeText);
    }

    /** The rival level opens with the rival ahead in the contested city, so the player has to act. */
    @Test
    public void theRivalLevelOpensWithTheRivalAheadInKingsmoor() {
        TradeGame game = new TradeGame(TradeLevel.campaign().get(TradeLevel.RIVAL_LEVEL_INDEX));
        int kingsmoor = 3;
        game.endLap();
        assertTrue(game.lastSold[TradeGame.RIVAL][kingsmoor] > game.lastSold[TradeGame.PLAYER][kingsmoor]);
        assertTrue(game.lastProfit[TradeGame.PLAYER] > 0, "the starting loop pays its way");
    }

    /**
     * A plain plan wins every tutorial: one loop from home through every town it can grow to, a
     * station at each, then end laps to the limit. The medals are tuned against this plan.
     */
    @Test
    public void aPlainPlanEarnsAMedalOnEveryTutorial() {
        for (int index = 0; index < TradeLevel.RIVAL_LEVEL_INDEX; index++) {
            TradeGame game = new TradeGame(TradeLevel.campaign().get(index));
            if (game.loopCities.isEmpty()) {
                game.pipe(0, 1);
            }
            for (int city = 1; city < game.cityCount; city++) {
                game.computeGrowTargets(0);
                int at = game.growCity.indexOf(city);
                if (at >= 0 && game.growDeficit.get(at) <= 0) {
                    game.grow(0, city);
                }
            }
            for (int city : game.loopCities.get(0)) {
                game.buyStation(city);
            }
            while (game.outcome == TradeGame.PLAYING) {
                game.endLap();
            }
            assertEquals(TradeGame.WON, game.outcome, game.level.title + ": " + game.outcomeText);
            assertTrue(game.medal >= 2, game.level.title + " earns at least silver: " + game.outcomeText);
        }
    }

    /**
     * A plain plan wins the rival level inside its lap limit: grow to the free western towns, buy their
     * stations, add a wagon whenever gold allows, and start a price war in Kingsmoor.
     */
    @Test
    public void aPlainPlanWinsTheRivalLevelInsideTheLapLimit() {
        TradeGame game = new TradeGame(TradeLevel.campaign().get(TradeLevel.RIVAL_LEVEL_INDEX));
        int kingsmoor = 3;
        int wagonBudget = 300;
        for (int city : new int[] { 2, 6, 5, 7 }) {
            game.grow(0, city);
        }
        game.priceWar[kingsmoor] = TradeGame.WAR_AT_COST;
        while (game.outcome == TradeGame.PLAYING) {
            for (int city : game.loopCities.get(0)) {
                game.buyStation(city);
            }
            if (game.gold[TradeGame.PLAYER] > wagonBudget) {
                game.buyWagon(0);
            }
            game.endLap();
        }
        assertEquals(TradeGame.WON, game.outcome, game.outcomeText);
        assertTrue(game.lastSold[TradeGame.PLAYER][kingsmoor] > game.lastSold[TradeGame.RIVAL][kingsmoor],
                "the price war takes Kingsmoor");
    }

    /** Every level builds, and a level's starting loop comes out whole. */
    @Test
    public void everyLevelBuildsWithItsStartingLoopIntact() {
        List<TradeLevel> levels = TradeLevel.campaign();
        for (TradeLevel level : levels) {
            TradeGame game = new TradeGame(level);
            assertNotNull(game.map);
            if (level.playerLoop.length > 0) {
                assertEquals(1, game.loopCities.size(), level.title);
                assertEquals(level.playerLoop.length, game.loopCities.get(0).length, level.title);
            }
        }
    }
}
