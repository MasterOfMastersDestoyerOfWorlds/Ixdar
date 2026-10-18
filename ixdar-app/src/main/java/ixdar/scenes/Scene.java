package ixdar.scenes;

import java.util.ArrayList;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorBox;
import ixdar.graphics.render.sdf.ShaderDrawable;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.PaneTypes;
import ixdar.gui.ui.code.ShaderCodePane;
import ixdar.platform.Platforms;
import ixdar.scenes.main.Toggle;
import ixdar.platform.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.Keys;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.PointerDispatcher;
import ixdar.tools.ControlHint;
import ixdar.tools.EditTool;

public abstract class Scene extends Canvas3D {

    public static final String VIEW_SCENE_TERMINAL = "SCENE_TERMINAL";

    public static final int TERMINAL_PANEL_HEIGHT = 300;

    public static final String TOOL_LOG_PREFIX = "[tools] ";

    public static final String ACTIVE_MARK = " (active)";

    public static final long DOUBLE_CLICK_MS = 500;

    public static final float DOUBLE_CLICK_RADIUS_PX = 6f;

    public static final String FRAME_ALL_CLICK = "double-click empty";

    public static final String FRAME_ALL_DESCRIPTION = "frame all: back to the tool's home view";

    public ShaderCodePane codePane;
    public float SCROLL_SPEED = 10f;


    public final ArrayList<ControlHint> controls = new ArrayList<>();

    /** Every tool the scene hosts, each run once per frame whether active or not; empty for none. */
    public final ArrayList<EditTool> tools = new ArrayList<>();

    /** The key hints that switch tools, as registered, each paired with {@link #switchedTools}. */
    public final ArrayList<ControlHint> toolSwitchHints = new ArrayList<>();

    /** The tool each of {@link #toolSwitchHints} switches to. */
    public final ArrayList<EditTool> switchedTools = new ArrayList<>();

    /** The tool that owns clicks, drags and tool keys now, or {@code null} in a scene with none. */
    public EditTool activeTool;

    /** The tool Esc returns to when the active tool leaves the key alone. */
    public EditTool defaultTool;

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

    /** When and where the last {@link #emptySpaceClicked} click fell, 0 once it made a double-click. */
    public long lastEmptyClickMillis;
    public float lastEmptyClickX;
    public float lastEmptyClickY;

    /**
     * Default Scene constructor; defers GL setup to {@link #initGL()}.
     */
    public Scene() {
        super();
    }

    /**
     * Initialize the scene: seed editable points, install a framebuffer-sized pane
     * bounds updater, and frame the 2D camera on the default view.
     */
    @Override
    public void initGL() {
        super.initGL();
        initPoints();
        paneBounds.setUpdateCallback(
                b -> b.update(0, 0, Platforms.get().getFrameBufferWidth(),
                        Platforms.get().getFrameBufferHeight()));
        camera2D.initCamera(webViews, DEFAULT_VIEW);
        refreshControls();
    }

    /**
     * Register the bottom terminal strip view and subscribe it for scroll.
     * Subclasses that add their own panes override this and chain
     * {@code super.initPanes()}.
     */
    public void initPanes() {
        webViews.put(VIEW_SCENE_TERMINAL, new Bounds(
                0, 0,
                Platforms.get().getFrameBufferWidth(), TERMINAL_PANEL_HEIGHT,
                bounds -> bounds.update(0, 0,
                        Platforms.get().getFrameBufferWidth(), TERMINAL_PANEL_HEIGHT),
                VIEW_SCENE_TERMINAL));
        PointerDispatcher.current().subscribe(webViews.get(VIEW_SCENE_TERMINAL), sceneTerminal);
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
        codePane = new ShaderCodePane(paneBounds, webViews, SCROLL_SPEED, shader, title, provider, camera2D, this);
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
        if (sceneTerminal != null && Toggle.IsTerminalFocused.value) {
            camera2D.updateView(VIEW_SCENE_TERMINAL);
            chromeBackground.draw(Color.GREY_VERY_DARK, camera2D);
            sceneTerminal.draw(camera2D);
        }
        camera2D.resetView();
    }

    /**
     * Populate {@link #controls}. Base adds the orbit/scroll rows; scenes call
     * {@code super.setControls()} then add their keyed hints.
     */
    public void setControls() {
        controls.add(
                new ControlHint(Keys.GRAVE, "~", "toggle the terminal", () -> {
                    boolean focusTerminal = !Toggle.IsTerminalFocused.value;
                    Toggle.setPanelFocus(focusTerminal ? PaneTypes.Terminal : PaneTypes.KnotView);
                }));
    }

    /**
     * Host {@code tool} with a key that switches to it, listed before every other control.
     *
     * @param tool       the tool, run every frame from now on
     * @param switchHint the key and purpose; the hint shows the tool's name and whether it is active
     */
    public void addToolSwitch(EditTool tool, ControlHint switchHint) {
        tools.add(tool);
        toolSwitchHints.add(switchHint);
        switchedTools.add(tool);
    }

    /**
     * Make {@code tool} active: the current one hands back the mouse keeping its state, and the
     * control hints become the new tool's. Switching to the active tool does nothing.
     *
     * @param tool the tool to switch to
     */
    public void switchTool(EditTool tool) {
        if (tool == activeTool) {
            return;
        }
        activeTool.deactivate();
        activeTool = tool;
        activeTool.activate();
        refreshControls();
        Platforms.get().log(TOOL_LOG_PREFIX + "active tool: " + activeTool.toolName());
    }

    /**
     * Rebuild {@link #controls}: the tool switches, the active tool's hints, F for
     * {@link #frameAll} unless the tool binds F itself, then the scene's own from
     * {@link #setControls}, left out by a tool that {@link EditTool#replacesView replaces the view}.
     */
    public void refreshControls() {
        controls.clear();
        for (int switchIndex = 0; switchIndex < toolSwitchHints.size(); switchIndex++) {
            ControlHint hint = toolSwitchHints.get(switchIndex);
            EditTool tool = switchedTools.get(switchIndex);
            controls.add(new ControlHint(hint.keyCode, hint.controlHeld, hint.shiftHeld, hint.key,
                    tool.toolName() + ": " + hint.description + (tool == activeTool ? ACTIVE_MARK : ""),
                    hint.action));
        }
        if (activeTool != null) {
            activeTool.addControls(controls);
            boolean toolBindsF = false;
            for (ControlHint hint : controls) {
                toolBindsF |= hint.keyCode == Keys.F && !hint.controlHeld && !hint.shiftHeld;
            }
            if (!toolBindsF) {
                controls.add(new ControlHint(Keys.F, "F", FRAME_ALL_DESCRIPTION, this::frameAll));
            }
        }
        if (activeTool == null || !activeTool.replacesView()) {
            setControls();
        }
    }

    /**
     * Frame everything the active tool shows, the same gesture in every tool, through
     * {@link EditTool#frameAll}; a scene with a view of its own frames that when the tool does not.
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
     * A tool's name as its switch hint shows it, marked when it is the active tool.
     *
     * @param tool the tool
     * @return the label
     */
    public String toolLabel(EditTool tool) {
        return tool.toolName() + (tool == activeTool ? ACTIVE_MARK : "");
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
