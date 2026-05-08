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
     * TODO: document {@code IrregularQuadGrid}.
     *
     * @param anchors TODO: describe
     * @param seed TODO: describe
     * @param relaxIterations TODO: describe
     * @param rows TODO: describe
     * @param cols TODO: describe
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
     * TODO: document {@code IrregularQuadGrid}.
     *
     * @param layout TODO: describe
     * @param seed TODO: describe
     * @param relaxIterations TODO: describe
     * @param jitterRatio TODO: describe
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
     * TODO: document {@code seed}.
     *
     * @return TODO: describe
     */
    public long seed() {
        return seed;
    }

    /**
     * TODO: document {@code relaxIterations}.
     *
     * @return TODO: describe
     */
    public int relaxIterations() {
        return relaxIterations;
    }

    /**
     * TODO: document {@code jitterRatio}.
     *
     * @return TODO: describe
     */
    public float jitterRatio() {
        return jitterRatio;
    }

    /**
     * TODO: document {@code rows}.
     *
     * @return TODO: describe
     */
    public int rows() {
        return rows;
    }

    /**
     * TODO: document {@code cols}.
     *
     * @return TODO: describe
     */
    public int cols() {
        return cols;
    }

    /**
     * TODO: document {@code anchorCount}.
     *
     * @return TODO: describe
     */
    public int anchorCount() {
        return anchors.size();
    }

    /**
     * TODO: document {@code dualPointCount}.
     *
     * @return TODO: describe
     */
    public int dualPointCount() {
        return dualPoints.size();
    }

    /**
     * TODO: document {@code edgeCount}.
     *
     * @return TODO: describe
     */
    public int edgeCount() {
        return edges.size();
    }

    /**
     * TODO: document {@code anchorPoints}.
     *
     * @return TODO: describe
     */
    public ArrayList<Vector2f> anchorPoints() {
        return anchors;
    }

    /**
     * TODO: document {@code dualPoints}.
     *
     * @return TODO: describe
     */
    public ArrayList<Vector2f> dualPoints() {
        return dualPoints;
    }

    /**
     * TODO: document {@code edgeIndices}.
     *
     * @return TODO: describe
     */
    public ArrayList<int[]> edgeIndices() {
        return edges;
    }

    /**
     * TODO: document {@code horizontalEdgeMean}.
     *
     * @return TODO: describe
     */
    public float horizontalEdgeMean() {
        return horizontalEdgeMean;
    }

    /**
     * TODO: document {@code verticalEdgeMean}.
     *
     * @return TODO: describe
     */
    public float verticalEdgeMean() {
        return verticalEdgeMean;
    }

    /**
     * TODO: document {@code horizontalEdgeStdDev}.
     *
     * @return TODO: describe
     */
    public float horizontalEdgeStdDev() {
        return horizontalEdgeStdDev;
    }

    /**
     * TODO: document {@code verticalEdgeStdDev}.
     *
     * @return TODO: describe
     */
    public float verticalEdgeStdDev() {
        return verticalEdgeStdDev;
    }

    /**
     * TODO: document {@code toCoordString}.
     *
     * @return TODO: describe
     */
    @Override
    public String toCoordString() {
        return "X:"
                + (int) MainScene.camera.screenTransformX(MainScene.mouse.normalizedPosX - MainScene.MAIN_VIEW_OFFSET_X)
                + " Y:" + (int) MainScene.camera
                        .screenTransformY(MainScene.mouse.normalizedPosY - MainScene.MAIN_VIEW_OFFSET_Y);
    }

    /**
     * TODO: document {@code allowsPoint}.
     *
     * @param pt TODO: describe
     * @return TODO: describe
     */
    @Override
    public boolean allowsPoint(PointND pt) {
        return pt instanceof PointND.Double || pt instanceof PointND.Float;
    }

    /**
     * TODO: document {@code allowableTypes}.
     *
     * @return TODO: describe
     */
    @SuppressWarnings("unchecked")
    @Override
    public Class<? extends PointCollection>[] allowableTypes() {
        return new Class[] { PointND.Double.class, PointND.Float.class };
    }

    /**
     * TODO: document {@code draw}.
     *
     * @param camera TODO: describe
     * @param gridLineThickness TODO: describe
     */
    @Override
    public void draw(Camera2D camera, float gridLineThickness) {
    }

    /**
     * TODO: document {@code coordinateToNearestGridPoint}.
     *
     * @param mouseX TODO: describe
     * @param mouseY TODO: describe
     * @return TODO: describe
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
