package unit.trade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.game.trade.TradeGame;
import ixdar.game.trade.economy.Company;
import ixdar.game.trade.economy.Facility;
import ixdar.game.trade.economy.Loop;
import ixdar.game.trade.economy.Market;
import ixdar.game.trade.economy.Outcome;
import ixdar.game.trade.level.TradeLevel;
import ixdar.game.trade.level.TradeLevelFile;
import ixdar.game.trade.level.TradeMapGenerator;
import ixdar.game.trade.map.Bridge;
import ixdar.game.trade.map.City;
import ixdar.game.trade.map.Place;
import ixdar.game.trade.map.Road;
import ixdar.game.trade.map.TradeMap;
import ixdar.game.trade.routes.Leg;
import ixdar.game.trade.routes.RouteNetwork;

/**
 * The trade game's rules on the campaign's own level files: generated maps, bridges, merit-order markets,
 * route edits with undo across knot layers, bases priced by distance, and small loops beating one big one.
 */
public class TradeGameTest {
    private static final double EPSILON = 1e-9;
    private static final String SMALL_LOOPS = "small_loops.ix";
    private static final String FAR_BASES = "far_bases.ix";
    private static final String HOME_LOOP = "Home Ashby Bramford";
    private static final String FARROW = "Farrow";
    private static final String FARROW_LOOP = "Farrow Elmet Cobham";
    private static final String GENERATED = "generated.ix";
    private static final String KINGSMOOR = "Kingsmoor";
    private static final String ELMET = "Elmet";
    private static final String COBHAM = "Cobham";
    private static final String BRAMFORD = "Bramford";

    /**
     * Read a campaign level file.
     *
     * @param name the file under {@code resources/trade}
     * @return the parsed level
     * @throws IOException when the file is missing
     */
    private static TradeLevel level(String name) throws IOException {
        return TradeLevelFile.read(Files.readString(Path.of("src/main/resources/trade", name)));
    }

    /**
     * A place on a game's map by name.
     *
     * @param game the game
     * @param name the place's name
     * @return the place
     */
    private static Place place(TradeGame game, String name) {
        if (name.equals(TradeMapGenerator.HEADQUARTERS_NAME_IN_LEVEL_FILE)) {
            return game.player.home;
        }
        for (Place place : game.map.places) {
            if (place.name.equals(name)) {
                return place;
            }
        }
        throw new IllegalArgumentException("no place " + name);
    }

    /**
     * Play a plan: optionally build a base, then run a loop through each list of places with a station in
     * every city on it, then end laps until the level is decided.
     *
     * @param file  the level file
     * @param base  a city to build a base in first, or empty
     * @param loops each loop's places, separated by spaces
     * @return the finished game
     * @throws IOException when the file is missing
     */
    private static TradeGame play(String file, String base, String... loops) throws IOException {
        TradeLevel level = level(file);
        level.loops.removeIf(loop -> loop.company().equals(level.companies.get(0).name()));
        TradeGame game = new TradeGame(level);
        if (!base.isEmpty()) {
            assertTrue(game.buildBase((City) place(game, base)));
        }
        for (String loop : loops) {
            String[] names = loop.split(" ");
            assertTrue(game.network.pipe(place(game, names[0]), place(game, names[1]), 0), game.network.lastRefusal);
            for (int k = 2; k < names.length; k++) {
                assertTrue(game.network.grow(place(game, names[0]), place(game, names[k]), 0),
                        game.network.lastRefusal);
            }
            for (String name : names) {
                if (place(game, name) instanceof City city && city.facility(game.player) == Facility.NONE) {
                    assertTrue(game.buyStation(city));
                }
            }
        }
        while (game.outcome == Outcome.PLAYING) {
            game.endLap();
        }
        return game;
    }

