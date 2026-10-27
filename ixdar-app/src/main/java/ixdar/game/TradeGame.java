package ixdar.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

import ixdar.geometry.grid2d.IrregularQuadGrid;
import ixdar.geometry.tsp.knot.Knot;
import ixdar.geometry.tsp.knot.Segment;
import ixdar.geometry.tsp.points.PointND;
import ixdar.geometry.tsp.shell.DistanceMatrix;

/**
 * One game of the trade MVP: the level's map, the player's loops as knots, the rival's fixed loops and
 * the lap economy. Route edits are an operation list, undone by replaying it.
 */
public class TradeGame {
    public static final int PLAYER = 0;
    public static final int RIVAL = 1;
    public static final int COMPANY_COUNT = 2;
    public static final int PLAYING = 0;
    public static final int WON = 1;
    public static final int LOST = 2;
    public static final int WAR_OFF = 0;
    public static final int WAR_AT_COST = 1;
    public static final int WAR_HALF_COST = 2;
    public static final int WAR_MODE_COUNT = 3;
    public static final int OPERATION_PIPE_NEW = 0;
    public static final int OPERATION_GROW = 1;
    public static final int OPERATION_JOIN = 2;
    public static final int OPERATION_COLLAPSE = 3;
    public static final double HARVEST_COST = 1.0;
    public static final int STATION_PRICE = 50;
    public static final int STATION_UPKEEP = 6;
    public static final int WAGON_PRICE = 90;
    public static final int WAGON_UPKEEP = 4;
    public static final int BASE_PRICE = 220;
    public static final int BASE_PRODUCTION = 600;
    public static final int BANKRUPT_LAPS = 3;
    public static final int DOMINANCE_LAPS_FOR_BASE = 2;
    public static final int RIVAL_WITHDRAW_LAPS = 3;
    public static final double DEMAND_SHRINK_FRACTION = 0.25;
    public static final double CEILING_RISE = 1.1;
    public static final double CEILING_RELAX = 0.97;
    public static final double CEILING_MAX_FACTOR = 1.6;
    public static final int MINIMUM_DEMAND = 5;
    public static final int MEDAL_GOLD = 3;
    public static final int PERCENT = 100;
    public static final String REFUSED_CROSSING = "that would cross one of your loops";
    public static final String REFUSED_SAME_LOOP = "both cities are already on that loop";
    public static final String REFUSED_NOT_ON_LOOP = "pick a city on one of your loops";

    public final TradeLevel level;
    public final TradeMap map;
    public final CityNetwork network;
    public final int cityCount;
    public final int[] cityCell;
    /** Blood burned between two cities: the cheapest grid route's cost in cells. */
    public final int[][] legCost;
    /** The cells of the cheapest grid route between two cities. */
    public final int[][][] legPath;

    public final int[] demand;
    public final int[] baseDemand;
    public final double[] ceiling;
    public final boolean[][] station;
    public final boolean[][] base;
    public final int[] priceWar;
    public final int[] dominantLaps;
    public final int[] rivalLossLaps;
    public final boolean[] rivalEverSold;

    public final double[] gold = new double[COMPANY_COUNT];
    public final int[] lapsBelowZero = new int[COMPANY_COUNT];
    public final boolean[] bankrupt = new boolean[COMPANY_COUNT];
    public final double[] lastProfit = new double[COMPANY_COUNT];

    /** Units each company offered and sold in each city on the last settlement or estimate. */
    public final int[][] offered;
    public final int[][] lastSold;
    /** Each company's cost per unit in each city, from the loops that serve it. */
    public final double[][] unitCost;
    public final double[] lastPrice;
    /** Gold each city paid the player on the last lap, for the end-of-lap animation. */
    public final double[] lastRevenue;

    /** The route operations so far, each a kind followed by the cities it names. */
    public final ArrayList<int[]> operations = new ArrayList<>();
    /** Undone operations, latest last, which redo brings back. */
    public final ArrayList<int[]> undone = new ArrayList<>();
    public String lastRefusal = "";

    public final ArrayList<int[]> loopCities = new ArrayList<>();
    public final ArrayList<Knot> loopTop = new ArrayList<>();
    public final ArrayList<Integer> loopWagons = new ArrayList<>();
    public final ArrayList<Integer> loopBurn = new ArrayList<>();
    public final ArrayList<Integer> loopCargo = new ArrayList<>();
    public final ArrayList<Integer> loopFuelDeficit = new ArrayList<>();
    public final ArrayList<Double> loopUnitCost = new ArrayList<>();
    public final ArrayList<Boolean> loopHasBase = new ArrayList<>();

    public final ArrayList<Integer> growCity = new ArrayList<>();
    public final ArrayList<Integer> growDelta = new ArrayList<>();
    public final ArrayList<Integer> growDeficit = new ArrayList<>();

    public int lap = 1;
    public int outcome = PLAYING;
    public int medal;
    public String outcomeText = "";

    private final HashMap<Knot, Integer> wagonsByTop = new HashMap<>();
    private final HashMap<Integer, Knot> previousTopOfCity = new HashMap<>();
    private final TradeMarket market = new TradeMarket();

