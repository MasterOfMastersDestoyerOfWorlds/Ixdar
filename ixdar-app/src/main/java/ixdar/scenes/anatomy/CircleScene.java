package ixdar.scenes.anatomy;

import org.joml.Vector2f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFCircleSimple;
import ixdar.scenes.Scene;

@SceneAnnotation(id = "circle-canvas")
public class CircleScene extends Scene {

    private SDFCircleSimple circle;

    /**
     * Anatomy demo for a single SDF circle filling the viewport.
     */
    public CircleScene() {
        super();
    }

    /**
     * Allocate the simple circle SDF drawable and attach the code pane
     * bound to its shader.
     */
    @Override
    public void initGL() {
        super.initGL();
        circle = new SDFCircleSimple();
        initCodePane("Circle SDF", circle.getShader(), circle);
    }

    /**
     * Center a circle in the current view and draw it with radius equal
     * to the smaller half-extent so it fits regardless of aspect ratio.
     */
    @Override
    public void drawScene() {
        super.drawScene();
        float cx = camera2D.getBounds().viewWidth / 2f;
        float cy = camera2D.getBounds().viewHeight / 2f;
        float radius = Math.min(cx, cy);

        Vector2f center = new Vector2f(cx, cy);
        circle.draw(center, radius, Color.BLUE_WHITE, camera2D);
    }

}
