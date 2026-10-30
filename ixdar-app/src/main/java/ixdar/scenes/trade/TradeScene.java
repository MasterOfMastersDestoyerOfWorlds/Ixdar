package ixdar.scenes.trade;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector2f;

import ixdar.annotations.command.Subsystem;
import ixdar.annotations.scene.SceneAnnotation;
import ixdar.game.trade.TradeGame;
import ixdar.game.trade.economy.Outcome;
import ixdar.game.trade.level.TradeLevel;
import ixdar.game.trade.level.TradeLevelFile;
import ixdar.game.trade.map.City;
import ixdar.game.trade.map.Place;
import ixdar.geometry.tsp.points.PointND;
import ixdar.geometry.tsp.points.PointSet;
import ixdar.geometry.tsp.shell.Shell;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Palettes;
import ixdar.graphics.render.sdf.SDFRoundedRect;
import ixdar.gui.ui.ErrorToasts;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.input.CanvasScroll;
import ixdar.input.PointerDispatcher;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.Scene;
import ixdar.scenes.trade.hud.Hud;
import ixdar.scenes.trade.hud.LoopListPanel;
import ixdar.scenes.trade.hud.OutcomeDialog;
import ixdar.scenes.trade.hud.PlacePanel;
import ixdar.scenes.trade.hud.Toolbar;
import ixdar.scenes.trade.hud.TopBar;
import ixdar.scenes.trade.render.GridRenderer;
import ixdar.scenes.trade.render.LapAnimation;
import ixdar.scenes.trade.render.PathRenderer;
import ixdar.tools.ControlHint;
import ixdar.tools.trade.CollapseTool;
import ixdar.tools.trade.GrowTool;
import ixdar.tools.trade.PipeTool;
import ixdar.tools.trade.TradeRouteTool;

/**
 * The trade game: a level's map on the irregular grid, the companies' loops as lanes, and separate HUD
 * panels (top bar, place panel, loop list, toolbar menus) that run the economy. Pipe, Grow and Collapse
 * are tools; {@code [} and {@code ]} step the knot layer they act at.
 */
@SceneAnnotation(id = "trade-game")
public class TradeScene extends Scene {
    public static final float CAMERA_ZOOM_DEFAULT = 1f;
    public static final String VIEW_MAIN = "MAIN";
    public static final float MAP_MARGIN = 8f;
    public static final float PLACE_CLICK_RADIUS_IN_CELLS = 0.5f;
    public static final int SIDE_PANEL_WIDTH = 320;
    public static final float SIDE_PANEL_MAX_SHARE = 0.42f;

    /** The camera over the map and, in its pixel space, the HUD. */
    public Camera2D mapCamera;
    /** The scene Escape returns to, or null when the game was opened directly. */
    public Scene backScene;
    public int levelIndex;
    public TradeGame game;
    /** The place whose panel is open, or null when none is. */
    public Place selectedPlace;
    public Place hoveredPlace;
    /** The knot layer the route tools act at: 0 for whole networks. */
    public int layer;
    public MapMode mapMode = MapMode.NETWORK;
    public boolean rivalOnTop;

    public final Hud hud = new Hud();
    /** Why an action was refused, shown below the top bar and printed to the terminal and log. */
    public final ErrorToasts errorToasts = new ErrorToasts(this);
    public final Toolbar toolbar = new Toolbar();
    public PipeTool pipeTool;
    public GrowTool growTool;
    public CollapseTool collapseTool;
    public final TopBar topBar = new TopBar();
    public final LapAnimation lapAnimation = new LapAnimation();
    public GridRenderer gridRenderer;
    public final PathRenderer pathRenderer = new PathRenderer();

    private final PlacePanel placePanel = new PlacePanel();
    private final LoopListPanel loopListPanel = new LoopListPanel();
    private final OutcomeDialog outcomeDialog = new OutcomeDialog();
    private final SDFRoundedRect paper = new SDFRoundedRect();
    private PointSet mapPoints;
    private int framedWidth = -1;
    private int framedHeight = -1;

