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

    private Segment lineSegment;
    public PointND point2;
    public PointND point1;

    public DashedLineRoundScene() {
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
        lineSegment.setStroke(10 * Drawing.MIN_THICKNESS * camera2D.ScaleFactor, true, 0.2f, 0.0f, true, false, camera2D);
        initCodePane("Dashed Line Round SDF", lineSegment.getShader(), lineSegment);
    }

    @Override
    public void drawScene() {
        super.drawScene();
        lineSegment.setStroke(10 * Drawing.MIN_THICKNESS * camera2D.ScaleFactor, true, 0.4f, 0.0f, true, false, camera2D);        
        Color startColor = Color.RED;
        Color endColor = Color.GREEN;
        Drawing.drawGradientSegment(lineSegment, startColor, endColor, camera2D);
    }

}
