package ixdar.scenes.anatomy;

import org.joml.Vector2f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.point.PointND;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFBezier;
import ixdar.scenes.Scene;

@SceneAnnotation(id = "bezier-curve-canvas")
public class BezierCurveScene extends Scene {
    public static final double NUM_0_7 = 0.7;
    public static final double NUM_0_2 = 0.2;
    public static final double NUM_0_35 = 0.35;
    public static final double NUM_0_5 = 0.5;
    public static final float NUM_1 = 1f;
    public static final float NUM_0 = 0f;
    public PointND point2;
    public PointND point1;
    public PointND control;

    SDFBezier bezier;

    /**
     * Anatomy demo for a quadratic Bezier curve rendered via SDF.
     */
    public BezierCurveScene() {
        super();
    }

    /**
     * Seed the shell with the two endpoints and the single control point
     * used by the quadratic Bezier.
     */
    @Override
    public void initPoints() {
        super.initPoints();
        point1 = new PointND.Double(-NUM_0_7, NUM_0_2);
        point2 = new PointND.Double(NUM_0_7, -NUM_0_2);
        control = new PointND.Double(-NUM_0_35, -NUM_0_5);
        shell.add(point1);
        shell.add(point2);
        shell.add(control);
    }

    /**
     * Allocate the SDF Bezier drawable and attach the code pane bound
     * to its shader.
     */
    @Override
    public void initGL() {
        super.initGL();
        bezier = new SDFBezier();
        initCodePane("Bezier SDF", bezier.bezierShader, bezier);
    }

    /**
     * Project the three world-space anchors into screen space and draw
     * the quadratic Bezier with a red start and green end color.
     */
    @Override
    public void drawScene() {
        super.drawScene();

        float cx = camera2D.getBounds().viewWidth;
        float cy = camera2D.getBounds().viewHeight;
        Vector2f[] screenSpaceVectors = camera2D.pointsToScreenSpace(point1, control, point2);
        bezier.pA = screenSpaceVectors[0];
        bezier.pControl = screenSpaceVectors[1];
        bezier.pB = screenSpaceVectors[2];
        bezier.lineWidth = NUM_1;
        bezier.c2 = Color.GREEN;
        bezier.draw(NUM_0, NUM_0, cx, cy, Color.RED, camera2D);
    }

}