    /**
     * Build a level: its terrain and roads, its cities, the route costs between every pair, the player's
     * starting loop and stations, and the rival's network.
     *
     * @param level the level to play
     */
    public TradeGame(TradeLevel level) {
        this.level = level;
        cityCount = level.cityNames.length;
        map = new TradeMap(level.seed, TradeLevel.MAP_WIDTH, TradeLevel.MAP_HEIGHT, TradeLevel.TARGET_CELLS);
        map.addMoor(level.moorX, level.moorY, level.moorRadiusX, level.moorRadiusY);
        if (level.riverX.length > 1) {
            map.addRiver(level.riverX, level.riverY, level.bridgeYs);
        }
        cityCell = new int[cityCount];
        ArrayList<City> cities = new ArrayList<>();
        for (int city = 0; city < cityCount; city++) {
            cityCell[city] = map.cellAt(level.cityX[city], level.cityY[city]);
            cities.add(new City(Integer.toString(city), level.cityNames[city], map.cellX[cityCell[city]],
                    map.cellY[cityCell[city]], level.cityDemand[city]));
        }
        map.layRoads(cityCell, level.villagesToPlace);
        network = new CityNetwork(cities,
                new IrregularQuadGrid(map.cornerPoints, level.seed, TradeMap.RELAX_ITERATIONS, 0, 0));
        network.setHeadquarters(cities.get(level.playerHome));

        legCost = new int[cityCount][cityCount];
        legPath = new int[cityCount][cityCount][];
        for (int a = 0; a < cityCount; a++) {
            for (int b = a; b < cityCount; b++) {
                int[] path = map.route(cityCell[a], cityCell[b]);
                if (path == null) {
                    path = new int[] { cityCell[a], cityCell[b] };
                }
                int[] reversed = new int[path.length];
                for (int k = 0; k < path.length; k++) {
                    reversed[k] = path[path.length - 1 - k];
                }
                legPath[a][b] = path;
                legPath[b][a] = reversed;
                legCost[a][b] = map.routeCost(path);
                legCost[b][a] = legCost[a][b];
            }
        }

        demand = level.cityDemand.clone();
        baseDemand = level.cityDemand.clone();
        ceiling = level.cityCeiling.clone();
        station = new boolean[COMPANY_COUNT][cityCount];
        base = new boolean[COMPANY_COUNT][cityCount];
        priceWar = new int[cityCount];
        dominantLaps = new int[cityCount];
        rivalLossLaps = new int[cityCount];
        rivalEverSold = new boolean[cityCount];
        offered = new int[COMPANY_COUNT][cityCount];
        lastSold = new int[COMPANY_COUNT][cityCount];
        unitCost = new double[COMPANY_COUNT][cityCount];
        lastPrice = new double[cityCount];
        lastRevenue = new double[cityCount];

        base[PLAYER][level.playerHome] = true;
        for (int city : level.playerStations) {
            station[PLAYER][city] = true;
        }
        gold[PLAYER] = level.startGold;
        if (level.rivalHome != TradeLevel.NO_RIVAL) {
            base[RIVAL][level.rivalHome] = true;
            for (int city : level.rivalStations) {
                station[RIVAL][city] = true;
            }
            gold[RIVAL] = level.rivalStartGold;
        }

        replay();
        if (level.playerLoop.length >= 2) {
            pipe(level.playerLoop[0], level.playerLoop[1]);
            for (int k = 2; k < level.playerLoop.length; k++) {
                grow(level.playerLoop[0], level.playerLoop[k]);
            }
        }
        estimate();
    }

    /**
     * Pipe two cities: two lone cities start an out-and-back loop, a lone city and a loop grow the loop,
     * and two loops join by cutting one segment of each and bridging the four ends at the least cost.
     *
     * @param cityA the first city clicked
     * @param cityB the second city clicked
     * @return true when the network changed; otherwise {@link #lastRefusal} says why
     */
    public boolean pipe(int cityA, int cityB) {
        Knot a = knotOf(cityA);
        Knot b = knotOf(cityB);
        boolean aOnLoop = a.matchCount > 0;
        boolean bOnLoop = b.matchCount > 0;
        if (!aOnLoop && !bOnLoop) {
            return apply(new int[] { OPERATION_PIPE_NEW, cityA, cityB });
        }
        if (aOnLoop != bOnLoop) {
            return grow(aOnLoop ? cityA : cityB, aOnLoop ? cityB : cityA);
        }
        if (top(a) == top(b)) {
            lastRefusal = REFUSED_SAME_LOOP;
            return false;
        }
        int[] best = null;
        int bestDelta = Integer.MAX_VALUE;
        ArrayList<Segment> segmentsA = segmentsOfLoop(cityA);
        ArrayList<Segment> segmentsB = segmentsOfLoop(cityB);
        for (Segment cutA : segmentsA) {
            for (Segment cutB : segmentsB) {
                int firstA = cityOf(cutA.first), lastA = cityOf(cutA.last);
                int firstB = cityOf(cutB.first), lastB = cityOf(cutB.last);
                int delta = legCost[firstA][firstB] + legCost[lastA][lastB] - legCost[firstA][lastA]
                        - legCost[firstB][lastB];
                if (delta < bestDelta && !crossesLoops(firstA, firstB, cutA, cutB)
                        && !crossesLoops(lastA, lastB, cutA, cutB)) {
                    bestDelta = delta;
                    best = new int[] { OPERATION_JOIN, firstA, lastA, firstB, lastB };
                }
            }
        }
        if (best == null) {
            lastRefusal = REFUSED_CROSSING;
            return false;
        }
        return apply(best);
    }

