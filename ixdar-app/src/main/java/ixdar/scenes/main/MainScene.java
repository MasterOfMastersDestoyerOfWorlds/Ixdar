package ixdar.scenes.main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

import org.apache.commons.math3.util.Pair;

import ixdar.scenes.Scene;
import ixdar.geometry.tsp.MultipleCyclesFoundException;
import ixdar.geometry.tsp.SegmentBalanceException;
import ixdar.gui.terminal.TerminalParseException;
import ixdar.platform.Compat;
import ixdar.geometry.tsp.knot.Knot;
import ixdar.geometry.tsp.knot.Segment;
import ixdar.geometry.grid2d.Grid;
import ixdar.geometry.tsp.points.PointND;
import ixdar.geometry.tsp.shell.DistanceMatrix;
import ixdar.geometry.tsp.shell.Shell;
import ixdar.geometry.tsp.shell.ShellComparator;
import ixdar.geometry.tsp.shell.ShellPair;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorLerp;
import ixdar.graphics.render.sdf.SDFTexture;
import ixdar.graphics.render.text.Font;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.InfoPane;
import ixdar.gui.ui.PaneTypes;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.platform.Platforms;
import ixdar.platform.file.FileManagement;
import ixdar.geometry.tsp.PointSetPath;
import ixdar.platform.file.TextFile;
import ixdar.platform.input.PointerDispatcher;
import ixdar.tools.knot.FreeTool;
import ixdar.tools.knot.Tool;

/**
 * The 2D TSP editor: a tour file's points, the knots solved from them, the active knot tool, the
 * info pane and the terminal. Launched by id ({@code ixdar-canvas}) or swapped in by {@link #open}.
 */
public class MainScene extends Scene {
    public static final String KNOT_FINDING_TIME = "Knot-finding time: ";
    public static final String PACKAGE_SEPARATOR = ".";
    public static final float CAMERA_ZOOM_DEFAULT = 0.9f;
    public static final float LOGO_SCALE = 0.6f;
    public static final int MAX_SOLVE_ATTEMPTS = 40;
    public static final double MILLIS_PER_SECOND = 1000.0;
    public static final float HOVER_SEGMENT_LERP_SPEED = 4f;
    public static final float HOVER_KNOT_LERP_SPEED = 2f;

    public static final String VIEW_MAIN = "MAIN";
    public static final String VIEW_RIGHT_TOP = "RIGHT_TOP";
    public static final String VIEW_RIGHT_BOTTOM = "RIGHT_BOTTOM";
    public static final String VIEW_BOTTOM = "BOTTOM";
    public static final int RIGHT_PANEL_SIZE = 195;
    public static final int BOTTOM_PANEL_SIZE = 195;
    public static final int MAIN_VIEW_OFFSET_X = 0;
    public static final int MAIN_VIEW_OFFSET_Y = BOTTOM_PANEL_SIZE;

    public TextFile file;
    public TextFile tempFile;
    public String fileName;

    /** The scene Back returns to once the free tool has nothing to step back from. */
    public Scene backScene;

    /** The camera over the tour's points, framing the main view left of and above the panels. */
    public Camera2D editorCamera;

    public FreeTool freeTool = new FreeTool();
    public Tool tool = null;

    /** The working copy of the tour that the knots are solved on. */
    public Shell tourShell;
    public PointSetPath retTup;
    public Shell orgShell;
    public ArrayList<Shell> subPaths = new ArrayList<>();
    public Shell resultShell;
    public ArrayList<Knot> resultKnots;
    public SegmentBalanceException sbe;

    public Knot manifoldKnot;
    public int knotDrawLayer = -1;
    public PriorityQueue<ShellPair> metroPathsLayer = new PriorityQueue<ShellPair>(new ShellComparator());
    public PriorityQueue<ShellPair> metroPathsHeight = new PriorityQueue<ShellPair>(new ShellComparator());
    public ArrayList<Knot> knotsDisplayed = new ArrayList<>();

