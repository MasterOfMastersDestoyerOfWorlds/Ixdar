package ixdar.game.trade.level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Random;

import org.joml.Vector2f;

import ixdar.game.trade.level.TradeLevel.CitySpec;
import ixdar.game.trade.level.TradeLevel.TownSpec;
import ixdar.game.trade.map.Bridge;
import ixdar.game.trade.map.City;
import ixdar.game.trade.map.Place;
import ixdar.game.trade.map.River;
import ixdar.game.trade.map.Road;
import ixdar.game.trade.map.Town;
import ixdar.game.trade.map.TradeMap;
import ixdar.geometry.grid2d.IrregularQuadGrid;
import ixdar.geometry.grid2d.IrregularQuadLayoutGenerator;

/**
 * Builds a level's map from its file and seed: grid, terrain and cities, a road between every two cities
 * merging into roads already laid, and towns where roads meet.
 */
public final class TradeMapGenerator {
    public static final int RELAX_ITERATIONS = 8;
    public static final double NEW_ROAD_STEP_COST_OVER_OLD_ROAD = 1.25;
    public static final double ROAD_ALONGSIDE_ROAD_EXTRA_COST = 0.3;
    public static final double OFF_THE_STRAIGHT_LINE_COST_PER_CELL = 0.35;
    public static final double ROAD_DETOUR_LIMIT = 1.15;
    public static final int LONGEST_BARE_ROAD_IN_STEPS = 7;
    public static final float TOWN_SPACING_IN_CELLS = 1.8f;
    public static final float CROSSROADS_SPACING_IN_CELLS = 1f;
    public static final float CITY_SPACING_IN_CELLS = 3.2f;
    public static final int PLACEMENT_ATTEMPTS = 4000;
    public static final int GENERATED_DEMAND_MIN = 25;
    public static final int GENERATED_DEMAND_SPREAD = 30;
    public static final double GENERATED_CEILING_MIN = 2.4;
    public static final double GENERATED_CEILING_SPREAD = 1.4;
    public static final String[] CITY_NAMES = { "Ashby", "Bramford", "Cobham", "Dorrin", "Elmet", "Farrow", "Garth",
        "Holme", "Ilkley", "Jarrow", "Kendal", "Ludlow" };
    public static final String[] TOWN_NAMES = { "Wick", "Thorpe", "Hale", "Brook", "Moss", "Ford", "Lea", "Combe",
        "Holt", "Stow", "Dene", "Ness", "Hythe", "Wold", "Carr", "Fell", "Mere", "Shaw", "Tarn", "Rigg", "Scar",
        "Slack", "Booth", "Royd", "Ings", "Clough", "Hagg", "Bank" };
    public static final String HEADQUARTERS_NAME_IN_LEVEL_FILE = "Home";
    public static final String HEADQUARTERS_FALLBACK_NAME = "Haven";

    private TradeMapGenerator() {
    }

