package shell.ui;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.function.IntFunction;

import org.joml.Vector3f;

import shell.cameras.Camera3D;
import shell.platform.Platforms;
import shell.platform.gl.GL;
import shell.platform.gl.Platform;
import shell.platform.input.KeyActions;
import shell.platform.input.KeyGuy;
import shell.platform.input.MouseTrap;
import shell.render.Clock;
import shell.render.sdf.SDFCircle;
import shell.render.sdf.SDFFluid;
import shell.render.shaders.DiffuseShader;
import shell.render.shaders.ShaderProgram;
import shell.ui.main.Main;
import shell.ui.menu.MenuBox;

public class Canvas3D {
    DiffuseShader shader;
    public static int frameBufferWidth;
    public static int frameBufferHeight;
    public static MenuBox menu;
    boolean changedSize = false;
    // private SDFTexture logo;
    public static Canvas3D canvas;
    public static boolean active;

    public static Camera3D camera = new Camera3D(new Vector3f(0, 0, 3.0f), -90.0f, 0.0f);
    public static MouseTrap mouse = new MouseTrap(null, camera);
    public static KeyGuy keys = new KeyGuy(camera, canvas);
    public static Platform platform;
    public static long checkPaintTime;

    public Canvas3D() {
        activate(true);
        Canvas3D.canvas = this;
        platform = Platforms.get();
        active = true;
    }


    public void initGL() throws UnsupportedEncodingException, IOException {
        GL gl = Platforms.gl();
        gl.createCapabilities(false, (IntFunction) null);
        float start = Clock.time();
        gl.coldStartStack();

        System.out.println("capabilities: " + (Clock.time() - start));

        menu = new MenuBox();
        fluid = new SDFFluid();

        gl.viewport(0, 0, (int) Platforms.get().getWindowWidth(), (int) Platforms.get().getWindowHeight());
        mouse.setCanvas(this);

        gl.enable(gl.DEPTH_TEST());

        gl.clearColor(0.7f, 0.1f, 0.1f, 1.0f);

        System.out.println("InitGL: " + (Clock.time() - start));
        System.out.println("Time to First Paint: " + (Clock.time() - Platforms.get().startTime()));
    }

    public SDFCircle circle;
    public SDFFluid fluid;

    public void paintGL() {
        GL gl = Platforms.gl();
        gl.clearColor(0.07f, 0.07f, 0.07f, 1.0f);
        gl.clear(gl.COLOR_BUFFER_BIT() | gl.DEPTH_BUFFER_BIT());
        camera.resetZIndex();

        // Always update input once per frame (centralize input handling here)
        float SHIFT_MOD = 1;
        if (keys != null && KeyActions.DoubleSpeed.keyPressed(keys.pressedKeys)) {
            SHIFT_MOD = 2;
        }
        if (keys != null) {
            keys.paintUpdate(SHIFT_MOD);
        }
        if (mouse != null) {
            mouse.paintUpdate(SHIFT_MOD);
        }

        // Delegate scene drawing to overridable hook
        drawScene();

        gl.viewport(0, 0, (int) Platforms.get().getWindowWidth(), (int) Platforms.get().getWindowHeight());
        ArrayList<ShaderProgram> shaders = gl.getShaders();
        for (ShaderProgram s : shaders) {
            s.updateProjectionMatrix(frameBufferWidth, frameBufferHeight, 1f);
            s.hotReload();
        }
        for (ShaderProgram s : shaders) {
            s.flush();
        }
        Clock.frameRendered();
    }

    public void drawScene() {
        if (MenuBox.menuVisible) {
            fluid.draw(0, 0, frameBufferWidth, frameBufferHeight, null, camera);
        }

        if (Main.main != null && !MenuBox.menuVisible) {
            Main.main.draw(camera);
        }

        if (MenuBox.menuVisible) {
            menu.draw(camera);
        }
    }

    public static void activate(boolean state) {
        if (state) {
            Platform p = Platforms.get();
            p.setKeyCallback((key, scancode, action, mods) -> keys.keyCallback(0L, key, scancode, action, mods));
            p.setCharCallback(codepoint -> keys.charCallback(0L, codepoint));
            p.setMouseButtonCallback((button, action, mods) -> mouse.mouseButton(button, action, mods));
            p.setCursorPosCallback((window, x, y) -> mouse.moveOrDrag(window, (float) x, (float) y));
            p.setScrollCallback((xoff, yoff) -> mouse.scrollCallback(yoff));
        }
        Canvas3D.keys.active = state;
        Canvas3D.mouse.active = state;
        MenuBox.menuVisible = state;
        active = state;
    }

}