    public Color stickyColor;
    public ArrayList<Color> metroColors = new ArrayList<>();
    public HashMap<Integer, Integer> knotLayerLookup = new HashMap<>();
    public ArrayList<Color> knotGradientColors = new ArrayList<>();
    public HashMap<Long, Integer> colorLookup = new HashMap<>();
    public Knot hoverKnot;
    public boolean showHoverKnot;
    public ColorLerp hoverKnotColor;
    public Segment hoverSegment;
    public boolean showHoverSegment;
    public ColorLerp hoverSegmentColor;
    public Terminal terminal;
    public InfoPane info = new InfoPane(BOTTOM_PANEL_SIZE);
    public Grid grid;
    public int totalLayers = -1;
    public double tourLength;
    public SDFTexture logo;
    public Font font;

    /**
     * Build the editor on a point-set file: parse it, lay out the main view and the panels around
     * it, wire the input handlers and the free tool, solve the knots and seed the per-knot colours.
     *
     * @param fileName  name of the point-set file to open (blank uses the temp file only)
     * @param backScene the scene Back returns to
     * @throws TerminalParseException if existing terminal commands in the file fail
     *                                to parse
     * @throws IOException            if the file cannot be read
     */
    public MainScene(String fileName, Scene backScene) throws TerminalParseException, IOException {
        this.backScene = backScene;
        tempFile = FileManagement.getTempFile(fileName);
        PointND.resetIds();
        if (Compat.isBlank(fileName)) {
            // retTup = PointSetPath.importFromFile(tempFile);
            // terminal = new Terminal(tempFile);
        } else {
            this.fileName = fileName;
            file = FileManagement.getFile(fileName);
            retTup = PointSetPath.importFromFile(file.getPath());
            terminal = new Terminal(file);
            Terminal.current = terminal;
        }
        for (String comment : retTup.comments) {
            terminal.history.addLine(comment, Color.BLUE_GREY);
        }

        Platforms.gl().setWindowTitle("Ixdar : " + fileName);

        int wWidth = (int) Platforms.get().getWindowWidth();
        int wHeight = (int) Platforms.get().getWindowHeight();
        editorCamera = new Camera2D(wWidth - RIGHT_PANEL_SIZE, wHeight - BOTTOM_PANEL_SIZE, CAMERA_ZOOM_DEFAULT, 0,
                BOTTOM_PANEL_SIZE,
                retTup.ps);
        editorCamera.resetTarget = () -> showHoverKnot ? hoverKnot : tool == null ? null : tool.selectedKnot();

        Toggle.setPanelFocus(PaneTypes.KnotView);
        grid = retTup.grid;
        keys = new MainSceneKeyGuy(this, editorCamera);
        mouse = new MainSceneMouseTrap(this, editorCamera);
        activate(true);
        tool = new FreeTool();
        logo = new SDFTexture("decal_sdf_small.png", Color.IXDAR_DARK, LOGO_SCALE, 0f, true);

        Map<String, Bounds> views = new HashMap<>();
        views.put(VIEW_MAIN,
                new Bounds(0, BOTTOM_PANEL_SIZE, wWidth - RIGHT_PANEL_SIZE, wHeight - BOTTOM_PANEL_SIZE, b -> {
                    int ww = (int) Platforms.get().getWindowWidth();
                    int wh = (int) Platforms.get().getWindowHeight();
                    b.update(0, BOTTOM_PANEL_SIZE, ww - RIGHT_PANEL_SIZE, wh - BOTTOM_PANEL_SIZE);
                }, VIEW_MAIN));
        views.put(VIEW_RIGHT_BOTTOM,
                new Bounds(wWidth - RIGHT_PANEL_SIZE, 0, RIGHT_PANEL_SIZE, BOTTOM_PANEL_SIZE, b -> {
                    int ww = (int) Platforms.get().getWindowWidth();
                    b.update(ww - RIGHT_PANEL_SIZE, 0, RIGHT_PANEL_SIZE, BOTTOM_PANEL_SIZE);
                }, VIEW_RIGHT_BOTTOM));
        views.put(VIEW_RIGHT_TOP, new Bounds(wWidth - RIGHT_PANEL_SIZE, BOTTOM_PANEL_SIZE, RIGHT_PANEL_SIZE,
                wHeight - BOTTOM_PANEL_SIZE, b -> {
                    int ww = (int) Platforms.get().getWindowWidth();
                    int wh = (int) Platforms.get().getWindowHeight();
                    b.update(ww - RIGHT_PANEL_SIZE, BOTTOM_PANEL_SIZE, RIGHT_PANEL_SIZE, wh - BOTTOM_PANEL_SIZE);
                }, VIEW_RIGHT_TOP));
        views.put(VIEW_BOTTOM, new Bounds(0, 0, wWidth - RIGHT_PANEL_SIZE, BOTTOM_PANEL_SIZE, b -> {
            int ww = (int) Platforms.get().getWindowWidth();
            b.update(0, 0, ww - RIGHT_PANEL_SIZE, BOTTOM_PANEL_SIZE);
        }, VIEW_BOTTOM));
        views.put(VIEW_TOOLTIP, tooltipBounds());

        editorCamera.initCamera(views, VIEW_MAIN);
        // Subscribe scroll regions for Info and Terminal panels
        Bounds rightTop = views.get(VIEW_RIGHT_TOP);
        Bounds bottom = views.get(VIEW_BOTTOM);
        PointerDispatcher pointer = PointerDispatcher.current();
        pointer.subscribe(rightTop, info);
        pointer.subscribe(bottom, terminal);
        pointer.subscribe(views.get(VIEW_MAIN), editorCamera);
        DistanceMatrix d = retTup.d;
        if (retTup.d == null) {
            d = new DistanceMatrix(retTup.ps);
            retTup.d = d;
        }
        ArrayList<PointND> toRemove = new ArrayList<>();
        for (PointND p : retTup.tsp) {
            if (p == null) {
                toRemove.add(p);
            }
        }
        retTup.tsp.removeAll(toRemove);
        if (tool.canUseToggle(Toggle.Manifold)) {
            Toggle.Manifold.value = true;
        }
        orgShell = retTup.tsp;

        tourShell = orgShell.copyShallow();

        tourShell.knotName = fileName;

        Collections.shuffle(tourShell);
        long startTimeKnotFinding = System.currentTimeMillis();
        if (Toggle.CalculateKnot.value) {
            try {
                resultKnots = new ArrayList<>(tourShell.slowSolve(tourShell, d, MAX_SOLVE_ATTEMPTS));
            } catch (MultipleCyclesFoundException e) {
                e.printStackTrace();
            }
        } else {
            resultKnots = new ArrayList<>();
        }

        long endTimeKnotFinding = System.currentTimeMillis() - startTimeKnotFinding;
        double knotFindingSeconds = ((double) endTimeKnotFinding) / MILLIS_PER_SECOND;

        Collection<Knot> flatKnots = resultKnots;
        if (flatKnots.size() > 0) {
            manifoldKnot = flatKnots.iterator().next();
        }
        for (Knot f : flatKnots) {
            if (f.knotPointsFlattened.size() > manifoldKnot.size()) {
                manifoldKnot = f;
            }
        }

        tourShell.buff.flush();
        retTup.grid.init();
        Random colorSeed = new Random();

        int numKnots = flatKnots.size();
        float startHue = colorSeed.nextFloat();
        float step = 1.0f / ((float) numKnots);
        int i = 0;
        for (Knot k : flatKnots) {
            knotGradientColors.add(Color.fullHue((startHue + step * i) % 1.0f));
            colorLookup.put((long) k.id, i);
            for (Knot vp : k.knotPoints) {
                if (vp.isSingleton()) {
                    colorLookup.put((long) vp.id, i);
                }
            }
            i++;
        }

        knotDrawLayer = totalLayers;
        totalLayers = -1;
        for (Knot k : flatKnots) {
            int height = k.getHeight();
            if (height > totalLayers) {
                totalLayers = height;
            }
        }
        for (Knot k : flatKnots) {
            int heightNum = k.getHeight();
            int layerNum = totalLayers - heightNum + 1;
            Shell knotShell = new Shell();
            for (Knot p : k.knotPointsFlattened) {
                knotShell.add((p).p);
            }
            if (totalLayers - layerNum == knotDrawLayer) {
                knotsDisplayed.add(k);
            }
            metroPathsHeight.add(new ShellPair(knotShell, k, heightNum));
            metroPathsLayer.add(new ShellPair(knotShell, k, layerNum));
            knotLayerLookup.put(k.id, layerNum);
        }

        float startHueM = colorSeed.nextFloat();
        float stepM = 1.0f / ((float) totalLayers);
        for (int j = 0; j <= totalLayers; j++) {
            metroColors.add(Color.fullHue((startHueM + stepM * j) % 1.0f));
        }

        Drawing.initDrawingSizes(tourShell, editorCamera, d);
        tourLength = tourShell.getLength();
        System.out.println(resultKnots);
        System.out.println(KNOT_FINDING_TIME + knotFindingSeconds);
        System.out.println("N " + tourShell.size());

        System.out.println(KNOT_FINDING_TIME + knotFindingSeconds);
        System.out.println("Saved Answer Length: " + orgShell.getLength());
        System.out.println("Calculated Length: " + tourLength);
        System.out.println("===============================================");

        stickyColor = Color.TEAL;

    }

