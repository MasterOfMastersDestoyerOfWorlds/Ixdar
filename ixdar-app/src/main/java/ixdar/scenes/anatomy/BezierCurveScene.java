package ixdar.scenes.anatomy;

import org.joml.Vector2f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.tsp.points.PointND;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFBezier;
import ixdar.scenes.Scene;

@SceneAnnotation(id = "bezier-curve-canvas")
public class BezierCurveScene extends Scene {
    public static final double ENDPOINT_X = 0.7;
    public static final double ENDPOINT_Y = 0.2;
    public static final double CONTROL_X = 0.35;
    public static final double CONTROL_Y = 0.5;
    public static final float STROKE_WIDTH_PIXELS = 40f;
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
        point1 = new PointND.Double(-ENDPOINT_X, ENDPOINT_Y);
        point2 = new PointND.Double(ENDPOINT_X, -ENDPOINT_Y);
        control = new PointND.Double(-CONTROL_X, -CONTROL_Y);
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

        Vector2f[] screenSpaceVectors = camera2D.pointsToScreenSpace(point1, control, point2);
        bezier.lineWidth = STROKE_WIDTH_PIXELS;
        bezier.draw(screenSpaceVectors[0], screenSpaceVectors[1], screenSpaceVectors[2], Color.RED, Color.GREEN,
                camera2D);
    }

}
