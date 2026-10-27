package ixdar.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.ToDoubleBiFunction;

import org.joml.Vector2f;

import ixdar.geometry.grid2d.IrregularQuadLayoutGenerator;
import ixdar.geometry.grid2d.IrregularQuadLayoutGenerator.Layout;

/**
 * The trade game's terrain on the dual graph of an irregular quad grid: moor, river, bridges, villages
 * and starting roads. Travel costs {@link #ON_ROAD_COST} per cell on a road, {@link #OFF_ROAD_COST} off.
 */
public class TradeMap {
    public static final int ON_ROAD_COST = 1;
    public static final int OFF_ROAD_COST = 2;
    public static final int RELAX_ITERATIONS = 8;
    public static final float ROAD_REUSE_PENALTY = 6f;
    public static final float ROAD_DETOUR_LIMIT = 1.7f;
    public static final float ROAD_MAX_LENGTH_IN_CELLS = 7f;
    public static final float VILLAGE_SPACING_IN_CELLS = 2f;
    public static final int VILLAGE_PLACEMENT_ATTEMPTS = 2000;

    public final float width;
    public final float height;
    public final ArrayList<Vector2f> cornerPoints;
    /** Each cell's corner indices into {@link #cornerPoints}. */
    public final ArrayList<int[]> cellCorners;
    public final float[] cellX;
    public final float[] cellY;
    /** Each cell's neighbours: the cells it shares a side with. */
    public final int[][] cellNeighbours;
    public final boolean[] cellBlocked;
    /** Mean distance between neighbouring cell centres, the map's unit of length. */
    public final float cellSpacing;

    public float[] riverX = new float[0];
    public float[] riverY = new float[0];
    /** Pairs of neighbouring cells whose shared side the river runs along, minus the bridges. */
    public final HashSet<Long> riverCrossings = new HashSet<>();
    /** Each bridge as the two cells it joins. */
    public final ArrayList<int[]> bridges = new ArrayList<>();

    public final HashSet<Long> roadEdges = new HashSet<>();
    /** Each starting road as the cells it runs through. */
    public final ArrayList<int[]> roads = new ArrayList<>();
    public final ArrayList<Integer> villageCells = new ArrayList<>();

    public float moorCenterX;
    public float moorCenterY;
    public float moorRadiusX;
    public float moorRadiusY;

    private final Random random;
    private final double[] pathCost;
    private final int[] previousCell;
    private final int[] visitStamp;
    private int currentStamp;

    /**
     * Generate the irregular grid for a map of the given size and build its dual graph; terrain,
     * villages and roads are added afterwards.
     *
     * @param seed        seeds the grid and every random choice after it
     * @param width       map width in world units
     * @param height      map height in world units
     * @param targetCells roughly how many cells the grid should have
     */
    public TradeMap(long seed, float width, float height, int targetCells) {
        this.width = width;
        this.height = height;
        this.random = new Random(seed);
        Layout layout = IrregularQuadLayoutGenerator.generate(targetCells, width, height, 0f, seed, RELAX_ITERATIONS,
                0f);
        cornerPoints = layout.points;
        cellCorners = layout.faces;
        int cellCount = cellCorners.size();
        cellX = new float[cellCount];
        cellY = new float[cellCount];
        for (int cell = 0; cell < cellCount; cell++) {
            cellX[cell] = layout.dualPoints.get(cell).x;
            cellY[cell] = layout.dualPoints.get(cell).y;
        }
        cellNeighbours = buildNeighbours();
        cellBlocked = new boolean[cellCount];
        float spacingSum = 0f;
        int spacingCount = 0;
        for (int cell = 0; cell < cellCount; cell++) {
            for (int neighbour : cellNeighbours[cell]) {
                spacingSum += distance(cell, neighbour);
                spacingCount++;
            }
        }
        cellSpacing = spacingCount == 0 ? 1f : spacingSum / spacingCount;
        pathCost = new double[cellCount];
        previousCell = new int[cellCount];
        visitStamp = new int[cellCount];
    }

