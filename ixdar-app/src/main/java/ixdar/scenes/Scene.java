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
     * Default Scene constructor; defers GL setup to {@link #initGL()}.
     */
    public Scene() {
        super();
    }

    /**
     * Initialize the scene: seed editable points, install a framebuffer-sized
     * pane bounds updater, and frame the 2D camera on the default view.
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
     * Attach a {@link ShaderCodePane} that renders {@code shader}'s source
     * alongside the live SDF rendered by {@code provider}, then re-frame
     * the 2D camera so the pane lays out correctly.
     *
     * @param title heading shown above the code pane
     * @param shader shader program whose source is displayed and edited
     * @param provider drawable bound to the same shader (used for live preview)
     */
    public void initCodePane(String title, ShaderProgram shader, ShaderDrawable provider) {
        codePane = new ShaderCodePane(paneBounds, webViews, SCROLL_SPEED, shader, title, provider, camera2D, this);

        camera2D.initCamera(webViews, DEFAULT_VIEW);
    }

    /**
     * Per-frame entry point: draw the UI overlay and reset the 2D camera
     * to the default view for the next frame.
     */
    public void drawScene() {
        drawUI();
        camera2D.resetZIndex();
        camera2D.updateView(DEFAULT_VIEW);
        camera2D.reset();
    }

    /**
     * Draw the scene UI; currently just the optional code pane if one
     * was attached via {@link #initCodePane}.
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
     * @param platform current platform whose input callbacks should be bound
     * @param keys keyboard handler to wire into the platform
     * @param mouse mouse handler to wire into the platform
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
