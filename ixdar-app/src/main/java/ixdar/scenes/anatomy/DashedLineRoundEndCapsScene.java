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

    private Segment lineSegment;
    public PointND point2;
    public PointND point1;

    public DashedLineRoundEndCapsScene() {
        super();
    }

    @Override
    public void initPoints() {
        super.initPoints();
        point1 = new PointND.Double(-0.8, -0.8);
        point2 = new PointND.Double(0.8, 0.8);
        shell.add(point1);
        shell.add(point2);
    }

    @Override
    public void initGL() {
        super.initGL();
        Knot knot1 = new Knot(point1, shell);
        Knot knot2 = new Knot(point2, shell);
        lineSegment = new Segment(knot1, knot2, distanceMatrix);
        lineSegment.setStroke(7.5f * Drawing.MIN_THICKNESS * camera2D.ScaleFactor, true, 0.2f, 10f, false, true, camera2D);

    }

    @Override
    public void drawScene() {
        super.drawScene();
        lineSegment.setStroke(7.5f * Drawing.MIN_THICKNESS * camera2D.ScaleFactor, true, 0.2f, 10f, false, true, camera2D);
        Color startColor = Color.RED;
        Color endColor = Color.GREEN;
        Drawing.drawGradientSegment(lineSegment, startColor, endColor, camera2D);
    }

}