    /**
     * The editor the window shows; only TSP code that runs while it is shown calls this.
     *
     * @return the current scene, an editor
     */
    public static MainScene current() {
        return (MainScene) Scene.instance;
    }

    /**
     * Open {@code fileName} in a new editor that replaces the current scene from the next frame;
     * Back from it returns where the current scene's Back would, or to the current scene itself.
     *
     * @param fileName name of the point-set file to open
     * @throws TerminalParseException if existing terminal commands in the file fail to parse
     * @throws IOException            if the file cannot be read
     */
    public static void open(String fileName) throws TerminalParseException, IOException {
        Scene current = Scene.instance;
        Scene back = current instanceof MainScene editor ? editor.backScene : current;
        current.switchTo(new MainScene(fileName, back));
    }

    /**
     * Take or release input: on activation bind the editor's handlers to the platform, pause the
     * menu music and hide the menu.
     *
     * @param state true to activate the editor, false to suspend it
     */
    @Override
    public void activate(boolean state) {
        if (state) {
            bindAutomationIfAvailable(Platforms.get(), keys, mouse);
            audioPauseMenuMusic();
            MenuBox.menuVisible = false;
        }
        active = state;
        mouse.active = state;
        keys.active = state;
    }

    /**
     * The editor's keys are its key handler's own, not control hints.
     */
    @Override
    public void setControls() {
    }

