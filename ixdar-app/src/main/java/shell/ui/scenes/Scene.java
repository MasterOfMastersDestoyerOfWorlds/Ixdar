package shell.ui.scenes;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

import shell.cameras.Bounds;
import shell.cameras.Camera2D;
import shell.point.PointND;
import shell.DistanceMatrix;
import shell.PointSet;
import shell.render.sdf.ShaderDrawable;
import shell.render.shaders.ShaderProgram;
import shell.shell.Shell;
import shell.ui.Canvas3D;
import shell.ui.code.ShaderCodePane;

public abstract class Scene extends Canvas3D {

    public Camera2D camera2D;
    public Map<String, Bounds> webViews;
    public Bounds paneBounds;
    public ShaderCodePane codePane;
    public float SCROLL_SPEED = 5f;
    public DistanceMatrix distanceMatrix;
    public Shell shell;
    public PointSet pointSet;

    public static final String DEFAULT_VIEW = "MAIN";

    public Scene() {
        super();
    }

    @Override
    public void initGL() throws UnsupportedEncodingException, IOException {
        super.initGL();
        shell = new Shell();
        initPoints();
        pointSet = shell.toPointSet();
        distanceMatrix = new DistanceMatrix(pointSet);
        shell.initShell(distanceMatrix);
        this.camera2D = new Camera2D(Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight, 1.0f, 0.0f, 0.0f,
                pointSet);
        webViews = new HashMap<>();
        paneBounds = new Bounds(0, 0, Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight, null, DEFAULT_VIEW);
        webViews.put(DEFAULT_VIEW, paneBounds);
        camera2D.initCamera(webViews, DEFAULT_VIEW);
        camera2D.calculateCameraTransform(pointSet);
        camera2D.updateView(DEFAULT_VIEW);
        camera.reset();
    }

    public void initPoints() {
        shell.clear();
        shell.add(new PointND.Double(-1.0, -1.0));
        shell.add(new PointND.Double(1.0, -1.0));
        shell.add(new PointND.Double(1.0, 1.0));
        shell.add(new PointND.Double(-1.0, 1.0));
    }

    public void initCodePane(String title, ShaderProgram shader, ShaderDrawable provider) {
        codePane = new ShaderCodePane(paneBounds, webViews, SCROLL_SPEED, shader, title, provider, camera2D, this);
        camera2D.initCamera(webViews, DEFAULT_VIEW);
    }

    public void drawScene() {
        drawUI();
        camera2D.resetZIndex();
        camera2D.updateView(DEFAULT_VIEW);
        camera2D.reset();
    }

    public void drawUI() {
        if (codePane != null) {
            codePane.draw(camera2D);
        }
    }
}
