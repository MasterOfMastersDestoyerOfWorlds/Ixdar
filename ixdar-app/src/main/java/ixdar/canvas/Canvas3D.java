package ixdar.canvas;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.joml.Vector3f;

import ixdar.annotations.scene.SceneDrawable;
import ixdar.geometry.point.PointND;
import ixdar.geometry.point.PointSet;
import ixdar.geometry.shell.DistanceMatrix;
import ixdar.geometry.shell.Shell;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.Clock;
import ixdar.graphics.render.sdf.SDFCircle;
import ixdar.graphics.render.sdf.SDFFluid;
import ixdar.graphics.render.shaders.DiffuseShader;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.SceneInputFrameUpdater;
import ixdar.scenes.main.MainScene;
import ixdar.scenes.trade.TradeScene;

public class Canvas3D extends SceneDrawable {

    private static Object audioSystem;
    private static boolean audioChecked;

    private static Object getAudioSystem() {
        if (!audioChecked) {
            audioChecked = true;
            try {
                Class<?> cls = Class.forName(
                        String.join(".", "ixdar", "audio", "AudioSystem"));
                audioSystem = cls.getMethod("get").invoke(null);
            } catch (Throwable ignored) {}
        }
        return audioSystem;
    }

    public static void audioInit() {
        Object audio = getAudioSystem();
        if (audio == null) return;
        try { audio.getClass().getMethod("init").invoke(audio); } catch (Throwable ignored) {}
    }

    public static void audioPlayMenuMusic() {
        Object audio = getAudioSystem();
        if (audio == null) return;
        try {
            String path = (String) Class.forName(
                    String.join(".", "ixdar", "audio", "AudioAssets"))
                    .getField("MENU_MUSIC").get(null);
            audio.getClass().getMethod("playMenuMusicLoop", String.class).invoke(audio, path);
        } catch (Throwable ignored) {}
    }

    public static void audioPauseMenuMusic() {
        Object audio = getAudioSystem();
        if (audio == null) return;
        try { audio.getClass().getMethod("pauseMenuMusic").invoke(audio); } catch (Throwable ignored) {}
    }

    public static void audioPlaySfx(String fieldName) {
        Object audio = getAudioSystem();
        if (audio == null) return;
        try {
            String path = (String) Class.forName(
                    String.join(".", "ixdar", "audio", "AudioAssets"))
                    .getField(fieldName).get(null);
            audio.getClass().getMethod("playSfxOnce", String.class).invoke(audio, path);
        } catch (Throwable ignored) {}
    }

    public static void audioShutdown() {
        Object audio = getAudioSystem();
        if (audio == null) return;
        try { audio.getClass().getMethod("shutdown").invoke(audio); } catch (Throwable ignored) {}
    }

    private static Object automationRuntime;
    private static boolean automationChecked;

    private static Object getAutomationRuntime() {
        if (!automationChecked) {
            automationChecked = true;
            try {
                Class<?> cls = Class.forName(
                        String.join(".", "ixdar", "platform", "automation", "AutomationRuntime"));
                automationRuntime = cls.getMethod("get").invoke(null);
            } catch (Throwable ignored) {}
        }
        return automationRuntime;
    }

    public static Canvas3D instance;

    protected DiffuseShader shader;
    public MenuBox menu;
    public boolean changedSize = false;
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
        instance = this;
        activate(true);
        platform = Platforms.get();
        active = true;
        Object rt = getAutomationRuntime();
        if (rt != null) {
            try {
                rt.getClass().getMethod("start", Canvas3D.class).invoke(rt, this);
            } catch (Throwable ignored) {}
        }

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
        this.camera2D = new Camera2D(Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(),
                1.0f, 0.0f, 0.0f,
                pointSet);
        webViews = new HashMap<>();
        paneBounds = new Bounds(0, 0, Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(),
                null, DEFAULT_VIEW);
        webViews.put(DEFAULT_VIEW, paneBounds);
        camera2D.initCamera(webViews, DEFAULT_VIEW);
        camera2D.calculateCameraTransform(pointSet);
        camera2D.reset();
    }

    public void initGL() {
        GL gl = Platforms.gl();
        gl.createCapabilities();
        float start = Clock.time();
        gl.coldStartStack();

        System.out.println("capabilities: " + (Clock.time() - start));

        gl.viewport(0, 0, (int) Platforms.get().getFrameBufferWidth(), (int) Platforms.get().getFrameBufferHeight());
        mouse.setCanvas(this);

        gl.enable(gl.DEPTH_TEST());

        gl.clearColor(0.7f, 0.1f, 0.1f, 1.0f);
        gl.blendFunc(gl.SRC_ALPHA(), gl.ONE_MINUS_SRC_ALPHA());
        gl.enable(gl.BLEND());
        audioInit();
        if (MenuBox.menuVisible) {
            audioPlayMenuMusic();
        }
        System.out.println("InitGL: " + (Clock.time() - start));
        System.out.println("Time to First Paint: " + (Clock.time() - Platforms.get().startTime()));
        initPoints();
    }

    public SDFCircle circle;
    public SDFFluid fluid;

    public void paintGL() {
        GL gl = Platforms.gl();
        int fbw = Platforms.get().getFrameBufferWidth();
        int fbh = Platforms.get().getFrameBufferHeight();
        if (fbw > 0 && fbh > 0) {
            gl.viewport(0, 0, fbw, fbh);
        }
        gl.clearColor(0.07f, 0.07f, 0.07f, 1.0f);
        gl.clear(gl.COLOR_BUFFER_BIT() | gl.DEPTH_BUFFER_BIT());
        camera.resetZIndex();
        camera2D.resetZIndex();

        SceneInputFrameUpdater.update(keys, mouse);

        drawScene();

        ArrayList<ShaderProgram> shaders = gl.getShaders();
        for (ShaderProgram s : shaders) {
            if (s.ID < 0) {
                continue;
            }
            s.updateProjectionMatrix(Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(), 1f);
            s.hotReload();
        }
        for (ShaderProgram s : shaders) {
            if (s.ID < 0) {
                continue;
            }
            s.flush();
        }
        Object rt2 = getAutomationRuntime();
        if (rt2 != null) {
            try {
                rt2.getClass().getMethod("processMainThreadCommands").invoke(rt2);
            } catch (Throwable ignored) {}
        }
        Clock.frameRendered();
    }

    public void drawScene() {
        if (menu == null) {
            menu = new MenuBox();
            fluid = new SDFFluid();
        }
        if (MenuBox.menuVisible) {
            fluid.draw(0, 0, Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(), null,
                    camera2D);
        }

        if (TradeScene.active && TradeScene.instance != null && !MenuBox.menuVisible) {
            TradeScene.instance.draw(camera2D);
        } else if (MainScene.main != null && !MenuBox.menuVisible) {
            MainScene.main.draw(camera2D);
        }

        if (MenuBox.menuVisible) {
            menu.draw(camera2D);
        }
    }

    public void activate(boolean state) {
        if (state) {
            Platform p = Platforms.get();
            bindAutomationIfAvailable(p, keys, mouse);
            audioPlayMenuMusic();
        } else {
            audioPauseMenuMusic();
        }
        keys.active = state;
        mouse.active = state;
        MenuBox.menuVisible = state;
        active = state;
    }

    @Override
    public void shutdown() {
        activate(false);
        Object rt3 = getAutomationRuntime();
        if (rt3 != null) {
            try {
                rt3.getClass().getMethod("stop").invoke(rt3);
            } catch (Throwable ignored) {}
        }
        audioShutdown();
    }

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