    /**
     * Open the campaign at its first level, as the scene registry and the launch entries do.
     */
    public TradeScene() {
        this(0);
    }

    /**
     * Open the campaign at a level; the level is built once the window's GL is up.
     *
     * @param levelIndex the level's index in {@link TradeLevel#CAMPAIGN}
     */
    protected TradeScene(int levelIndex) {
        this.levelIndex = levelIndex;
        palette = Palettes.PAPER;
    }

    /**
     * Build the camera, the input handlers and the level.
     */
    @Override
    public void initGL() {
        super.initGL();
        MenuBox.menuVisible = false;
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        mapPoints = new Shell().toPointSet();
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
        loadLevel(levelIndex);
    }

    /**
     * Start a level from its file: read synchronously where the platform can, else when the file
     * arrives.
     *
     * @param index the level's index in {@link TradeLevel#CAMPAIGN}
     */
    public void loadLevel(int index) {
        levelIndex = index;
        String name = TradeLevel.CAMPAIGN[index];
        String text = Platforms.get().trySyncLoadSource(TradeLevelFile.RESOURCE_FOLDER, name);
        if (text != null) {
            start(TradeLevelFile.read(text));
        } else {
            Platforms.get().loadSourceAsync(TradeLevelFile.RESOURCE_FOLDER, name, Platforms.gl().getPlatformID(),
                    loaded -> start(TradeLevelFile.read(loaded)));
        }
    }

    /**
     * Start a parsed level: build the game, frame the whole map between the HUD bars, and reset the
     * selection, layer, menus and tools.
     *
     * @param level the level
     */
    private void start(TradeLevel level) {
        game = new TradeGame(level);
        gridRenderer = new GridRenderer(game.map);
        selectedPlace = null;
        hoveredPlace = null;
        layer = 0;
        toolbar.open = null;
        lapAnimation.stop();
        if (activeTool instanceof TradeRouteTool routeTool) {
            routeTool.activate();
        }
        framedWidth = -1;
    }


    /**
     * Take or release input: on activation bind the game's handlers, pause the menu music and hide the
     * menu.
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

    /** Host the three route tools on P, G and C; Pipe is where the game opens. */
    @Override
    public void setTools() {
        super.setTools();
        pipeTool = new PipeTool(this);
        growTool = new GrowTool(this);
        collapseTool = new CollapseTool(this);
        addToolSwitch(pipeTool, new ControlHint("pipe-tool", Keys.P, "P", "join two places or two loops",
                () -> switchTool(pipeTool)));
        addToolSwitch(growTool, new ControlHint("grow-tool", Keys.G, "G", "add a place to a loop",
                () -> switchTool(growTool)));
        addToolSwitch(collapseTool, new ControlHint("collapse-tool", Keys.C, "C",
                "join the ends of a chain of knots", () -> switchTool(collapseTool)));
        defaultTool = pipeTool;
    }

    /**
     * The game shows a trade map on an irregular grid.
     *
     * @param subsystem a scene-held subsystem
     * @return the game for {@link Subsystem#TRADE}, its grid for {@link Subsystem#GRID}, else the base's
     */
    @Override
    public Object dataset(Subsystem subsystem) {
        if (subsystem == Subsystem.TRADE) {
            return game;
        }
        if (subsystem == Subsystem.GRID) {
            return game == null ? null : game.map.grid;
        }
        return super.dataset(subsystem);
    }

    /**
     * The game's own keys after the tool's: E ends the lap, M steps the map mode, R restarts the level,
     * and Esc closes the city panel or returns to the menu.
     *
     * @param controls the scene's control rows
     */
    @Override
    public void setControls(List<ControlHint> controls) {
        controls.add(new ControlHint("end-lap", Keys.E, "E", "end the lap", this::endLap));
        controls.add(new ControlHint("map-mode", Keys.M, "M", "step the map mode", () -> mapMode = mapMode.next()));
        controls.add(new ControlHint("restart-level", Keys.R, "R", "restart the level", () -> loadLevel(levelIndex)));
        controls.add(new ControlHint("escape", Keys.ESCAPE, "esc", "close a menu or the place panel, else back to the menu",
                () -> {
                    if (!activeTool.escapePressed() && selectedPlace == null && toolbar.open == null
                            && backScene != null) {
                        switchTo(backScene);
                    }
                    selectedPlace = null;
                    toolbar.open = null;
                }));
    }

