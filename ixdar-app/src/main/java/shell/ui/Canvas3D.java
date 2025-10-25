package shell.ui;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;

import shell.cameras.Camera3D;
import shell.DistanceMatrix;
import shell.PointSet;
import shell.cameras.Bounds;
import shell.cameras.Camera2D;
import shell.platform.Platforms;
import shell.platform.gl.GL;
import shell.platform.gl.Platform;
import shell.platform.input.KeyActions;
import shell.platform.input.KeyGuy;
import shell.platform.input.MouseTrap;
import shell.point.PointND;
import shell.render.Clock;
import shell.render.sdf.SDFCircle;
import shell.render.sdf.SDFFluid;
import shell.render.shaders.DiffuseShader;
import shell.render.shaders.ShaderProgram;
import shell.shell.Shell;
import shell.ui.main.Main;
import shell.ui.menu.MenuBox;

public class Canvas3D {
    protected DiffuseShader shader;
    public MenuBox menu;
    boolean changedSize = false;
    // private SDFTexture logo;
    public boolean active;

    public Camera3D camera = new Camera3D(new Vector3f(0, 0, 3.0f), -90.0f, 0.0f, this);
    public MouseTrap mouse = new MouseTrap(null, camera, this);
    public KeyGuy keys = new KeyGuy(camera, this);
    public Platform platform;
    public long checkPaintTime;
    public Shell shell;
    
    public Camera2D camera2D;
    public Map<String, Bounds> webViews;
    public Bounds paneBounds;

    public DistanceMatrix distanceMatrix;
    public PointSet pointSet;

    public static final String DEFAULT_VIEW = "MAIN";

    public Canvas3D() {
        activate(true);
        platform = Platforms.get();
        active = true;
        
        shell = new Shell();
    }

    
    public void initPoints() {
        shell.clear();
        shell.add(new PointND.Double(-1.0, -1.0));
        shell.add(new PointND.Double(1.0, -1.0));
        shell.add(new PointND.Double(1.0, 1.0));
        shell.add(new PointND.Double(-1.0, 1.0));
        pointSet = shell.toPointSet();
        distanceMatrix = new DistanceMatrix(pointSet);
        shell.initShell(distanceMatrix);
        this.camera2D = new Camera2D(Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(), 1.0f, 0.0f, 0.0f,
                pointSet);
        webViews = new HashMap<>();
        paneBounds = new Bounds(0, 0, Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(), null, DEFAULT_VIEW);
        webViews.put(DEFAULT_VIEW, paneBounds);
        camera2D.initCamera(webViews, DEFAULT_VIEW);
        camera2D.calculateCameraTransform(pointSet);
        camera2D.reset();
    }

    public void initGL() throws UnsupportedEncodingException, IOException {
        GL gl = Platforms.gl();
        gl.createCapabilities(false, (IntFunction<PointerBuffer>) null);
        float start = Clock.time();
        gl.coldStartStack();
        
        System.out.println("capabilities: " + (Clock.time() - start));

        gl.viewport(0, 0, (int) Platforms.get().getFrameBufferWidth(), (int) Platforms.get().getFrameBufferHeight());
        mouse.setCanvas(this);

        gl.enable(gl.DEPTH_TEST());

        gl.clearColor(0.7f, 0.1f, 0.1f, 1.0f);
        gl.blendFunc(gl.SRC_ALPHA(), gl.ONE_MINUS_SRC_ALPHA());
        gl.enable(gl.BLEND());
        System.out.println("InitGL: " + (Clock.time() - start));
        System.out.println("Time to First Paint: " + (Clock.time() - Platforms.get().startTime()));
        initPoints();
    }

    public SDFCircle circle;
    public SDFFluid fluid;

    public void paintGL() {
        GL gl = Platforms.gl();
        gl.clearColor(0.07f, 0.07f, 0.07f, 1.0f);
        gl.clear(gl.COLOR_BUFFER_BIT() | gl.DEPTH_BUFFER_BIT());
        camera.resetZIndex();
        camera2D.resetZIndex();

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

        drawScene();

        gl.viewport(0, 0, (int) Platforms.get().getFrameBufferWidth(), (int) Platforms.get().getFrameBufferHeight());
        ArrayList<ShaderProgram> shaders = gl.getShaders();
        for (ShaderProgram s : shaders) {
            s.updateProjectionMatrix(Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(), 1f);
            s.hotReload();
        }
        for (ShaderProgram s : shaders) {
            s.flush();
        }
        Clock.frameRendered();
    }

    public void drawScene() {
        if(menu == null){
            menu = new MenuBox();
            fluid = new SDFFluid();
        }
        if (MenuBox.menuVisible) {
            fluid.draw(0, 0, Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(), null, camera2D);
        }

        if (Main.main != null && !MenuBox.menuVisible) {
            Main.main.draw(camera2D);
        }

        if (MenuBox.menuVisible) {
            menu.draw(camera2D);
        }
    }

    public void activate(boolean state) {
        if (state) {
            Platform p = Platforms.get();
            p.setKeyCallback((key, scancode, action, mods) -> keys.keyCallback(0L, key, scancode, action, mods));
            p.setCharCallback(codepoint -> keys.charCallback(0L, codepoint));
            p.setMouseButtonCallback((button, action, mods) -> mouse.mouseButton(button, action, mods));
            p.setCursorPosCallback((window, x, y) -> mouse.moveOrDrag(window, (float) x, (float) y));
            p.setScrollCallback((xoff, yoff) -> mouse.scrollCallback(yoff));
        }
        keys.active = state;
        mouse.active = state;
        MenuBox.menuVisible = state;
        active = state;
    }

}