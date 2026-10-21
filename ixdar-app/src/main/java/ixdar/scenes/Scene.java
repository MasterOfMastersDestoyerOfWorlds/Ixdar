package ixdar.scenes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.joml.Vector3f;

import ixdar.annotations.scene.SceneDrawable;
import ixdar.audio.AudioAssets;
import ixdar.audio.AudioBackend;
import ixdar.geometry.tsp.points.PointND;
import ixdar.geometry.tsp.points.PointSet;
import ixdar.geometry.tsp.shell.DistanceMatrix;
import ixdar.geometry.tsp.shell.Shell;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorBox;
import ixdar.graphics.render.sdf.ShaderDrawable;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.PaneTypes;
import ixdar.gui.ui.code.ShaderCodePane;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.AutomationBackend;
import ixdar.platform.Clock;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.input.KeyGuy;
import ixdar.input.MouseTrap;
import ixdar.input.PointerDispatcher;
import ixdar.input.SceneInputFrameUpdater;
import ixdar.platform.input.Keys;
import ixdar.scenes.main.Toggle;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

/**
 * Base of every launchable view: the GL frame loop, input handlers, cameras, hosted tools, tooltip
 * and optional terminal. {@link #instance} is the scene shown; {@link #switchTo}
 * hands the window on.
 */
public abstract class Scene extends SceneDrawable {

    public static final float PROJECTION_MATRIX_SCALE = 1f;

    public static final String DEFAULT_VIEW = "MAIN";

    public static final String VIEW_SCENE_TERMINAL = "SCENE_TERMINAL";

    public static final int TERMINAL_PANEL_HEIGHT = 300;

    public static final String TOOL_LOG_PREFIX = "[tools] ";

    public static final String ACTIVE_MARK = " (active)";

    public static final long DOUBLE_CLICK_MS = 500;

    public static final float DOUBLE_CLICK_RADIUS_PX = 6f;

    public static final String FRAME_ALL_CLICK = "double-click empty";

    public static final String FRAME_ALL_DESCRIPTION = "frame all: back to the tool's home view";

    public static final String VIEW_TOOLTIP = "TOOLTIP";

    public static volatile Scene instance;

    public boolean changedSize = false;
    public boolean active;

    /** The scene the window shows from the next frame on, handed over by {@link #switchTo}; null while this one stays. */
    public Scene successor;

    /** Tooltip body {@link #drawTooltip} draws at the cursor, or {@code null} for none. */
    public HyperString toolTip;

    /** Whether {@link #toolTip} is drawn this frame. */
    public boolean showToolTip;

    /**
     * Whether {@link #initGL()} has returned, so the scene is built and safe to drive.
     */
    public volatile boolean sceneReady;

    public Camera3D camera = new Camera3D(new Vector3f(0, 0, 3.0f), -90.0f, 0.0f, this);
    public MouseTrap mouse = new MouseTrap(camera, this);
    public KeyGuy keys = new KeyGuy(this);
    public Platform platform;
    public Shell shell;

    public Camera2D camera2D;
    public Map<String, Bounds> webViews;
    public Bounds paneBounds;

    public DistanceMatrix distanceMatrix;
    public PointSet pointSet;

    public ShaderCodePane codePane;

    public final ArrayList<ControlHint> controls = new ArrayList<>();

    /** Every tool the scene hosts, each run once per frame whether active or not; empty for none. */
    public final ArrayList<Tool> tools = new ArrayList<>();

    /** The key hints that switch tools, as registered, each paired with {@link #switchedTools}. */
    public final ArrayList<ControlHint> toolSwitchHints = new ArrayList<>();

    /** The tool each of {@link #toolSwitchHints} switches to. */
    public final ArrayList<Tool> switchedTools = new ArrayList<>();

    /**
     * The tool that owns clicks, drags and tool keys now; {@code null} in a scene with none, and
     * until {@link #initGL} has hosted the tools.
     */
    public Tool activeTool;

    /** The tool Esc returns to when the active tool leaves the key alone. */
    public Tool defaultTool;

    /**
     * In-scene terminal opened with {@code ~}; {@code null} for scenes with no
     * terminal.
     */
    public Terminal sceneTerminal;

