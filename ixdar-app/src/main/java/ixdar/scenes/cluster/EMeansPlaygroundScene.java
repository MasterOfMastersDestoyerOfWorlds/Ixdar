package ixdar.scenes.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Vector2f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.tsp.points.PointND;
import ixdar.geometry.tsp.points.PointSet;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.sdf.SDFCircle;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.graphics.render.sdf.SDFRoundedRect;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.text.HyperString;
import ixdar.input.CanvasScroll;
import ixdar.input.PointerDispatcher;
import ixdar.numeric.cluster.EMeans;
import ixdar.numeric.cluster.LloydKMeans;
import ixdar.numeric.cluster.NearestCentroid;
import ixdar.numeric.cluster.ThreeArmSpirals;
import ixdar.numeric.cluster.VoronoiCells;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.Scene;
import ixdar.tools.ControlHint;

/**
 * A 2D playground after https://e-means.github.io/playground.html: e-means
 * (left) and Lloyd's k-means (right) cluster the same three-arm spirals from
 * the same starting centroids, drawn with their Voronoi cells and animated pass
 * by pass, over a chart of train and held-out test MSE.
 */
@SceneAnnotation(id = "e-means-playground")
public class EMeansPlaygroundScene extends Scene {
    public static final int DEFAULT_POINTS = 100_000;
    public static final int DEFAULT_CLUSTERS = 500;
    public static final int DEFAULT_EPOCHS = 5;
    public static final int DEFAULT_LLOYD_EPOCHS = 25;
    public static final int DRAWN_POINTS = 1500;
    public static final int MAX_CELLS_DRAWN = 700;
    public static final int TEST_POINTS_PER_TRAIN_POINT = 10;
    public static final int MIN_POINTS = 1000;
    public static final int MAX_POINTS = 1_600_000;
    public static final int MIN_CLUSTERS = 2;
    public static final int MAX_EPOCHS = 50;
    public static final float FRAMES_PER_PASS = 60f;
    public static final float MIN_SPEED = 0.25f;
    public static final float MAX_SPEED = 16f;
    public static final double NANOS_PER_SECOND = 1e9;
    public static final double PERCENT = 100;
    public static final double CHART_MARGIN = 0.05;
    public static final float PANEL_HALF = 1f;
    public static final float PANEL_CENTRE = 1.1f;
    public static final float CHART_TOP = -1.35f;
    public static final float CHART_BOTTOM = -2.35f;
    public static final float CHART_LEFT = -2.1f;
    public static final float CHART_RIGHT = 2.1f;
    public static final float TEXT_HEIGHT = 0.06f;
    public static final float TITLE_LIFT = 0.07f;
    public static final float LEGEND_INSET = 0.12f;
    public static final float CANVAS_MARGIN = 0.15f;
    public static final float POINT_RADIUS = 0.005f;
    public static final float CENTROID_RADIUS = 0.011f;
    public static final float CELL_WIDTH = 0.0012f;
    public static final float CURVE_WIDTH = 0.004f;
    public static final float MIN_LINE_PIXELS = 0.5f;
    public static final float MIN_DOT_PIXELS = 1.5f;
    public static final int CURVES = 4;
    public static final String MSE_FORMAT = "%.5f";
    public static final String LOWER = "lower";
    public static final String HIGHER = "higher";

    /** Training points. */
    public int pointCount = DEFAULT_POINTS;

    /** Seed of the spirals and of both runs. */
    public long seed = EMeans.DEFAULT_SEED;

    /** Centroids in both runs. */
    public int clusterCount = DEFAULT_CLUSTERS;

    /** Passes e-means makes. */
    public int emeansEpochs = DEFAULT_EPOCHS;

    /** Iterations Lloyd's k-means makes. */
    public int lloydEpochs = DEFAULT_LLOYD_EPOCHS;

    /** Passes shown per second of animation, at 60 frames a second. */
    public float playSpeed = 1f;

    /** Whether a run is animating. */
    public boolean training;

    /** Passes of animation shown so far. */
    public float clock;

    /** CPU time e-means' batches have taken this run, MSE readouts excluded. */
    public long emeansNanos;

    /** CPU time Lloyd's iterations have taken this run, MSE readouts excluded. */
    public long lloydNanos;

    /** The e-means run on the left. */
    public EMeans emeans = new EMeans(2, DEFAULT_CLUSTERS);

