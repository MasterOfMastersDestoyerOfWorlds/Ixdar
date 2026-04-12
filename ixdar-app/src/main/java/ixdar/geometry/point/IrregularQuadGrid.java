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
     * Creates an IrregularQuadGrid from a layout with explicit row/col counts.
     */
    public IrregularQuadGrid(IrregularQuadLayoutGenerator.Layout layout, long seed, int relaxIterations,
            float jitterRatio, int rows, int cols) {
        this.anchors = layout == null || layout.points == null ? new ArrayList<>() : new ArrayList<>(layout.points);
        this.dualPoints = layout == null || layout.dualPoints == null ? new ArrayList<>()
                : new ArrayList<>(layout.dualPoints);
        this.edges = layout == null || layout.edges == null ? new ArrayList<>() : new ArrayList<>(layout.edges);
        this.seed = seed;
        this.relaxIterations = relaxIterations;
        this.jitterRatio = jitterRatio;
        this.rows = rows;
        this.cols = cols;
        this.horizontalEdgeMean = layout == null ? 0f : layout.horizontalEdgeMean;
        this.verticalEdgeMean = layout == null ? 0f : layout.verticalEdgeMean;
        this.horizontalEdgeStdDev = layout == null ? 0f : layout.horizontalEdgeStdDev;
        this.verticalEdgeStdDev = layout == null ? 0f : layout.verticalEdgeStdDev;
    }

    public long seed() {
        return seed;
    }

    public int relaxIterations() {
        return relaxIterations;
    }

    public float jitterRatio() {
        return jitterRatio;
    }

    public int rows() {
        return rows;
    }

    public int cols() {
        return cols;
    }

    public int anchorCount() {
        return anchors.size();
    }

    public int dualPointCount() {
        return dualPoints.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public ArrayList<Vector2f> anchorPoints() {
        return anchors;
    }

    public ArrayList<Vector2f> dualPoints() {
        return dualPoints;
    }

    public ArrayList<int[]> edgeIndices() {
        return edges;
    }

    public float horizontalEdgeMean() {
        return horizontalEdgeMean;
    }

    public float verticalEdgeMean() {
        return verticalEdgeMean;
    }

    public float horizontalEdgeStdDev() {
        return horizontalEdgeStdDev;
    }

    public float verticalEdgeStdDev() {
        return verticalEdgeStdDev;
    }

    @Override
    public String toCoordString() {
        return "X:"
                + (int) MainScene.camera.screenTransformX(MainScene.mouse.normalizedPosX - MainScene.MAIN_VIEW_OFFSET_X)
                + " Y:" + (int) MainScene.camera
                        .screenTransformY(MainScene.mouse.normalizedPosY - MainScene.MAIN_VIEW_OFFSET_Y);
    }

    @Override
    public boolean allowsPoint(PointND pt) {
        return pt instanceof PointND.Double || pt instanceof PointND.Float;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Class<? extends PointCollection>[] allowableTypes() {
        return new Class[] { PointND.Double.class, PointND.Float.class };
    }

    @Override
    public void draw(Camera2D camera, float gridLineThickness) {
    }

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