    /**
     * Grow the loop through {@code loopCity} by a lone city, inserted at the cheapest segment whose new
     * legs cross none of the player's loops.
     *
     * @param loopCity any city on the loop
     * @param newCity  the lone city to add
     * @return true when the loop grew; otherwise {@link #lastRefusal} says why
     */
    public boolean grow(int loopCity, int newCity) {
        if (knotOf(loopCity).matchCount == 0) {
            lastRefusal = REFUSED_NOT_ON_LOOP;
            return false;
        }
        if (knotOf(newCity).matchCount > 0) {
            lastRefusal = "that city is already on a loop; pipe the two loops instead";
            return false;
        }
        Segment cut = cheapestGrowSegment(loopCity, newCity);
        if (cut == null) {
            lastRefusal = REFUSED_CROSSING;
            return false;
        }
        return apply(new int[] { OPERATION_GROW, newCity, cityOf(cut.first), cityOf(cut.last) });
    }

    /**
     * Collapse the chain of knots between the knots holding two cities into a higher knot: one pipe
     * segment of each link is dropped and the chain's ends are joined.
     *
     * @param startCity a city in the knot the chain starts at
     * @param endCity   a city in the knot it ends at
     * @return true when the knot changed; otherwise {@link #lastRefusal} says why
     */
    public boolean collapse(int startCity, int endCity) {
        Knot startKnot = knotOf(startCity);
        Knot endKnot = knotOf(endCity);
        if (startKnot.matchCount == 0 || endKnot.matchCount == 0 || top(startKnot) != top(endKnot)) {
            lastRefusal = "pick two cities in different knots of one piped network";
            return false;
        }
        Knot topKnot = top(startKnot);
        if (childOf(topKnot, startKnot) == childOf(topKnot, endKnot)) {
            lastRefusal = "those cities are in the same knot; pick the two ends of a chain";
            return false;
        }
        return apply(new int[] { OPERATION_COLLAPSE, startCity, endCity });
    }

    /**
     * Undo the last route operation by replaying the ones before it.
     */
    public void undo() {
        if (!operations.isEmpty()) {
            undone.add(operations.remove(operations.size() - 1));
            replay();
            estimate();
        }
    }

    /**
     * Redo the last undone route operation; any new operation clears what can be redone.
     */
    public void redo() {
        if (!undone.isEmpty()) {
            operations.add(undone.remove(undone.size() - 1));
            replay();
            estimate();
        }
    }

    /**
     * Apply a new operation, keep it only if every city on a loop still walks round a whole loop and no
     * two legs cross, and refresh the loops and the lap estimate.
     *
     * @param operation the operation's kind and cities
     * @return true when kept
     */
    private boolean apply(int[] operation) {
        operations.add(operation);
        boolean valid = true;
        try {
            perform(operation);
            HashSet<Integer> seen = new HashSet<>();
            for (int city = 0; city < cityCount && valid; city++) {
                Knot k = knotOf(city);
                if (k.matchCount == 0 || seen.contains(city)) {
                    continue;
                }
                int[] order = k.s1 == null || k.s2 == null ? null : walk(city);
                if (order == null) {
                    valid = false;
                } else {
                    for (int member : order) {
                        seen.add(member);
                    }
                }
            }
            for (int city = 0; city < cityCount && valid; city++) {
                valid = knotOf(city).matchCount == 0 || seen.contains(city);
            }
            if (valid) {
                refreshLoops();
                ArrayList<int[]> legs = new ArrayList<>();
                for (int[] order : loopCities) {
                    for (int k = 0; k < order.length; k++) {
                        legs.add(new int[] { order[k], order[(k + 1) % order.length] });
                    }
                }
                for (int i = 0; i < legs.size() && valid; i++) {
                    for (int j = i + 1; j < legs.size() && valid; j++) {
                        valid = !legsCross(legs.get(i)[0], legs.get(i)[1], legs.get(j)[0], legs.get(j)[1]);
                    }
                }
            }
        } catch (RuntimeException failure) {
            valid = false;
        }
        if (!valid) {
            operations.remove(operations.size() - 1);
            replay();
            lastRefusal = REFUSED_CROSSING;
            estimate();
            return false;
        }
        lastRefusal = "";
        undone.clear();
        estimate();
        return true;
    }

    /**
     * Rebuild every knot from single cities and redo the operation list; route costs are written into the
     * fresh shell's distance matrix so the knots price edges in grid cells.
     */
    private void replay() {
        network.resetTradeRoutes();
        DistanceMatrix distances = network.getTradeShell().distanceMatrix;
        ArrayList<PointND> points = distances.getPoints();
        double[][] matrix = distances.getMatrix();
        for (int i = 0; i < points.size(); i++) {
            for (int j = 0; j < points.size(); j++) {
                matrix[i][j] = legCost[points.get(i).getID()][points.get(j).getID()];
            }
        }
        for (int[] operation : operations) {
            perform(operation);
        }
    }