    /**
     * Join every pair of cells that share a side; a side is shared when both cells list its two corners
     * consecutively.
     *
     * @return each cell's neighbour list
     */
    private int[][] buildNeighbours() {
        HashMap<Long, Integer> firstCellOnSide = new HashMap<>();
        ArrayList<ArrayList<Integer>> neighbours = new ArrayList<>();
        for (int cell = 0; cell < cellCorners.size(); cell++) {
            neighbours.add(new ArrayList<>());
        }
        int cornerCount = cornerPoints.size();
        for (int cell = 0; cell < cellCorners.size(); cell++) {
            int[] corners = cellCorners.get(cell);
            for (int k = 0; k < corners.length; k++) {
                long side = pairKey(corners[k], corners[(k + 1) % corners.length], cornerCount);
                Integer other = firstCellOnSide.putIfAbsent(side, cell);
                if (other != null && other != cell) {
                    neighbours.get(cell).add(other);
                    neighbours.get(other).add(cell);
                }
            }
        }
        int[][] result = new int[cellCorners.size()][];
        for (int cell = 0; cell < result.length; cell++) {
            result[cell] = neighbours.get(cell).stream().mapToInt(Integer::intValue).toArray();
        }
        return result;
    }

    /**
     * Block every cell whose centre lies inside the moor ellipse.
     *
     * @param centerX ellipse centre x
     * @param centerY ellipse centre y
     * @param radiusX half-width
     * @param radiusY half-height
     */
    public void addMoor(float centerX, float centerY, float radiusX, float radiusY) {
        moorCenterX = centerX;
        moorCenterY = centerY;
        moorRadiusX = radiusX;
        moorRadiusY = radiusY;
        for (int cell = 0; cell < cellX.length; cell++) {
            float dx = (cellX[cell] - centerX) / radiusX;
            float dy = (cellY[cell] - centerY) / radiusY;
            if (dx * dx + dy * dy < 1f) {
                cellBlocked[cell] = true;
            }
        }
    }

    /**
     * Lay a river along a polyline: every step between neighbouring cells that crosses it is closed
     * except one bridge nearest each requested height.
     *
     * @param pointsX  river polyline x coordinates
     * @param pointsY  river polyline y coordinates
     * @param bridgeYs the heights at which to place a bridge
     */
    public void addRiver(float[] pointsX, float[] pointsY, float[] bridgeYs) {
        riverX = pointsX;
        riverY = pointsY;
        ArrayList<int[]> crossings = new ArrayList<>();
        for (int cell = 0; cell < cellX.length; cell++) {
            for (int neighbour : cellNeighbours[cell]) {
                if (neighbour < cell) {
                    continue;
                }
                float ax = cellX[cell], ay = cellY[cell], bx = cellX[neighbour], by = cellY[neighbour];
                for (int k = 1; k < pointsX.length; k++) {
                    float cx = pointsX[k - 1], cy = pointsY[k - 1], dx = pointsX[k], dy = pointsY[k];
                    float side1 = Math.signum((bx - ax) * (cy - ay) - (by - ay) * (cx - ax));
                    float side2 = Math.signum((bx - ax) * (dy - ay) - (by - ay) * (dx - ax));
                    float side3 = Math.signum((dx - cx) * (ay - cy) - (dy - cy) * (ax - cx));
                    float side4 = Math.signum((dx - cx) * (by - cy) - (dy - cy) * (bx - cx));
                    if (side1 != side2 && side3 != side4) {
                        riverCrossings.add(edgeKey(cell, neighbour));
                        crossings.add(new int[] { cell, neighbour });
                        break;
                    }
                }
            }
        }
        for (float bridgeY : bridgeYs) {
            int[] best = null;
            float bestGap = Float.MAX_VALUE;
            for (int[] crossing : crossings) {
                float gap = Math.abs((cellY[crossing[0]] + cellY[crossing[1]]) * 0.5f - bridgeY);
                if (gap < bestGap) {
                    bestGap = gap;
                    best = crossing;
                }
            }
            if (best != null) {
                riverCrossings.remove(edgeKey(best[0], best[1]));
                bridges.add(best);
            }
        }
    }

