package ixdar.scenes.anatomy;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.point.PointND;
import ixdar.graphics.render.color.Color;
import ixdar.gui.ui.Drawing;
import ixdar.scenes.Scene;

@SceneAnnotation(id = "dashed-line-canvas")
public class DashedLineScene extends Scene {
    public static final double ENDPOINT_OFFSET = 0.8;
    public static final int STROKE_WIDTH_SCALE = 20;
    public static final float DASH_LENGTH = 0.2f;
    public PointND point2;
    public PointND point1;

    private Segment lineSegment;

    /**
     * Anatomy demo for a horizontal SDF segment rendered as a dashed line
     * with square caps.
     */
    public DashedLineScene() {
        super();
    }

    /**
     * Seed the shell with the two horizontal endpoints used as draggable
     * line anchors.
     */
    @Override
    public void initPoints() {
        super.initPoints();
        point1 = new PointND.Double(-ENDPOINT_OFFSET, 0.0);
        point2 = new PointND.Double(ENDPOINT_OFFSET, 0.0);
        shell.add(point1);
        shell.add(point2);
    }

    /**
     * Build the segment between the two anchor knots, configure the
     * dashed stroke pattern, and attach the code pane bound to its SDF
     * shader.
     */
    @Override
    public void initGL() {
        super.initGL();
        Knot knot1 = new Knot(point1, shell);
        Knot knot2 = new Knot(point2, shell);
        lineSegment = new Segment(knot1, knot2, distanceMatrix);
        lineSegment.setStroke(STROKE_WIDTH_SCALE * Drawing.MIN_THICKNESS * camera2D.ScaleFactor, true, DASH_LENGTH, 0f, false, false, false, camera2D);
        initCodePane("Dashed Line SDF", lineSegment.getShader(), lineSegment);
    }

    /**
     * Re-apply the dashed stroke each frame (so resize/zoom updates take
     * effect) and draw the segment with a red-to-green gradient body.
     */
    @Override
    public void drawScene() {
        super.drawScene();
        lineSegment.setStroke(STROKE_WIDTH_SCALE * Drawing.MIN_THICKNESS * camera2D.ScaleFactor, true, DASH_LENGTH, 0f, false, false, false, camera2D);
        Color startColor = Color.RED;
        Color endColor = Color.GREEN;

        Drawing.drawGradientSegment(lineSegment, startColor, endColor, camera2D);
    }

}
