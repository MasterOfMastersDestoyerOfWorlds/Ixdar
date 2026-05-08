package ixdar.scenes.anatomy;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.point.PointND;
import ixdar.graphics.render.color.Color;
import ixdar.gui.ui.Drawing;
import ixdar.scenes.Scene;

@SceneAnnotation(id = "dashed-line-round-canvas")
public class DashedLineRoundScene extends Scene {
    public static final double NUM_0_8 = 0.8;
    public static final int NUM_10 = 10;
    public static final float NUM_0_2 = 0.2f;
    public static final float NUM_0_4 = 0.4f;
    public PointND point2;
    public PointND point1;

    private Segment lineSegment;

    /**
     * TODO: document {@code DashedLineRoundScene}.
     */
    public DashedLineRoundScene() {
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
        lineSegment.setStroke(NUM_10 * Drawing.MIN_THICKNESS * camera2D.ScaleFactor, true, NUM_0_2, 0.0f, true, false, false, camera2D);
        initCodePane("Dashed Line Round SDF", lineSegment.getShader(), lineSegment);
    }

    /**
     * TODO: document {@code drawScene}.
     */
    @Override
    public void drawScene() {
        super.drawScene();
        lineSegment.setStroke(NUM_10 * Drawing.MIN_THICKNESS * camera2D.ScaleFactor, true, NUM_0_4, 0.0f, true, false, false, camera2D);        
        Color startColor = Color.RED;
        Color endColor = Color.GREEN;
        Drawing.drawGradientSegment(lineSegment, startColor, endColor, camera2D);
    }

}