    /**
     * Per-frame draw of the editor: the active tool over the main view, the segment-balance
     * exception trace if any, the displayed knots or metro diagram, then the logo, info pane,
     * terminal and tooltip, sharing the frame's z-order with the window-sized 2D camera.
     */
    @Override
    public void drawScene() {
        try {
            Camera2D camera = editorCamera;
            camera.updateView(VIEW_MAIN);
            camera.setZIndex(camera2D);
            camera.calculateCameraTransform(retTup.ps);

            if (tool.canUseToggle(Toggle.DrawGridLines)) {
                grid.draw(camera, Drawing.MIN_THICKNESS / 2);
            }

            tool.setScreenOffset(camera);
            tool.draw(camera, Drawing.MIN_THICKNESS);
            if (sbe != null) {
                Drawing.drawShell(resultShell, true, Drawing.MIN_THICKNESS, Color.PURPLE_PINK, retTup.ps, camera);
                if (sbe.cutMatchList != null) {
                    Drawing.drawCutMatch(sbe, Drawing.MIN_THICKNESS, retTup.ps, camera);
                }
            }
            if (tool.canUseToggle(Toggle.DrawMainPath)) {
                Drawing.drawShell(orgShell, false, Drawing.MIN_THICKNESS, Color.BLUE, retTup.ps, camera);
            }
            if (tool.canUseToggle(Toggle.DrawDisplayedKnots) && tool.canUseToggle(Toggle.DrawMetroDiagram)
                    && tourShell != null) {
                if (knotDrawLayer == totalLayers) {
                    if (tool.canUseToggle(Toggle.DrawKnotGradient) && manifoldKnot != null) {
                        for (Integer id : knotLayerLookup.keySet()) {
                            if (knotLayerLookup.get(id) == totalLayers) {
                                Knot drawKnot = tourShell.pointMap.get(id);
                                Drawing.drawGradientPath(drawKnot, lookupPairs(drawKnot), colorLookup,
                                        knotGradientColors, camera, Drawing.MIN_THICKNESS);
                            }
                        }
                    } else if (tool.canUseToggle(Toggle.DrawMetroDiagram)) {
                        for (Shell temp : subPaths) {
                            Drawing.drawShell(temp, true, Drawing.MIN_THICKNESS, metroColors.get(0), retTup.ps,
                                    camera);
                        }
                    }
                } else {
                    PriorityQueue<ShellPair> newQueue = new PriorityQueue<ShellPair>(new ShellComparator());
                    int size = metroPathsLayer.size();
                    for (int i = 0; i < size; i++) {
                        ShellPair temp = metroPathsLayer.remove();
                        newQueue.add(temp);
                        if (knotDrawLayer >= 0 && temp.priority != knotDrawLayer) {
                            continue;
                        }
                        if (tool.canUseToggle(Toggle.DrawKnotGradient)) {
                            Drawing.drawGradientPath(temp.k, lookupPairs(temp.k), colorLookup, knotGradientColors,
                                    camera, Drawing.MIN_THICKNESS);
                        } else if (tool.canUseToggle(Toggle.DrawMetroDiagram)) {
                            float thickness = knotDrawLayer < 0
                                    ? Drawing.MIN_THICKNESS + Drawing.MIN_THICKNESS * (temp.priority - 1)
                                    : Drawing.MIN_THICKNESS;
                            Drawing.drawShell(temp.shell, true, thickness, metroColors.get(temp.priority),
                                    retTup.ps, camera);
                        }
                    }
                    metroPathsLayer = newQueue;
                }
                if (showHoverKnot) {
                    Drawing.drawKnot(hoverKnot, hoverKnotColor, Drawing.MIN_THICKNESS, camera);
                }
                for (Knot k : knotsDisplayed) {
                    if (k.s1 != null && k.s2 != null) {
                        Segment s1 = k.s1;
                        if (!k.contains(s1.last)) {
                            s1 = new Segment(s1.last, s1.first, s1.distance);
                        }
                        Segment s2 = k.s2;
                        if (!k.contains(s2.last)) {
                            s2 = new Segment(s2.last, s2.first, s2.distance);
                        }
                        Color c = Color.WHITE;
                        if (tool.canUseToggle(Toggle.DrawKnotGradient)) {
                            c = getKnotGradientColor(s1.last);
                        } else if (tool.canUseToggle(Toggle.DrawMetroDiagram)) {
                            c = getMetroColor(s1.last, k);
                        }
                        Drawing.drawDashedSegment(s1, c, camera);
                        Drawing.drawDashedSegment(s2, c, camera);
                    }
                }
            }

            if (showHoverSegment) {
                Drawing.drawScaledSegment(hoverSegment, hoverSegmentColor, Drawing.MIN_THICKNESS, camera);
            }

            if (!(retTup == null) && tool.canUseToggle(Toggle.DrawNumberLabels)) {
                Drawing.drawPath(retTup.tsp, Drawing.MIN_THICKNESS, Color.RED, retTup.ps, false, false, true, false,
                        camera);
            }
            camera.updateView(VIEW_RIGHT_BOTTOM);
            logo.draw(0, 0, RIGHT_PANEL_SIZE, BOTTOM_PANEL_SIZE, Color.IXDAR, camera);

            camera.updateView(VIEW_RIGHT_TOP);
            info.draw(camera, tool);

            camera.updateView(VIEW_BOTTOM);
            terminal.draw(camera);

            drawTooltip(camera);
            camera2D.setZIndex(camera);

        } catch (Exception e) {
            for (StackTraceElement ste : e.getStackTrace()) {
                Platforms.get()
                        .log(ste.getFileName() + PACKAGE_SEPARATOR + ste.getMethodName() + ":" + ste.getLineNumber());
            }
        }
    }