    /**
     * The nearest open cell to a point.
     *
     * @param x world x
     * @param y world y
     * @return the cell index, or -1 when every cell is blocked
     */
    public int cellAt(float x, float y) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int cell = 0; cell < cellX.length; cell++) {
            float dx = cellX[cell] - x;
            float dy = cellY[cell] - y;
            float d = dx * dx + dy * dy;
            if (!cellBlocked[cell] && d < bestDistance) {
                bestDistance = d;
                best = cell;
            }
        }
        return best;
    }

    /**
     * Scatter villages and lay the starting roads: a relative-neighbourhood graph over the cities and
     * villages, each edge routed on the grid away from roads already laid, so roads wind through
     * villages and meet only at them.
     *
     * @param cityCells    the cells the cities sit on
     * @param villageCount how many villages to place
     */
    public void layRoads(int[] cityCells, int villageCount) {
        ArrayList<Integer> nodes = new ArrayList<>();
        for (int cell : cityCells) {
            nodes.add(cell);
        }
        float minimumSpacing = VILLAGE_SPACING_IN_CELLS * cellSpacing;
        for (int attempt = 0; attempt < VILLAGE_PLACEMENT_ATTEMPTS && villageCells.size() < villageCount; attempt++) {
            int cell = random.nextInt(cellX.length);
            if (cellBlocked[cell] || cellNeighbours[cell].length < 3) {
                continue;
            }
            boolean crowded = false;
            for (int node : nodes) {
                if (distance(cell, node) < minimumSpacing) {
                    crowded = true;
                    break;
                }
            }
            if (!crowded) {
                villageCells.add(cell);
                nodes.add(cell);
            }
        }

        HashSet<Integer> nodeSet = new HashSet<>(nodes);
        ArrayList<int[]> pairs = new ArrayList<>();
        float maxLength = ROAD_MAX_LENGTH_IN_CELLS * cellSpacing;
        for (int a = 0; a < nodes.size(); a++) {
            for (int b = a + 1; b < nodes.size(); b++) {
                float length = distance(nodes.get(a), nodes.get(b));
                if (length > maxLength) {
                    continue;
                }
                boolean dominated = false;
                for (int r = 0; r < nodes.size() && !dominated; r++) {
                    if (r != a && r != b && Math.max(distance(nodes.get(a), nodes.get(r)),
                            distance(nodes.get(b), nodes.get(r))) < length) {
                        dominated = true;
                    }
                }
                if (!dominated) {
                    pairs.add(new int[] { nodes.get(a), nodes.get(b) });
                }
            }
        }
        pairs.sort((p, q) -> Float.compare(distance(p[0], p[1]), distance(q[0], q[1])));

        HashSet<Integer> roadCells = new HashSet<>();
        for (int[] pair : pairs) {
            int[] path = shortestPath(pair[0], pair[1], (from, to) -> 1.0
                    + (roadCells.contains(to) && !nodeSet.contains(to) ? ROAD_REUSE_PENALTY : 0.0));
            float straightSteps = distance(pair[0], pair[1]) / cellSpacing;
            if (path == null || path.length - 1 > ROAD_DETOUR_LIMIT * straightSteps + 2) {
                continue;
            }
            for (int k = 1; k < path.length; k++) {
                roadEdges.add(edgeKey(path[k - 1], path[k]));
            }
            for (int cell : path) {
                roadCells.add(cell);
            }
            roads.add(path);
        }
    }

    /**
     * Whether the step between two neighbouring cells runs along a road.
     *
     * @param from one cell
     * @param to   the neighbouring cell
     * @return true on a road
     */
    public boolean isRoad(int from, int to) {
        return roadEdges.contains(edgeKey(from, to));
    }

    /**
     * The cheapest route between two cells at {@link #ON_ROAD_COST} per step on a road and
     * {@link #OFF_ROAD_COST} off it.
     *
     * @param from start cell
     * @param to   end cell
     * @return the cells along the route, both ends included, or null when unreachable
     */
    public int[] route(int from, int to) {
        return shortestPath(from, to, (a, b) -> isRoad(a, b) ? ON_ROAD_COST : OFF_ROAD_COST);
    }

    /**
     * The blood a route burns: its steps priced on and off road.
     *
     * @param path cells along a route
     * @return the route's cost in cells
     */
    public int routeCost(int[] path) {
        int cost = 0;
        for (int k = 1; k < path.length; k++) {
            cost += isRoad(path[k - 1], path[k]) ? ON_ROAD_COST : OFF_ROAD_COST;
        }
        return cost;
    }

    /**
     * How many of a route's steps leave the roads.
     *
     * @param path cells along a route
     * @return the off-road step count
     */
    public int offRoadSteps(int[] path) {
        int steps = 0;
        for (int k = 1; k < path.length; k++) {
            if (!isRoad(path[k - 1], path[k])) {
                steps++;
            }
        }
        return steps;
    }

    /**
     * A* over the open dual graph with a step price; the straight-line distance over the longest step
     * is its heuristic, which never overestimates since every step costs at least one.
     *
     * @param from     start cell
     * @param to       end cell
     * @param stepCost the price of each step
     * @return the cheapest path, both ends included, or null when unreachable
     */
    private int[] shortestPath(int from, int to, ToDoubleBiFunction<Integer, Integer> stepCost) {
        if (from == to) {
            return new int[] { from };
        }
        currentStamp++;
        float longestStep = ROAD_DETOUR_LIMIT * cellSpacing * 2;
        PriorityQueue<double[]> frontier = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        visitStamp[from] = currentStamp;
        pathCost[from] = 0;
        previousCell[from] = -1;
        frontier.add(new double[] { distance(from, to) / longestStep, from });
        while (!frontier.isEmpty()) {
            double[] entry = frontier.poll();
            int cell = (int) entry[1];
            if (cell == to) {
                break;
            }
            if (entry[0] - distance(cell, to) / longestStep > pathCost[cell] + 1e-9) {
                continue;
            }
            for (int neighbour : cellNeighbours[cell]) {
                if (cellBlocked[neighbour] || riverCrossings.contains(edgeKey(cell, neighbour))) {
                    continue;
                }
                double candidate = pathCost[cell] + stepCost.applyAsDouble(cell, neighbour);
                if (visitStamp[neighbour] != currentStamp || candidate < pathCost[neighbour]) {
                    visitStamp[neighbour] = currentStamp;
                    pathCost[neighbour] = candidate;
                    previousCell[neighbour] = cell;
                    frontier.add(new double[] { candidate + distance(neighbour, to) / longestStep, neighbour });
                }
            }
        }
        if (visitStamp[to] != currentStamp) {
            return null;
        }
        ArrayList<Integer> reversed = new ArrayList<>();
        for (int cell = to; cell != -1; cell = previousCell[cell]) {
            reversed.add(cell);
        }
        int[] path = new int[reversed.size()];
        for (int k = 0; k < path.length; k++) {
            path[k] = reversed.get(path.length - 1 - k);
        }
        return path;
    }

    /**
     * Straight-line distance between two cell centres.
     *
     * @param a one cell
     * @param b the other
     * @return the distance in world units
     */
    public float distance(int a, int b) {
        return (float) Math.hypot(cellX[a] - cellX[b], cellY[a] - cellY[b]);
    }

    /**
     * An order-free key for a pair of cells.
     *
     * @param a one cell
     * @param b the other
     * @return the same key for (a, b) and (b, a)
     */
    public long edgeKey(int a, int b) {
        return pairKey(a, b, cellX.length);
    }

    private static long pairKey(int a, int b, int count) {
        return (long) Math.min(a, b) * count + Math.max(a, b);
    }

}