    /**
     * Refuse an action as every scene does, through {@link #errorToasts} under the active tool's name.
     *
     * @param reason why, in a few words
     */
    public void refuse(String reason) {
        errorToasts.show(activeTool.toolName(), reason);
    }

    /**
     * Run the lap and start the wagons and the gold flying home.
     */
    public void endLap() {
        if (game == null || game.outcome != Outcome.PLAYING) {
            return;
        }
        double goldBefore = game.player.gold;
        game.endLap();
        activeTool.activate();
        lapAnimation.start(this, goldBefore);
    }

    /**
     * The cursor hovers the place under it, then the tool hovers what it shows.
     *
     * @param windowX cursor x in window pixels
     * @param windowY cursor y in window pixels, top-down
     */
    @Override
    public void viewHovered(float windowX, float windowY) {
        if (game == null) {
            return;
        }
        hoveredPlace = placeAt(mouse.normalizedPosX, mouse.normalizedPosY);
        activeTool.onHover(mouse.normalizedPosX, mouse.normalizedPosY);
    }

    /**
     * A click on the HUD stays there, a click off an open toolbar menu shuts it, and any other click on
     * the map goes to the active route tool.
     *
     * @param windowX click x in window pixels
     * @param windowY click y in window pixels, top-down
     * @param button  the mouse button
     */
    @Override
    public void viewClicked(float windowX, float windowY, int button) {
        if (hud.click(mouse.normalizedPosX, mouse.normalizedPosY)) {
            return;
        }
        if (toolbar.open != null) {
            toolbar.open = null;
            return;
        }
        if (game != null && game.outcome == Outcome.PLAYING) {
            super.viewClicked(windowX, windowY, button);
        }
    }

    /**
     * Frame the map again in the space the HUD leaves, from the next frame.
     *
     * @return true
     */
    @Override
    public boolean frameAll() {
        framedWidth = -1;
        return true;
    }

    /**
     * The place under a screen pixel.
     *
     * @param x pixel x
     * @param y pixel y, y up
     * @return the nearest place within half a cell, or null
     */
    public Place placeAt(float x, float y) {
        Place best = null;
        float bestDistance = game.map.grid.cellSpacing() * PLACE_CLICK_RADIUS_IN_CELLS * scale();
        for (Place place : game.map.places) {
            float d = placePixel(place).distance(x, y);
            if (d < bestDistance) {
                bestDistance = d;
                best = place;
            }
        }
        return best;
    }

    /**
     * Start a new trade game from the campaign's first level, replacing {@code canvas} from the next
     * frame on.
     *
     * @param canvas the scene the game replaces, which Escape returns to
     * @return the created scene
     */
    public static TradeScene startNewGame(Scene canvas) {
        TradeScene scene = new TradeScene();
        scene.backScene = canvas;
        canvas.switchTo(scene);
        return scene;
    }