    /**
     * Generate a level's map. The same file and seed always give the same map.
     *
     * @param level the level's file
     * @return the map with its terrain, places and roads
     */
    public static TradeMap build(TradeLevel level) {
        Random random = new Random(level.seed);
        IrregularQuadGrid grid = new IrregularQuadGrid(IrregularQuadLayoutGenerator.generate(level.targetCells,
                level.width, level.height, 0f, level.seed, RELAX_ITERATIONS, 0f), level.seed, RELAX_ITERATIONS, 0f);
        TradeMap map = new TradeMap(grid);
        int cellCount = grid.dualPoints().size();
        float spacing = grid.cellSpacing();

        for (float[] moor : level.moors) {
            for (int cell = 0; cell < cellCount; cell++) {
                Vector2f at = grid.dualPoints().get(cell);
                float dx = (at.x - moor[0]) / moor[2];
                float dy = (at.y - moor[1]) / moor[3];
                if (dx * dx + dy * dy < 1f) {
                    map.moor.cells.add(cell);
                }
            }
        }

        HashMap<Integer, ArrayList<Integer>> cornerNeighbours = new HashMap<>();
        HashMap<Long, ArrayList<Integer>> cellsOnSide = new HashMap<>();
        int cornerCount = grid.anchorCount();
        for (int cell = 0; cell < cellCount; cell++) {
            int[] corners = grid.faces().get(cell);
            for (int k = 0; k < corners.length; k++) {
                int p = corners[k], q = corners[(k + 1) % corners.length];
                cornerNeighbours.computeIfAbsent(p, c -> new ArrayList<>()).add(q);
                cornerNeighbours.computeIfAbsent(q, c -> new ArrayList<>()).add(p);
                cellsOnSide.computeIfAbsent((long) Math.min(p, q) * cornerCount + Math.max(p, q),
                        s -> new ArrayList<>()).add(cell);
            }
        }
        for (float[] course : level.rivers) {
            ArrayList<Integer> corners = new ArrayList<>();
            int previous = -1;
            for (int k = 0; k + 1 < course.length; k += 2) {
                int target = -1;
                float best = Float.MAX_VALUE;
                for (int corner = 0; corner < cornerCount; corner++) {
                    float d = grid.anchorPoints().get(corner).distanceSquared(course[k], course[k + 1]);
                    if (d < best && cornerNeighbours.containsKey(corner)) {
                        best = d;
                        target = corner;
                    }
                }
                if (previous < 0) {
                    corners.add(target);
                } else if (target != previous) {
                    double[] cost = new double[cornerCount];
                    int[] from = new int[cornerCount];
                    Arrays.fill(cost, Double.POSITIVE_INFINITY);
                    cost[previous] = 0;
                    PriorityQueue<double[]> frontier = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
                    frontier.add(new double[] { 0, previous });
                    while (!frontier.isEmpty()) {
                        double[] entry = frontier.poll();
                        int corner = (int) entry[1];
                        if (corner == target) {
                            break;
                        }
                        if (entry[0] > cost[corner]) {
                            continue;
                        }
                        for (int next : cornerNeighbours.get(corner)) {
                            double candidate = cost[corner]
                                    + grid.anchorPoints().get(corner).distance(grid.anchorPoints().get(next));
                            if (candidate < cost[next]) {
                                cost[next] = candidate;
                                from[next] = corner;
                                frontier.add(new double[] { candidate, next });
                            }
                        }
                    }
                    ArrayList<Integer> stretch = new ArrayList<>();
                    for (int corner = target; corner != previous; corner = from[corner]) {
                        stretch.add(0, corner);
                    }
                    corners.addAll(stretch);
                }
                previous = target;
            }
            map.rivers.add(new River(grid, corners.stream().mapToInt(Integer::intValue).toArray()));
        }
        for (float[] at : level.bridges) {
            River bestRiver = null;
            int[] bestCells = null;
            float bestDistance = Float.MAX_VALUE;
            for (River river : map.rivers) {
                for (int k = 1; k < river.nodes.length; k++) {
                    int p = river.nodes[k - 1], q = river.nodes[k];
                    ArrayList<Integer> cells = cellsOnSide.get((long) Math.min(p, q) * cornerCount + Math.max(p, q));
                    float d = new Vector2f(river.points[k - 1]).add(river.points[k]).mul(0.5f).distance(at[0], at[1]);
                    if (cells != null && cells.size() == 2 && d < bestDistance) {
                        bestDistance = d;
                        bestRiver = river;
                        bestCells = new int[] { cells.get(0), cells.get(1) };
                    }
                }
            }
            if (bestRiver != null) {
                map.bridges.add(new Bridge(bestRiver, bestCells[0], bestCells[1]));
            }
        }

        HashSet<String> usedNames = new HashSet<>();
        for (CitySpec spec : level.cities) {
            usedNames.add(spec.name());
        }
        for (int count = level.cities.size(); count < level.cities.size() + level.generatedCities; count++) {
            usedNames.add(CITY_NAMES[Math.floorMod(count - 1, CITY_NAMES.length)]);
        }
        String headquarters = HEADQUARTERS_FALLBACK_NAME;
        for (int k = CITY_NAMES.length - 1; k >= 0; k--) {
            String candidate = CITY_NAMES[(int) Math.floorMod(level.seed + k, (long) CITY_NAMES.length)];
            headquarters = usedNames.contains(candidate) ? headquarters : candidate;
        }
        HashSet<Integer> taken = new HashSet<>();
        for (CitySpec spec : level.cities) {
            int cell = grid.nearestCell(spec.x(), spec.y(), c -> !map.moor.blocks(c) && !taken.contains(c));
            taken.add(cell);
            map.add(new City(spec.name().equals(HEADQUARTERS_NAME_IN_LEVEL_FILE) ? headquarters : spec.name(), cell,
                    map.places.size(), spec.demand(), spec.ceiling()));
        }
        for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS && map.cities.size() < level.cities.size()
                + level.generatedCities; attempt++) {
            int cell = random.nextInt(cellCount);
            if (spacedOut(map, cell, CITY_SPACING_IN_CELLS * spacing)) {
                taken.add(cell);
                map.add(new City(CITY_NAMES[(map.cities.size() - 1) % CITY_NAMES.length], cell, map.places.size(),
                        GENERATED_DEMAND_MIN + random.nextInt(GENERATED_DEMAND_SPREAD),
                        GENERATED_CEILING_MIN + random.nextDouble() * GENERATED_CEILING_SPREAD));
            }
        }
        for (TownSpec spec : level.towns) {
            int cell = grid.nearestCell(spec.x(), spec.y(), c -> !map.moor.blocks(c) && !taken.contains(c));
            taken.add(cell);
            map.add(new Town(spec.name(), cell, map.places.size()));
        }

