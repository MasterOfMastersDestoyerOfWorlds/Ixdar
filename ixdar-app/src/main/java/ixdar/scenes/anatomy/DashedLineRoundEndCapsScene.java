package ixdar.scenes.anatomy;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.point.PointND;
import ixdar.graphics.render.color.Color;
import ixdar.gui.ui.Drawing;
import ixdar.scenes.Scene;

@SceneAnnotation(id = "dashed-line-round-end-caps-canvas")
public class DashedLineRoundEndCapsScene extends Scene {
    public static final double ENDPOINT_OFFSET = 0.8;
    public static final float STROKE_WIDTH_SCALE = 7.5f;
    public static final float DASH_LENGTH = 0.2f;
    public static final float END_CAP_SIZE = 10f;
    public PointND point2;
    public PointND point1;

    private Segment lineSegment;

    /**
     * Anatomy demo for a diagonal SDF segment rendered as a dashed line
     * with rounded end caps.
     */
    public DashedLineRoundEndCapsScene() {
        super();
    }

    /**
     * Seed the shell with the two diagonal endpoints used as draggable
     * line anchors.
     */
    @Override
    public void initPoints() {
        super.initPoints();
        point1 = new PointND.Double(-ENDPOINT_OFFSET, -ENDPOINT_OFFSET);
        point2 = new PointND.Double(ENDPOINT_OFFSET, ENDPOINT_OFFSET);
        shell.add(point1);
        shell.add(point2);
    }

    /**
     * Build the segment between the two anchor knots and configure the
     * dashed stroke with rounded end caps.
     */
    @Override
    public void initGL() {
        super.initGL();
        Knot knot1 = new Knot(point1, shell);
        Knot knot2 = new Knot(point2, shell);
        lineSegment = new Segment(knot1, knot2, distanceMatrix);
        lineSegment.setStroke(STROKE_WIDTH_SCALE * Drawing.MIN_THICKNESS * camera2D.ScaleFactor, true, DASH_LENGTH, END_CAP_SIZE, false, true, false, camera2D);

    }

    /**
     * Re-apply the dashed-with-rounded-caps stroke each frame and draw
     * the segment with a red-to-green gradient body.
     */
    @Override
    public void drawScene() {
        super.drawScene();
        lineSegment.setStroke(STROKE_WIDTH_SCALE * Drawing.MIN_THICKNESS * camera2D.ScaleFactor, true, DASH_LENGTH, END_CAP_SIZE, false, true, false, camera2D);
        Color startColor = Color.RED;
        Color endColor = Color.GREEN;
        Drawing.drawGradientSegment(lineSegment, startColor, endColor, camera2D);
    }

}