    /**
     * Every campaign file parses and builds, and home reaches every city over the grid.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void everyCampaignLevelBuildsAndHomeReachesEveryCity() throws IOException {
        for (String name : TradeLevel.CAMPAIGN) {
            TradeGame game = new TradeGame(level(name));
            assertFalse(game.map.towns.isEmpty(), name);
            assertFalse(game.map.roads.isEmpty(), name);
            for (City city : game.map.cities) {
                assertTrue(game.table.cost(game.player.home, city) < Leg.UNREACHABLE_COST_BEYOND_ANY_TANK,
                        name + " " + city.name);
            }
        }
    }

    /**
     * The same file and seed always generate the same towns, cities and roads.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void theGeneratorIsDeterministic() throws IOException {
        TradeMap first = TradeMapGenerator.build(level(GENERATED));
        TradeMap second = TradeMapGenerator.build(level(GENERATED));
        assertEquals(first.places.size(), second.places.size());
        for (int k = 0; k < first.places.size(); k++) {
            assertEquals(first.places.get(k).cell, second.places.get(k).cell);
            assertEquals(first.places.get(k).name, second.places.get(k).name);
        }
        assertEquals(first.roads.size(), second.roads.size());
        for (int k = 0; k < first.roads.size(); k++) {
            assertArrayEquals(first.roads.get(k).nodes, second.roads.get(k).nodes);
        }
        assertTrue(first.cities.size() > 1, "generated cities");
    }

    /**
     * A bridge is built on a side its river runs along, opens that one step, and the river closes others.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void aBridgeSpansOneSideOfItsRiver() throws IOException {
        TradeMap map = TradeMapGenerator.build(level(SMALL_LOOPS));
        assertEquals(2, map.bridges.size());
        for (Bridge bridge : map.bridges) {
            assertTrue(bridge.river.separates(bridge.fromCell, bridge.toCell));
            assertTrue(map.canStep(bridge.fromCell, bridge.toCell));
            assertTrue(bridge.sideLength > 0);
        }
        int closed = 0;
        for (int cell = 0; cell < map.grid.dualPoints().size(); cell++) {
            for (int neighbour : map.grid.neighbours(cell)) {
                closed += map.rivers.get(0).separates(cell, neighbour) && !map.canStep(cell, neighbour) ? 1 : 0;
            }
        }
        assertTrue(closed > 0, "the river closes the steps across it");
        for (Road road : map.roads) {
            for (int k = 1; k < road.nodes.length; k++) {
                assertTrue(map.canStep(road.nodes[k - 1], road.nodes[k]), "roads cross rivers only on bridges");
            }
        }
    }

    /**
     * Sellers fill demand cheapest first and the cheapest seller left with blood sets the price.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void meritOrderSellsCheapestFirstAndTheCheapestUnsoldSetsThePrice() throws IOException {
        TradeGame game = new TradeGame(level("blood_war.ix"));
        City city = (City) place(game, KINGSMOOR);
        city.demand = 60;
        city.ceiling = 2.0;
        game.player.offers.clear();
        game.rival.offers.clear();
        game.player.offer(city).add(40, 1.30);
        game.rival.offer(city).add(50, 1.45);
        Market.clear(city, List.of(game.player, game.rival));
        assertEquals(40, city.lastSold.get(game.player));
        assertEquals(20, city.lastSold.get(game.rival));
        assertEquals(1.45, city.lastPrice, EPSILON);
    }

    /**
     * Undo restores the network as it was before an edit, wagons included, and redo puts the edit back.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void undoAndRedoRestoreWholeSnapshots() throws IOException {
        TradeGame game = new TradeGame(level(SMALL_LOOPS));
        RouteNetwork network = game.network;
        assertEquals(1, game.player.loops.size());
        assertTrue(network.pipe(place(game, FARROW), place(game, ELMET), 0));
        assertTrue(network.grow(place(game, FARROW), place(game, COBHAM), 0));
        assertEquals(2, game.player.loops.size());
        assertEquals(3, network.loopOf(place(game, FARROW)).stops.length);
        assertTrue(network.undo());
        assertEquals(2, network.loopOf(place(game, FARROW)).stops.length);
        assertTrue(network.undo());
        assertEquals(1, game.player.loops.size());
        assertFalse(network.undo());
        assertTrue(network.redo());
        assertTrue(network.redo());
        assertEquals(3, network.loopOf(place(game, FARROW)).stops.length);
    }

    /**
     * Ending laps and building a base leave the undo history alone: an edit from two laps ago still undoes.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void undoReachesBackAcrossLapsAndPurchases() throws IOException {
        TradeGame game = new TradeGame(level(SMALL_LOOPS));
        RouteNetwork network = game.network;
        assertTrue(network.pipe(place(game, FARROW), place(game, ELMET), 0), network.lastRefusal);
        game.endLap();
        assertTrue(game.buildBase((City) place(game, COBHAM)));
        game.endLap();
        assertEquals(2, game.player.loops.size());
        assertTrue(network.undo(), "the pipe from before two laps and a base purchase still undoes");
        assertEquals(1, game.player.loops.size());
        assertTrue(network.redo());
        assertEquals(2, game.player.loops.size());
    }

    /**
     * Two places with no base and on no loop cannot start a loop, so no wagon appears that could never load.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void aPipeBetweenTwoUnconnectedPlacesIsRefused() throws IOException {
        TradeGame game = new TradeGame(level(SMALL_LOOPS));
        RouteNetwork network = game.network;
        int loops = game.player.loops.size();
        assertFalse(network.pipe(place(game, ELMET), place(game, COBHAM), 0));
        assertEquals(RouteNetwork.REFUSED_UNCONNECTED, network.lastRefusal);
        assertEquals(loops, game.player.loops.size());
        assertFalse(network.history.undoDepth > 0, "a refused pipe is not an edit");
        assertTrue(network.pipe(place(game, FARROW), place(game, ELMET), 0), "a base can start a loop");
    }

    /**
     * A loop runs exactly one wagon per base on it: a base built on a loop adds its wagon.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void aLoopRunsOneWagonPerBase() throws IOException {
        TradeGame game = new TradeGame(level(SMALL_LOOPS));
        Loop home = game.network.loopOf(game.player.home);
        assertEquals(1, home.wagons);
        assertTrue(game.network.pipe(place(game, FARROW), place(game, ELMET), 0));
        assertTrue(game.network.grow(place(game, FARROW), place(game, COBHAM), 0), game.network.lastRefusal);
        assertEquals(1, game.network.loopOf(place(game, FARROW)).wagons, "Farrow's loop runs Farrow's wagon");
        assertTrue(game.network.pipe(place(game, BRAMFORD), place(game, FARROW), 0), game.network.lastRefusal);
        Loop joined = game.network.loopOf(game.player.home);
        assertEquals(2, joined.wagons, "headquarters and Farrow");
        assertTrue(game.buildBase((City) place(game, ELMET)));
        assertEquals(3, game.network.loopOf(game.player.home).wagons);
        for (Loop loop : game.player.loops) {
            assertEquals(loop.baseCount(), loop.wagons);
        }
    }

    /**
     * Headquarters is drawn with a generated city name, not the level file's placeholder.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void headquartersHasAGeneratedName() throws IOException {
        for (String name : TradeLevel.CAMPAIGN) {
            TradeGame game = new TradeGame(level(name));
            assertNotEquals(TradeMapGenerator.HEADQUARTERS_NAME_IN_LEVEL_FILE, game.player.home.name, name);
            for (Place other : game.map.places) {
                assertTrue(other == game.player.home || !other.name.equals(game.player.home.name), name);
            }
        }
    }

    /**
     * Piping two loops makes a deeper layer; growing at that layer splits only the clicked knot's own legs,
     * so the pipe legs between the two knots stay as they were.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void growingAtALowerLayerLeavesTheParentsPipesAlone() throws IOException {
        TradeGame game = new TradeGame(level(SMALL_LOOPS));
        RouteNetwork network = game.network;
        assertTrue(network.pipe(place(game, FARROW), place(game, ELMET), 0));
        assertTrue(network.grow(place(game, FARROW), place(game, COBHAM), 0));
        assertEquals(0, network.deepestLayer());
        assertTrue(network.pipe(place(game, BRAMFORD), place(game, FARROW), 0), network.lastRefusal);
        assertEquals(1, game.player.loops.size());
        assertTrue(network.deepestLayer() >= 1);
        List<String> pipesBefore = pipeLegs(game, network);
        assertEquals(2, pipesBefore.size());
        assertNotEquals(network.knotAt(place(game, "Home"), 1), network.knotAt(place(game, FARROW), 1));
        List<Place> lone = new ArrayList<>();
        for (Place candidate : game.map.places) {
            if (network.knotOf(candidate).matchCount == 0) {
                lone.add(candidate);
            }
        }
        boolean grew = false;
        for (int k = 0; k < lone.size() && !grew; k++) {
            grew = network.grow(place(game, FARROW), lone.get(k), 1);
        }
        assertTrue(grew, "some lone place can join the Farrow knot");
        assertEquals(pipesBefore, pipeLegs(game, network));
    }

    /**
     * The legs of the player's loop that run between two different knots at layer 1.
     *
     * @param game    the game
     * @param network its route network
     * @return each such leg as "from-to" with the names sorted
     */
    private static List<String> pipeLegs(TradeGame game, RouteNetwork network) {
        List<String> legs = new ArrayList<>();
        for (Loop loop : game.player.loops) {
            for (int k = 0; k < loop.stops.length; k++) {
                Place from = loop.stops[k];
                Place to = loop.stops[(k + 1) % loop.stops.length];
                if (network.knotAt(from, 1) != network.knotAt(to, 1)) {
                    legs.add(from.name.compareTo(to.name) < 0 ? from.name + "-" + to.name : to.name + "-" + from.name);
                }
            }
        }
        legs.sort(null);
        return legs;
    }

