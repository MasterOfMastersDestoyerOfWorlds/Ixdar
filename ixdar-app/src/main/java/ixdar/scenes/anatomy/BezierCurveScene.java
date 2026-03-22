package ixdar.scenes.anatomy;

import org.joml.Vector2f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.point.PointND;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFBezier;
import ixdar.scenes.Scene;

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
    public void initGL() {
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