    /**
     * Draw one frame back to front: paper, grid and cell tints, terrain, both companies' lanes, the tool's
     * overlay, the places, the lap animation and the place names, then the HUD panels and the gold flying into the counter.
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
        hud.begin(mapCamera, palette, mouse.normalizedPosX, mouse.normalizedPosY);
        paper.draw(0, 0, width, height, 0, 0, palette.background, palette.background, mapCamera);

        gridRenderer.draw(this);
        pathRenderer.drawTerrain(this);
        pathRenderer.drawLoops(this);
        activeTool.drawOverlay(mapCamera, PathRenderer.LANE_WIDTH);
        pathRenderer.drawPlaces(this);
        lapAnimation.drawMap(this);
        pathRenderer.drawPlaceNames(this);

        float panelWidth = Math.min(SIDE_PANEL_WIDTH, width * SIDE_PANEL_MAX_SHARE);
        float toolbarHeight = Toolbar.HEIGHT;
        float topBarBottom = topBar.draw(this, width, height);
        float panelX = width - panelWidth - Hud.PADDING;
        float panelBottom = loopListPanel.draw(this, panelX, topBarBottom - Hud.PADDING, toolbarHeight + Hud.PADDING,
                panelWidth);
        if (width != framedWidth || height != framedHeight) {
            // Fit the whole map between the top bar and the toolbar, clear of the side panel: beside it or
            // below it, whichever shows the map larger. The camera spans a square range: measure the pixels a
            // range covers, size the range to fit, then pan onto the free space's centre.
            framedWidth = width;
            framedHeight = height;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (Vector2f corner : game.map.grid.anchorPoints()) {
                minX = Math.min(minX, corner.x);
                minY = Math.min(minY, corner.y);
                maxX = Math.max(maxX, corner.x);
                maxY = Math.max(maxY, corner.y);
            }
            float spanX = maxX - minX + 2 * MAP_MARGIN;
            float spanY = maxY - minY + 2 * MAP_MARGIN;
            float freeLeft = Hud.PADDING, freeBottom = toolbarHeight + Hud.PADDING;
            float besideFit = Math.min((panelX - Hud.PADDING - freeLeft) / spanX,
                    (topBarBottom - Hud.PADDING - freeBottom) / spanY);
            float belowFit = Math.min((width - Hud.PADDING - freeLeft) / spanX,
                    (panelBottom - Hud.PADDING - freeBottom) / spanY);
            float freeRight = besideFit >= belowFit ? panelX - Hud.PADDING : width - Hud.PADDING;
            float freeTop = besideFit >= belowFit ? topBarBottom - Hud.PADDING : panelBottom - Hud.PADDING;
            float fit = Math.max(besideFit, belowFit);
            float pixelsPerRange = 0;
            for (float range : new float[] { Math.max(spanX, spanY), 0 }) {
                float span = range > 0 ? range : pixelsPerRange / fit;
                Shell corners = new Shell();
                // PointND drops trailing zero coordinates, and the map's corner sits at 0: keep it off zero.
                corners.add(new PointND.Float(minX - MAP_MARGIN, minY - MAP_MARGIN));
                corners.add(new PointND.Float(minX - MAP_MARGIN + span, minY - MAP_MARGIN + span));
                mapPoints = corners.toPointSet();
                mapCamera.calculateCameraTransform(mapPoints);
                mapCamera.reset();
                mapCamera.calculateCameraTransform(mapPoints);
                pixelsPerRange = scale() * span;
            }
            Vector2f centre = screen(new Vector2f((minX + maxX) * 0.5f, (minY + maxY) * 0.5f));
            mapCamera.PanX += (freeLeft + freeRight) * 0.5f - centre.x;
            mapCamera.PanY += (freeBottom + freeTop) * 0.5f - centre.y;
            mapCamera.calculateCameraTransform(mapPoints);
        }
        if (selectedPlace != null) {
            placePanel.draw(this, Hud.PADDING, topBarBottom - Hud.PADDING,
                    Math.min(panelWidth, panelX - 2 * Hud.PADDING));
        }
        if (game.outcome != Outcome.PLAYING) {
            outcomeDialog.draw(this, width - panelWidth, height);
        }
        toolbar.draw(this, width);
        errorToasts.perFrame();
        errorToasts.draw(mapCamera, height - topBarBottom);
        lapAnimation.drawCoins(this);
        drawTooltip(mapCamera);
        camera2D.setZIndex(mapCamera);
    }

    /**
     * A map point in screen pixels.
     *
     * @param point a map point
     * @return the pixel
     */
    public Vector2f screen(Vector2f point) {
        return new Vector2f(mapCamera.pointTransformX(point.x), mapCamera.pointTransformY(point.y));
    }

    /**
     * Where a place draws on screen.
     *
     * @param place a place
     * @return its pixel
     */
    public Vector2f placePixel(Place place) {
        return screen(game.map.grid.dualPoints().get(place.cell));
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