    /** The Lloyd run on the right. */
    public LloydKMeans lloyd = new LloydKMeans(2, DEFAULT_CLUSTERS);

    /**
     * Each curve's (pass, MSE) pairs: e-means train, e-means test, Lloyd train,
     * Lloyd test.
     */
    public final List<List<double[]>> curves = new ArrayList<>();

    /**
     * Draws the panels and the chart; its point set is the canvas's bounding box.
     */
    public Camera2D canvas;

    /** Pans and zooms {@link #canvas}. */
    public CanvasScroll scroll;

    /**
     * Each side's Voronoi edges, packed {@code x0, y0, x1, y1} in the side's own
     * panel frame.
     */
    public double[][] cellEdges = { new double[0], new double[0] };

    private double[] trainPoints = new double[0];
    private double[] testPoints = new double[0];
    private final SDFCircle dot = new SDFCircle();
    private final SDFLine line = new SDFLine();
    private final SDFRoundedRect backdrop = new SDFRoundedRect();
    private final ArrayList<HyperString> labels = new ArrayList<>();
    private final ArrayList<Vector2f> labelPositions = new ArrayList<>();

    @Override
    public void initPoints() {
        super.initPoints();
        float left = CHART_LEFT - CANVAS_MARGIN;
        float bottom = CHART_BOTTOM - CANVAS_MARGIN - 2 * TITLE_LIFT;
        float right = CHART_RIGHT + CANVAS_MARGIN;
        float top = PANEL_HALF + CANVAS_MARGIN + TITLE_LIFT;
        PointSet bounds = new PointSet();
        bounds.add(new PointND.Double(left, bottom));
        bounds.add(new PointND.Double(right, top));
        canvas = new Camera2D(Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(),
                1.0f, 0.0f, 0.0f, bounds);
        canvas.initCamera(webViews, DEFAULT_VIEW);
        scroll = new CanvasScroll(canvas, true, () -> active);
        scroll.setContent(left, bottom, right, top);
    }

    /**
     * Hand the pointer to the canvas's pan and zoom, frame the canvas and draw the
     * starting state.
     */
    @Override
    public void initGL() {
        super.initGL();
        MenuBox.menuVisible = false;
        mouse.controller = scroll;
        PointerDispatcher.current().subscribe(canvas.mainViewBounds, scroll);
        frameAll();
        restart();
    }

    /**
     * Frame the two panels and the chart.
     *
     * @return true: the canvas is the scene's whole view
     */
    @Override
    public boolean frameAll() {
        scroll.frame((float) canvas.ps.get(0).getCoord(0), (float) canvas.ps.get(0).getCoord(1),
                (float) canvas.ps.get(1).getCoord(0), (float) canvas.ps.get(1).getCoord(1));
        return true;
    }

    /**
     * T trains; N, K, S, E, L and P raise the point count, cluster count, seed,
     * e-means passes, Lloyd iterations and play speed, and with shift lower them.
     * Each change restarts both runs.
     */
    @Override
    public void setControls(List<ControlHint> controls) {
        controls.add(new ControlHint(Keys.DRAG, "pan the canvas"));
        controls.add(new ControlHint(Keys.SCROLL, true, false, "zoom about the cursor"));
        controls.add(new ControlHint("train", Keys.T, "train both from the start", () -> {
            restart();
            training = true;
        }));
        controls.add(new ControlHint("fewer-points", Keys.N, false, true, "halve the points",
                () -> resize(() -> pointCount = Math.max(MIN_POINTS, pointCount / 2))));
        controls.add(new ControlHint("more-points", Keys.N, "double the points",
                () -> resize(() -> pointCount = Math.min(MAX_POINTS, 2 * pointCount))));
        controls.add(new ControlHint("fewer-clusters", Keys.K, false, true, "halve the clusters",
                () -> resize(() -> clusterCount = Math.max(MIN_CLUSTERS, clusterCount / 2))));
        controls.add(new ControlHint("more-clusters", Keys.K, "double the clusters",
                () -> resize(() -> clusterCount = Math.min(pointCount, 2 * clusterCount))));
        controls.add(new ControlHint("previous-seed", Keys.S, false, true, "previous seed",
                () -> resize(() -> seed--)));
        controls.add(new ControlHint("next-seed", Keys.S, "next seed", () -> resize(() -> seed++)));
        controls.add(new ControlHint("fewer-emeans-epochs", Keys.E, false, true, "one fewer e-means pass",
                () -> resize(() -> emeansEpochs = Math.max(1, emeansEpochs - 1))));
        controls.add(new ControlHint("more-emeans-epochs", Keys.E, "one more e-means pass",
                () -> resize(() -> emeansEpochs = Math.min(MAX_EPOCHS, emeansEpochs + 1))));
        controls.add(new ControlHint("fewer-lloyd-epochs", Keys.L, false, true, "one fewer Lloyd iteration",
                () -> resize(() -> lloydEpochs = Math.max(1, lloydEpochs - 1))));
        controls.add(new ControlHint("more-lloyd-epochs", Keys.L, "one more Lloyd iteration",
                () -> resize(() -> lloydEpochs = Math.min(MAX_EPOCHS, lloydEpochs + 1))));
        controls.add(new ControlHint("slower", Keys.P, false, true, "play half as fast",
                () -> playSpeed = Math.max(MIN_SPEED, playSpeed / 2)));
        controls.add(new ControlHint("faster", Keys.P, "play twice as fast",
                () -> playSpeed = Math.min(MAX_SPEED, 2 * playSpeed)));
        super.setControls(controls);
    }

