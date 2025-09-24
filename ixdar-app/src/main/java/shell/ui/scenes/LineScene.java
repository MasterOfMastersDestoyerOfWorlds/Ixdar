package shell.ui.scenes;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import shell.knot.Knot;
import shell.knot.Segment;
import shell.point.PointND;
import shell.render.color.Color;
import shell.render.sdf.SDFLine;
import shell.ui.Drawing;
import shell.annotations.SceneAnnotation;

@SceneAnnotation(id = "line-canvas")
public class LineScene extends Scene {

    private SDFLine line;
    private Segment lineSegment;
    public PointND point2;
    public PointND point1;

    public LineScene() {
        super();
    }

    @Override
    public void initPoints() {
        super.initPoints();
        point1 = new PointND.Double(-0.8, 0.0);
        point2 = new PointND.Double(0.8, 0.0);
        shell.add(point1);
        shell.add(point2);
    }


    @Override
    public void initGL() throws UnsupportedEncodingException, IOException {
        super.initGL();
        Knot knot1 = new Knot(point1, shell);
        Knot knot2 = new Knot(point2, shell);
        lineSegment = new Segment(knot1, knot2, distanceMatrix);
        initCodePane("Line SDF", line.shader, lineSegment);
    }


    @Override
    public void drawScene() {
        super.drawScene();
        lineSegment.setStroke(20*Drawing.MIN_THICKNESS * camera2D.ScaleFactor, false, 1f, 0f, true, false);
        Color startColor = Color.RED;
        Color endColor = Color.GREEN;
        Drawing.drawGradientSegment(lineSegment, startColor, endColor, camera2D);
    }

}