        ArrayList<int[]> pairs = new ArrayList<>();
        ArrayList<Place> ends = new ArrayList<>(map.places);
        for (int a = 0; a < ends.size(); a++) {
            for (int b = a + 1; b < ends.size(); b++) {
                pairs.add(new int[] { ends.get(a).cell, ends.get(b).cell });
            }
        }
        pairs.sort((p, q) -> Float.compare(grid.cellDistance(p[0], p[1]), grid.cellDistance(q[0], q[1])));
        HashSet<Long> roadSteps = new HashSet<>();
        HashMap<Integer, ArrayList<Integer>> roadNeighbours = new HashMap<>();
        for (int[] pair : pairs) {
            int[] open = grid.shortestPath(pair[0], pair[1],
                    (from, to) -> map.canStep(from, to) ? 1.0 : Double.POSITIVE_INFINITY);
            int[] byRoad = grid.shortestPath(pair[0], pair[1],
                    (from, to) -> roadSteps.contains(map.stepKey(from, to)) ? 1.0 : Double.POSITIVE_INFINITY);
            if (open == null || byRoad != null && byRoad.length <= ROAD_DETOUR_LIMIT * open.length) {
                continue;
            }
            Vector2f start = grid.dualPoints().get(pair[0]);
            Vector2f chord = new Vector2f(grid.dualPoints().get(pair[1])).sub(start);
            float chordSquared = Math.max(chord.lengthSquared(), 1e-6f);
            int[] path = grid.shortestPath(pair[0], pair[1], (from, to) -> {
                if (!map.canStep(from, to)) {
                    return Double.POSITIVE_INFINITY;
                }
                Vector2f at = new Vector2f(grid.dualPoints().get(to)).sub(start);
                float share = Math.max(0f, Math.min(1f, at.dot(chord) / chordSquared));
                double offLine = OFF_THE_STRAIGHT_LINE_COST_PER_CELL
                        * at.distance(new Vector2f(chord).mul(share)) / spacing;
                if (roadSteps.contains(map.stepKey(from, to))) {
                    return 1.0 + offLine;
                }
                boolean besideRoad = false;
                for (int beside : grid.neighbours(to)) {
                    besideRoad |= roadNeighbours.containsKey(beside);
                }
                return NEW_ROAD_STEP_COST_OVER_OLD_ROAD + offLine
                        + (besideRoad && !roadNeighbours.containsKey(to) ? ROAD_ALONGSIDE_ROAD_EXTRA_COST : 0.0);
            });
            if (path == null) {
                continue;
            }
            for (int k = 1; k < path.length; k++) {
                if (roadSteps.add(map.stepKey(path[k - 1], path[k]))) {
                    roadNeighbours.computeIfAbsent(path[k - 1], c -> new ArrayList<>()).add(path[k]);
                    roadNeighbours.computeIfAbsent(path[k], c -> new ArrayList<>()).add(path[k - 1]);
                }
            }
        }

