package ixdar.geometry.grid2d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.function.IntPredicate;
import java.util.function.ToDoubleBiFunction;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Camera2D;
import ixdar.scenes.main.MainScene;
import ixdar.geometry.tsp.input.PointCollection;
import ixdar.geometry.tsp.points.PointND;

public class IrregularQuadGrid extends Grid {
    public static final float HEURISTIC_STEP_FACTOR = 3.4f;
    public static final double STALE_ENTRY_TOLERANCE = 1e-9;

    private final ArrayList<Vector2f> anchors;
    private final ArrayList<Vector2f> dualPoints;
    private final ArrayList<int[]> edges;
    private final long seed;
    private final int relaxIterations;
    private final float jitterRatio;
    private final int rows;
    private final int cols;
    private final float horizontalEdgeMean;
    private final float verticalEdgeMean;
    private final float horizontalEdgeStdDev;
    private final float verticalEdgeStdDev;
    private final ArrayList<int[]> faces;
    private final int[][] neighbours;
    private final float cellSpacing;
    private final double[] pathCost;
    private final int[] previousCell;
    private final int[] visitStamp;
    private int currentStamp;

    /**
     * Bare-bones constructor that records the grid's dimensions and reuses the
     * supplied {@code anchors} as its quad corners. Dual points, edges and
     * edge-statistics fields default to empty/zero.
     *
     * @param anchors quad corners; {@code null} is treated as an empty list
     * @param seed RNG seed used to generate the layout
     * @param relaxIterations number of relaxation passes that produced {@code anchors}
     * @param rows quad row count
     * @param cols quad column count
     */
    public IrregularQuadGrid(ArrayList<Vector2f> anchors, long seed, int relaxIterations, int rows, int cols) {
        this.anchors = anchors == null ? new ArrayList<>() : anchors;
        this.dualPoints = new ArrayList<>();
        this.edges = new ArrayList<>();
        this.seed = seed;
        this.relaxIterations = relaxIterations;
        this.jitterRatio = 0f;
        this.rows = rows;
        this.cols = cols;
        this.horizontalEdgeMean = 0f;
        this.verticalEdgeMean = 0f;
        this.horizontalEdgeStdDev = 0f;
        this.verticalEdgeStdDev = 0f;
        this.faces = new ArrayList<>();
        this.neighbours = new int[0][];
        this.cellSpacing = 1f;
        this.pathCost = new double[0];
        this.previousCell = new int[0];
        this.visitStamp = new int[0];
    }

    /**
     * Build a grid from a fully populated
     * {@link IrregularQuadLayoutGenerator.Layout}, copying its anchor points,
     * dual points, edge index pairs, dimensions and edge-length statistics.
     *
     * @param layout produced layout to ingest; {@code null} yields an empty grid
     * @param seed RNG seed that produced {@code layout}
     * @param relaxIterations number of relaxation passes used during generation
     * @param jitterRatio per-point jitter ratio applied during generation
     */
    public IrregularQuadGrid(IrregularQuadLayoutGenerator.Layout layout, long seed, int relaxIterations,
            float jitterRatio) {
        this.anchors = layout == null || layout.points == null ? new ArrayList<>() : new ArrayList<>(layout.points);
        this.dualPoints = layout == null || layout.dualPoints == null ? new ArrayList<>()
                : new ArrayList<>(layout.dualPoints);
        this.edges = layout == null || layout.edges == null ? new ArrayList<>() : new ArrayList<>(layout.edges);
        this.seed = seed;
        this.relaxIterations = relaxIterations;
        this.jitterRatio = jitterRatio;
        this.rows = layout == null ? 0 : layout.rows;
        this.cols = layout == null ? 0 : layout.cols;
        this.horizontalEdgeMean = layout == null ? 0f : layout.horizontalEdgeMean;
        this.verticalEdgeMean = layout == null ? 0f : layout.verticalEdgeMean;
        this.horizontalEdgeStdDev = layout == null ? 0f : layout.horizontalEdgeStdDev;
        this.verticalEdgeStdDev = layout == null ? 0f : layout.verticalEdgeStdDev;
        this.faces = layout == null || layout.faces == null ? new ArrayList<>() : new ArrayList<>(layout.faces);
        this.neighbours = buildNeighbours();
        float spacingSum = 0f;
        int spacingCount = 0;
        for (int cell = 0; cell < neighbours.length; cell++) {
            for (int neighbour : neighbours[cell]) {
                spacingSum += cellDistance(cell, neighbour);
                spacingCount++;
            }
        }
        this.cellSpacing = spacingCount == 0 ? 1f : spacingSum / spacingCount;
        this.pathCost = new double[faces.size()];
        this.previousCell = new int[faces.size()];
        this.visitStamp = new int[faces.size()];
    }

