package shell.ui.scenes;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.joml.Vector2f;

import shell.cameras.Camera2D;
import shell.render.color.Color;
import shell.render.sdf.SDFCircleSimple;
import shell.ui.Canvas3D;

public class CircleScene extends Scene {

    private SDFCircleSimple circle;

    public CircleScene() {
        super();
        circle = new SDFCircleSimple();
    }

    @Override
    public void initGL() throws UnsupportedEncodingException, IOException {
        super.initGL();

        camera2D = new Camera2D(
                Canvas3D.frameBufferWidth,
                Canvas3D.frameBufferHeight,
                1.0f,
                0,
                0,
                cameraBounds);

        camera2D.calculateCameraTransform(cameraBounds);
        camera2D.updateView(DEFAULT_VIEW_LEFT);

        initViews(camera2D, DEFAULT_VIEW_LEFT, DEFAULT_VIEW_RIGHT);
        initCodePane("Circle SDF", circle.shader, circle);
    }

    @Override
    public void drawScene() {
        camera2D.updateSize(Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight);
        camera2D.resetZIndex();
        camera2D.updateView(DEFAULT_VIEW_LEFT);

        float cx = camera2D.getBounds().viewWidth / 2f;
        float cy = camera2D.getBounds().viewHeight / 2f;
        float radius = Math.min(cx, cy);

        Vector2f center = new Vector2f(cx, cy);
        circle.draw(center, radius, Color.IXDAR, camera2D);

        drawUI(DEFAULT_VIEW_LEFT, DEFAULT_VIEW_RIGHT);
    }

}
