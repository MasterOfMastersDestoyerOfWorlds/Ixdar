package ixdar.geometry.point;

import java.util.ArrayList;

import org.joml.Vector2f;

import ixdar.game.IrregularQuadLayoutGenerator;
import ixdar.graphics.cameras.Camera2D;
import ixdar.scenes.main.MainScene;

public class IrregularQuadGrid extends Grid {
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
     * @return Cartesian coordinate readout for the status bar
     */
    @Override
    public String toCoordString() {
        return "X:"
                + (int) MainScene.camera.screenTransformX(MainScene.mouse.normalizedPosX - MainScene.MAIN_VIEW_OFFSET_X)
                + " Y:" + (int) MainScene.camera
                        .screenTransformY(MainScene.mouse.normalizedPosY - MainScene.MAIN_VIEW_OFFSET_Y);
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