    /**
     * Join every pair of cells that share a side; two cells share a side when both list its corners
     * consecutively.
     *
     * @return each cell's neighbour list, in the order the sides are met
     */
    private int[][] buildNeighbours() {
        HashMap<Long, Integer> firstCellOnSide = new HashMap<>();
        ArrayList<ArrayList<Integer>> found = new ArrayList<>();
        for (int cell = 0; cell < faces.size(); cell++) {
            found.add(new ArrayList<>());
        }
        for (int cell = 0; cell < faces.size(); cell++) {
            int[] corners = faces.get(cell);
            for (int k = 0; k < corners.length; k++) {
                int p = corners[k], q = corners[(k + 1) % corners.length];
                long side = (long) Math.min(p, q) * anchors.size() + Math.max(p, q);
                Integer other = firstCellOnSide.putIfAbsent(side, cell);
                if (other != null && other != cell) {
                    found.get(cell).add(other);
                    found.get(other).add(cell);
                }
            }
        }
        int[][] result = new int[faces.size()][];
        for (int cell = 0; cell < result.length; cell++) {
            result[cell] = found.get(cell).stream().mapToInt(Integer::intValue).toArray();
        }
        return result;
    }

    /**
     * Each cell's corner indices into {@link #anchorPoints()}.
     *
     * @return live reference to the face list
     */
    public ArrayList<int[]> faces() {
        return faces;
    }

    /**
     * The cells sharing a side with a cell.
     *
     * @param cell a cell index
     * @return its neighbours
     */
    public int[] neighbours(int cell) {
        return neighbours[cell];
    }

    /**
     * Mean distance between neighbouring cell centres, the grid's unit of length.
     *
     * @return the mean step length
     */
    public float cellSpacing() {
        return cellSpacing;
    }

    /**
     * Straight-line distance between two cell centres.
     *
     * @param a one cell
     * @param b the other
     * @return the distance in world units
     */
    public float cellDistance(int a, int b) {
        return dualPoints.get(a).distance(dualPoints.get(b));
    }

    /**
     * The side two neighbouring cells share, as its two corner indices.
     *
     * @param a one cell
     * @param b a neighbouring cell
     * @return the shared side's corners, or null when the cells are not neighbours
     */
    public int[] sharedSide(int a, int b) {
        int[] cornersA = faces.get(a);
        int[] cornersB = faces.get(b);
        for (int k = 0; k < cornersA.length; k++) {
            int p = cornersA[k], q = cornersA[(k + 1) % cornersA.length];
            for (int j = 0; j < cornersB.length; j++) {
                int r = cornersB[j], s = cornersB[(j + 1) % cornersB.length];
                if ((p == r && q == s) || (p == s && q == r)) {
                    return new int[] { p, q };
                }
            }
        }
        return null;
    }

