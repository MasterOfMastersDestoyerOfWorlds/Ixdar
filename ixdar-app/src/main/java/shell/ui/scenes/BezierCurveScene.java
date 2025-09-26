package shell.ui.scenes;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import shell.point.PointND;
import shell.render.color.Color;
import shell.render.sdf.SDFBezier;
import shell.annotations.SceneAnnotation;

@SceneAnnotation(id = "bezier-curve-canvas")
public class BezierCurveScene extends Scene {

    SDFBezier bezier;
    public PointND point2;
    public PointND point1;

    public BezierCurveScene() {
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
    public void initGL() throws UnsupportedEncodingException, IOException {
        super.initGL();
        bezier = new SDFBezier();
        initCodePane("Bezier SDF", bezier.bezierShader, bezier);
    }

    @Override
    public void drawScene() {
        super.drawScene();
        
        float cx = camera2D.getBounds().viewWidth;
        float cy = camera2D.getBounds().viewHeight;
        bezier.draw(0f, 0f, cx, cy, Color.RED, camera2D);
    }

}