    /**
     * Advance training by one frame of the animation clock, timing each side's own
     * work, then draw the panels, the chart and the readouts on the canvas, and the
     * scene's chrome over them.
     */
    @Override
    public void drawScene() {
        if (training) {
            clock += playSpeed / FRAMES_PER_PASS;
            boolean moved = false;
            while (!emeans.finished && emeans.passesDone() < clock) {
                int pass = emeans.epoch;
                long started = System.nanoTime();
                emeans.step();
                emeansNanos += System.nanoTime() - started;
                moved = true;
                if (emeans.epoch != pass) {
                    record(0, emeans.passesDone(), emeans.centroids);
                }
            }
            while (lloyd.iterations < lloydEpochs && lloyd.iterations + 1 <= clock) {
                long started = System.nanoTime();
                lloyd.iterate();
                lloydNanos += System.nanoTime() - started;
                moved = true;
                record(2, lloyd.iterations, lloyd.centroids);
            }
            training = !emeans.finished || lloyd.iterations < lloydEpochs;
            if (moved) {
                cellEdges = cells();
            }
        }
        canvas.resetZIndex();
        canvas.setZIndex(camera);
        canvas.updateView(DEFAULT_VIEW);
        canvas.calculateCameraTransform(canvas.ps);
        Palette shown = palette;
        float pixelsPerUnit = canvas.screenSize(1f);
        float[][] boxes = {
                { -PANEL_CENTRE - PANEL_HALF, -PANEL_HALF, 2 * PANEL_HALF, 2 * PANEL_HALF },
                { PANEL_CENTRE - PANEL_HALF, -PANEL_HALF, 2 * PANEL_HALF, 2 * PANEL_HALF },
                { CHART_LEFT, CHART_BOTTOM, CHART_RIGHT - CHART_LEFT, CHART_TOP - CHART_BOTTOM } };
        for (float[] box : boxes) {
            backdrop.draw(canvas.pointTransformX(box[0]), canvas.pointTransformY(box[1]), box[2] * pixelsPerUnit,
                    box[3] * pixelsPerUnit, 0f, MIN_LINE_PIXELS, shown.panel, shown.panelBorder, canvas);
        }
        float[] centres = { -PANEL_CENTRE, PANEL_CENTRE };
        double[][] sides = { emeans.centroids, lloyd.centroids };
        int drawnPoints = Math.min(pointCount, DRAWN_POINTS);
        float pointRadius = Math.max(MIN_DOT_PIXELS, POINT_RADIUS * pixelsPerUnit);
        float centroidRadius = Math.max(MIN_DOT_PIXELS, CENTROID_RADIUS * pixelsPerUnit);
        Vector2f from = new Vector2f();
        Vector2f to = new Vector2f();
        line.setStroke(Math.max(MIN_LINE_PIXELS, CELL_WIDTH * pixelsPerUnit), false);
        for (int side = 0; side < sides.length; side++) {
            for (int point = 0; point < drawnPoints; point++) {
                dot.draw(from.set(canvas.pointTransformX(centres[side] + trainPoints[2 * point]),
                        canvas.pointTransformY(trainPoints[2 * point + 1])), pointRadius, shown.text, canvas);
            }
            double[] edges = cellEdges[side];
            for (int at = 0; at < edges.length; at += VoronoiCells.FLOATS_PER_SEGMENT) {
                from.set(canvas.pointTransformX(centres[side] + edges[at]), canvas.pointTransformY(edges[at + 1]));
                to.set(canvas.pointTransformX(centres[side] + edges[at + 2]), canvas.pointTransformY(edges[at + 3]));
                line.draw(from, to, curveColour(2 * side), canvas);
            }
            for (int cluster = 0; cluster < clusterCount; cluster++) {
                dot.draw(from.set(canvas.pointTransformX(centres[side] + sides[side][2 * cluster]),
                        canvas.pointTransformY(sides[side][2 * cluster + 1])), centroidRadius, curveColour(2 * side),
                        canvas);
            }
        }
        double maxPasses = Math.max(1, Math.max(emeansEpochs, lloydEpochs));
        double maxError = 0;
        double minError = Double.POSITIVE_INFINITY;
        for (List<double[]> curve : curves) {
            for (double[] sample : curve) {
                maxError = Math.max(maxError, sample[1]);
                minError = Math.min(minError, sample[1]);
            }
        }
        double errorSpan = maxError - minError;
        minError -= CHART_MARGIN * errorSpan;
        double errorRange = Math.max(Double.MIN_NORMAL, (1 + 2 * CHART_MARGIN) * errorSpan);
        line.setStroke(Math.max(MIN_LINE_PIXELS, CURVE_WIDTH * pixelsPerUnit), false);
        for (int curve = 0; curve < curves.size(); curve++) {
            List<double[]> samples = curves.get(curve);
            for (int sample = 1; sample < samples.size(); sample++) {
                double[] before = samples.get(sample - 1);
                double[] after = samples.get(sample);
                from.set(canvas.pointTransformX(CHART_LEFT + (CHART_RIGHT - CHART_LEFT) * before[0] / maxPasses),
                        canvas.pointTransformY(CHART_BOTTOM
                                + (CHART_TOP - CHART_BOTTOM) * (before[1] - minError) / errorRange));
                to.set(canvas.pointTransformX(CHART_LEFT + (CHART_RIGHT - CHART_LEFT) * after[0] / maxPasses),
                        canvas.pointTransformY(CHART_BOTTOM
                                + (CHART_TOP - CHART_BOTTOM) * (after[1] - minError) / errorRange));
                line.draw(from, to, curveColour(curve), canvas);
            }
        }
        labels.clear();
        labelPositions.clear();
        double emeansSeconds = emeansNanos / NANOS_PER_SECOND;
        double lloydSeconds = lloydNanos / NANOS_PER_SECOND;
        double testGain = 1 - last(1) / Math.max(Double.MIN_NORMAL, last(3));
        double trainGain = 1 - last(0) / Math.max(Double.MIN_NORMAL, last(2));
        boolean bothDone = emeans.finished && lloyd.iterations == lloydEpochs;
        String[] texts = {
                String.format(Locale.ROOT, "e-means: train MSE " + MSE_FORMAT + ", test MSE " + MSE_FORMAT
                        + " after %.2f epochs, CPU %.2f s", last(0), last(1), emeans.passesDone(), emeansSeconds),
                String.format(Locale.ROOT, "Lloyd k-means: train MSE " + MSE_FORMAT + ", test MSE " + MSE_FORMAT
                        + " after %d epochs, CPU %.2f s", last(2), last(3), lloyd.iterations, lloydSeconds),
                String.format(Locale.ROOT, "n %d, k %d, seed %d, e-means %d epochs (batch %d, alpha0 %.3g), "
                        + "Lloyd %d epochs, speed %.2fx%s", pointCount, clusterCount, seed, emeansEpochs,
                        emeans.batchSize, emeans.alpha0, lloydEpochs, playSpeed,
                        training ? ", training" : ", T trains"),
                !bothDone ? " "
                        : String.format(Locale.ROOT, "e-means: %.1f%% %s test loss and %.1f%% %s train "
                                + "loss than Lloyd, in %d epochs instead of %d, and %.2f s of CPU time instead of %.2f s",
                                PERCENT * Math.abs(testGain), testGain >= 0 ? LOWER : HIGHER,
                                PERCENT * Math.abs(trainGain), trainGain >= 0 ? LOWER : HIGHER, emeansEpochs,
                                lloydEpochs, emeansSeconds, lloydSeconds),
                "e-means train", "e-means test", "Lloyd train", "Lloyd test" };
        float[][] anchors = {
                { -PANEL_CENTRE, PANEL_HALF + TITLE_LIFT }, { PANEL_CENTRE, PANEL_HALF + TITLE_LIFT },
                { 0f, CHART_BOTTOM - TITLE_LIFT }, { 0f, CHART_BOTTOM - 2 * TITLE_LIFT },
                { CHART_RIGHT - 2 * LEGEND_INSET, CHART_TOP - LEGEND_INSET },
                { CHART_RIGHT - 2 * LEGEND_INSET, CHART_TOP - 2 * LEGEND_INSET },
                { CHART_RIGHT - 2 * LEGEND_INSET, CHART_TOP - 3 * LEGEND_INSET },
                { CHART_RIGHT - 2 * LEGEND_INSET, CHART_TOP - 4 * LEGEND_INSET } };
        Color[] colours = { curveColour(0), curveColour(2), shown.text, shown.headingText,
                curveColour(0), curveColour(1), curveColour(2), curveColour(3) };
        for (int text = 0; text < texts.length; text++) {
            HyperString label = new HyperString();
            label.addWord(texts[text], colours[text]);
            labels.add(label);
            labelPositions.add(new Vector2f(canvas.pointTransformX(anchors[text][0]),
                    canvas.pointTransformY(anchors[text][1])));
        }
        Drawing.getDrawing().font.drawHyperStrings(labels, labelPositions, canvas.screenSize(TEXT_HEIGHT), canvas);
        scroll.drawBars();
        camera2D.setZIndex(canvas);
        super.drawScene();
    }