    /**
     * Fills a chrome strip's viewport before its text is drawn, so the panel reads
     * as an opaque pane over the 3D view instead of bare glyphs floating on the
     * mesh. Built on the render thread with the views, since {@link ColorBox} binds
     * a shader program.
     */
    public ColorBox chromeBackground;

    /** Dark box behind the tooltip, built on its first draw on the render thread. */
    public ColorBox tooltipBackground;

    /** When and where the last {@link #emptySpaceClicked} click fell, 0 once it made a double-click. */
    public long lastEmptyClickMillis;
    public float lastEmptyClickX;
    public float lastEmptyClickY;

    /**
     * Construct the scene with the shell {@link #initPoints()} seeds; it becomes {@link #instance}
     * and active only when {@link #show()}n, and GL waits for {@link #initGL()}.
     */
    public Scene() {
        platform = Platforms.get();
        shell = new Shell();
    }

    /**
     * Start looping the {@link AudioAssets#MENU_MUSIC} track; no-op on a platform without audio.
     */
    public static void audioPlayMenuMusic() {
        AudioBackend audio = Platforms.get().audio();
        if (audio != null) {
            audio.playMenuMusicLoop(AudioAssets.MENU_MUSIC);
        }
    }

    /**
     * Pause the menu music; no-op on a platform without audio.
     */
    public static void audioPauseMenuMusic() {
        AudioBackend audio = Platforms.get().audio();
        if (audio != null) {
            audio.pauseMenuMusic();
        }
    }

    /**
     * Play a one-shot sound effect; no-op on a platform without audio.
     *
     * @param path asset path of the effect, one of the {@link AudioAssets} constants
     */
    public static void audioPlaySfx(String path) {
        AudioBackend audio = Platforms.get().audio();
        if (audio != null) {
            audio.playSfxOnce(path);
        }
    }

    /**
     * Seed the unit-square shell the 2D demos draw on, build its {@link DistanceMatrix} and
     * {@link PointSet}, and initialize the 2D camera and default web-view bounds covering the
     * framebuffer. Subclasses adding points chain {@code super.initPoints()} first.
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
     * Initialize GL state (viewport, depth and blend state, clear colour), start audio, seed the
     * points through {@link #initPoints()}, keep the default pane framebuffer-sized, host the tools
     * through {@link #setTools()}, activate the default one and build the control hints. The window
     * binds the GL functions before the scene is built.
     */
    public void initGL() {
        GL gl = Platforms.gl();
        gl.coldStartStack();

        gl.viewport(0, 0, (int) Platforms.get().getFrameBufferWidth(), (int) Platforms.get().getFrameBufferHeight());
        mouse.setCanvas(this);

        gl.enable(gl.DEPTH_TEST());
        gl.depthFunc(gl.LEQUAL());
        gl.enable(gl.POLYGON_OFFSET_FILL());
        gl.polygonOffset(0f, 1f);

        gl.clearColor(Color.RED_DARK);
        gl.blendFunc(gl.SRC_ALPHA(), gl.ONE_MINUS_SRC_ALPHA());
        gl.enable(gl.BLEND());
        AudioBackend audio = Platforms.get().audio();
        if (audio != null) {
            audio.init();
        }
        if (MenuBox.menuVisible) {
            audioPlayMenuMusic();
        }
        initPoints();
        paneBounds.setUpdateCallback(
                b -> b.update(0, 0, Platforms.get().getFrameBufferWidth(),
                        Platforms.get().getFrameBufferHeight()));
        camera2D.initCamera(webViews, DEFAULT_VIEW);
        setTools();
        switchTool(defaultTool);
        refreshControls();
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
        gl.clearColor(Color.GREY_VERY_DARK);
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
        AutomationBackend automation = Platforms.get().automation();
        if (automation != null) {
            automation.processMainThreadCommands();
        }
        Clock.frameRendered();
    }

    /**
     * Hand the window to {@code next} once this frame is painted: {@link #paintFrame} then
     * deactivates this scene and activates, initializes and binds {@code next}.
     *
     * @param next the scene the window shows from the next frame on
     */
    public void switchTo(Scene next) {
        successor = next;
    }

    /**
     * Paint one frame, then return the scene the window shows next: this one, or the
     * {@link #successor} it switched to, {@link #show shown} once this one is deactivated.
     *
     * @return the scene to paint on the next frame
     */
    public Scene paintFrame() {
        paintGL();
        Scene next = successor;
        if (next == null) {
            return this;
        }
        successor = null;
        activate(false);
        next.show();
        return next;
    }