    /**
     * The cell whose centre is nearest a point, among those a filter accepts.
     *
     * @param x      world x
     * @param y      world y
     * @param usable which cells may be returned
     * @return the cell index, or -1 when no cell is usable
     */
    public int nearestCell(float x, float y, IntPredicate usable) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int cell = 0; cell < dualPoints.size(); cell++) {
            float d = dualPoints.get(cell).distanceSquared(x, y);
            if (d < bestDistance && usable.test(cell)) {
                bestDistance = d;
                best = cell;
            }
        }
        return best;
    }

    /**
     * A* from cell to cell over the dual graph. A step priced infinite is closed; the straight-line
     * distance over twice the mean step is the heuristic, which stays below any path of steps costing one
     * or more.
     *
     * @param from     start cell
     * @param to       end cell
     * @param stepCost the price of stepping from a cell to a neighbour
     * @return the cheapest path, both ends included, or null when unreachable
     */
    public int[] shortestPath(int from, int to, ToDoubleBiFunction<Integer, Integer> stepCost) {
        if (from == to) {
            return new int[] { from };
        }
        currentStamp++;
        float longestStep = cellSpacing * HEURISTIC_STEP_FACTOR;
        PriorityQueue<double[]> frontier = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        visitStamp[from] = currentStamp;
        pathCost[from] = 0;
        previousCell[from] = -1;
        frontier.add(new double[] { cellDistance(from, to) / longestStep, from });
        while (!frontier.isEmpty()) {
            double[] entry = frontier.poll();
            int cell = (int) entry[1];
            if (cell == to) {
                break;
            }
            if (entry[0] - cellDistance(cell, to) / longestStep > pathCost[cell] + STALE_ENTRY_TOLERANCE) {
                continue;
            }
            for (int neighbour : neighbours[cell]) {
                double step = stepCost.applyAsDouble(cell, neighbour);
                if (Double.isInfinite(step)) {
                    continue;
                }
                double candidate = pathCost[cell] + step;
                if (visitStamp[neighbour] != currentStamp || candidate < pathCost[neighbour]) {
                    visitStamp[neighbour] = currentStamp;
                    pathCost[neighbour] = candidate;
                    previousCell[neighbour] = cell;
                    frontier.add(new double[] { candidate + cellDistance(neighbour, to) / longestStep, neighbour });
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
     * RNG seed that produced this grid's layout.
     *
     * @return the stored seed
     */
    public long seed() {
        return seed;
    }

    /**
     * Number of relaxation passes that were run on the layout.
     *
     * @return relaxation iteration count
     */
    public int relaxIterations() {
        return relaxIterations;
    }

    /**
     * Per-point jitter ratio applied when generating the layout.
     *
     * @return the jitter ratio in {@code [0, 1]}
     */
    public float jitterRatio() {
        return jitterRatio;
    }

    /**
     * Number of quad rows in the layout.
     *
     * @return row count
     */
    public int rows() {
        return rows;
    }

    /**
     * Number of quad columns in the layout.
     *
     * @return column count
     */
    public int cols() {
        return cols;
    }

    /**
     * Count of anchor points (quad corners).
     *
     * @return {@code anchors.size()}
     */
    public int anchorCount() {
        return anchors.size();
    }

    /**
     * Count of dual points (one per quad face).
     *
     * @return {@code dualPoints.size()}
     */
    public int dualPointCount() {
        return dualPoints.size();
    }

    /**
     * Count of edges in the layout.
     *
     * @return {@code edges.size()}
     */
    public int edgeCount() {
        return edges.size();
    }

    /**
     * The anchor points (quad corners) of the layout.
     *
     * @return live reference to the anchor list
     */
    public ArrayList<Vector2f> anchorPoints() {
        return anchors;
    }

    /**
     * The dual points (per-face centers) of the layout.
     *
     * @return live reference to the dual point list
     */
    public ArrayList<Vector2f> dualPoints() {
        return dualPoints;
    }

    /**
     * Edge connectivity as pairs of indices into {@link #anchorPoints()}.
     *
     * @return live reference to the edge list
     */
    public ArrayList<int[]> edgeIndices() {
        return edges;
    }

    /**
     * Mean length of horizontal edges in the layout.
     *
     * @return the horizontal edge-length mean
     */
    public float horizontalEdgeMean() {
        return horizontalEdgeMean;
    }

    /**
     * Mean length of vertical edges in the layout.
     *
     * @return the vertical edge-length mean
     */
    public float verticalEdgeMean() {
        return verticalEdgeMean;
    }

    /**
     * Standard deviation of horizontal edge lengths.
     *
     * @return the horizontal edge-length standard deviation
     */
    public float horizontalEdgeStdDev() {
        return horizontalEdgeStdDev;
    }

    /**
     * Standard deviation of vertical edge lengths.
     *
     * @return the vertical edge-length standard deviation
     */
    public float verticalEdgeStdDev() {
        return verticalEdgeStdDev;
    }

    /**
     * Format the cursor's world-space coordinates as {@code "X:.. Y:.."}, the
     * same readout as {@link CartesianGrid}.
     *
     * @param editor the editor whose cursor and camera are read
     * @return Cartesian coordinate readout for the status bar
     */
    @Override
    public String toCoordString(MainScene editor) {
        return "X:"
                + (int) editor.editorCamera.screenTransformX(editor.mouse.normalizedPosX - MainScene.MAIN_VIEW_OFFSET_X)
                + " Y:" + (int) editor.editorCamera
                        .screenTransformY(editor.mouse.normalizedPosY - MainScene.MAIN_VIEW_OFFSET_Y);
    }

    /**
     * The irregular quad grid accepts the same floating-point coordinate types
     * as the Cartesian grid.
     *
     * @param pt candidate point
     * @return {@code true} when {@code pt} is a {@link PointND.Double} or {@link PointND.Float}
     */
    @Override
    public boolean allowsPoint(PointND pt) {
        return pt instanceof PointND.Double || pt instanceof PointND.Float;
    }

    /**
     * The point types this grid accepts: {@link PointND.Double} and
     * {@link PointND.Float}.
     *
     * @return the two floating-point coordinate classes
     */
    @SuppressWarnings("unchecked")
    @Override
    public Class<? extends PointCollection>[] allowableTypes() {
        return new Class[] { PointND.Double.class, PointND.Float.class };
    }

    /**
     * Drawing of the quad grid is delegated elsewhere (see the layout
     * generator's debug overlay); this method is intentionally a no-op.
     *
     * @param camera 2D camera providing screen/world transforms
     * @param gridLineThickness stroke width (unused)
     */
    @Override
    public void draw(Camera2D camera, float gridLineThickness) {
    }

    /**
     * Snap to the closest anchor point by squared screen-space distance, or
     * return the input position when no anchors are loaded.
     *
     * @param mouseX screen-space X
     * @param mouseY screen-space Y
     * @return a fresh vector at the nearest anchor (or the input if empty)
     */
    @Override
    public Vector2f coordinateToNearestGridPoint(float mouseX, float mouseY) {
        if (anchors.isEmpty()) {
            return new Vector2f(mouseX, mouseY);
        }
        Vector2f nearest = anchors.get(0);
        float bestSq = distanceSq(nearest.x, nearest.y, mouseX, mouseY);
        for (int i = 1; i < anchors.size(); i++) {
            Vector2f candidate = anchors.get(i);
            float sq = distanceSq(candidate.x, candidate.y, mouseX, mouseY);
            if (sq < bestSq) {
                bestSq = sq;
                nearest = candidate;
            }
        }
        return new Vector2f(nearest);
    }

    private float distanceSq(float ax, float ay, float bx, float by) {
        float dx = ax - bx;
        float dy = ay - by;
        return (dx * dx) + (dy * dy);
    }
}