    /**
     * The colour {@code k} is drawn in under the active tool's toggles: its gradient colour, its
     * layer's metro colour, or the sticky colour when neither diagram is on. A null knot, or one
     * on no layer, is {@link Color#IXDAR} under either diagram.
     *
     * @param k knot to colour
     * @return the knot's colour
     */
    public Color knotColor(Knot k) {
        if (tool.canUseToggle(Toggle.DrawKnotGradient)) {
            return getKnotGradientColor(k);
        }
        if (!tool.canUseToggle(Toggle.DrawMetroDiagram)) {
            return stickyColor;
        }
        if (k == null || knotLayerLookup.get(k.id) < 0) {
            return Color.IXDAR;
        }
        return metroColors.get(knotLayerLookup.get(k.id));
    }

    /**
     * Look up the gradient color assigned to the given knot.
     *
     * @param displayPoint knot to color
     * @return assigned gradient color, or {@link Color#IXDAR} if
     *         {@code displayPoint} is null
     */
    public Color getKnotGradientColor(Knot displayPoint) {
        Knot smallestKnot = displayPoint;
        if (smallestKnot == null) {
            return Color.IXDAR;
        }
        return knotGradientColors.get(colorLookup.get((long) smallestKnot.id));
    }

    /**
     * Look up the metro-diagram color for {@code k}'s layer; when no specific layer
     * is being drawn ({@code knotDrawLayer < 0}) the lookup keys off the same knot.
     *
     * @param displayPoint knot being drawn (currently unused but mirrors
     *                     {@link #getKnotGradientColor})
     * @param k            knot whose layer is looked up
     * @return color assigned to {@code k}'s layer
     */
    public Color getMetroColor(Knot displayPoint, Knot k) {
        if (knotDrawLayer < 0) {
            Knot smallestKnot = k;
            return metroColors.get(knotLayerLookup.get(smallestKnot.id));
        } else {
            return metroColors.get(knotLayerLookup.get(k.id));
        }
    }