    /**
     * Make this fully built scene the window's: {@link #instance}, {@link #activate active}, and
     * the one the platform's automation runtime drives, starting its server on the first call;
     * then build its GL on its first showing.
     */
    public void show() {
        instance = this;
        activate(true);
        AutomationBackend automation = platform.automation();
        if (automation != null) {
            automation.start(this);
        }
        if (!sceneReady) {
            initGL();
            sceneReady = true;
        }
    }

    /**
     * Lay the terminal's scroll box out as the bottom terminal strip view and subscribe it for the
     * wheel and the presses on it. Subclasses that add their own panes override this and chain
     * {@code super.initPanes()}.
     */
    public void initPanes() {
        Bounds terminalBounds = sceneTerminal.scrollBox.bounds;
        terminalBounds.setUpdateCallback(bounds -> bounds.update(0, 0,
                Platforms.get().getFrameBufferWidth(), TERMINAL_PANEL_HEIGHT));
        terminalBounds.recalc();
        webViews.put(VIEW_SCENE_TERMINAL, terminalBounds);
        PointerDispatcher.current().subscribe(terminalBounds, sceneTerminal.scrollBox);
    }

    /**
     * Whether the scene's terminal is on screen, so its box takes the wheel; the strip shows while
     * the scene is active and the terminal has focus.
     *
     * @return whether the terminal is drawn this frame
     */
    public boolean terminalShown() {
        return active && Toggle.IsTerminalFocused.value;
    }

    /**
     * Attach a {@link ShaderCodePane} that renders {@code shader}'s source
     * alongside the live SDF rendered by {@code provider}, then re-frame the 2D
     * camera so the pane lays out correctly.
     *
     * @param title    heading shown above the code pane
     * @param shader   shader program whose source is displayed and edited
     * @param provider drawable bound to the same shader (used for live preview)
     */
    public void initCodePane(String title, ShaderProgram shader, ShaderDrawable provider) {
        codePane = new ShaderCodePane(paneBounds, webViews, shader, title, provider, camera2D, this);
        camera2D.initCamera(webViews, DEFAULT_VIEW);
    }

    /**
     * Per-frame entry point: draw the UI overlay and reset the 2D camera to the
     * default view for the next frame.
     */
    public void drawScene() {
        drawUI();
        camera2D.resetZIndex();
        camera2D.updateView(DEFAULT_VIEW);
        camera2D.reset();
    }

    /**
     * Draw the scene UI; currently just the optional code pane if one was attached
     * via {@link #initCodePane}.
     */
    public void drawUI() {
        if (codePane != null) {
            codePane.draw(camera2D);
        }
        if (sceneTerminal != null && terminalShown()) {
            camera2D.updateView(VIEW_SCENE_TERMINAL);
            chromeBackground.draw(Color.GREY_VERY_DARK, camera2D);
            sceneTerminal.draw(camera2D);
        }
        camera2D.resetView();
    }

    /**
     * Show {@code tip} as the floating tooltip from the next frame on.
     *
     * @param tip formatted tooltip body
     */
    public void setTooltipText(HyperString tip) {
        toolTip = tip;
        showToolTip = true;
    }

    /**
     * Hide the floating tooltip and clear its text.
     */
    public void clearTooltipText() {
        toolTip = null;
        showToolTip = false;
    }

    /**
     * The {@link #VIEW_TOOLTIP} view: sized to {@link #toolTip} and placed at the cursor, opening
     * away from the window's nearer edges; empty while no tooltip is set.
     *
     * @return the view bounds, recalculated from the cursor on every update
     */
    public Bounds tooltipBounds() {
        return new Bounds(0, 0, 0, 0, b -> {
            int ww = Platforms.get().getFrameBufferWidth();
            int wh = Platforms.get().getFrameBufferHeight();
            if (toolTip == null) {
                b.update(0, 0, 0, 0);
                return;
            }
            float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
            int isRight = mouse.normalizedPosX > ww / 2 ? 1 : 0;
            int isTop = mouse.normalizedPosY > wh / 2 ? 1 : 0;
            float toolTipWidth = toolTip.getWidthPixels();
            int toolTipHeight = toolTip.getHeightPixels();
            int x = (int) (mouse.normalizedPosX - (isRight * toolTipWidth));
            int y = (int) mouse.normalizedPosY - (isTop * toolTipHeight);
            b.update(x, y, (int) Math.ceil(toolTipWidth), (int) (toolTip.getLines() * rowHeight));
        }, VIEW_TOOLTIP);
    }

