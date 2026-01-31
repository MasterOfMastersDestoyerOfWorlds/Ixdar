package ixdar.scenes.trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.joml.Vector2f;

import ixdar.canvas.Canvas3D;
import ixdar.game.data.City;
import ixdar.geometry.point.PointND;
import ixdar.geometry.point.PointSet;
import ixdar.geometry.shell.Shell;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorBox;
import ixdar.graphics.render.sdf.SDFCircleSimple;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyActions;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.TradeKeyGuy;
import ixdar.platform.input.TradeMouseTrap;

/**
 * The main scene for the trade game.
 * Handles rendering cities, camera controls, and player interactions.
 */
public class TradeScene {

    public static TradeScene instance;
    public static Camera2D camera;
    public static boolean active = false;

    // Game state
    public ArrayList<City> cities = new ArrayList<>();
    public City hoveredCity = null;
    public City headquartersCity = null;
    public int gold = 100;

    // View constants
    public static final String VIEW_MAIN = "MAIN";
    public static final String VIEW_TOOLTIP = "TOOLTIP";
    public static final int TOP_BAR_HEIGHT = 40;

    // Tooltip state
    private static HyperString toolTip;
    private static boolean showToolTip = false;

    // Rendering
    private Canvas3D canvas;
    private PointSet pointSet;
    private SDFCircleSimple cityCircle = new SDFCircleSimple();

    // Input handlers
    private TradeKeyGuy keys;
    private TradeMouseTrap mouse;

    public TradeScene(ArrayList<City> cities, Canvas3D canvas) {
        this.cities = cities;
        this.canvas = canvas;
        instance = this;

        // Create a PointSet from the cities for the camera to focus on
        Shell shell = new Shell();
        for (City city : cities) {
            shell.add(new PointND.Float(city.getX(), city.getY()));
        }
        // Add margin points to ensure good camera bounds
        if (cities.size() > 0) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
            for (City city : cities) {
                minX = Math.min(minX, city.getX());
                minY = Math.min(minY, city.getY());
                maxX = Math.max(maxX, city.getX());
                maxY = Math.max(maxY, city.getY());
            }
            float margin = 50f;
            shell.add(new PointND.Float(minX - margin, minY - margin));
            shell.add(new PointND.Float(maxX + margin, maxY + margin));
        }
        pointSet = shell.toPointSet();

        int wWidth = (int) Platforms.get().getWindowWidth();
        int wHeight = (int) Platforms.get().getWindowHeight();

        camera = new Camera2D(wWidth, wHeight - TOP_BAR_HEIGHT, 0.9f, 0, 0, pointSet);

        // Create input handlers
        keys = new TradeKeyGuy(this, camera, canvas);
        mouse = new TradeMouseTrap(this, camera, canvas);

        Platforms.gl().setWindowTitle("Ixdar : Trade Game");
    }

    /**
     * Initialize the camera views
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
     * Activate or deactivate this scene's input handlers
     */
    public void activate(boolean state) {
        if (state) {
            Platform p = Platforms.get();
            p.setKeyCallback((key, scancode, action, mods) -> keys.keyCallback(0L, key, scancode, action, mods));
            p.setCharCallback(codepoint -> keys.charCallback(0L, codepoint));
            p.setMouseButtonCallback((button, action, mods) -> mouse.mouseButton(button, action, mods));
            p.setCursorPosCallback((window, x, y) -> mouse.moveOrDrag(window, (float) x, (float) y));
            p.setScrollCallback((xoff, yoff) -> mouse.scrollCallback(yoff));
        }
        if (canvas != null) {
            canvas.activate(!state);
        }
        active = state;
        keys.active = state;
        mouse.active = state;
    }

    /**
     * Main draw loop
     */
    public void draw(Camera camera3D) {
        try {
            int wWidth = (int) Platforms.get().getWindowWidth();
            int wHeight = (int) Platforms.get().getWindowHeight();

            // Process input
            float speedMod = KeyActions.DoubleSpeed.keyPressed(keys.pressedKeys) ? 2f : 1f;
            keys.paintUpdate(speedMod);
            mouse.paintUpdate(speedMod);

            // Setup camera
            camera.updateView(VIEW_MAIN);
            camera.resetZIndex();
            camera.setZIndex(camera3D);
            camera.calculateCameraTransform(pointSet);

            // Draw scene
            drawCities();
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
                Platforms.get().log(ste.getFileName() + "." + ste.getMethodName() + ":" + ste.getLineNumber());
            }
        }
    }

    /**
     * Draw all cities on the map
     */
    private void drawCities() {
        Drawing d = Drawing.getDrawing();
        float cityRadius = 20f;

        for (City city : cities) {
            Color cityColor = Color.CYAN;

            if (city == headquartersCity) {
                cityColor = Color.YELLOW;
            } else if (city == hoveredCity) {
                cityColor = Color.GREEN;
            }

            float screenX = camera.pointTransformX(city.getX());
            float screenY = camera.pointTransformY(city.getY());
            Vector2f screenPos = new Vector2f(screenX, screenY);

            // Draw city circle
            cityCircle.draw(screenPos, cityRadius, cityColor, camera);

            // Draw city name (using cached HyperString from City)
            float labelScreenY = screenY - cityRadius - 20;
            d.font.drawHyperString(city.getNameLabel(), screenX, labelScreenY, Drawing.FONT_HEIGHT_PIXELS, camera);
        }
    }

    /**
     * Draw the top bar HUD (placeholder for TRADE-11)
     */
    private void drawTopBar(int wWidth, int wHeight) {
        // TODO: Implement in TRADE-11
    }

    /**
     * Find which city is at the given world coordinates
     * @param worldX x coordinate in world space
     * @param worldY y coordinate in world space
     * @return the city at that location, or null if none
     */
    public City getCityAt(float worldX, float worldY) {
        float clickRadius = 20f;
        for (City city : cities) {
            if (city.containsPoint(worldX, worldY, clickRadius)) {
                return city;
            }
        }
        return null;
    }

    /**
     * Handle city click - place headquarters on first click
     * @param city the city that was clicked
     */
    public void onCityClick(City city) {
        if (headquartersCity == null) {
            headquartersCity = city;
            city.placeHeadquarters();
            System.out.println("Headquarters placed at: " + city.name);
        } else {
            // Future: handle route creation, contracts, etc.
            System.out.println("Clicked on: " + city.name);
        }
    }

    /**
     * Update the currently hovered city and tooltip
     * @param city the city being hovered, or null if none
     */
    public void updateHoveredCity(City city) {
        if (city != hoveredCity) {
            hoveredCity = city;
            if (hoveredCity != null) {
                HyperString tip = new HyperString();
                tip.addWord(hoveredCity.name, Color.WHITE);
                tip.wrap();
                tip.addWord("Pop: " + hoveredCity.population, Color.LIGHT_GRAY);
                setTooltipText(tip);
            } else {
                clearTooltipText();
            }
        }
    }

    /**
     * Return to the game menu
     */
    public void returnToMenu() {
        active = false;
        keys.active = false;
        mouse.active = false;
        MenuBox.menuVisible = true;
    }

    // Static tooltip methods

    public static void setTooltipText(HyperString tip) {
        toolTip = tip;
        showToolTip = true;
    }

    public static void clearTooltipText() {
        toolTip = null;
        showToolTip = false;
    }

    /**
     * Start a new trade game with the given cities
     */
    public static TradeScene startNewGame(ArrayList<City> cities, Canvas3D canvas) {
        TradeScene scene = new TradeScene(cities, canvas);
        scene.initViews();
        scene.activate(true);
        MenuBox.menuVisible = false;
        return scene;
    }
}
