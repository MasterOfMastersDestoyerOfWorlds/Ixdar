package ixdar.scenes.trade;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import ixdar.canvas.Canvas3D;
import ixdar.game.City;
import ixdar.game.CityNetwork;
import ixdar.geometry.point.Grid.CartesianGrid;
import ixdar.geometry.point.PointSet;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorBox;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.tools.HeadquartersPickerTool;
import ixdar.gui.ui.tools.RoutePlanningTool;
import ixdar.gui.ui.tools.Tool;
import ixdar.canvas.Canvas3D;
import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.SceneInputFrameUpdater;
import ixdar.platform.input.TradeKeyGuy;
import ixdar.platform.input.TradeMouseTrap;

/**
 * The main scene for the trade game. Handles rendering cities, camera controls,
 * and player interactions.
 */
public class TradeScene {
    public static final String STR = ".";
    public static final float NUM_0_9 = 0.9f;
    public static final float NUM_2 = 2f;
    public static final float NUM_200 = 200f;

    public static TradeScene instance;
    public static Camera2D camera;
    public static boolean active = false;

    // View constants
    public static final String VIEW_MAIN = "MAIN";
    public static final String VIEW_TOOLTIP = "TOOLTIP";
    public static final int TOP_BAR_HEIGHT = 40;

    // Tooltip state
    private static HyperString toolTip;
    private static boolean showToolTip = false;

    // Game state
    public CityNetwork network;
    public City hoveredCity = null;
    public int gold = 100;

    // Tools
    public HeadquartersPickerTool hqPickerTool;
    public RoutePlanningTool routePlanningTool;
    public Tool activeTool;

    // Rendering
    private Canvas3D canvas;
    private PointSet pointSet;

    // Input handlers
    private TradeKeyGuy keys;
    private TradeMouseTrap mouse;

    /**
     * Build a trade scene around {@code network}: derive the camera's
     * point set from its cities, instantiate the HQ-picker and route-
     * planning tools, and wire input handlers.
     *
     * @param network city network containing cities and roads
     * @param canvas backing 3D canvas used for input dispatch
     */
    public TradeScene(CityNetwork network, Canvas3D canvas) {
        this.network = network;
        this.canvas = canvas;
        instance = this;

        // Create PointSet from the network for camera bounds
        pointSet = network.toPointSet();

        int wWidth = (int) Platforms.get().getWindowWidth();
        int wHeight = (int) Platforms.get().getWindowHeight();

        camera = new Camera2D(wWidth, wHeight - TOP_BAR_HEIGHT, NUM_0_9, 0, 0, pointSet);

        // Create tools
        hqPickerTool = new HeadquartersPickerTool(this, network);
        routePlanningTool = new RoutePlanningTool(this, network);
        activeTool = hqPickerTool; // Start with HQ picker

        // Create input handlers
        keys = new TradeKeyGuy(this, camera, canvas);
        mouse = new TradeMouseTrap(this, camera, canvas);

        Platforms.gl().setWindowTitle("Ixdar : Trade Game");
    }

    /**
     * Initialize the camera views.
     */
    public void initViews() {
        int wWidth = (int) Platforms.get().getWindowWidth();
        int wHeight = (int) Platforms.get().getWindowHeight();

        Map<String, Bounds> views = new HashMap<>();
        views.put(VIEW_MAIN, new Bounds(0, 0, wWidth, wHeight - TOP_BAR_HEIGHT, b -> {
            int ww = (int) Platforms.get().getWindowWidth();
            int wh = (int) Platforms.get().getWindowHeight();
            b.update(0, 0, ww, wh - TOP_BAR_HEIGHT);
        }, VIEW_MAIN));

        views.put(VIEW_TOOLTIP, new Bounds(0, 0, 0, 0, b -> {
            int ww = (int) Platforms.get().getWindowWidth();
            int wh = (int) Platforms.get().getWindowHeight();
            if (toolTip == null) {
                b.update(0, 0, 0, 0);
                return;
            }
            float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
            float mouseX = mouse != null ? mouse.normalizedPosX : 0;
            float mouseY = mouse != null ? mouse.normalizedPosY : 0;
            int isRight = mouseX > ww / 2 ? 1 : 0;
            int isTop = mouseY > wh / 2 ? 1 : 0;
            float toolTipWidth = toolTip.getWidthPixels();
            int toolTipHeight = toolTip.getHeightPixels();
            int x = (int) (mouseX - (isRight * toolTipWidth));
            int y = (int) mouseY - (isTop * toolTipHeight);
            b.update(x, y, (int) Math.ceil(toolTipWidth), (int) (toolTip.getLines() * rowHeight));
        }, VIEW_TOOLTIP));

        camera.initCamera(views, VIEW_MAIN);
        MouseTrap.subscribeScrollRegion(views.get(VIEW_MAIN), camera);
    }

