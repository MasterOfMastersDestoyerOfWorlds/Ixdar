package shell.ui.scenes;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import shell.platform.Platforms;
import shell.render.sdf.ShaderDrawable;
import shell.render.shaders.ShaderProgram;
import shell.ui.Canvas3D;
import shell.ui.code.ShaderCodePane;

public abstract class Scene extends Canvas3D {

    public ShaderCodePane codePane;
    public float SCROLL_SPEED = 10f;

    public Scene() {
        super();
    }

    @Override
    public void initGL() throws UnsupportedEncodingException, IOException {
        super.initGL();
        initPoints();
        paneBounds.setUpdateCallback(
            b -> b.update(0, 0,Platforms.get().getFrameBufferWidth(),
                    Platforms.get().getFrameBufferHeight()));
        camera2D.initCamera(webViews, DEFAULT_VIEW);
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
