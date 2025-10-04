package shell.ui.scenes;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.joml.Vector2f;

import shell.point.PointND;
import shell.render.color.Color;
import shell.render.sdf.SDFBezier;
import shell.annotations.SceneAnnotation;

@SceneAnnotation(id = "bezier-curve-canvas")
public class BezierCurveScene extends Scene {

    SDFBezier bezier;
    public PointND point2;
    public PointND point1;
    public PointND control;

    public BezierCurveScene() {
        super();
    }

    @Override
    public void initPoints() {
        super.initPoints();
        point1 = new PointND.Double(-0.7, 0.2);
        point2 = new PointND.Double(0.7, -0.2);
        control = new PointND.Double(-0.35, -0.5);
        shell.add(point1);
        shell.add(point2);
        shell.add(control);
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
        Vector2f[] screenSpaceVectors = camera2D.pointsToScreenSpace(point1, control, point2);
        bezier.pA = screenSpaceVectors[0];
        bezier.pControl = screenSpaceVectors[1];
        bezier.pB = screenSpaceVectors[2];
        bezier.lineWidth = 1f;
        bezier.c2 = Color.GREEN;
        bezier.draw(0f, 0f, cx, cy, Color.RED, camera2D);
    }

}