    /**
     * Activate or deactivate this scene's input handlers.
     *
     * @param state true to take input focus, false to release it back to the canvas
     */
    public void activate(boolean state) {
        if (state) {
            Platform p = Platforms.get();
            bindAutomationIfAvailable(p, keys, mouse);
        }
        if (canvas != null) {
            canvas.activate(!state);
        }
        active = state;
        keys.active = state;
        mouse.active = state;
    }

    /**
     * Main draw loop.
     *
     * @param camera3D outer 3D camera whose z-index is shared with the 2D camera
     */
    public void draw(Camera camera3D) {
        try {
            int wWidth = (int) Platforms.get().getWindowWidth();
            int wHeight = (int) Platforms.get().getWindowHeight();

            // Process input
            SceneInputFrameUpdater.update(keys, mouse);

            // Setup camera
            camera.updateView(VIEW_MAIN);
            camera.resetZIndex();
            camera.setZIndex(camera3D);
            camera.calculateCameraTransform(pointSet);

            // Draw scene - network draws cities and roads
            network.draw(camera, hoveredCity);

            // Draw active tool overlay
            if (activeTool != null) {
                activeTool.draw(camera, NUM_2);
            }

            drawTopBar(wWidth, wHeight);

            // Draw tooltip
            if (toolTip != null && showToolTip) {
                float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
                toolTip.setLineOffsetFromTopRow(camera, 0, 0, rowHeight);
                camera.updateView(VIEW_TOOLTIP);
                new ColorBox().draw(Color.DARK_GRAY, camera);
                Drawing.getDrawing().font.drawHyperStringRows(toolTip, 0, 0, rowHeight, camera);
            }

            camera3D.setZIndex(camera);

        } catch (Exception e) {
            for (StackTraceElement ste : e.getStackTrace()) {
                Platforms.get().log(ste.getFileName() + STR + ste.getMethodName() + ":" + ste.getLineNumber());
            }
        }
    }

    /**
     * Draw the top bar HUD (placeholder for TRADE-11).
     *
     * @param wWidth current window width in pixels
     * @param wHeight current window height in pixels
     */
    private void drawTopBar(int wWidth, int wHeight) {
        // TODO: Implement in TRADE-11
    }

    /**
     * Find which city is at the given world coordinates.
     *
     * @param worldX x coordinate in world space
     * @param worldY y coordinate in world space
     * @return the city at that location, or null if none
     */
    public City getCityAt(float worldX, float worldY) {
        return network.getCityAt(worldX, worldY, City.CLICK_RADIUS);
    }

    /**
     * Handle city click - delegates to the active tool.
     *
     * @param city the city that was clicked
     */
    public void onCityClick(City city) {
        System.out.println("[TradeScene] onCityClick: " + (city != null ? city.name : "null") +
                " activeTool=" + activeTool.displayName());
        if (activeTool instanceof HeadquartersPickerTool) {
            System.out.println("[TradeScene] Forwarding to HeadquartersPickerTool");
            ((HeadquartersPickerTool) activeTool).onCityClick(city);
        } else if (activeTool instanceof RoutePlanningTool) {
            System.out.println("[TradeScene] Forwarding to RoutePlanningTool");
            ((RoutePlanningTool) activeTool).onCityClick(city);
        }
    }

    /**
     * Activate the route planning tool (called after HQ is placed).
     */
    public void activateRoutePlanningTool() {
        activeTool = routePlanningTool;
        routePlanningTool.reset();
        System.out.println("Route planning tool activated");
    }

    /**
     * Keyboard input handler for this scene.
     *
     * @return the trade-specific key handler
     */
    public TradeKeyGuy getKeys() {
        return keys;
    }

    /**
     * Mouse input handler for this scene.
     *
     * @return the trade-specific mouse handler
     */
    public TradeMouseTrap getMouse() {
        return mouse;
    }