    /**
     * A base can go anywhere, dearer the further it is from home, and home itself costs nothing.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void aBaseCostsMoreTheFurtherItIsFromHome() throws IOException {
        TradeGame game = new TradeGame(level(FAR_BASES));
        Company you = game.player;
        City near = (City) place(game, KINGSMOOR);
        City far = (City) place(game, ELMET);
        assertTrue(game.table.cost(you.home, near) < game.table.cost(you.home, far));
        assertTrue(game.economy.basePrice(you, near) < game.economy.basePrice(you, far));
        double gold = you.gold;
        assertTrue(game.buildBase(far));
        assertEquals(gold - game.economy.basePrice(you, far), you.gold, EPSILON);
        assertEquals(Facility.BASE, far.facility(you));
        assertFalse(game.buildBase(far), "already a base");
    }

    /**
     * One loop through every market runs at a loss; compact loops, one at each base, win the level.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void oneMegaLoopLosesWhereSmallLoopsWin() throws IOException {
        TradeGame mega = play(SMALL_LOOPS, "", "Home Ashby Bramford Farrow Elmet Garth Dorrin Cobham");
        assertEquals(Outcome.LOST, mega.outcome);
        assertTrue(mega.player.gold < mega.player.startGold);
        TradeGame small = play(SMALL_LOOPS, "", HOME_LOOP, FARROW_LOOP);
        assertEquals(Outcome.WON, small.outcome, small.outcomeText);
    }

    /**
     * On every level won on gold, the best single loop from home through one or two cities earns bronze
     * and no more, so silver and gold take a network of loops.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void oneSmallLoopEarnsBronzeAtBest() throws IOException {
        StringBuilder report = new StringBuilder();
        boolean balanced = true;
        for (String name : TradeLevel.CAMPAIGN) {
            TradeLevel level = level(name);
            if (level.medals.length == 0) {
                continue;
            }
            List<City> cities = new TradeGame(level).map.cities;
            double best = Double.NEGATIVE_INFINITY;
            String bestPlan = "";
            for (int a = 0; a < cities.size(); a++) {
                for (int b = a; b < cities.size(); b++) {
                    TradeLevel fresh = level(name);
                    fresh.loops.removeIf(loop -> loop.company().equals(fresh.companies.get(0).name()));
                    TradeGame game = new TradeGame(fresh);
                    Place home = game.player.home;
                    Place one = place(game, cities.get(a).name);
                    Place two = place(game, cities.get(b).name);
                    boolean built = one != home && two != home && game.network.pipe(home, one, 0)
                            && (two == one || game.network.grow(home, two, 0));
                    for (Place stop : new Place[] { one, two }) {
                        built &= !(stop instanceof City city && city.facility(game.player) == Facility.NONE
                                && !game.buyStation(city));
                    }
                    while (built && game.outcome == Outcome.PLAYING) {
                        game.endLap();
                    }
                    double earned = game.player.gold - game.player.startGold;
                    if (built && earned > best) {
                        best = earned;
                        bestPlan = one.name + " " + two.name;
                    }
                }
            }
            report.append(name).append(": best ").append(Math.round(best)).append(" by ").append(bestPlan)
                    .append(", medals ").append(Arrays.toString(level.medals)).append('\n');
            balanced &= best >= level.medals[0] && best < level.medals[1];
        }
        assertTrue(balanced, report.toString());
    }

    /**
     * Two loops, one at headquarters and one at the Farrow base, earn silver where one loop earns bronze.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void aNetworkOfLoopsEarnsSilver() throws IOException {
        TradeGame network = play(SMALL_LOOPS, "", HOME_LOOP, FARROW_LOOP);
        assertTrue(network.medal >= 2, network.outcomeText);
    }

    /**
     * Out of reach from home, the far markets pay only once a base out there refills the wagons.
     *
     * @throws IOException when a level file is missing
     */
    @Test
    public void aFarBaseOpensMarketsHomeCannotReach() throws IOException {
        TradeGame mega = play(FAR_BASES, "", "Home Ashby Kingsmoor Dorrin Elmet Farrow Bramford");
        assertTrue(mega.player.bankrupt, "a wagon that runs dry sells nothing");
        TradeGame near = play(FAR_BASES, "", HOME_LOOP);
        TradeGame far = play(FAR_BASES, "Dorrin", HOME_LOOP, "Dorrin Elmet Farrow");
        assertTrue(far.player.gold > near.player.gold);
        assertEquals(Outcome.WON, far.outcome, far.outcomeText);
        assertTrue(far.medal > near.medal);
    }
}