    /**
     * Carry out one operation on the knots.
     *
     * @param operation the operation's kind and cities
     */
    private void perform(int[] operation) {
        switch (operation[0]) {
        case OPERATION_PIPE_NEW:
            Knot.pipeSimple(knotOf(operation[1]), knotOf(operation[2]));
            break;
        case OPERATION_GROW: {
            Segment cut = segmentBetween(operation[2], operation[3]);
            Knot owner = deepestOwner(top(cut.first), cut);
            Knot singleton = knotOf(operation[1]);
            owner.grow(singleton, cut);
            Segment added1 = singleton.s1;
            Segment added2 = singleton.s2;
            for (Knot ancestor = owner.topGroupKnot; ancestor != null; ancestor = ancestor.topGroupKnot) {
                if (ancestor.manifoldSegments.removeIf(s -> s == cut)) {
                    ancestor.manifoldSegments.add(added1);
                    ancestor.manifoldSegments.add(added2);
                }
                ancestor.knotPointsFlattened.add(singleton);
            }
            break;
        }
        case OPERATION_JOIN: {
            Segment cutA = segmentBetween(operation[1], operation[2]);
            Segment cutB = segmentBetween(operation[3], operation[4]);
            Knot.pipe(top(cutA.first), top(cutB.first), cutA, cutB);
            break;
        }
        case OPERATION_COLLAPSE: {
            Knot startKnot = knotOf(operation[1]);
            Knot topKnot = top(startKnot);
            topKnot.collapse(childOf(topKnot, startKnot), childOf(topKnot, knotOf(operation[2])));
            break;
        }
        default:
            throw new IllegalArgumentException("unknown route operation " + operation[0]);
        }
    }

    /**
     * The cheapest segment of the loop through {@code loopCity} to insert {@code newCity} into without
     * crossing a loop.
     *
     * @param loopCity a city on the loop
     * @param newCity  the city to insert
     * @return the segment to cut, or null when every insertion crosses
     */
    private Segment cheapestGrowSegment(int loopCity, int newCity) {
        Segment best = null;
        int bestDelta = Integer.MAX_VALUE;
        for (Segment cut : segmentsOfLoop(loopCity)) {
            int first = cityOf(cut.first), last = cityOf(cut.last);
            int delta = legCost[first][newCity] + legCost[newCity][last] - legCost[first][last];
            if (delta < bestDelta && !crossesLoops(first, newCity, cut, null) && !crossesLoops(newCity, last, cut,
                    null)) {
                bestDelta = delta;
                best = cut;
            }
        }
        return best;
    }

    /**
     * Work out, for the loop through {@code loopCity}, every lone city it could grow to: the cells it adds
     * and how far it would leave the wagon short of fuel.
     *
     * @param loopCity a city on the loop
     */
    public void computeGrowTargets(int loopCity) {
        growCity.clear();
        growDelta.clear();
        growDeficit.clear();
        int loopIndex = loopIndexOf(loopCity);
        if (loopIndex < 0) {
            return;
        }
        int[] order = loopCities.get(loopIndex);
        for (int city = 0; city < cityCount; city++) {
            if (knotOf(city).matchCount > 0) {
                continue;
            }
            Segment cut = cheapestGrowSegment(loopCity, city);
            if (cut == null) {
                continue;
            }
            int first = cityOf(cut.first), last = cityOf(cut.last);
            int[] grown = new int[order.length + 1];
            int write = 0;
            for (int k = 0; k < order.length; k++) {
                grown[write++] = order[k];
                int next = order[(k + 1) % order.length];
                if (order[k] == first && next == last && write == k + 1) {
                    grown[write++] = city;
                }
            }
            if (write < grown.length) {
                grown[write] = city;
            }
            growCity.add(city);
            growDelta.add(legCost[first][city] + legCost[city][last] - legCost[first][last]);
            growDeficit.add(longestStretch(grown) - level.wagonCapacity);
        }
    }

    /**
     * The singleton knot of a city.
     *
     * @param city a city index
     * @return its knot
     */
    public Knot knotOf(int city) {
        return network.getKnotForCity(network.cities.get(city));
    }

    /**
     * The city a singleton knot stands for.
     *
     * @param knot a singleton knot
     * @return its city index
     */
    public int cityOf(Knot knot) {
        return knot.p.getID();
    }

    /**
     * The outermost knot holding a knot.
     *
     * @param knot any knot
     * @return the top of its hierarchy
     */
    public Knot top(Knot knot) {
        Knot current = knot;
        while (current.topGroupKnot != null) {
            current = current.topGroupKnot;
        }
        return current;
    }

    private Knot childOf(Knot topKnot, Knot knot) {
        Knot current = knot;
        while (current.topGroupKnot != null && current.topGroupKnot != topKnot) {
            current = current.topGroupKnot;
        }
        return current;
    }

    private Knot deepestOwner(Knot knot, Segment segment) {
        for (Knot child : knot.knotPoints) {
            if (!child.isSingleton() && child != knot) {
                Knot found = deepestOwner(child, segment);
                if (found != null) {
                    return found;
                }
            }
        }
        for (Segment s : knot.manifoldSegments) {
            if (s == segment) {
                return knot;
            }
        }
        return knot.topGroupKnot == null ? knot : null;
    }

    /**
     * The live segment running from one city to the next, preferring that exact direction.
     *
     * @param first the segment's first city
     * @param last  its last city
     * @return the segment
     */
    private Segment segmentBetween(int first, int last) {
        Knot a = knotOf(first);
        Knot b = knotOf(last);
        for (Segment s : new Segment[] { a.s1, a.s2 }) {
            if (s != null && s.first == a && s.last == b) {
                return s;
            }
        }
        for (Segment s : new Segment[] { a.s1, a.s2 }) {
            if (s != null && s.getOther(a) == b) {
                return s;
            }
        }
        throw new IllegalStateException("no segment between " + first + " and " + last);
    }