    /**
     * Get the headquarters city.
     *
     * @return the headquarters city, or null if not set
     */
    public City getHeadquartersCity() {
        return network.headquartersCity;
    }

    /**
     * Update the currently hovered city and tooltip.
     *
     * @param city the city being hovered, or null if none
     */
    public void updateHoveredCity(City city) {
        if (city != hoveredCity) {
            hoveredCity = city;

            // Update tool hover state
            if (activeTool instanceof HeadquartersPickerTool) {
                ((HeadquartersPickerTool) activeTool).updateHoveredCity(city);
            } else if (activeTool instanceof RoutePlanningTool) {
                ((RoutePlanningTool) activeTool).updateHoveredCity(city);
            }

            // Update tooltip
            if (hoveredCity != null) {
                setTooltipText(hoveredCity.buildTooltip(network.headquartersCity));
            } else {
                clearTooltipText();
            }
        }
    }

    /**
     * Update hovered toolbar state and tooltip for RoutePlanningTool.
     *
     * @param mouseX screen-space mouse x
     * @param mouseY screen-space mouse y
     */
    public void updateHoveredToolbar(float mouseX, float mouseY) {
        if (!(activeTool instanceof RoutePlanningTool)) {
            return;
        }

        RoutePlanningTool routeTool = (RoutePlanningTool) activeTool;
        routeTool.updateHoveredButton(mouseX, mouseY);
        HyperString toolbarTip = routeTool.buildHoveredToolbarTooltip();
        if (toolbarTip != null) {
            setTooltipText(toolbarTip);
        } else if (hoveredCity == null) {
            clearTooltipText();
        }
    }

    /**
     * Return to the game menu.
     */
    public void returnToMenu() {
        active = false;
        keys.active = false;
        mouse.active = false;
        MenuBox.menuVisible = true;
        Canvas3D.audioPlayMenuMusic();
        if (canvas != null) {
            canvas.activate(true); // Restore menu input handling
        }
    }

    // Static tooltip methods

    /**
     * Show a floating tooltip with the given text on the next frame.
     *
     * @param tip formatted tooltip body
     */
    public static void setTooltipText(HyperString tip) {
        toolTip = tip;
        showToolTip = true;
    }

    /**
     * Hide the floating tooltip and clear its text.
     */
    public static void clearTooltipText() {
        toolTip = null;
        showToolTip = false;
    }

    /**
     * Current tooltip body, or {@code null} when no tooltip is set.
     *
     * @return the tooltip text, or null
     */
    public static HyperString getToolTip() {
        return toolTip;
    }

    /**
     * Whether the tooltip should be drawn this frame.
     *
     * @return true if a tooltip is set and visible
     */
    public static boolean isToolTipVisible() {
        return showToolTip;
    }

    /**
     * Start a new trade game with the given city network.
     *
     * @param network the city network containing cities and roads
     * @param canvas  the 3D canvas
     * @return the created TradeScene
     */
    public static TradeScene startNewGame(CityNetwork network, Canvas3D canvas) {
        TradeScene scene = new TradeScene(network, canvas);
        scene.initViews();
        scene.activate(true);
        Canvas3D.audioPauseMenuMusic();
        MenuBox.menuVisible = false;
        return scene;
    }

    /**
     * Start a new trade game with the given cities (convenience method) Creates a
     * CityNetwork from the cities with proximity-based roads.
     *
     * @param cities list of cities
     * @param canvas the 3D canvas
     * @return the created TradeScene
     */
    public static TradeScene startNewGame(ArrayList<City> cities, Canvas3D canvas) {
        CityNetwork network = new CityNetwork(cities, new CartesianGrid());
        // Generate roads based on proximity - cities within 200 units get connected
        network.generateRoadsFromProximity(NUM_200);
        return startNewGame(network, canvas);
    }

    private static void bindAutomationIfAvailable(Platform platform, KeyGuy keys, MouseTrap mouse) {
        try {
            Class<?> binder = Class.forName(
                    String.join(STR, "ixdar", "platform", "automation", "AutomationInputBinder"));
            Method bind = binder.getMethod("bind", Platform.class, KeyGuy.class, MouseTrap.class);
            bind.invoke(null, platform, keys, mouse);
        } catch (Throwable ignored) {
        }
    }
}