        int townLimit = level.towns.size() + level.generatedTowns;
        for (int cell = 0; cell < cellCount; cell++) {
            ArrayList<Integer> next = roadNeighbours.get(cell);
            if (next != null && next.size() > 2 && spacedOut(map, cell, CROSSROADS_SPACING_IN_CELLS * spacing)) {
                taken.add(cell);
                map.add(new Town(TOWN_NAMES[map.towns.size() % TOWN_NAMES.length], cell, map.places.size()));
            }
        }
        for (int[] stretch : stretches(map, roadNeighbours, cellCount)) {
            int middle = stretch[stretch.length / 2];
            if (map.towns.size() < townLimit && stretch.length > LONGEST_BARE_ROAD_IN_STEPS
                    && spacedOut(map, middle, TOWN_SPACING_IN_CELLS * spacing)) {
                taken.add(middle);
                map.add(new Town(TOWN_NAMES[map.towns.size() % TOWN_NAMES.length], middle, map.places.size()));
            }
        }
        for (int[] stretch : stretches(map, roadNeighbours, cellCount)) {
            map.add(new Road(grid, stretch));
        }
        return map;
    }

    /**
     * Cut the road network into stretches that run from one node to the next, a node being a place or a
     * cell where roads meet or end, so each step of road lies on exactly one stretch.
     *
     * @param map            the map, for the places
     * @param roadNeighbours each road cell's neighbours along the roads
     * @param cellCount      how many cells the grid has
     * @return each stretch's cells, node to node
     */
    private static ArrayList<int[]> stretches(TradeMap map, HashMap<Integer, ArrayList<Integer>> roadNeighbours,
            int cellCount) {
        boolean[] node = new boolean[cellCount];
        for (Place place : map.places) {
            node[place.cell] = true;
        }
        for (int cell = 0; cell < cellCount; cell++) {
            ArrayList<Integer> next = roadNeighbours.get(cell);
            node[cell] |= next != null && next.size() != 2;
        }
        HashSet<Long> walked = new HashSet<>();
        ArrayList<int[]> stretches = new ArrayList<>();
        for (int start = 0; start < cellCount; start++) {
            if (!node[start] || !roadNeighbours.containsKey(start)) {
                continue;
            }
            for (int first : roadNeighbours.get(start)) {
                if (!walked.add(map.stepKey(start, first))) {
                    continue;
                }
                ArrayList<Integer> cells = new ArrayList<>();
                cells.add(start);
                int previous = start;
                int current = first;
                while (true) {
                    cells.add(current);
                    if (node[current]) {
                        break;
                    }
                    ArrayList<Integer> next = roadNeighbours.get(current);
                    int onward = next.get(0) == previous ? next.get(1) : next.get(0);
                    walked.add(map.stepKey(current, onward));
                    previous = current;
                    current = onward;
                }
                stretches.add(cells.stream().mapToInt(Integer::intValue).toArray());
            }
        }
        return stretches;
    }

    /**
     * Whether a cell is open ground at least a distance from every place.
     *
     * @param map     the map so far
     * @param cell    the candidate cell
     * @param minimum the least distance to any place
     * @return true when a place may go there
     */
    private static boolean spacedOut(TradeMap map, int cell, float minimum) {
        if (map.moor.blocks(cell)) {
            return false;
        }
        for (Place place : map.places) {
            if (map.grid.cellDistance(cell, place.cell) < minimum) {
                return false;
            }
        }
        return true;
    }
}