    private ArrayList<Segment> segmentsOfLoop(int loopCity) {
        ArrayList<Segment> segments = new ArrayList<>();
        int loopIndex = loopIndexOf(loopCity);
        if (loopIndex < 0) {
            return segments;
        }
        for (int city : loopCities.get(loopIndex)) {
            Knot k = knotOf(city);
            for (Segment s : new Segment[] { k.s1, k.s2 }) {
                if (s != null && segments.stream().noneMatch(known -> known == s)) {
                    segments.add(s);
                }
            }
        }
        return segments;
    }

    /**
     * Walk the match pointers from a city once round its loop.
     *
     * @param start a city on a loop
     * @return the cities in travel order, starting at {@code start}
     */
    private int[] walk(int start) {
        ArrayList<Integer> order = new ArrayList<>();
        Knot first = knotOf(start);
        Knot current = first;
        Segment arrivedBy = null;
        do {
            order.add(cityOf(current));
            Segment leave = current.s1 != arrivedBy && current.s1 != null ? current.s1 : current.s2;
            if (leave == null || order.size() > cityCount) {
                return null;
            }
            arrivedBy = leave;
            current = leave.getOther(current);
        } while (current != first);
        return order.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Whether a new leg between two cities would cross a leg of the player's loops, ignoring legs that
     * share a city with it and the segments about to be cut.
     *
     * @param a    one end of the new leg
     * @param b    the other end
     * @param cutA a segment being removed, or null
     * @param cutB another segment being removed, or null
     * @return true when the leg crosses
     */
    public boolean crossesLoops(int a, int b, Segment cutA, Segment cutB) {
        for (int[] order : loopCities) {
            for (int k = 0; k < order.length; k++) {
                int u = order[k], v = order[(k + 1) % order.length];
                if (u == v || isCut(u, v, cutA) || isCut(u, v, cutB)) {
                    continue;
                }
                if (legsCross(a, b, u, v)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isCut(int u, int v, Segment cut) {
        if (cut == null) {
            return false;
        }
        int first = cityOf(cut.first), last = cityOf(cut.last);
        return (first == u && last == v) || (first == v && last == u);
    }

    /**
     * Whether the straight legs between two pairs of cities properly cross; legs sharing a city only
     * touch.
     *
     * @param a first leg start
     * @param b first leg end
     * @param c second leg start
     * @param d second leg end
     * @return true on a proper crossing
     */
    public boolean legsCross(int a, int b, int c, int d) {
        if (a == c || a == d || b == c || b == d) {
            return false;
        }
        float ax = map.cellX[cityCell[a]], ay = map.cellY[cityCell[a]];
        float bx = map.cellX[cityCell[b]], by = map.cellY[cityCell[b]];
        float cx = map.cellX[cityCell[c]], cy = map.cellY[cityCell[c]];
        float dx = map.cellX[cityCell[d]], dy = map.cellY[cityCell[d]];
        float side1 = Math.signum((bx - ax) * (cy - ay) - (by - ay) * (cx - ax));
        float side2 = Math.signum((bx - ax) * (dy - ay) - (by - ay) * (dx - ax));
        float side3 = Math.signum((dx - cx) * (ay - cy) - (dy - cy) * (ax - cx));
        float side4 = Math.signum((dx - cx) * (by - cy) - (dy - cy) * (bx - cx));
        return side1 * side2 < 0 && side3 * side4 < 0;
    }

    /**
     * Rebuild the loop list from the knots: each loop's cities in travel order starting at a base, its
     * top knot and its wagons, which carry over from the loops it was made from.
     */
    public void refreshLoops() {
        loopCities.clear();
        loopTop.clear();
        loopWagons.clear();
        HashSet<Integer> seen = new HashSet<>();
        HashSet<Knot> claimed = new HashSet<>();
        HashMap<Knot, Integer> nextWagons = new HashMap<>();
        HashMap<Integer, Knot> nextTopOfCity = new HashMap<>();
        for (int start = 0; start < cityCount; start++) {
            if (knotOf(start).matchCount == 0 || seen.contains(start)) {
                continue;
            }
            int[] order = walk(start);
            if (order == null) {
                continue;
            }
            int rotation = 0;
            for (int k = 0; k < order.length; k++) {
                if (base[PLAYER][order[k]]) {
                    rotation = k;
                    break;
                }
            }
            int[] rotated = new int[order.length];
            for (int k = 0; k < order.length; k++) {
                rotated[k] = order[(k + rotation) % order.length];
                seen.add(rotated[k]);
            }
            Knot topKnot = top(knotOf(start));
            int wagons;
            if (wagonsByTop.containsKey(topKnot)) {
                wagons = wagonsByTop.get(topKnot);
            } else {
                wagons = 0;
                for (int city : rotated) {
                    Knot old = previousTopOfCity.get(city);
                    if (old != null && claimed.add(old)) {
                        wagons += wagonsByTop.getOrDefault(old, 0);
                    }
                }
                if (wagons == 0) {
                    wagons = 1;
                }
            }
            nextWagons.put(topKnot, wagons);
            for (int city : rotated) {
                nextTopOfCity.put(city, topKnot);
            }
            loopCities.add(rotated);
            loopTop.add(topKnot);
            loopWagons.add(wagons);
        }
        wagonsByTop.clear();
        wagonsByTop.putAll(nextWagons);
        previousTopOfCity.clear();
        previousTopOfCity.putAll(nextTopOfCity);
    }

    /**
     * The index of the loop through a city.
     *
     * @param city a city
     * @return the loop index, or -1 when the city is on no loop
     */
    public int loopIndexOf(int city) {
        for (int loop = 0; loop < loopCities.size(); loop++) {
            for (int member : loopCities.get(loop)) {
                if (member == city) {
                    return loop;
                }
            }
        }
        return -1;
    }

    /**
     * The most fuel a wagon on this loop needs between two bases, or for the whole loop when it passes
     * one base or none.
     *
     * @param order the loop's cities in travel order
     * @return blood burned on the longest stretch
     */
    private int longestStretch(int[] order) {
        int start = 0;
        for (int k = 0; k < order.length; k++) {
            if (base[PLAYER][order[k]]) {
                start = k;
                break;
            }
        }
        int longest = 0;
        int stretch = 0;
        for (int k = 0; k < order.length; k++) {
            int from = order[(start + k) % order.length];
            int to = order[(start + k + 1) % order.length];
            stretch += legCost[from][to];
            if (base[PLAYER][to]) {
                longest = Math.max(longest, stretch);
                stretch = 0;
            }
        }
        return Math.max(longest, stretch);
    }

    /**
     * Refresh the loops and work out this lap's offers and costs for both companies without settling, so
     * the screen can show what End lap would do.
     */
    public void estimate() {
        refreshLoops();
        loopBurn.clear();
        loopCargo.clear();
        loopFuelDeficit.clear();
        loopUnitCost.clear();
        loopHasBase.clear();
        ArrayList<int[]> playerOrders = new ArrayList<>(loopCities);
        computeOffers(PLAYER, playerOrders, loopWagons, true);
        if (level.rivalHome != TradeLevel.NO_RIVAL && !bankrupt[RIVAL]) {
            ArrayList<int[]> rivalOrders = new ArrayList<>();
            ArrayList<Integer> rivalWagons = new ArrayList<>();
            for (int loop = 0; loop < level.rivalLoops.length; loop++) {
                rivalOrders.add(level.rivalLoops[loop]);
                rivalWagons.add(level.rivalWagons[loop]);
            }
            computeOffers(RIVAL, rivalOrders, rivalWagons, false);
        }
    }

    /**
     * Fill one company's offers and per-city costs from its loops. Each wagon burns the loop's cells and
     * sells the rest of its capacity, best ceiling first, at one cost per unit for the whole loop.
     *
     * @param company       whose offers to fill
     * @param orders        the company's loops in travel order
     * @param wagons        wagons on each loop
     * @param recordForLoops whether to fill the player's per-loop lists
     */
    private void computeOffers(int company, ArrayList<int[]> orders, ArrayList<Integer> wagons,
            boolean recordForLoops) {
        double[] weightedCost = new double[cityCount];
        for (int city = 0; city < cityCount; city++) {
            offered[company][city] = 0;
            unitCost[company][city] = 0;
        }
        int production = 0;
        for (int city = 0; city < cityCount; city++) {
            if (base[company][city]) {
                production += BASE_PRODUCTION;
            }
        }
        for (int loop = 0; loop < orders.size(); loop++) {
            int[] order = orders.get(loop);
            int burn = 0;
            int baseVisits = 0;
            for (int k = 0; k < order.length; k++) {
                burn += legCost[order[k]][order[(k + 1) % order.length]];
                if (base[company][order[k]]) {
                    baseVisits++;
                }
            }
            int deficit = company == PLAYER ? longestStretch(order) - level.wagonCapacity : 0;
            int wagonCount = wagons.get(loop);
            int cargo = 0;
            ArrayList<Integer> stops = new ArrayList<>();
            for (int city : order) {
                if (station[company][city] && !stops.contains(city)) {
                    stops.add(city);
                }
            }
            if (baseVisits > 0 && deficit <= 0) {
                int capacity = company == PLAYER ? level.wagonCapacity : level.rivalWagonCapacity;
                int capacityCargo = Math.max(0, (capacity * baseVisits - burn) * wagonCount);
                int harvestRoom = Math.max(0, production - burn * wagonCount);
                int remaining = Math.min(capacityCargo, harvestRoom);
                int other = 1 - company;
                stops.sort((x, y) -> Double.compare(station[other][y] ? 0 : ceiling[y],
                        station[other][x] ? 0 : ceiling[x]));
                int[] given = new int[stops.size()];
                for (int s = 0; s < stops.size(); s++) {
                    int city = stops.get(s);
                    given[s] = Math.max(0, Math.min(remaining, demand[city] - offered[company][city]));
                    remaining -= given[s];
                    cargo += given[s];
                }
                production -= burn * wagonCount + cargo;
                double loopCost = cargo == 0 ? Double.POSITIVE_INFINITY
                        : (HARVEST_COST * (burn * wagonCount + cargo) + STATION_UPKEEP * stops.size()
                                + WAGON_UPKEEP * wagonCount) / cargo;
                for (int s = 0; s < stops.size(); s++) {
                    int city = stops.get(s);
                    if (given[s] > 0) {
                        offered[company][city] += given[s];
                        weightedCost[city] += given[s] * loopCost;
                    }
                }
                if (recordForLoops) {
                    loopUnitCost.add(loopCost);
                }
            } else if (recordForLoops) {
                loopUnitCost.add(Double.POSITIVE_INFINITY);
            }
            if (recordForLoops) {
                loopBurn.add(burn);
                loopCargo.add(cargo);
                loopFuelDeficit.add(Math.max(0, deficit));
                loopHasBase.add(baseVisits > 0);
            }
        }
        for (int city = 0; city < cityCount; city++) {
            if (offered[company][city] > 0) {
                unitCost[company][city] = weightedCost[city] / offered[company][city];
            }
        }
    }

    /**
     * End the lap: every wagon runs its loop, each city clears by merit order, harvest and upkeep are
     * paid, starved towns shrink, and the level checks for a win, a bankruptcy or the lap limit.
     */
    public void endLap() {
        if (outcome != PLAYING) {
            return;
        }
        estimate();
        double[] revenue = new double[COMPANY_COUNT];
        int[] soldTotal = new int[COMPANY_COUNT];
        int demandTotal = 0;
        int[] sellerOffered = new int[COMPANY_COUNT];
        double[] sellerCost = new double[COMPANY_COUNT];
        double[] sellerWar = new double[COMPANY_COUNT];
        int[] sold = new int[COMPANY_COUNT];
        for (int city = 0; city < cityCount; city++) {
            for (int company = 0; company < COMPANY_COUNT; company++) {
                sellerOffered[company] = offered[company][city];
                sellerCost[company] = unitCost[company][city];
                sellerWar[company] = -1;
            }
            if (priceWar[city] != WAR_OFF && offered[PLAYER][city] > 0) {
                sellerWar[PLAYER] = priceWar[city] == WAR_AT_COST ? unitCost[PLAYER][city]
                        : unitCost[PLAYER][city] * 0.5;
            }
            double price = market.clear(demand[city], ceiling[city], sellerOffered, sellerCost, sellerWar, sold);
            lastPrice[city] = price;
            int cityTotal = 0;
            for (int company = 0; company < COMPANY_COUNT; company++) {
                lastSold[company][city] = sold[company];
                revenue[company] += sold[company] * price;
                soldTotal[company] += sold[company];
                cityTotal += sold[company];
            }
            lastRevenue[city] = sold[PLAYER] * price;
            demandTotal += demand[city];
            int shortfall = demand[city] - cityTotal;
            if (demand[city] > 0 && shortfall > 0) {
                demand[city] = Math.max(MINIMUM_DEMAND,
                        demand[city] - (int) Math.ceil(shortfall * DEMAND_SHRINK_FRACTION));
                ceiling[city] = Math.min(level.cityCeiling[city] * CEILING_MAX_FACTOR, ceiling[city] * CEILING_RISE);
            } else if (demand[city] > 0) {
                demand[city] = Math.min(baseDemand[city], demand[city] + 1);
                ceiling[city] = Math.max(level.cityCeiling[city], ceiling[city] * CEILING_RELAX);
            }
            if (sold[RIVAL] > 0) {
                rivalEverSold[city] = true;
            }
            dominantLaps[city] = sold[PLAYER] > sold[RIVAL] ? dominantLaps[city] + 1 : 0;
            boolean rivalLosing = offered[RIVAL][city] > 0 && (sold[RIVAL] == 0 || price < unitCost[RIVAL][city]);
            rivalLossLaps[city] = rivalLosing ? rivalLossLaps[city] + 1 : 0;
            if (rivalLossLaps[city] >= RIVAL_WITHDRAW_LAPS && city != level.rivalHome) {
                station[RIVAL][city] = false;
            }
        }
        int[] burned = new int[COMPANY_COUNT];
        for (int loop = 0; loop < loopBurn.size(); loop++) {
            if (loopCargo.get(loop) > 0) {
                burned[PLAYER] += loopBurn.get(loop) * loopWagons.get(loop);
            }
        }
        if (level.rivalHome != TradeLevel.NO_RIVAL && !bankrupt[RIVAL]) {
            for (int loop = 0; loop < level.rivalLoops.length; loop++) {
                int[] order = level.rivalLoops[loop];
                for (int k = 0; k < order.length; k++) {
                    burned[RIVAL] += legCost[order[k]][order[(k + 1) % order.length]] * level.rivalWagons[loop];
                }
            }
        }
        for (int company = 0; company < COMPANY_COUNT; company++) {
            double expenses = 0;
            int stations = 0;
            for (int city = 0; city < cityCount; city++) {
                if (station[company][city]) {
                    stations++;
                }
            }
            expenses += STATION_UPKEEP * stations;
            expenses += HARVEST_COST * (soldTotal[company] + burned[company]);
            int wagonCount = 0;
            for (int wagons : company == PLAYER ? loopWagons
                    : Arrays.stream(level.rivalWagons).boxed().toList()) {
                wagonCount += wagons;
            }
            expenses += WAGON_UPKEEP * wagonCount;
            lastProfit[company] = revenue[company] - expenses;
            if (company == RIVAL && (level.rivalHome == TradeLevel.NO_RIVAL || bankrupt[RIVAL])) {
                lastProfit[company] = 0;
                continue;
            }
            gold[company] += lastProfit[company];
            lapsBelowZero[company] = gold[company] < 0 ? lapsBelowZero[company] + 1 : 0;
            if (lapsBelowZero[company] >= BANKRUPT_LAPS) {
                bankrupt[company] = true;
            }
        }
        if (bankrupt[RIVAL]) {
            for (int city = 0; city < cityCount; city++) {
                station[RIVAL][city] = false;
            }
        }
        double share = demandTotal == 0 ? 0 : (double) soldTotal[PLAYER] / demandTotal;
        long sharePercent = Math.round(share * PERCENT);
        if (bankrupt[PLAYER]) {
            outcome = LOST;
            outcomeText = "Bankrupt: three laps below zero gold.";
        } else if (level.shareGoal > 0 && share >= level.shareGoal) {
            outcome = WON;
            outcomeText = "You hold " + sharePercent + "% of the blood trade.";
        } else if (level.shareGoal > 0 && bankrupt[RIVAL]) {
            outcome = WON;
            outcomeText = "The rival is bankrupt.";
        } else if (level.shareGoal > 0 && lap >= level.lapLimit) {
            outcome = LOST;
            outcomeText = "Out of laps at " + sharePercent + "% of the trade.";
        } else if (level.shareGoal == 0 && lap >= level.lapLimit) {
            double earned = gold[PLAYER] - level.startGold;
            medal = 0;
            for (int threshold : level.medalGold) {
                if (earned >= threshold) {
                    medal++;
                }
            }
            outcome = medal > 0 ? WON : LOST;
            outcomeText = "Earned " + Math.round(earned) + " gold.";
        }
        lap++;
        estimate();
    }

    /**
     * The player's share of all demand on the last lap.
     *
     * @return sold over demand, between zero and one
     */
    public double playerShare() {
        int sold = 0;
        int total = 0;
        for (int city = 0; city < cityCount; city++) {
            sold += lastSold[PLAYER][city];
            total += demand[city];
        }
        return total == 0 ? 0 : (double) sold / total;
    }

    /**
     * The rival's share of all demand on the last lap.
     *
     * @return sold over demand, between zero and one
     */
    public double rivalShare() {
        int sold = 0;
        int total = 0;
        for (int city = 0; city < cityCount; city++) {
            sold += lastSold[RIVAL][city];
            total += demand[city];
        }
        return total == 0 ? 0 : (double) sold / total;
    }

    /**
     * A contested city's medal: bronze when the player outsells the rival, silver when the rival is also
     * selling below its cost, gold once the rival has withdrawn.
     *
     * @param city a city
     * @return 0 for none up to {@link #MEDAL_GOLD}
     */
    public int marketMedal(int city) {
        if (!rivalEverSold[city] || lastSold[PLAYER][city] == 0) {
            return 0;
        }
        if (!station[RIVAL][city]) {
            return MEDAL_GOLD;
        }
        if (lastSold[PLAYER][city] > lastSold[RIVAL][city]) {
            return lastSold[RIVAL][city] > 0 && lastPrice[city] < unitCost[RIVAL][city] ? 2 : 1;
        }
        return 0;
    }

    /**
     * Buy a station in a city, where the player's wagons may then sell.
     *
     * @param city the city
     * @return true when bought
     */
    public boolean buyStation(int city) {
        if (station[PLAYER][city] || gold[PLAYER] < STATION_PRICE) {
            return false;
        }
        gold[PLAYER] -= STATION_PRICE;
        station[PLAYER][city] = true;
        estimate();
        return true;
    }

    /**
     * Why the player cannot build a base in a city yet.
     *
     * @param city the city
     * @return the reason, or an empty string when a base can be built
     */
    public String baseLockReason(int city) {
        if (base[PLAYER][city]) {
            return "already a base";
        }
        if (!station[PLAYER][city]) {
            return "needs a station";
        }
        if (dominantLaps[city] < DOMINANCE_LAPS_FOR_BASE) {
            return "dominant " + dominantLaps[city] + "/" + DOMINANCE_LAPS_FOR_BASE + " laps";
        }
        if (gold[PLAYER] < BASE_PRICE) {
            return "needs " + BASE_PRICE + " gold";
        }
        return "";
    }

    /**
     * Build a base, which produces blood and refills wagons, once the player has dominated the city.
     *
     * @param city the city
     * @return true when built
     */
    public boolean buildBase(int city) {
        if (!baseLockReason(city).isEmpty()) {
            return false;
        }
        gold[PLAYER] -= BASE_PRICE;
        base[PLAYER][city] = true;
        estimate();
        return true;
    }

    /**
     * Add a wagon to the loop through a city.
     *
     * @param loopCity a city on the loop
     * @return true when bought
     */
    public boolean buyWagon(int loopCity) {
        int loop = loopIndexOf(loopCity);
        if (loop < 0 || gold[PLAYER] < WAGON_PRICE) {
            return false;
        }
        gold[PLAYER] -= WAGON_PRICE;
        Knot topKnot = loopTop.get(loop);
        wagonsByTop.put(topKnot, wagonsByTop.getOrDefault(topKnot, 1) + 1);
        estimate();
        return true;
    }

    /**
     * Step a city's price-war toggle: off, at cost, half cost.
     *
     * @param city the city
     */
    public void cyclePriceWar(int city) {
        priceWar[city] = (priceWar[city] + 1) % WAR_MODE_COUNT;
    }

    /**
     * Whether a company's network reaches a city at all: the player sees rival information only where
     * both sell.
     *
     * @param city a city
     * @return true when both companies have a station there
     */
    public boolean contested(int city) {
        return station[PLAYER][city] && station[RIVAL][city];
    }
}
