package ixdar.scenes.trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import ixdar.scenes.Scene;
import ixdar.game.City;
import ixdar.game.CityNetwork;
import ixdar.geometry.grid2d.Grid.CartesianGrid;
import ixdar.geometry.tsp.points.PointSet;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.Platforms;
import ixdar.platform.input.PointerDispatcher;
import ixdar.tools.knot.Tool;
import ixdar.tools.trade.HeadquartersPickerTool;
import ixdar.tools.trade.RoutePlanningTool;

/**
 * The main scene for the trade game. Handles rendering cities, camera controls,
 * and player interactions. Swapped in by the menu through {@link #startNewGame}.
 */
public class TradeScene extends Scene {
    public static final float CAMERA_ZOOM_DEFAULT = 0.9f;
    public static final String PACKAGE_SEPARATOR = ".";
    public static final float DRAW_LINE_WIDTH = 2f;
    public static final float ROAD_PROXIMITY_THRESHOLD = 200f;

    // View constants
    public static final String VIEW_MAIN = "MAIN";
    public static final int TOP_BAR_HEIGHT = 40;

    /** The camera over the city map, framing the window below the top bar. */
    public Camera2D mapCamera;

    /** The scene Escape returns to: the menu the game was started from. */
    public Scene backScene;

    // Game state
    public CityNetwork network;
    public City hoveredCity = null;
    public int gold = 100;

    // Tools
    public HeadquartersPickerTool hqPickerTool;
    public RoutePlanningTool routePlanningTool;
    public Tool activeTradeTool;

    /** The cities' positions, which bound {@link #mapCamera}. */
    public PointSet cityPoints;

    /**
     * Build a trade scene around {@code network}: derive the camera's
     * point set from its cities, instantiate the HQ-picker and route-
     * planning tools, wire input handlers and lay out the views.
     *
     * @param network city network containing cities and roads
     * @param backScene the scene Escape returns to
     */
    public TradeScene(CityNetwork network, Scene backScene) {
        this.network = network;
        this.backScene = backScene;

        // Create PointSet from the network for camera bounds
        cityPoints = network.toPointSet();

        int wWidth = (int) Platforms.get().getWindowWidth();
        int wHeight = (int) Platforms.get().getWindowHeight();

        mapCamera = new Camera2D(wWidth, wHeight - TOP_BAR_HEIGHT, CAMERA_ZOOM_DEFAULT, 0, 0, cityPoints);

        // Create tools
        hqPickerTool = new HeadquartersPickerTool(this, network);
        routePlanningTool = new RoutePlanningTool(this, network);
        activeTradeTool = hqPickerTool; // Start with HQ picker

        // Create input handlers
        keys = new TradeKeyGuy(this, mapCamera);
        mouse = new TradeMouseTrap(this, mapCamera);

        Platforms.gl().setWindowTitle("Ixdar : Trade Game");

        Map<String, Bounds> views = new HashMap<>();
        views.put(VIEW_MAIN, new Bounds(0, 0, wWidth, wHeight - TOP_BAR_HEIGHT, b -> {
            int ww = (int) Platforms.get().getWindowWidth();
            int wh = (int) Platforms.get().getWindowHeight();
            b.update(0, 0, ww, wh - TOP_BAR_HEIGHT);
        }, VIEW_MAIN));
        views.put(VIEW_TOOLTIP, tooltipBounds());

        mapCamera.initCamera(views, VIEW_MAIN);
        PointerDispatcher.current().subscribe(views.get(VIEW_MAIN), mapCamera);
    }

    /**
     * Take or release input: on activation bind the game's handlers to the platform, pause the
     * menu music and hide the menu.
     *
     * @param state true to take input focus, false to release it
     */
    @Override
    public void activate(boolean state) {
        if (state) {
            bindAutomationIfAvailable(Platforms.get(), keys, mouse);
            audioPauseMenuMusic();
            MenuBox.menuVisible = false;
        }
        active = state;
        keys.active = state;
        mouse.active = state;
    }

