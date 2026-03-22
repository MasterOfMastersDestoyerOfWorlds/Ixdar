package ixdar.scenes;
import ixdar.canvas.Canvas3D;
import ixdar.graphics.render.sdf.ShaderDrawable;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.gui.ui.code.ShaderCodePane;
import ixdar.platform.Platforms;

public abstract class Scene extends Canvas3D {

    public ShaderCodePane codePane;
    public float SCROLL_SPEED = 10f;

    public Scene() {
        super();
    }

    @Override
    public void initGL() {
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