    /**
     * Draw the visible tooltip over a dark box in {@code camera}'s {@link #VIEW_TOOLTIP} view.
     *
     * @param camera 2D camera whose views include {@link #tooltipBounds()}
     */
    public void drawTooltip(Camera2D camera) {
        if (toolTip != null && showToolTip) {
            float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
            toolTip.setLineOffsetFromTopRow(camera, 0, 0, rowHeight);
            camera.updateView(VIEW_TOOLTIP);
            if (tooltipBackground == null) {
                tooltipBackground = new ColorBox();
            }
            tooltipBackground.draw(Color.GREY_VERY_DARK, camera);
            Drawing.getDrawing().font.drawHyperStringRows(toolTip, 0, 0, rowHeight, camera);
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
            bindAutomationIfAvailable(p);
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
     * Deactivate the scene, stop the optional automation runtime, and shut down
     * audio.
     */
    @Override
    public void shutdown() {
        activate(false);
        AutomationBackend automation = platform.automation();
        if (automation != null) {
            automation.stop();
        }
        AudioBackend audio = platform.audio();
        if (audio != null) {
            audio.shutdown();
        }
    }

    /**
     * Populate {@link #controls}, the rows the key handler fires. Base adds the {@code ~} terminal
     * toggle when the scene has a terminal; scenes add their keyed hints and call
     * {@code super.setControls()}.
     */
    public void setControls() {
        if (sceneTerminal == null) {
            return;
        }
        controls.add(
                new ControlHint(Keys.GRAVE, "~", "toggle the terminal", () -> {
                    boolean focusTerminal = !Toggle.IsTerminalFocused.value;
                    Toggle.setPanelFocus(focusTerminal ? PaneTypes.Terminal : PaneTypes.KnotView);
                }));
    }

    /**
     * Host the scene's tools with {@link #tools}{@code .add} and {@link #addToolSwitch}, and set the
     * {@link #defaultTool} the scene opens on; {@link #initGL} calls it once, then activates that
     * tool. Base hosts none; scenes call {@code super.setTools()} then add theirs.
     */
    public void setTools() {
    }

    /**
     * Host {@code tool} with a key that switches to it, listed before every other control.
     *
     * @param tool       the tool, run every frame from now on
     * @param switchHint the key and purpose; the hint shows the tool's name and whether it is active
     */
    public void addToolSwitch(Tool tool, ControlHint switchHint) {
        tools.add(tool);
        toolSwitchHints.add(switchHint);
        switchedTools.add(tool);
    }

    /**
     * Make {@code tool} active: the current one hands back the mouse keeping its state, and the
     * control hints become the new tool's. Switching to the active tool does nothing, and the
     * first switch, from no tool, has none to hand back.
     *
     * @param tool the tool to switch to
     */
    public void switchTool(Tool tool) {
        if (tool == activeTool) {
            return;
        }
        if (activeTool != null) {
            activeTool.deactivate();
        }
        activeTool = tool;
        activeTool.activate();
        refreshControls();
        Platforms.get().log(TOOL_LOG_PREFIX + "active tool: " + activeTool.toolName());
    }

    /**
     * Rebuild {@link #controls}: the tool switches, the active tool's hints, F for
     * {@link #frameAll} unless the tool or the scene binds F itself, then the scene's own from
     * {@link #setControls}, left out by a tool that {@link Tool#replacesView replaces the view}.
     */
    public void refreshControls() {
        controls.clear();
        for (int switchIndex = 0; switchIndex < toolSwitchHints.size(); switchIndex++) {
            ControlHint hint = toolSwitchHints.get(switchIndex);
            Tool tool = switchedTools.get(switchIndex);
            StringBuilder description = new StringBuilder(tool.toolName()).append(": ").append(hint.description);
            if (tool == activeTool) {
                description.append(ACTIVE_MARK);
            }
            controls.add(new ControlHint(hint.keyCode, hint.controlHeld, hint.shiftHeld, hint.key,
                    description.toString(), hint.action));
        }
        if (activeTool != null) {
            activeTool.addControls(controls);
        }
        int frameAllRow = controls.size();
        if (activeTool == null || !activeTool.replacesView()) {
            setControls();
        }
        if (activeTool != null) {
            boolean bindsF = false;
            for (ControlHint hint : controls) {
                bindsF |= hint.keyCode == Keys.F && !hint.controlHeld && !hint.shiftHeld;
            }
            if (!bindsF) {
                controls.add(frameAllRow, new ControlHint(Keys.F, "F", FRAME_ALL_DESCRIPTION, this::frameAll));
            }
        }
    }

    /**
     * Frame everything the active tool shows, the same gesture in every tool, through
     * {@link Tool#frameAll}; a scene with a view of its own frames that when the tool does not.
     *
     * @return whether the active tool framed its own view
     */
    public boolean frameAll() {
        return activeTool != null && activeTool.frameAll();
    }

    /**
     * A tool's click that landed on nothing it acts on: the second within {@link #DOUBLE_CLICK_MS}
     * and {@link #DOUBLE_CLICK_RADIUS_PX} of the first is a double-click, which {@link #frameAll frames all}.
     *
     * @param x framebuffer x of the click
     * @param y framebuffer y of the click, y up
     */
    public void emptySpaceClicked(float x, float y) {
        long now = System.currentTimeMillis();
        boolean doubleClick = now - lastEmptyClickMillis <= DOUBLE_CLICK_MS
                && Math.hypot(x - lastEmptyClickX, y - lastEmptyClickY) <= DOUBLE_CLICK_RADIUS_PX;
        lastEmptyClickMillis = doubleClick ? 0 : now;
        lastEmptyClickX = x;
        lastEmptyClickY = y;
        if (doubleClick) {
            frameAll();
        }
    }

    /**
     * The cursor moved over the scene with no press dragging: the active tool hovers what is under
     * it. Scenes with panes or a menu decide what the cursor is over.
     *
     * @param windowX cursor x in window pixels
     * @param windowY cursor y in window pixels, top-down
     */
    public void viewHovered(float windowX, float windowY) {
        if (activeTool != null) {
            activeTool.onHover(mouse.normalizedPosX, mouse.normalizedPosY);
        }
    }

    /**
     * A click that no pane and no tool press took: the active tool's, by default. Scenes with
     * panes or a menu route it to what it landed on.
     *
     * @param windowX click x in window pixels
     * @param windowY click y in window pixels, top-down
     * @param button  button index
     */
    public void viewClicked(float windowX, float windowY, int button) {
        if (activeTool != null) {
            activeTool.onClick(mouse.normalizedPosX, mouse.normalizedPosY);
        }
    }

    /**
     * A tool's name as its switch hint shows it, marked when it is the active tool.
     *
     * @param tool the tool
     * @return the label
     */
    public String toolLabel(Tool tool) {
        return tool.toolName() + (tool == activeTool ? ACTIVE_MARK : "");
    }

    /**
     * Route the platform's input through the automation recorder to {@link #keys} and
     * {@link #mouse}; no-op on a platform without automation.
     *
     * @param platform host platform whose input callbacks to bind
     */
    public void bindAutomationIfAvailable(Platform platform) {
        AutomationBackend automation = platform.automation();
        if (automation != null) {
            automation.bindInput(platform, this);
        }
    }

    /**
     * Route raw platform input to the scene's key and mouse handlers, bypassing the automation
     * binding, which the browser platform has none of and so leaves every callback null.
     *
     * @param platform  active platform
     * @param keyGuy    key handler
     * @param mouseTrap mouse handler
     */
    public static void bindInputDirect(Platform platform, KeyGuy keyGuy, MouseTrap mouseTrap) {
        PointerDispatcher pointer = PointerDispatcher.forPlatform(platform.getPlatformID());
        platform.setCursorPosCallback(
                (window, x, y) -> pointer.moveOrDrag(mouseTrap, window, (float) x, (float) y));
        platform.setMouseButtonCallback(
                (button, action, mods) -> pointer.mouseButton(mouseTrap, button, action, mods));
        platform.setScrollCallback(mouseTrap::scrollCallback);
        platform.setKeyCallback(
                (key, scancode, action, mods) -> keyGuy.keyCallback(0L, key, scancode, action, mods));
    }
}
