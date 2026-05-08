package ixdar.scenes;

import java.lang.reflect.Method;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.render.sdf.ShaderDrawable;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.gui.ui.code.ShaderCodePane;
import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;

public abstract class Scene extends Canvas3D {

    public ShaderCodePane codePane;
    public float SCROLL_SPEED = 10f;

    /**
     * TODO: document {@code Scene}.
     */
    public Scene() {
        super();
    }

    /**
     * TODO: document {@code initGL}.
     */
    @Override
    public void initGL() {
        super.initGL();
        initPoints();
        paneBounds.setUpdateCallback(
            b -> b.update(0, 0,Platforms.get().getFrameBufferWidth(),
                    Platforms.get().getFrameBufferHeight()));
        camera2D.initCamera(webViews, DEFAULT_VIEW);
    }


    /**
     * TODO: document {@code initCodePane}.
     *
     * @param title TODO: describe
     * @param shader TODO: describe
     * @param provider TODO: describe
     */
    public void initCodePane(String title, ShaderProgram shader, ShaderDrawable provider) {
        codePane = new ShaderCodePane(paneBounds, webViews, SCROLL_SPEED, shader, title, provider, camera2D, this);
        
        camera2D.initCamera(webViews, DEFAULT_VIEW);
    }

    /**
     * TODO: document {@code drawScene}.
     */
    public void drawScene() {
        drawUI();
        camera2D.resetZIndex();
        camera2D.updateView(DEFAULT_VIEW);
        camera2D.reset();
    }

    /**
     * TODO: document {@code drawUI}.
     */
    public void drawUI() {
        if (codePane != null) {
            codePane.draw(camera2D);
        }
    }

    /**
     * Bind automation input via reflection to avoid pulling desktop-only classes
     * into the TeaVM compilation graph. On web, this silently does nothing.
     *
     * @param platform TODO: describe
     * @param keys TODO: describe
     * @param mouse TODO: describe
     */
    protected static void bindAutomationIfAvailable(Platform platform, KeyGuy keys, MouseTrap mouse) {
        try {
            Class<?> binder = Class.forName(
                    String.join(".", "ixdar", "platform", "automation", "AutomationInputBinder"));
            Method bind = binder.getMethod("bind", Platform.class, KeyGuy.class, MouseTrap.class);
            bind.invoke(null, platform, keys, mouse);
        } catch (Throwable ignored) {
        }
    }
}
