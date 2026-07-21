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
import ixdar.graphics.render.color.Color;
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
    public static final String STR = ".";
    public static final String IXDAR = "ixdar";
    public static final String AUDIO = "audio";
    public static final String GET = "get";
    public static final String AUDIOASSETS = "AudioAssets";
    public static final String PLATFORM = "platform";
    public static final String AUTOMATION = "automation";
    public static final String ENDPOINTS = "endpoints";
    public static final float PROJECTION_MATRIX_SCALE = 1f;

    public static Canvas3D instance;

    public static final String DEFAULT_VIEW = "MAIN";

    private static Object audioSystem;
    private static boolean audioChecked;

    private static Object automationRuntime;
    private static boolean automationChecked;
    public MenuBox menu;
    public boolean changedSize = false;
    // private SDFTexture logo;
    public boolean active;

    /**
     * Whether {@link #initGL()} has returned, so the scene is built and safe to drive.
     */
    public volatile boolean sceneReady;

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

    public SDFCircle circle;
    public SDFFluid fluid;

    protected DiffuseShader shader;

    /**
     * Construct the active canvas: store as the singleton, mark active, capture the
     * current platform, start the optional automation runtime, and seed an empty
     * Shell.
     */
    public Canvas3D() {
        instance = this;
        activate(true);
        platform = Platforms.get();
        active = true;
        Object rt = getAutomationRuntime();
        if (rt != null) {
            try {
                rt.getClass().getMethod("start", Canvas3D.class).invoke(rt, this);
            } catch (Throwable ignored) {
            }
        }

        shell = new Shell();
    }

    private static Object getAudioSystem() {
        if (!audioChecked) {
            audioChecked = true;
            try {
                Class<?> cls = Class.forName(
                        String.join(STR, IXDAR, AUDIO, "AudioSystem"));
                audioSystem = cls.getMethod(GET).invoke(null);
            } catch (Throwable ignored) {
            }
        }
        return audioSystem;
    }

    /**
     * Reflectively start looping the {@code AudioAssets.MENU_MUSIC} track via
     * {@code AudioSystem.playMenuMusicLoop}; no-op if audio is unavailable.
     */
    public static void audioPlayMenuMusic() {
        Object audio = getAudioSystem();
        if (audio == null)
            return;
        try {
            String path = (String) Class.forName(
                    String.join(STR, IXDAR, AUDIO, AUDIOASSETS))
                    .getField("MENU_MUSIC").get(null);
            audio.getClass().getMethod("playMenuMusicLoop", String.class).invoke(audio, path);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Reflectively call {@code AudioSystem.pauseMenuMusic()}; no-op if audio is
     * unavailable.
     */
    public static void audioPauseMenuMusic() {
        Object audio = getAudioSystem();
        if (audio == null)
            return;
        try {
            audio.getClass().getMethod("pauseMenuMusic").invoke(audio);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Reflectively play a one-shot SFX whose path is the named static field on
     * {@code AudioAssets}; no-op if audio is unavailable or the field is missing.
     *
     * @param fieldName public static String field on
     *                  {@code ixdar.audio.AudioAssets}
     */
    public static void audioPlaySfx(String fieldName) {
        Object audio = getAudioSystem();
        if (audio == null)
            return;
        try {
            String path = (String) Class.forName(
                    String.join(STR, IXDAR, AUDIO, AUDIOASSETS))
                    .getField(fieldName).get(null);
            audio.getClass().getMethod("playSfxOnce", String.class).invoke(audio, path);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Reflectively call {@code AudioSystem.shutdown()}; no-op if audio is
     * unavailable.
     */
    public static void audioShutdown() {
        Object audio = getAudioSystem();
        if (audio == null)
            return;
        try {
            audio.getClass().getMethod("shutdown").invoke(audio);
        } catch (Throwable ignored) {
        }
    }

    private static Object getAutomationRuntime() {
        if (!automationChecked) {
            automationChecked = true;
            try {
                Class<?> cls = Class.forName(
                        String.join(STR, IXDAR, PLATFORM, AUTOMATION, ENDPOINTS, "AutomationRuntime"));
                automationRuntime = cls.getMethod(GET).invoke(null);
            } catch (Throwable ignored) {
            }
        }
        return automationRuntime;
    }

    /**
     * Seed the unit-square shell, build its {@link DistanceMatrix} and
     * {@link PointSet},
     * and initialize the 2D camera and default web-view bounds covering the
     * framebuffer.
     */
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

    /**
     * Initialize GL state on the render thread: capabilities, viewport, depth/blend
     * state, clear color, audio init/start, and {@link #initPoints()}.
     */
    public void initGL() {
        GL gl = Platforms.gl();
        gl.createCapabilities();
        float start = Clock.time();
        gl.coldStartStack();

        System.out.println("capabilities: " + (Clock.time() - start));

        gl.viewport(0, 0, (int) Platforms.get().getFrameBufferWidth(), (int) Platforms.get().getFrameBufferHeight());
        mouse.setCanvas(this);

        gl.enable(gl.DEPTH_TEST());

        gl.clearColor(Color.DARK_RED);
        gl.blendFunc(gl.SRC_ALPHA(), gl.ONE_MINUS_SRC_ALPHA());
        gl.enable(gl.BLEND());
        Object audio = getAudioSystem();
        try {
            audio.getClass().getMethod("init").invoke(audio);
        } catch (Throwable ignored) {
        }
        if (MenuBox.menuVisible) {
            audioPlayMenuMusic();
        }
        System.out.println("InitGL: " + (Clock.time() - start));
        System.out.println("Time to First Paint: " + (Clock.time() - Platforms.get().startTime()));
        initPoints();
    }

    /**
     * Render one frame: resize the viewport, clear, drive input, call
     * {@link #drawScene()}, hot-reload and flush all shaders, run automation
     * mainthread commands, and tick the frame clock.
     */
    public void paintGL() {
        GL gl = Platforms.gl();
        int fbw = Platforms.get().getFrameBufferWidth();
        int fbh = Platforms.get().getFrameBufferHeight();
        if (fbw > 0 && fbh > 0) {
            gl.viewport(0, 0, fbw, fbh);
        }
        gl.clearColor(Color.DARK_GRAY);
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
            s.updateProjectionMatrix(Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(),
                    PROJECTION_MATRIX_SCALE);
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
            } catch (Throwable ignored) {
            }
        }
        Clock.frameRendered();
    }

    /**
     * Lazily construct the menu and SDF fluid, then dispatch drawing to the menu,
     * the active {@link TradeScene}, or the {@link MainScene} based on visibility
     * flags.
     */
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

    /**
     * Toggle interactive state: bind/unbind automation input, start or pause menu
     * music, and update {@code keys}, {@code mouse}, menu-visibility, and the
     * {@code active} flag.
     *
     * @param state {@code true} to enable input/audio/menu; {@code false} to
     *              suspend
     */
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

    /**
     * Deactivate the canvas, stop the optional automation runtime, and shut down
     * audio.
     */
    @Override
    public void shutdown() {
        activate(false);
        Object rt3 = getAutomationRuntime();
        if (rt3 != null) {
            try {
                rt3.getClass().getMethod("stop").invoke(rt3);
            } catch (Throwable ignored) {
            }
        }
        audioShutdown();
    }

    /**
     * Reflectively wire the optional {@code AutomationInputBinder} into the
     * platform
     * and input listeners; silently no-op when the automation module is absent.
     *
     * @param platform host platform whose input queue to bind
     * @param keys     keyboard listener to register
     * @param mouse    mouse listener to register
     */
    protected static void bindAutomationIfAvailable(Platform platform, KeyGuy keys, MouseTrap mouse) {
        try {
            Class<?> binder = Class.forName(
                    String.join(STR, IXDAR, PLATFORM, AUTOMATION, "AutomationInputBinder"));
            Method bind = binder.getMethod("bind", Platform.class, KeyGuy.class, MouseTrap.class);
            bind.invoke(null, platform, keys, mouse);
        } catch (Throwable ignored) {
        }
    }

}