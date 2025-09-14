package shell.ui.scenes;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.joml.Vector2f;

import shell.cameras.Camera2D;
import shell.render.color.Color;
import shell.render.sdf.SDFCircleSimple;
import shell.render.sdf.SDFLine;
import shell.ui.Canvas3D;

public class LineScene extends Scene {

    private SDFLine line;

    public LineScene() {
        super();
    }

    @Override
    public void initGL() throws UnsupportedEncodingException, IOException {
        super.initGL();
        line = new SDFLine();
        camera2D.calculateCameraTransform(cameraBounds);
        camera2D.updateView(DEFAULT_VIEW);

        initCodePane("Line SDF", line.shader, line);
    }

    @Override
    public void drawScene() {
        camera2D.updateSize(Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight);
        camera2D.resetZIndex();
        camera2D.updateView(DEFAULT_VIEW);

        float cx = camera2D.getBounds().viewWidth;
        float cy = camera2D.getBounds().viewHeight / 2f;

        Vector2f pA = new Vector2f(0, cy);
        Vector2f pB = new Vector2f(cx, cy);
        line.draw(pA, pB, Color.MAGENTA, camera2D);
        drawUI();
    }

}