    /**
     * The game's keys are its key handler's own, not control hints.
     */
    @Override
    public void setControls() {
    }

    /**
     * Main draw loop: the city network, the active tool's overlay, the top bar and the tooltip,
     * sharing the frame's z-order with the window-sized 2D camera.
     */
    @Override
    public void drawScene() {
        try {
            int wWidth = (int) Platforms.get().getWindowWidth();
            int wHeight = (int) Platforms.get().getWindowHeight();

            // Setup camera
            mapCamera.updateView(VIEW_MAIN);
            mapCamera.resetZIndex();
            mapCamera.setZIndex(camera2D);
            mapCamera.calculateCameraTransform(cityPoints);

            // Draw scene - network draws cities and roads
            network.draw(mapCamera, hoveredCity);

            // Draw active tool overlay
            if (activeTradeTool != null) {
                activeTradeTool.draw(mapCamera, DRAW_LINE_WIDTH);
            }

            drawTopBar(wWidth, wHeight);

            drawTooltip(mapCamera);

            camera2D.setZIndex(mapCamera);

        } catch (Exception e) {
            for (StackTraceElement ste : e.getStackTrace()) {
                Platforms.get().log(ste.getFileName() + PACKAGE_SEPARATOR + ste.getMethodName() + ":" + ste.getLineNumber());
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
        if (activeTradeTool instanceof HeadquartersPickerTool) {
            System.out.println("[TradeScene] Forwarding to HeadquartersPickerTool");
            ((HeadquartersPickerTool) activeTradeTool).onCityClick(city);
        } else if (activeTradeTool instanceof RoutePlanningTool) {
            System.out.println("[TradeScene] Forwarding to RoutePlanningTool");
            ((RoutePlanningTool) activeTradeTool).onCityClick(city);
        }
    }

    /**
     * Activate the route planning tool (called after HQ is placed).
     */
    public void activateRoutePlanningTool() {
        activeTradeTool = routePlanningTool;
        routePlanningTool.reset();
        System.out.println("Route planning tool activated");
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
            if (activeTradeTool instanceof HeadquartersPickerTool) {
                ((HeadquartersPickerTool) activeTradeTool).updateHoveredCity(city);
            } else if (activeTradeTool instanceof RoutePlanningTool) {
                ((RoutePlanningTool) activeTradeTool).updateHoveredCity(city);
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
        if (!(activeTradeTool instanceof RoutePlanningTool)) {
            return;
        }

        RoutePlanningTool routeTool = (RoutePlanningTool) activeTradeTool;
        routeTool.updateHoveredButton(mouseX, mouseY);
        HyperString toolbarTip = routeTool.buildHoveredToolbarTooltip();
        if (toolbarTip != null) {
            setTooltipText(toolbarTip);
        } else if (hoveredCity == null) {
            clearTooltipText();
        }
    }

    /**
     * Return to the game menu from the next frame on.
     */
    public void returnToMenu() {
        switchTo(backScene);
    }

    /**
     * Start a new trade game with the given city network, replacing {@code canvas} from the next
     * frame on.
     *
     * @param network the city network containing cities and roads
     * @param canvas  the scene the game replaces, which Escape returns to
     * @return the created TradeScene
     */
    public static TradeScene startNewGame(CityNetwork network, Scene canvas) {
        TradeScene scene = new TradeScene(network, canvas);
        canvas.switchTo(scene);
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
    public static TradeScene startNewGame(ArrayList<City> cities, Scene canvas) {
        CityNetwork network = new CityNetwork(cities, new CartesianGrid());
        // Generate roads based on proximity - cities within 200 units get connected
        network.generateRoadsFromProximity(ROAD_PROXIMITY_THRESHOLD);
        return startNewGame(network, canvas);
    }
}
