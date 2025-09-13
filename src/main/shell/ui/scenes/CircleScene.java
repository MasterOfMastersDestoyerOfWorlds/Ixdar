package shell.ui.scenes;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.joml.Vector2f;

import shell.PointSet;
import shell.cameras.Camera2D;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram.ShaderType;
import shell.ui.Canvas3D;
import shell.ui.Drawing;

public class CircleScene extends Scene {

    private PointSet cameraBounds;

    public CircleScene() {
        super();
    }

    @Override
    public void initGL() throws UnsupportedEncodingException, IOException {
        super.initGL();

        cameraBounds = new PointSet();
        cameraBounds.add(new shell.point.PointND.Double(-1.0, -1.0));
        cameraBounds.add(new shell.point.PointND.Double(1.0, -1.0));
        cameraBounds.add(new shell.point.PointND.Double(1.0, 1.0));
        cameraBounds.add(new shell.point.PointND.Double(-1.0, 1.0));

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
        initCodePane("Circle SDF", ShaderType.CircleSDF.shader);
    }

    @Override
    public void drawScene() {
        camera2D.updateSize(Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight);
        camera2D.resetZIndex();
        camera2D.updateView(DEFAULT_VIEW_LEFT);

        float cx = camera2D.getBounds().viewWidth / 2f;
        float cy = camera2D.getBounds().viewHeight / 2f;
        float radius = Math.min(cx, cy);

        Vector2f center = new Vector2f(camera2D.screenTransformX(cx), camera2D.screenTransformY(cy));
        Drawing.circle.draw(center, radius, Color.IXDAR, camera2D);

        drawUI(DEFAULT_VIEW_LEFT, DEFAULT_VIEW_RIGHT);
    }
}