    /**
     * Draw new spirals and test points for the settings, start both runs from the
     * same sampled centroids and reset the chart; training waits for T.
     */
    public void restart() {
        training = false;
        clock = 0;
        emeansNanos = 0;
        lloydNanos = 0;
        trainPoints = ThreeArmSpirals.points(pointCount, seed);
        int testCount = Math.max(1, pointCount / TEST_POINTS_PER_TRAIN_POINT);
        testPoints = ThreeArmSpirals.points(testCount, ~seed);
        double[] initial = EMeans.sampleCentroids(trainPoints, pointCount, clusterCount, 2, seed);
        emeans = new EMeans(2, clusterCount);
        emeans.seed = seed;
        emeans.epochs = emeansEpochs;
        emeans.begin(trainPoints, pointCount, initial);

        lloyd = new LloydKMeans(2, clusterCount);
        lloyd.begin(trainPoints, pointCount, initial);
        curves.clear();
        for (int curve = 0; curve < CURVES; curve++) {
            curves.add(new ArrayList<>());
        }
        record(0, 0, initial);
        record(2, 0, initial);
        cellEdges = cells();
    }

    private void resize(Runnable change) {
        change.run();
        clusterCount = Math.min(clusterCount, pointCount);
        restart();
    }

    /**
     * Record the train and test MSE of one side's centroids at a pass count, into
     * the side's two curves (0 for e-means, 2 for Lloyd).
     */
    private void record(int firstCurve, double passes, double[] centroids) {
        curves.get(firstCurve).add(new double[] { passes, EMeans.meanSquaredError(trainPoints, pointCount,
                centroids, clusterCount, 2, NearestCentroid.EUCLIDEAN) });
        curves.get(firstCurve + 1).add(new double[] { passes, EMeans.meanSquaredError(testPoints,
                testPoints.length / 2, centroids, clusterCount, 2, NearestCentroid.EUCLIDEAN) });
    }

    private double[][] cells() {
        if (clusterCount > MAX_CELLS_DRAWN) {
            return new double[][] { new double[0], new double[0] };
        }
        return new double[][] {
                VoronoiCells.edges(emeans.centroids, clusterCount, -PANEL_HALF, -PANEL_HALF, PANEL_HALF, PANEL_HALF),
                VoronoiCells.edges(lloyd.centroids, clusterCount, -PANEL_HALF, -PANEL_HALF, PANEL_HALF, PANEL_HALF) };
    }

    private double last(int curve) {
        List<double[]> samples = curves.isEmpty() ? List.of() : curves.get(curve);
        return samples.isEmpty() ? 0 : samples.get(samples.size() - 1)[1];
    }

    private Color curveColour(int curve) {
        return Palette.entry(palette.overlayGroup, curve);
    }
}
