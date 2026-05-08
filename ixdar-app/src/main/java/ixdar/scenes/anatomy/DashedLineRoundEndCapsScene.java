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
    public static final double NUM_0_8 = 0.8;
    public static final float NUM_7_5 = 7.5f;
    public static final float NUM_0_2 = 0.2f;
    public static final float NUM_10 = 10f;
    public PointND point2;
    public PointND point1;

    private Segment lineSegment;

    /**
     * TODO: document {@code DashedLineRoundEndCapsScene}.
     */
    public DashedLineRoundEndCapsScene() {
        super();
    }

    /**
     * TODO: document {@code initPoints}.
     */
    @Override
    public void initPoints() {
        super.initPoints();
        point1 = new PointND.Double(-NUM_0_8, -NUM_0_8);
        point2 = new PointND.Double(NUM_0_8, NUM_0_8);
        shell.add(point1);
        shell.add(point2);
    }

    /**
     * TODO: document {@code initGL}.
     */
    @Override
    public void initGL() {
        super.initGL();
        Knot knot1 = new Knot(point1, shell);
        Knot knot2 = new Knot(point2, shell);
        lineSegment = new Segment(knot1, knot2, distanceMatrix);
        lineSegment.setStroke(NUM_7_5 * Drawing.MIN_THICKNESS * camera2D.ScaleFactor, true, NUM_0_2, NUM_10, false, true, false, camera2D);

    }

    /**
     * TODO: document {@code drawScene}.
     */
    @Override
    public void drawScene() {
        super.drawScene();
        lineSegment.setStroke(NUM_7_5 * Drawing.MIN_THICKNESS * camera2D.ScaleFactor, true, NUM_0_2, NUM_10, false, true, false, camera2D);
        Color startColor = Color.RED;
        Color endColor = Color.GREEN;
        Drawing.drawGradientSegment(lineSegment, startColor, endColor, camera2D);
    }

}
