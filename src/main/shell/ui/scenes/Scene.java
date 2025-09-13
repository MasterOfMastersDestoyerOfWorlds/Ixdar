package shell.ui.scenes;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

import shell.cameras.Bounds;
import shell.cameras.Camera2D;
import shell.PointSet;
import shell.render.color.Color;
import shell.render.sdf.ShaderDrawable;
import shell.render.shaders.ShaderProgram;
import shell.ui.Canvas3D;
import shell.ui.Drawing;
import shell.render.text.HyperString;

public abstract class Scene extends Canvas3D {

    protected Camera2D camera2D;
    protected Map<String, Bounds> webViews;
    protected Bounds paneBounds;
    protected ShaderCodePane codePane;
    protected float SCROLL_SPEED = 5f;
    protected PointSet cameraBounds;

    public static final String DEFAULT_VIEW = "MAIN";

    public Scene() {
        super();
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
        cameraBounds = new PointSet();
        cameraBounds.clear();
        cameraBounds.add(new shell.point.PointND.Double(-1.0, -1.0));
        cameraBounds.add(new shell.point.PointND.Double(1.0, -1.0));
        cameraBounds.add(new shell.point.PointND.Double(1.0, 1.0));
        cameraBounds.add(new shell.point.PointND.Double(-1.0, 1.0));
        this.camera2D = new Camera2D(Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight, 1.0f, 0.0f, 0.0f,
                cameraBounds);
        webViews = new HashMap<>();
        paneBounds = new Bounds(0, 0, Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight);
        webViews.put(DEFAULT_VIEW, paneBounds);
        camera2D.initCamera(webViews, DEFAULT_VIEW);
    }

    protected void initCodePane(String title, ShaderProgram shader, ShaderDrawable provider) {
        codePane = new ShaderCodePane(paneBounds, webViews, SCROLL_SPEED, shader, title, provider, camera2D);
        camera2D.initCamera(webViews, DEFAULT_VIEW);
    }

    protected void drawUI() {
        if (codePane != null) {
            codePane.draw(camera2D);
        }
    }
}
