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
    }

    @Override
    public void initGL() throws UnsupportedEncodingException, IOException {
        super.initGL();
        circle = new SDFCircleSimple();
        camera2D.calculateCameraTransform(cameraBounds);
        camera2D.updateView(DEFAULT_VIEW);

        initCodePane("Circle SDF", circle.shader, circle);
    }

    @Override
    public void drawScene() {
        camera2D.updateSize(Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight);
        camera2D.resetZIndex();
        camera2D.updateView(DEFAULT_VIEW);

        float cx = camera2D.getBounds().viewWidth / 2f;
        float cy = camera2D.getBounds().viewHeight / 2f;
        float radius = Math.min(cx, cy);

        Vector2f center = new Vector2f(cx, cy);
        circle.draw(center, radius, Color.BLUE_WHITE, camera2D);
        drawUI();
    }

}
