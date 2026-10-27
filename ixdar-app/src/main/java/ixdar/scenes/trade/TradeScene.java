package ixdar.scenes.trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector2f;

import ixdar.annotations.command.Subsystem;
import ixdar.annotations.scene.SceneAnnotation;
import ixdar.game.City;
import ixdar.game.CityNetwork;
import ixdar.game.TradeGame;
import ixdar.game.TradeLevel;
import ixdar.game.TradeMap;
import ixdar.geometry.grid2d.IrregularQuadGrid;
import ixdar.geometry.tsp.points.PointND;
import ixdar.geometry.tsp.points.PointSet;
import ixdar.geometry.tsp.shell.Shell;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFBezier;
import ixdar.graphics.render.sdf.SDFCircle;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.graphics.render.sdf.SDFRoundedRect;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.text.HyperString;
import ixdar.input.CanvasScroll;
import ixdar.input.PointerDispatcher;
import ixdar.platform.Clock;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.Scene;
import ixdar.tools.ControlHint;
import ixdar.tools.trade.RoutePlanningTool;

/**
 * The trade game: a level's map on the dual of the irregular grid, the player's and rival's loops as
 * lanes, and the HUD that runs the economy (top bar, city menu, loop list, toolbar and End lap).
 */
@SceneAnnotation(id = "trade-game")
public class TradeScene extends Scene {
    public static final float CAMERA_ZOOM_DEFAULT = 1f;
    public static final String VIEW_MAIN = "MAIN";
    public static final int TOP_BAR_HEIGHT = 48;
    public static final int TOOLBAR_HEIGHT = 60;
    public static final int PANEL_WIDTH = 330;
    public static final int PADDING = 10;
    public static final int ROW_HEIGHT = 24;
    public static final float TEXT_HEIGHT = 22f;
    public static final float SMALL_TEXT_HEIGHT = 18f;
    public static final float TITLE_TEXT_HEIGHT = 28f;
    public static final float CORNER_RADIUS = 6f;
    public static final float BORDER_WIDTH = 1.5f;
    public static final float LANE_WIDTH = 5f;
    public static final float RIVAL_LANE_WIDTH = 4f;
    public static final float LANE_OFFSET = 5f;
    public static final float ROAD_WIDTH = 2.5f;
    public static final float RIVER_WIDTH = 12f;
    public static final float GRID_LINE_WIDTH = 1f;
    public static final float CITY_RADIUS = 11f;
    public static final float BASE_HALF_SIZE = 12f;
    public static final float VILLAGE_HALF_SIZE = 4f;
    public static final float TARGET_RING_RADIUS = 18f;
    public static final float LABEL_OFFSET = 20f;
    public static final float NAME_OFFSET = 22f;
    public static final float ANIMATION_SECONDS = 2.5f;
    public static final float GOLD_RISE_PIXELS = 40f;
    public static final float WAGON_RADIUS = 11f;
    public static final float MAP_MARGIN = 30f;
    public static final float FOG_ALPHA = 0.12f;
    public static final float RIVAL_ALPHA = 0.65f;
    public static final float TERRITORY_ALPHA = 0.28f;
    public static final float TERRITORY_RANGE_IN_CELLS = 6f;
    public static final float HEAT_SCALE = 9f;
    public static final int MAP_NETWORK = 0;
    public static final int MAP_OWNER = 1;
    public static final int MAP_MARKET_SIZE = 2;
    public static final int MAP_MODE_COUNT = 3;
    public static final int OUTCOME_BOX_WIDTH = 520;
    public static final int OUTCOME_BOX_HEIGHT = 190;
    public static final int BUTTON_WIDTH = 100;
    public static final int WIDE_BUTTON_WIDTH = 150;
    public static final float CITY_BORDER_SHARE = 0.25f;
    public static final float RING_BORDER_SHARE = 0.18f;
    public static final float DASH_PIXELS = 14f;
    public static final int END_LAP_WIDTH = 200;
    public static final int SHARE_BAR_WIDTH = 320;
    public static final int FILL_BAR_HEIGHT = 12;
    public static final int PERCENT = 100;
    public static final String GOLD_FORMAT = "%.0f";
    public static final String COST_FORMAT = "%.2f";
    public static final String SEPARATOR = " · ";
    public static final String SPACE = " ";
    public static final String PLUS = "+";
    public static final String[] MEDAL_NAMES = { "", "bronze", "silver", "gold" };


    /** The camera over the map and, in its pixel space, the HUD. */
    public Camera2D mapCamera;
    /** The scene Escape returns to, or null when the game was opened directly. */
    public Scene backScene;
    public final List<TradeLevel> levels;
    public int levelIndex;
    public TradeGame game;
    public CityNetwork network;
    public City hoveredCity;
    public RoutePlanningTool routePlanningTool;
    /** The city whose menu is open, or -1. */
    public int selectedCity = -1;
    public int mapMode = MAP_NETWORK;
    public boolean rivalOnTop;
    /** The status line under the top bar: the last refusal or hint. */
    public String status = "";
    public PointSet mapPoints;

    private float animationStart = -1;
    private final ArrayList<float[]> buttonRectangles = new ArrayList<>();
    private final ArrayList<Runnable> buttonActions = new ArrayList<>();
    private final ArrayList<float[]> panelRectangles = new ArrayList<>();
    private int[] nearestCityOfCell = new int[0];
    private final SDFLine line = new SDFLine();
    private final SDFBezier bezier = new SDFBezier();
    private final SDFCircle circle = new SDFCircle();
    private final SDFRoundedRect box = new SDFRoundedRect();

    /**
     * Open the campaign at its first level, as the scene registry and the launch entries do.
     */
    public TradeScene() {
        this(TradeLevel.campaign(), 0, null);
    }