    /**
     * Build the per-segment color-lookup pairs used by gradient path drawing — one
     * (id, id) pair per manifold segment in {@code k}.
     *
     * @param k knot whose manifold segments drive the result
     * @return list of identity pairs, one per manifold segment
     */
    public static ArrayList<Pair<Long, Long>> lookupPairs(Knot k) {

        ArrayList<Pair<Long, Long>> idTransform = new ArrayList<>();
        for (int i = 0; i < k.manifoldSegments.size(); i++) {
            Knot smallestKnot1 = k;

            Knot smallestKnot2 = k;
            idTransform.add(new Pair<Long, Long>((long) smallestKnot1.id, (long) smallestKnot2.id));
        }
        return idTransform;
    }

    /**
     * Rebuild {@link #knotsDisplayed} by walking the metro-paths queue and
     * selecting the entries whose layer matches {@link #knotDrawLayer}. Called
     * after {@link #knotDrawLayer} changes.
     */
    public void updateKnotsDisplayed() {
        PriorityQueue<ShellPair> newQueue = new PriorityQueue<ShellPair>(new ShellComparator());
        int size = metroPathsLayer.size();
        knotsDisplayed = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ShellPair temp = metroPathsLayer.remove();
            if (temp.priority == knotDrawLayer) {
                knotsDisplayed.add(temp.k);
            }
            newQueue.add(temp);
        }
        metroPathsLayer = newQueue;
    }

    /**
     * Highlight {@code segment} on the next frame, fading {@code c} toward
     * 25%-transparent over a few frames.
     *
     * @param segment segment to highlight
     * @param c       base color used for the lerp
     */
    public void setHoverSegment(Segment segment, Color c) {
        hoverSegment = segment;
        showHoverSegment = true;
        hoverSegmentColor = new ColorLerp(c, Color.BLACK_25, new byte[] { 0, 0, 0, 1 }, HOVER_SEGMENT_LERP_SPEED);

    }

    /**
     * Stop highlighting any hovered segment.
     */
    public void clearHoverSegment() {
        hoverSegment = null;
        showHoverSegment = false;
    }

    /**
     * Highlight {@code k} on the next frame, fading the knot's current color toward
     * 25%-transparent over a few frames.
     *
     * @param k knot to highlight
     */
    public void setHoverKnot(Knot k) {
        hoverKnot = k;
        showHoverKnot = true;
        hoverKnotColor = new ColorLerp(knotColor(hoverKnot), Color.BLACK_25, new byte[] { 0, 0, 0, 1 },
                HOVER_KNOT_LERP_SPEED);
    }

    /**
     * Stop highlighting any hovered knot.
     */
    public void clearHoverKnot() {
        hoverKnot = null;
        showHoverKnot = false;
    }

    /**
     * Classify the screen-space point {@code (x, y)} into one of the four panes
     * (knot view, terminal, info, logo) of the editor laid out in the current window.
     *
     * @param x window-space x in pixels
     * @param y window-space y in pixels (top-down; flipped internally)
     * @return the {@link PaneTypes} containing the point, or {@link PaneTypes#None}
     */
    public static PaneTypes inView(float x, float y) {
        int mainViewWidth = (int) Platforms.get().getWindowWidth() - RIGHT_PANEL_SIZE;
        int mainViewHeight = (int) Platforms.get().getWindowHeight() - BOTTOM_PANEL_SIZE;
        boolean inMainViewRightBound = x < mainViewWidth + MAIN_VIEW_OFFSET_X;
        boolean inMainViewLeftBound = x > MAIN_VIEW_OFFSET_X;
        float invY = Platforms.get().getWindowHeight() - y;
        boolean inMainViewLowerBound = invY > MAIN_VIEW_OFFSET_Y;
        boolean inMainViewUpperBound = invY < mainViewHeight + MAIN_VIEW_OFFSET_Y;
        if (inMainViewLeftBound && inMainViewRightBound && inMainViewLowerBound && inMainViewUpperBound) {
            return PaneTypes.KnotView;
        } else if (!inMainViewLowerBound && !inMainViewRightBound) {
            return PaneTypes.Logo;
        } else if (!inMainViewLowerBound && inMainViewRightBound && inMainViewLeftBound) {
            return PaneTypes.Terminal;
        } else if (inMainViewLowerBound && inMainViewUpperBound && !inMainViewRightBound) {
            return PaneTypes.Info;
        }
        return PaneTypes.None;
    }

    /**
     * Switch the draw layer to the layer that contains {@code k}, then refresh
     * {@link #knotsDisplayed}. Falls back to the top layer if the knot is unknown.
     *
     * @param k knot whose layer becomes the new draw layer
     */
    public void setDrawLevelToKnot(Knot k) {
        Knot smallestKnot = tourShell.pointMap.get(k.id);
        if (smallestKnot == null) {
            knotDrawLayer = totalLayers;
        } else {
            knotDrawLayer = knotLayerLookup.get(smallestKnot.id);
        }
        updateKnotsDisplayed();
    }

    /**
     * Toggle between drawing every layer (sentinel {@code -1}) and the top layer,
     * then refresh {@link #knotsDisplayed}.
     */
    public void setDrawLevelMetro() {
        if (knotDrawLayer != -1) {
            knotDrawLayer = -1;
        } else {
            knotDrawLayer = totalLayers;
        }
        updateKnotsDisplayed();
    }

    /**
     * Return {@code k} unchanged, or the first result knot when {@code k} is null —
     * used as a safe default for color/layer lookups.
     *
     * @param k candidate knot (may be null)
     * @return {@code k} or the first {@link #resultKnots} entry when null
     */
    public Knot getKnotFlatten(Knot k) {
        Knot smallestKnot = k;
        if (smallestKnot == null) {
            Knot first = resultKnots.get(0);
            return (Knot) first;
        }
        return smallestKnot;
    }

}
