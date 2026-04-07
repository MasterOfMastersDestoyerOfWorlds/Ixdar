package ixdar.scenes;

import java.lang.reflect.Method;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.render.sdf.ShaderDrawable;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.gui.ui.code.ShaderCodePane;
import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.InputHandler;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;

/**
 * Base class for all scenes.
 * 
 * Supports both the legacy KeyGuy/MouseTrap pattern and the new InputHandler abstraction.
 * Scenes can choose which pattern to use via the abstract inputHandler() method.
 */
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

    /**
     * Get the input handler for this scene.
     * 
     * Scenes can override this to return a new InputHandler instance using the
     * new declarative API, or return null to use the legacy keys/mouse fields.
     */
    protected InputHandler getInputHandler() {
        return null; // Default: use legacy fields
    }

    /**
     * Bind automation input via reflection to avoid pulling desktop-only classes
     * into the TeaVM compilation graph. On web, this silently does nothing.
     * 
     * Supports both legacy (KeyGuy/MouseTrap) and new (InputHandler) patterns.
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

    /**
     * Bind automation input for the new InputHandler abstraction.
     */
    protected static void bindAutomationIfAvailable(Platform platform, InputHandler handler) {
        try {
            Class<?> binder = Class.forName(
                    String.join(".", "ixdar", "platform", "automation", "AutomationInputBinder"));
            Method bind = binder.getMethod("bind", Platform.class, InputHandler.class);
            bind.invoke(null, platform, handler);
        } catch (Throwable ignored) {
        }
    }
}