    /**
     * Open the campaign at a level; the level is built once the window's GL is up.
     *
     * @param levels     the campaign's levels
     * @param levelIndex the level to start on
     * @param backScene  the scene Escape returns to, or null
     */
    public TradeScene(List<TradeLevel> levels, int levelIndex, Scene backScene) {
        this.levels = levels;
        this.levelIndex = levelIndex;
        this.backScene = backScene;
    }

    /**
     * Build the level, the map camera framed so the map clears the HUD, and the input handlers.
     */
    @Override
    public void initGL() {
        super.initGL();
        MenuBox.menuVisible = false;
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        loadLevel(levelIndex);
        mapCamera = new Camera2D(width, height, CAMERA_ZOOM_DEFAULT, 0, 0, mapPoints);
        mouse.camera = mapCamera;
        CanvasScroll mapScroll = new CanvasScroll(mapCamera, false, () -> active);
        mouse.controller = mapScroll;
        Map<String, Bounds> views = new HashMap<>();
        views.put(VIEW_MAIN, new Bounds(0, 0, width, height, b -> b.update(0, 0,
                Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight()), VIEW_MAIN));
        views.put(VIEW_TOOLTIP, tooltipBounds());
        mapCamera.initCamera(views, VIEW_MAIN);
        PointerDispatcher.current().subscribe(views.get(VIEW_MAIN), mapScroll);
        Platforms.get().setTitle("Ixdar : Blood Trade");
    }

    /**
     * Start a level from scratch.
     *
     * @param index the level's index in {@link #levels}
     */
    public void loadLevel(int index) {
        levelIndex = index;
        game = new TradeGame(levels.get(index));
        network = game.network;
        selectedCity = -1;
        animationStart = -1;
        status = game.level.goalText;
        routePlanningTool.reset();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Vector2f corner : game.map.cornerPoints) {
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
        }
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        float hudX = (float) (PANEL_WIDTH + 2 * PADDING) / Math.max(1, width - PANEL_WIDTH - 2 * PADDING);
        float hudY = (float) (TOP_BAR_HEIGHT + ROW_HEIGHT * 2 + TOOLBAR_HEIGHT)
                / Math.max(1, height - TOP_BAR_HEIGHT - ROW_HEIGHT * 2 - TOOLBAR_HEIGHT);
        float spanX = maxX - minX + 2 * MAP_MARGIN;
        float spanY = maxY - minY + 2 * MAP_MARGIN;
        Shell corners = new Shell();
        corners.add(new PointND.Float(minX - MAP_MARGIN, minY - MAP_MARGIN - spanY * hudY * 0.5f));
        corners.add(new PointND.Float(maxX + MAP_MARGIN + spanX * hudX, maxY + MAP_MARGIN + spanY * hudY * 0.5f));
        mapPoints = corners.toPointSet();
        if (mapCamera != null) {
            mapCamera.reset();
        }
        nearestCityOfCell = new int[game.map.cellX.length];
        for (int cell = 0; cell < nearestCityOfCell.length; cell++) {
            float best = Float.MAX_VALUE;
            for (int city = 0; city < game.cityCount; city++) {
                float d = game.map.distance(cell, game.cityCell[city]);
                if (d < best) {
                    best = d;
                    nearestCityOfCell[cell] = city;
                }
            }
        }
    }

    /**
     * Take or release input: on activation bind the game's handlers, pause the menu music and hide
     * the menu.
     *
     * @param state true to take input focus, false to release it
     */
    @Override
    public void activate(boolean state) {
        if (state) {
            bindAutomationIfAvailable(Platforms.get());
            audioPauseMenuMusic();
            MenuBox.menuVisible = false;
        }
        active = state;
        keys.active = state;
        mouse.active = state;
    }

    /** Host route planning, the game's one tool. */
    @Override
    public void setTools() {
        super.setTools();
        routePlanningTool = new RoutePlanningTool(this);
        tools.add(routePlanningTool);
        defaultTool = routePlanningTool;
    }

    /**
     * The game shows a trade map and its city network, which lies on an irregular grid.
     *
     * @param subsystem a scene-held subsystem
     * @return the network for {@link Subsystem#TRADE}, its grid for {@link Subsystem#GRID}, else the base's
     */
    @Override
    public Object dataset(Subsystem subsystem) {
        if (subsystem == Subsystem.TRADE) {
            return network;
        }
        if (subsystem == Subsystem.GRID) {
            return network != null && network.grid instanceof IrregularQuadGrid grid ? grid : null;
        }
        return super.dataset(subsystem);
    }

    /**
     * The game's own keys after the tool's: E ends the lap, M steps the map mode, R restarts the level,
     * and Esc closes the city menu or returns to the menu.
     *
     * @param controls the scene's control rows
     */
    @Override
    public void setControls(List<ControlHint> controls) {
        controls.add(new ControlHint("end-lap", Keys.E, "E", "end the lap", this::endLap));
        controls.add(new ControlHint("map-mode", Keys.M, "M", "step the map mode",
                () -> mapMode = (mapMode + 1) % MAP_MODE_COUNT));
        controls.add(new ControlHint("restart-level", Keys.R, "R", "restart the level", () -> loadLevel(levelIndex)));
        controls.add(new ControlHint("escape", Keys.ESCAPE, "esc", "close the city menu, else back to the menu",
                () -> {
                    if (!activeTool.escapePressed() && selectedCity < 0 && backScene != null) {
                        switchTo(backScene);
                    }
                    selectedCity = -1;
                }));
    }

    /**
     * Run the lap and start the wagons' animation.
     */
    public void endLap() {
        if (game.outcome != TradeGame.PLAYING) {
            return;
        }
        game.endLap();
        animationStart = Clock.time();
        status = "Lap " + (game.lap - 1) + ": profit " + String.format(GOLD_FORMAT, game.lastProfit[TradeGame.PLAYER])
                + " gold";
    }

    /**
     * The cursor hovers the city under it, then the tool hovers what it shows.
     *
     * @param windowX cursor x in window pixels
     * @param windowY cursor y in window pixels, top-down
     */
    @Override
    public void viewHovered(float windowX, float windowY) {
        if (game == null) {
            return;
        }
        hoveredCity = getCityAt(mapCamera.screenTransformX(mouse.normalizedPosX),
                mapCamera.screenTransformY(mouse.normalizedPosY));
        activeTool.onHover(mouse.normalizedPosX, mouse.normalizedPosY);
    }

    /**
     * A click on a HUD button runs it; a click elsewhere on a panel does nothing; a click on the map
     * goes to route planning.
     *
     * @param windowX click x in window pixels
     * @param windowY click y in window pixels, top-down
     * @param button  the mouse button
     */
    @Override
    public void viewClicked(float windowX, float windowY, int button) {
        float x = mouse.normalizedPosX;
        float y = mouse.normalizedPosY;
        for (int k = buttonRectangles.size() - 1; k >= 0; k--) {
            float[] r = buttonRectangles.get(k);
            if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) {
                buttonActions.get(k).run();
                return;
            }
        }
        for (float[] r : panelRectangles) {
            if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) {
                return;
            }
        }
        if (game != null && game.outcome == TradeGame.PLAYING) {
            super.viewClicked(windowX, windowY, button);
        }
    }

    /**
     * Frame the map: the map camera's reset.
     *
     * @return true
     */
    @Override
    public boolean frameAll() {
        mapCamera.reset();
        return true;
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
     * Start a new trade game from the campaign's first level, replacing {@code canvas} from the next
     * frame on.
     *
     * @param canvas the scene the game replaces, which Escape returns to
     * @return the created TradeScene
     */
    public static TradeScene startNewGame(Scene canvas) {
        TradeScene scene = new TradeScene(TradeLevel.campaign(), 0, canvas);
        canvas.switchTo(scene);
        return scene;
    }

    /**
     * Draw one frame back to front: terrain, owner tint, both companies' lanes, the tool's overlay, the
     * cities and the lap animation, then the HUD's top bar, side panel, toolbar and the level's result.
     */
    @Override
    public void drawScene() {
        if (game == null) {
            return;
        }
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        mapCamera.updateView(VIEW_MAIN);
        mapCamera.resetZIndex();
        mapCamera.setZIndex(camera2D);
        mapCamera.calculateCameraTransform(mapPoints);
        buttonRectangles.clear();
        buttonActions.clear();
        panelRectangles.clear();
        box.draw(0, 0, width, height, 0, 0, Color.WHITE_ORANGE, Color.WHITE_ORANGE, mapCamera);

        TradeMap map = game.map;
        line.setStroke(GRID_LINE_WIDTH, false);
        for (int[] corners : map.cellCorners) {
            for (int k = 0; k < corners.length; k++) {
                Vector2f a = map.cornerPoints.get(corners[k]);
                Vector2f b = map.cornerPoints.get(corners[(k + 1) % corners.length]);
                if (corners[k] < corners[(k + 1) % corners.length]) {
                    line.draw(screen(a.x, a.y), screen(b.x, b.y), Color.BROWN_GREY_VERY_LIGHT, mapCamera);
                }
            }
        }
        float moorRadius = map.cellSpacing * 0.5f * scale();
        for (int cell = 0; cell < map.cellX.length; cell++) {
            if (map.cellBlocked[cell]) {
                circle.draw(screen(map.cellX[cell], map.cellY[cell]), moorRadius, Color.BROWN_GREY_LIGHT, mapCamera);
            }
        }
        line.setStroke(RIVER_WIDTH, false, 1f, 0f, true, true, false);
        for (int k = 1; k < map.riverX.length; k++) {
            line.draw(screen(map.riverX[k - 1], map.riverY[k - 1]), screen(map.riverX[k], map.riverY[k]), Color.BLUE_GREY_VERY_LIGHT,
                    mapCamera);
        }
        for (int[] road : map.roads) {
            drawPath(road, 0, Color.BROWN_GREY_LIGHT, ROAD_WIDTH, false);
        }
        for (int[] bridge : map.bridges) {
            Vector2f mid = screen((map.cellX[bridge[0]] + map.cellX[bridge[1]]) * 0.5f,
                    (map.cellY[bridge[0]] + map.cellY[bridge[1]]) * 0.5f);
            box.draw(mid.x - BASE_HALF_SIZE, mid.y - BASE_HALF_SIZE * 0.5f, 2 * BASE_HALF_SIZE, BASE_HALF_SIZE, 2,
                    1, Color.BROWN, Color.BROWN_VERY_DARK, mapCamera);
        }
        for (int village : map.villageCells) {
            Vector2f p = screen(map.cellX[village], map.cellY[village]);
            box.draw(p.x - VILLAGE_HALF_SIZE, p.y - VILLAGE_HALF_SIZE, 2 * VILLAGE_HALF_SIZE, 2 * VILLAGE_HALF_SIZE, 1,
                    1, Color.BROWN_GREY_LIGHT, Color.BROWN_GREY, mapCamera);
        }

        if (mapMode == MAP_OWNER) {
            float tintRadius = map.cellSpacing * 0.6f * scale();
            for (int cell = 0; cell < map.cellX.length; cell++) {
                int market = nearestCityOfCell[cell];
                if (map.cellBlocked[cell]
                        || map.distance(cell, game.cityCell[market]) > TERRITORY_RANGE_IN_CELLS * map.cellSpacing) {
                    continue;
                }
                int youSold = game.lastSold[TradeGame.PLAYER][market];
                int rivalSold = game.lastSold[TradeGame.RIVAL][market];
                boolean yours = youSold > rivalSold || (youSold == rivalSold && game.base[TradeGame.PLAYER][market]);
                boolean theirs = rivalSold > youSold || (youSold == rivalSold && game.base[TradeGame.RIVAL][market]);
                if (yours || theirs) {
                    circle.draw(screen(map.cellX[cell], map.cellY[cell]), tintRadius,
                            (yours ? Color.IXDAR: Color.BLUE_GREY_DARK).withAlpha(TERRITORY_ALPHA), mapCamera);
                }
            }
        }

        boolean rivalRuns = game.level.rivalHome != TradeLevel.NO_RIVAL && !game.bankrupt[TradeGame.RIVAL];
        for (int pass = 0; pass < 2; pass++) {
            boolean rivalPass = (pass == 0) != rivalOnTop;
            float alpha = pass == 0 ? RIVAL_ALPHA : 1f;
            if (rivalPass && rivalRuns) {
                for (int[] order : game.level.rivalLoops) {
                    for (int k = 0; k < order.length; k++) {
                        int from = order[k], to = order[(k + 1) % order.length];
                        boolean seen = game.contested(from) || game.contested(to);
                        drawPath(game.legPath[from][to], LANE_OFFSET, Color.BLUE_GREY_DARK.withAlpha(seen ? alpha : FOG_ALPHA),
                                RIVAL_LANE_WIDTH, seen);
                    }
                }
            } else if (!rivalPass) {
                Color laneColor = Color.IXDAR.withAlpha(alpha);
                for (int[] order : game.loopCities) {
                    for (int k = 0; k < order.length; k++) {
                        int from = order[k], to = order[(k + 1) % order.length];
                        int[] path = game.legPath[from][to];
                        Vector2f mid = drawPath(path, LANE_OFFSET, laneColor, LANE_WIDTH, true);
                        int off = map.offRoadSteps(path);
                        drawTag(PLUS + game.legCost[from][to] + (off > 0 ? " (" + off + " off)" : ""), mid.x, mid.y,
                                laneColor);
                    }
                }
            }
        }
        routePlanningTool.drawOverlay(mapCamera, LANE_WIDTH);

        for (int city = 0; city < game.cityCount; city++) {
            Vector2f p = screen(map.cellX[game.cityCell[city]], map.cellY[game.cityCell[city]]);
            boolean playerBase = game.base[TradeGame.PLAYER][city];
            boolean rivalBase = game.base[TradeGame.RIVAL][city];
            if (mapMode == MAP_MARKET_SIZE && game.demand[city] > 0) {
                circle.draw(p, (float) Math.sqrt(game.demand[city]) * HEAT_SCALE, Color.ORANGE_YELLOW_DARK.withAlpha(TERRITORY_ALPHA),
                        mapCamera);
                drawText(Integer.toString(game.demand[city]), p.x, p.y - NAME_OFFSET * 2, TEXT_HEIGHT, Color.ORANGE_YELLOW_DARK);
            }
            if (playerBase || rivalBase) {
                box.draw(p.x - BASE_HALF_SIZE, p.y - BASE_HALF_SIZE, 2 * BASE_HALF_SIZE, 2 * BASE_HALF_SIZE, 2,
                        BORDER_WIDTH, playerBase ? Color.IXDAR: Color.BLUE_GREY_DARK, Color.BROWN_VERY_DARK, mapCamera);
            } else {
                boolean you = game.station[TradeGame.PLAYER][city];
                boolean rival = game.station[TradeGame.RIVAL][city];
                circle.borderThickness = CITY_BORDER_SHARE;
                circle.draw(p, CITY_RADIUS, you ? Color.IXDAR: rival ? Color.BLUE_GREY_DARK: Color.WHITE_YELLOW, you && rival ? Color.BLUE_GREY_DARK: Color.BROWN_VERY_DARK, mapCamera);
            }
            if (city == selectedCity) {
                circle.borderThickness = RING_BORDER_SHARE;
                circle.draw(p, TARGET_RING_RADIUS + 4, Color.TRANSPARENT, Color.BROWN_VERY_DARK, mapCamera);
            }
            drawText(game.level.cityNames[city], p.x, p.y + NAME_OFFSET, SMALL_TEXT_HEIGHT, Color.BROWN_VERY_DARK);
        }

        if (animationStart >= 0 && (Clock.time() - animationStart) / ANIMATION_SECONDS > 1f) {
            animationStart = -1;
        }
        if (animationStart >= 0) {
            float t = (Clock.time() - animationStart) / ANIMATION_SECONDS;
            for (int[] order : game.loopCities) {
                int legs = order.length;
                float along = t * legs;
                int leg = Math.min(legs - 1, (int) along);
                int[] path = game.legPath[order[leg]][order[(leg + 1) % legs]];
                float step = (along - leg) * (path.length - 1);
                int cell = Math.min(path.length - 2, (int) step);
                if (path.length >= 2) {
                    Vector2f from = screen(map.cellX[path[cell]], map.cellY[path[cell]]);
                    Vector2f to = screen(map.cellX[path[cell + 1]], map.cellY[path[cell + 1]]);
                    circle.borderThickness = CITY_BORDER_SHARE;
                    circle.draw(new Vector2f(from).lerp(to, step - cell), WAGON_RADIUS, Color.BROWN_VERY_DARK, Color.ORANGE_YELLOW_DARK, mapCamera);
                }
                for (int k = 0; k < legs; k++) {
                    int stop = order[k];
                    float reached = (float) k / legs;
                    if (t >= reached && game.lastRevenue[stop] > 0) {
                        Vector2f p = screen(map.cellX[game.cityCell[stop]], map.cellY[game.cityCell[stop]]);
                        drawText(PLUS + String.format(GOLD_FORMAT, game.lastRevenue[stop]) + " gold", p.x,
                                p.y + NAME_OFFSET * 2 + (t - reached) * GOLD_RISE_PIXELS * 2, TEXT_HEIGHT, Color.ORANGE_YELLOW_DARK);
                    }
                }
            }
        }

        float barTop = height - TOP_BAR_HEIGHT;
        box.draw(0, barTop, width, TOP_BAR_HEIGHT, 0, 0, Color.BROWN_VERY_DARK, Color.BROWN_VERY_DARK, mapCamera);
        panelRectangles.add(new float[] { 0, barTop, width, TOP_BAR_HEIGHT });
        float barMiddle = barTop + TOP_BAR_HEIGHT * 0.5f;
        drawText(String.format(GOLD_FORMAT, game.gold[TradeGame.PLAYER]) + " gold", 90, barMiddle,
                TITLE_TEXT_HEIGHT, Color.YELLOW_ORANGE_VERY_LIGHT);
        drawText((game.lastProfit[TradeGame.PLAYER] >= 0 ? PLUS : "")
                + String.format(GOLD_FORMAT, game.lastProfit[TradeGame.PLAYER]) + " / lap", 230, barMiddle,
                TEXT_HEIGHT, Color.WHITE_ORANGE);
        drawText("Lap " + Math.min(game.lap, game.level.lapLimit) + " / " + game.level.lapLimit, 360, barMiddle,
                TITLE_TEXT_HEIGHT, Color.WHITE_ORANGE);
        drawText(game.level.title, 560, barMiddle, TEXT_HEIGHT, Color.WHITE_ORANGE);
        float shareX = width - SHARE_BAR_WIDTH - PADDING * 2;
        float shareY = barTop + PADDING;
        float shareHeight = TOP_BAR_HEIGHT - 2 * PADDING;
        if (game.level.shareGoal > 0) {
            double youShare = game.playerShare();
            double rivalShare = game.rivalShare();
            box.draw(shareX, shareY, SHARE_BAR_WIDTH, shareHeight, 0, 0, Color.BROWN_GREY_DARK, Color.BROWN_GREY_DARK, mapCamera);
            box.draw(shareX, shareY, (float) (youShare * SHARE_BAR_WIDTH), shareHeight, 0, 0, Color.IXDAR, Color.IXDAR, mapCamera);
            box.draw(shareX + (float) (youShare * SHARE_BAR_WIDTH), shareY, (float) (rivalShare * SHARE_BAR_WIDTH),
                    shareHeight, 0, 0, Color.BLUE_GREY_DARK, Color.BLUE_GREY_DARK, mapCamera);
            float goalX = shareX + (float) game.level.shareGoal * SHARE_BAR_WIDTH;
            line.setStroke(2f, false);
            line.draw(new Vector2f(goalX, barTop + 2), new Vector2f(goalX, barTop + TOP_BAR_HEIGHT - 2), Color.YELLOW_ORANGE_VERY_LIGHT,
                    mapCamera);
            drawText("you " + Math.round(youShare * PERCENT) + "%  rival " + Math.round(rivalShare * PERCENT)
                    + "%  goal " + Math.round(game.level.shareGoal * PERCENT) + "%", shareX + SHARE_BAR_WIDTH * 0.5f,
                    barMiddle, SMALL_TEXT_HEIGHT, Color.WHITE_ORANGE);
        } else {
            double earned = game.gold[TradeGame.PLAYER] - game.level.startGold;
            StringBuilder medals = new StringBuilder("earned " + String.format(GOLD_FORMAT, earned) + "  medals");
            for (int threshold : game.level.medalGold) {
                medals.append(SPACE).append(threshold);
            }
            drawText(medals.toString(), shareX + SHARE_BAR_WIDTH * 0.5f, barMiddle, TEXT_HEIGHT, Color.YELLOW_ORANGE_VERY_LIGHT);
        }
        if (!status.isEmpty()) {
            float statusY = barTop - ROW_HEIGHT * 0.5f - PADDING;
            box.draw(PADDING, statusY - ROW_HEIGHT * 0.5f, width - PANEL_WIDTH - 3 * PADDING, ROW_HEIGHT,
                    CORNER_RADIUS, 0, Color.WHITE_YELLOW.withAlpha(0.9f), Color.WHITE_YELLOW, mapCamera);
            drawText(status, (width - PANEL_WIDTH) * 0.5f, statusY, SMALL_TEXT_HEIGHT, Color.BROWN_VERY_DARK);
        }

        float panelX = width - PANEL_WIDTH - PADDING;
        float panelTop = barTop - PADDING;
        float panelBottom = TOOLBAR_HEIGHT + PADDING;
        box.draw(panelX, panelBottom, PANEL_WIDTH, panelTop - panelBottom, CORNER_RADIUS, BORDER_WIDTH, Color.WHITE_YELLOW, Color.BROWN_VERY_DARK,
                mapCamera);
        panelRectangles.add(new float[] { panelX, panelBottom, PANEL_WIDTH, panelTop - panelBottom });
        float centerX = panelX + PANEL_WIDTH * 0.5f;
        float buttonLeft = panelX + PADDING;
        float buttonWidth = PANEL_WIDTH - 2 * PADDING;
        float row = panelTop - ROW_HEIGHT;
        if (selectedCity >= 0) {
            int city = selectedCity;
            drawText(game.level.cityNames[city], centerX, row, TITLE_TEXT_HEIGHT, Color.BROWN_VERY_DARK);
            row -= ROW_HEIGHT * 1.2f;
            drawText("needs " + game.demand[city] + " / lap" + SEPARATOR + "pays up to "
                    + String.format(COST_FORMAT, game.ceiling[city]), centerX, row, SMALL_TEXT_HEIGHT, Color.BROWN_GREY);
            row -= ROW_HEIGHT;
            if (game.station[TradeGame.PLAYER][city]) {
                drawText("you sold " + game.lastSold[TradeGame.PLAYER][city] + " at "
                        + String.format(COST_FORMAT, game.lastPrice[city]) + SEPARATOR + "cost "
                        + String.format(COST_FORMAT, game.unitCost[TradeGame.PLAYER][city]), centerX, row,
                        SMALL_TEXT_HEIGHT, Color.IXDAR);
                row -= ROW_HEIGHT;
            }
            if (game.contested(city)) {
                double rivalGold = game.gold[TradeGame.RIVAL];
                String reserve = rivalGold > game.level.rivalStartGold * 0.5 ? "healthy"
                        : rivalGold > 0 ? "strained" : "distressed";
                drawText("rival sold " + game.lastSold[TradeGame.RIVAL][city] + SEPARATOR + "cost "
                        + String.format(COST_FORMAT, game.unitCost[TradeGame.RIVAL][city]) + SEPARATOR + reserve,
                        centerX, row, SMALL_TEXT_HEIGHT, Color.BLUE_GREY_DARK);
                row -= ROW_HEIGHT;
                int medal = game.marketMedal(city);
                drawText("market: " + (medal == 0 ? "no medal" : MEDAL_NAMES[medal]), centerX, row,
                        SMALL_TEXT_HEIGHT, Color.ORANGE_YELLOW_DARK);
                row -= ROW_HEIGHT * 1.2f;
                String[] wars = { "Price war: off", "Price war: at cost", "Price war: half cost" };
                button(buttonLeft, row - ROW_HEIGHT * 0.5f, buttonWidth, ROW_HEIGHT, wars[game.priceWar[city]],
                        game.priceWar[city] == TradeGame.WAR_OFF ? Color.WHITE_YELLOW: Color.YELLOW_ORANGE_VERY_LIGHT, Color.BROWN_VERY_DARK,
                        () -> game.cyclePriceWar(city));
                row -= ROW_HEIGHT * 1.3f;
            }
            if (!game.station[TradeGame.PLAYER][city] && !game.base[TradeGame.PLAYER][city]) {
                button(buttonLeft, row - ROW_HEIGHT * 0.5f, buttonWidth, ROW_HEIGHT,
                        "Buy station (" + TradeGame.STATION_PRICE + " gold)", Color.YELLOW_ORANGE_VERY_LIGHT, Color.BROWN_VERY_DARK, () -> {
                            if (!game.buyStation(city)) {
                                status = "Not enough gold for a station";
                            }
                        });
                row -= ROW_HEIGHT * 1.3f;
            }
            String lock = game.baseLockReason(city);
            if (!game.base[TradeGame.PLAYER][city]) {
                button(buttonLeft, row - ROW_HEIGHT * 0.5f, buttonWidth, ROW_HEIGHT,
                        lock.isEmpty() ? "Build base (" + TradeGame.BASE_PRICE + " gold)" : "Base locked: " + lock,
                        lock.isEmpty() ? Color.YELLOW_ORANGE_VERY_LIGHT: Color.WHITE_ORANGE, lock.isEmpty() ? Color.BROWN_VERY_DARK: Color.BROWN_GREY, () -> game.buildBase(city));
                row -= ROW_HEIGHT * 1.3f;
            }
            if (game.loopIndexOf(city) >= 0) {
                button(buttonLeft, row - ROW_HEIGHT * 0.5f, buttonWidth, ROW_HEIGHT,
                        "Buy wagon for this loop (" + TradeGame.WAGON_PRICE + " gold)", Color.WHITE_ORANGE, Color.BROWN_VERY_DARK, () -> {
                            if (!game.buyWagon(city)) {
                                status = "Not enough gold for a wagon";
                            }
                        });
                row -= ROW_HEIGHT * 1.3f;
            }
            row -= ROW_HEIGHT * 0.5f;
        } else {
            drawText("Click a city to open its menu", centerX, row, SMALL_TEXT_HEIGHT, Color.BROWN_GREY);
            row -= ROW_HEIGHT * 2;
        }
        drawText("Loops", centerX, row, TEXT_HEIGHT, Color.BROWN_VERY_DARK);
        row -= ROW_HEIGHT;
        if (game.loopCities.isEmpty()) {
            drawText("Pipe from home to a town to start one", centerX, row, SMALL_TEXT_HEIGHT, Color.BROWN_GREY);
        }
        for (int loop = 0; loop < game.loopCities.size() && row > panelBottom + ROW_HEIGHT * 2; loop++) {
            StringBuilder name = new StringBuilder();
            for (int city : game.loopCities.get(loop)) {
                name.append(name.length() == 0 ? "" : "-").append(game.level.cityNames[city].charAt(0));
            }
            double cost = game.loopUnitCost.get(loop);
            drawText(name + SEPARATOR + game.loopWagons.get(loop) + " wagon" + SEPARATOR
                    + (Double.isInfinite(cost) ? "idle" : String.format(COST_FORMAT, cost) + "/unit"), centerX, row,
                    SMALL_TEXT_HEIGHT, Color.IXDAR);
            row -= ROW_HEIGHT * 0.8f;
            int burn = game.loopBurn.get(loop) * game.loopWagons.get(loop);
            int cargo = game.loopCargo.get(loop);
            int capacity = game.level.wagonCapacity * game.loopWagons.get(loop);
            float fillWidth = PANEL_WIDTH - 4 * PADDING;
            float fillX = panelX + 2 * PADDING;
            float perUnit = fillWidth / Math.max(capacity, burn + cargo);
            box.draw(fillX, row - FILL_BAR_HEIGHT * 0.5f, fillWidth, FILL_BAR_HEIGHT, 0, 1, Color.WHITE_YELLOW, Color.BROWN_VERY_DARK, mapCamera);
            box.draw(fillX, row - FILL_BAR_HEIGHT * 0.5f, burn * perUnit, FILL_BAR_HEIGHT, 0, 0,
                    burn > capacity ? Color.RED : Color.IXDAR, Color.IXDAR, mapCamera);
            box.draw(fillX + burn * perUnit, row - FILL_BAR_HEIGHT * 0.5f, cargo * perUnit, FILL_BAR_HEIGHT, 0, 0,
                    Color.PINK_VERY_LIGHT, Color.PINK_VERY_LIGHT, mapCamera);
            row -= ROW_HEIGHT * 0.8f;
            String detail = !game.loopHasBase.get(loop) ? "no base: this loop cannot run"
                    : game.loopFuelDeficit.get(loop) > 0 ? "runs dry by " + game.loopFuelDeficit.get(loop) + " blood"
                            : burn + " burned" + SEPARATOR + cargo + " sold" + SEPARATOR
                                    + Math.max(0, capacity - burn - cargo) + " spare";
            drawText(detail, centerX, row, SMALL_TEXT_HEIGHT, game.loopFuelDeficit.get(loop) > 0 ? Color.RED : Color.BROWN_GREY);
            row -= ROW_HEIGHT;
        }

        box.draw(0, 0, width, TOOLBAR_HEIGHT, 0, 0, Color.BROWN_VERY_DARK, Color.BROWN_VERY_DARK, mapCamera);
        panelRectangles.add(new float[] { 0, 0, width, TOOLBAR_HEIGHT });
        float toolY = PADDING;
        float toolHeight = TOOLBAR_HEIGHT - 2 * PADDING;
        float toolX = PADDING;
        String[] toolNames = { "P Pipe", "G Grow", "C Collapse" };
        for (int index = 0; index < toolNames.length; index++) {
            int chosen = index;
            button(toolX, toolY, BUTTON_WIDTH, toolHeight, toolNames[index],
                    routePlanningTool.mode == index ? Color.IXDAR: Color.BROWN_GREY_DARK, Color.WHITE_ORANGE,
                    () -> routePlanningTool.chooseMode(chosen));
            toolX += BUTTON_WIDTH + PADDING;
        }
        button(toolX, toolY, BUTTON_WIDTH * 0.6f, toolHeight, "Undo", Color.BROWN_GREY_DARK, Color.WHITE_ORANGE,
                routePlanningTool::undo);
        toolX += BUTTON_WIDTH * 0.6f + PADDING * 3;
        String[] modeNames = { "Network", "Owner", "Market size" };
        for (int index = 0; index < modeNames.length; index++) {
            int chosen = index;
            button(toolX, toolY, BUTTON_WIDTH, toolHeight, modeNames[index], mapMode == index ? Color.IXDAR: Color.BROWN_GREY_DARK,
                    Color.WHITE_ORANGE, () -> mapMode = chosen);
            toolX += BUTTON_WIDTH + PADDING;
        }
        button(toolX, toolY, WIDE_BUTTON_WIDTH, toolHeight, rivalOnTop ? "On top: rival" : "On top: you",
                Color.BROWN_GREY_DARK, Color.WHITE_ORANGE, () -> rivalOnTop = !rivalOnTop);
        toolX += WIDE_BUTTON_WIDTH + PADDING;
        button(toolX, toolY, BUTTON_WIDTH, toolHeight, "Restart", Color.BROWN_GREY_DARK, Color.WHITE_ORANGE,
                () -> loadLevel(levelIndex));
        button(width - END_LAP_WIDTH - PADDING, toolY, END_LAP_WIDTH, toolHeight, "End lap", Color.ORANGE_YELLOW_DARK, Color.WHITE_ORANGE,
                this::endLap);

        if (game.outcome != TradeGame.PLAYING) {
            float boxX = (width - PANEL_WIDTH - OUTCOME_BOX_WIDTH) * 0.5f;
            float boxY = (height - OUTCOME_BOX_HEIGHT) * 0.5f;
            box.draw(boxX, boxY, OUTCOME_BOX_WIDTH, OUTCOME_BOX_HEIGHT, CORNER_RADIUS * 2, BORDER_WIDTH * 2, Color.WHITE_YELLOW,
                    Color.BROWN_VERY_DARK, mapCamera);
            panelRectangles.add(new float[] { boxX, boxY, OUTCOME_BOX_WIDTH, OUTCOME_BOX_HEIGHT });
            boolean won = game.outcome == TradeGame.WON;
            String headline = won ? "Level won" : "Level lost";
            if (won && game.medal > 0) {
                headline += SEPARATOR + MEDAL_NAMES[game.medal] + " medal";
            }
            float boxCenterX = boxX + OUTCOME_BOX_WIDTH * 0.5f;
            drawText(headline, boxCenterX, boxY + OUTCOME_BOX_HEIGHT - ROW_HEIGHT * 1.5f, TITLE_TEXT_HEIGHT,
                    won ? Color.GREEN_DARK: Color.IXDAR);
            drawText(game.outcomeText, boxCenterX, boxY + OUTCOME_BOX_HEIGHT - ROW_HEIGHT * 3, TEXT_HEIGHT, Color.BROWN_VERY_DARK);
            button(boxX + PADDING * 2, boxY + PADDING * 2, WIDE_BUTTON_WIDTH, ROW_HEIGHT * 1.5f, "Retry", Color.WHITE_ORANGE, Color.BROWN_VERY_DARK,
                    () -> loadLevel(levelIndex));
            if (won && levelIndex + 1 < levels.size()) {
                button(boxX + OUTCOME_BOX_WIDTH - WIDE_BUTTON_WIDTH - PADDING * 2, boxY + PADDING * 2,
                        WIDE_BUTTON_WIDTH, ROW_HEIGHT * 1.5f, "Next level", Color.ORANGE_YELLOW_DARK, Color.WHITE_ORANGE,
                        () -> loadLevel(levelIndex + 1));
            }
        }
        drawTooltip(mapCamera);
        camera2D.setZIndex(mapCamera);
    }

    /**
     * Draw a run of cells as a lane: quadratic Beziers through the cell centres, off-road steps dashed,
     * shifted left of travel, with arrow chevrons on the middle step.
     *
     * @param cells  the cells in travel order
     * @param offset sideways shift in pixels, left of travel
     * @param color  lane colour
     * @param width  lane width in pixels
     * @param arrows whether to mark the direction of travel
     * @return the label point beside the middle of the lane
     */
    public Vector2f drawPath(int[] cells, float offset, Color color, float width, boolean arrows) {
        int count = cells.length;
        Vector2f[] points = new Vector2f[count];
        for (int k = 0; k < count; k++) {
            points[k] = screen(game.map.cellX[cells[k]], game.map.cellY[cells[k]]);
        }
        Vector2f[] shifted = new Vector2f[count];
        for (int k = 0; k < count; k++) {
            Vector2f before = points[Math.max(k - 1, 0)];
            Vector2f after = points[Math.min(k + 1, count - 1)];
            float dx = after.x - before.x, dy = after.y - before.y;
            float length = (float) Math.hypot(dx, dy);
            shifted[k] = length == 0 ? new Vector2f(points[k])
                    : new Vector2f(points[k].x - dy / length * offset, points[k].y + dx / length * offset);
        }
        if (count < 2) {
            return count == 1 ? shifted[0] : new Vector2f();
        }
        bezier.lineWidth = width;
        for (int k = 0; k < count - 1; k++) {
            Vector2f a = shifted[k], b = shifted[k + 1];
            Vector2f midAB = new Vector2f(a).add(b).mul(0.5f);
            if (!game.map.isRoad(cells[k], cells[k + 1]) && arrows) {
                line.setStroke(width, true, DASH_PIXELS, 0f, true, false, false);
                line.draw(a, b, color, mapCamera);
                continue;
            }
            line.setStroke(width, false, 1f, 0f, true, true, false);
            if (k == 0) {
                line.draw(a, midAB, color, mapCamera);
            }
            if (k == count - 2) {
                line.draw(midAB, b, color, mapCamera);
            } else {
                Vector2f c = shifted[k + 2];
                Vector2f midBC = new Vector2f(b).add(c).mul(0.5f);
                if (!game.map.isRoad(cells[k + 1], cells[k + 2]) && arrows) {
                    line.draw(midAB, b, color, mapCamera);
                } else {
                    bezier.draw(midAB, b, midBC, color, color, mapCamera);
                }
            }
        }
        int middle = (count - 1) / 2;
        Vector2f a = shifted[middle], b = shifted[Math.min(middle + 1, count - 1)];
        if (arrows) {
            line.setStroke(width * 2, false, 1f, 1f, true, false, true);
            line.draw(a, b, color, mapCamera);
        }
        float dx = b.x - a.x, dy = b.y - a.y;
        float length = Math.max(1e-3f, (float) Math.hypot(dx, dy));
        Vector2f center = new Vector2f(a).add(b).mul(0.5f);
        return new Vector2f(center.x - dy / length * LABEL_OFFSET, center.y + dx / length * LABEL_OFFSET);
    }

    /**
     * Draw a clickable HUD button and record it for {@link #viewClicked}; its label is clickable text so
     * automation can find it by name.
     *
     * @param x      left edge in pixels
     * @param y      bottom edge in pixels
     * @param w      width in pixels
     * @param h      height in pixels
     * @param label  the button's text
     * @param fill   its colour
     * @param text   its text colour
     * @param action what a click does
     */
    private void button(float x, float y, float w, float h, String label, Color fill, Color text, Runnable action) {
        box.draw(x, y, w, h, CORNER_RADIUS, 1, fill, Color.BROWN_VERY_DARK, mapCamera);
        HyperString words = new HyperString();
        words.addWordClick(label, text, () -> {
        });
        Drawing.getDrawing().font.drawHyperString(words, x + w * 0.5f, y + h * 0.5f, TEXT_HEIGHT, mapCamera);
        buttonRectangles.add(new float[] { x, y, w, h });
        buttonActions.add(action);
    }

    /**
     * Draw a leg's cell-count tag: the label on a small card.
     *
     * @param label the tag's text
     * @param x     centre x in pixels
     * @param y     centre y in pixels
     * @param color text and border colour
     */
    public void drawTag(String label, float x, float y, Color color) {
        float w = label.length() * SMALL_TEXT_HEIGHT * 0.5f + PADDING;
        box.draw(x - w * 0.5f, y - SMALL_TEXT_HEIGHT * 0.5f, w, SMALL_TEXT_HEIGHT, SMALL_TEXT_HEIGHT * 0.5f, 1, Color.WHITE_YELLOW,
                color, mapCamera);
        drawText(label, x, y, SMALL_TEXT_HEIGHT, color);
    }

    /**
     * Draw a line of text centred on a pixel.
     *
     * @param text   the text
     * @param x      centre x in pixels
     * @param y      centre y in pixels
     * @param height row height in pixels
     * @param color  text colour
     */
    public void drawText(String text, float x, float y, float height, Color color) {
        HyperString words = new HyperString();
        words.addWord(text, color);
        Drawing.getDrawing().font.drawHyperString(words, x, y, height, mapCamera);
    }

    /**
     * A map point in screen pixels.
     *
     * @param x map x
     * @param y map y
     * @return the pixel
     */
    public Vector2f screen(float x, float y) {
        return new Vector2f(mapCamera.pointTransformX(x), mapCamera.pointTransformY(y));
    }

    /**
     * Pixels per map unit at the current zoom.
     *
     * @return the scale
     */
    public float scale() {
        return Math.abs(mapCamera.pointTransformX(1f) - mapCamera.pointTransformX(0f));
    }
}
