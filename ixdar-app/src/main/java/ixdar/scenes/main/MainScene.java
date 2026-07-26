package ixdar.scenes.main;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

import org.apache.commons.math3.util.Pair;

import ixdar.canvas.Canvas3D;
import ixdar.common.exceptions.MultipleCyclesFoundException;
import ixdar.common.exceptions.SegmentBalanceException;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.common.utils.Compat;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.point.Grid;
import ixdar.geometry.point.PointND;
import ixdar.geometry.shell.DistanceMatrix;
import ixdar.geometry.shell.Shell;
import ixdar.geometry.shell.ShellComparator;
import ixdar.geometry.shell.ShellPair;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorBox;
import ixdar.graphics.render.color.ColorLerp;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.sdf.SDFTexture;
import ixdar.graphics.render.text.Font;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.tools.FreeTool;
import ixdar.gui.ui.tools.Tool;
import ixdar.platform.Platforms;
import ixdar.platform.Toggle;
import ixdar.platform.file.FileManagement;
import ixdar.platform.file.PointSetPath;
import ixdar.platform.file.TextFile;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.SceneInputFrameUpdater;

public class MainScene {
    public static final String KNOT_FINDING_TIME = "Knot-finding time: ";
    public static final String STR = ".";
    public static final String STR_2 = ":";
    public static final float NUM_0_9 = 0.9f;
    public static final float NUM_0_6 = 0.6f;
    public static final float NUM_0 = 0f;
    public static final int NUM_40 = 40;
    public static final double NUM_1000_0 = 1000.0;
    public static final float NUM_4 = 4f;
    public static final float NUM_2 = 2f;

    public static TextFile file;
    public static TextFile tempFile;

    public static MainScene main;
    public static Camera2D camera;

    public static FreeTool freeTool = new FreeTool();
    public static Tool tool = null;

    public static Shell shell;
    public static PointSetPath retTup;
    public static Shell orgShell;
    public static ArrayList<Shell> subPaths = new ArrayList<>();
    public static Shell resultShell;
    public static ArrayList<Knot> resultKnots;
    public static SegmentBalanceException sbe;

    public static Knot manifoldKnot;
    public static int manifoldIdx = 0;
    public static int knotDrawLayer = -1;
    public static PriorityQueue<ShellPair> metroPathsLayer = new PriorityQueue<ShellPair>(new ShellComparator());
    public static ArrayList<Knot> knotsDisplayed;

    public static Color stickyColor;
    public static ArrayList<Color> metroColors = new ArrayList<>();
    public static HashMap<Integer, Integer> knotLayerLookup = new HashMap<>();
    public static ArrayList<Color> knotGradientColors = new ArrayList<>();
    public static HashMap<Long, Integer> colorLookup = new HashMap<>();
    public static boolean active;
    public static KeyGuy keys;
    public static MouseTrap mouse;
    public static int MAIN_VIEW_OFFSET_X;
    public static int MAIN_VIEW_OFFSET_Y;
    public static int MAIN_VIEW_WIDTH;
    public static int MAIN_VIEW_HEIGHT;
    public static Knot hoverKnot;
    public static boolean showHoverKnot;
    public static ColorLerp hoverKnotColor;
    public static Segment hoverSegment;
    public static boolean showHoverSegment;
    public static ColorLerp hoverSegmentColor;
    public static Terminal terminal;
    public static InfoPane info;
    public static Grid grid;
    public static int totalLayers = -1;
    public static double tourLength;
    public static Canvas3D canvas;

    public static final String VIEW_MAIN = "MAIN";
    public static final String VIEW_RIGHT_TOP = "RIGHT_TOP";
    public static final String VIEW_RIGHT_BOTTOM = "RIGHT_BOTTOM";
    public static final String VIEW_BOTTOM = "BOTTOM";
    public static final String VIEW_TOOLTIP = "TOOLTIP";
    static String fileName;
    static String tempFileName;
    static PriorityQueue<ShellPair> metroPathsHeight = new PriorityQueue<ShellPair>(new ShellComparator());

    final static int RIGHT_PANEL_SIZE = 195;
    final static int BOTTOM_PANEL_SIZE = 195;
    private static HyperString toolTip;
    private static boolean showToolTip;
    public SDFTexture logo;
    public Font font;

    /**
     * Construct the main editor scene: load (or create) the temp/source
     * file, parse its point set, build the camera and view layout, wire
     * input handlers, and pick a default tool.
     *
     * @param fileName name of the point-set file to open (blank uses the temp file only)
     * @param canvas backing 3D canvas used for input dispatch and timing
     * @throws TerminalParseException if existing terminal commands in the file fail to parse
     * @throws IOException if the file cannot be read
     */
    public MainScene(String fileName, Canvas3D canvas) throws TerminalParseException, IOException {
        metroPathsHeight = new PriorityQueue<ShellPair>(new ShellComparator());
        metroPathsLayer = new PriorityQueue<ShellPair>(new ShellComparator());
        knotLayerLookup = new HashMap<>();
        knotGradientColors = new ArrayList<>();
        knotsDisplayed = new ArrayList<>();
        colorLookup = new HashMap<>();
        metroColors = new ArrayList<>();
        subPaths = new ArrayList<>();
        info = new InfoPane();
        tempFile = FileManagement.getTempFile(fileName);
        MainScene.tempFileName = tempFile.getName();
        PointND.resetIds();
        if (Compat.isBlank(fileName)) {
            // retTup = FileManagement.importFromFile(tempFile);
            // terminal = new Terminal(tempFile);
        } else {
            MainScene.fileName = fileName;
            file = FileManagement.getFile(fileName);
            retTup = FileManagement.importFromFile(file.getPath());
            terminal = new Terminal(file);
            Terminal.current = terminal;
        }
        for (String comment : retTup.comments) {
            terminal.history.addLine(comment, Color.BLUE_WHITE);
        }

        Platforms.gl().setWindowTitle("Ixdar : " + fileName);

        int wWidth = (int) Platforms.get().getWindowWidth();
        int wHeight = (int) Platforms.get().getWindowHeight();
        camera = new Camera2D(wWidth - RIGHT_PANEL_SIZE, wHeight - BOTTOM_PANEL_SIZE, NUM_0_9, 0, BOTTOM_PANEL_SIZE,
                retTup.ps);

        Toggle.setPanelFocus(PaneTypes.KnotView);
        grid = retTup.grid;
        keys = new KeyGuy(this, fileName, camera, canvas);
        mouse = new MouseTrap(this, camera, canvas);
        activate(true);
        tool = new FreeTool();
        logo = new SDFTexture("decal_sdf_small.png", Color.DARK_IXDAR, NUM_0_6, NUM_0, true);
    }

    /**
     * Entry point that bootstraps the editor: creates the canvas and
     * scene, registers all named view bounds (main, right top/bottom,
     * bottom, tooltip) with scroll subscriptions, kicks off knot finding
     * on the loaded point set, and seeds the per-knot color tables used
     * by the metro/gradient diagram modes.
     *
     * @param args command-line arguments; {@code args[0]} is the point-set file name
     * @throws TerminalParseException if existing terminal commands in the file fail to parse
     * @throws IOException if the file cannot be read
     */
    public static void main(String[] args) throws TerminalParseException, IOException {
        canvas = new Canvas3D();
        main = new MainScene(args[0], canvas);

        int wWidth = (int) Platforms.get().getWindowWidth();
        int wHeight = (int) Platforms.get().getWindowHeight();
        MAIN_VIEW_WIDTH = wWidth - RIGHT_PANEL_SIZE;
        MAIN_VIEW_HEIGHT = wHeight - BOTTOM_PANEL_SIZE;
        MAIN_VIEW_OFFSET_X = 0;
        MAIN_VIEW_OFFSET_Y = BOTTOM_PANEL_SIZE;
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

        views.put(VIEW_TOOLTIP, new Bounds(0, 0, 0, 0, b -> {
            int ww = (int) Platforms.get().getWindowWidth();
            int wh = (int) Platforms.get().getWindowHeight();
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
        }, VIEW_TOOLTIP));

        camera.initCamera(views, VIEW_MAIN);
        // Subscribe scroll regions for Info and Terminal panels
        Bounds rightTop = views.get(VIEW_RIGHT_TOP);
        Bounds bottom = views.get(VIEW_BOTTOM);
        MouseTrap.subscribeScrollRegion(rightTop, info);
        MouseTrap.subscribeScrollRegion(bottom, terminal);
        MouseTrap.subscribeScrollRegion(views.get(VIEW_MAIN), camera);
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

        shell = orgShell.copyShallow();

        shell.knotName = fileName;

        Collections.shuffle(shell);
        long startTimeKnotFinding = System.currentTimeMillis();
        if (Toggle.CalculateKnot.value) {
            try {
                resultKnots = new ArrayList<>(shell.slowSolve(shell, d, NUM_40));
            } catch (MultipleCyclesFoundException e) {
                e.printStackTrace();
            }
        } else {
            resultKnots = new ArrayList<>();
        }

        long endTimeKnotFinding = System.currentTimeMillis() - startTimeKnotFinding;
        double knotFindingSeconds = ((double) endTimeKnotFinding) / NUM_1000_0;

        Collection<Knot> flatKnots = resultKnots;
        if (flatKnots.size() > 0) {
            manifoldKnot = flatKnots.iterator().next();
        }
        for (Knot f : flatKnots) {
            if (f.knotPointsFlattened.size() > manifoldKnot.size()) {
                manifoldKnot = f;
            }
        }

        shell.buff.flush();
        retTup.grid.init();
        Random colorSeed = new Random();

        int numKnots = flatKnots.size();
        float startHue = colorSeed.nextFloat();
        float step = 1.0f / ((float) numKnots);
        int i = 0;
        for (Knot k : flatKnots) {
            knotGradientColors.add(Color.getHSBColor((startHue + step * i) % 1.0f, 1.0f, 1.0f));
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
            metroColors.add(Color.getHSBColor((startHueM + stepM * j) % 1.0f, 1.0f, 1.0f));
        }

        Drawing.initDrawingSizes(shell, camera, d);
        tourLength = shell.getLength();
        System.out.println(resultKnots);
        System.out.println(KNOT_FINDING_TIME + knotFindingSeconds);
        System.out.println("N " + shell.size());

        System.out.println(KNOT_FINDING_TIME + knotFindingSeconds);
        System.out.println("Saved Answer Length: " + orgShell.getLength());
        System.out.println("Calculated Length: " + tourLength);
        System.out.println("===============================================");

        stickyColor = new ColorRGB(colorSeed.nextFloat(), colorSeed.nextFloat(), colorSeed.nextFloat());
        stickyColor = Color.CYAN;

    }

    /**
     * Per-frame draw of the editor: refresh view dimensions, pump input,
     * render the active tool over the main view, draw the segment-balance
     * exception trace if any, draw the displayed knots/metro diagram,
     * then render the right-side logo, info pane, bottom terminal, and
     * any tooltip.
     *
     * @param camera3D outer 3D camera whose z-index is shared with the 2D camera
     */
    public void draw(Camera camera3D) {
        try {
            int wWidth = (int) Platforms.get().getWindowWidth();
            int wHeight = (int) Platforms.get().getWindowHeight();
            MAIN_VIEW_WIDTH = wWidth - RIGHT_PANEL_SIZE;
            MAIN_VIEW_HEIGHT = wHeight - BOTTOM_PANEL_SIZE;
            MAIN_VIEW_OFFSET_X = 0;
            MAIN_VIEW_OFFSET_Y = BOTTOM_PANEL_SIZE;
            camera.updateView(VIEW_MAIN);
            SceneInputFrameUpdater.update(keys, mouse);
            camera.setZIndex(camera3D);
            camera.calculateCameraTransform(retTup.ps);

            if (tool.canUseToggle(Toggle.DrawGridLines)) {
                grid.draw(camera, Drawing.MIN_THICKNESS / 2);
            }

            tool.setScreenOffset(camera);
            tool.draw(camera, Drawing.MIN_THICKNESS);
            if (sbe != null) {
                Drawing.drawShell(resultShell, true, Drawing.MIN_THICKNESS, Color.MAGENTA, retTup.ps, camera);
                if (sbe.cutMatchList != null) {
                    Drawing.drawCutMatch(sbe, Drawing.MIN_THICKNESS, retTup.ps, camera);
                }
            }
            if (tool.canUseToggle(Toggle.DrawMainPath)) {
                Drawing.drawShell(orgShell, false, Drawing.MIN_THICKNESS, Color.BLUE, retTup.ps, camera);
            }
            if (tool.canUseToggle(Toggle.DrawDisplayedKnots) && tool.canUseToggle(Toggle.DrawMetroDiagram)
                    && shell != null) {
                drawDisplayedKnots(camera);
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
            info.draw(camera);

            camera.updateView(VIEW_BOTTOM);
            terminal.draw(camera);

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
                Platforms.get().log(ste.getFileName() + STR + ste.getMethodName() + STR_2 + ste.getLineNumber());
            }
        }
    }

    /**
     * Render the currently visible layer of knots. At the top layer this
     * draws gradient paths (or metro sub-paths); at intermediate layers
     * it walks {@link #metroPathsLayer} and draws each knot's shell
     * styled per active toggle. Always overlays the hover knot and any
     * partial-cycle dashed segments for displayed knots.
     *
     * @param camera 2D camera providing the main-view transform
     */
    public static void drawDisplayedKnots(Camera2D camera) {
        if (knotDrawLayer == totalLayers) {
            if (tool.canUseToggle(Toggle.DrawKnotGradient) && manifoldKnot != null) {
                for (Integer id : knotLayerLookup.keySet()) {
                    if (knotLayerLookup.get(id) == totalLayers) {
                        Knot drawKnot = shell.pointMap.get(id);

                        ArrayList<Pair<Long, Long>> idTransform = lookupPairs(drawKnot);
                        Drawing.drawGradientPath(drawKnot, idTransform, colorLookup, knotGradientColors, camera,
                                Drawing.MIN_THICKNESS);
                    }
                }
            } else if (tool.canUseToggle(Toggle.DrawMetroDiagram)) {
                for (Shell temp : subPaths) {
                    Drawing.drawShell(temp, true, Drawing.MIN_THICKNESS, metroColors.get(0), retTup.ps, camera);
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
                if (knotDrawLayer < 0) {
                    if (tool.canUseToggle(Toggle.DrawKnotGradient)) {
                        ArrayList<Pair<Long, Long>> idTransform = lookupPairs(temp.k);
                        Drawing.drawGradientPath(temp.k, idTransform, colorLookup, knotGradientColors, camera,
                                Drawing.MIN_THICKNESS);
                    } else if (tool.canUseToggle(Toggle.DrawMetroDiagram)) {
                        Drawing.drawShell(temp.shell, true,
                                Drawing.MIN_THICKNESS + Drawing.MIN_THICKNESS * (temp.priority - 1),
                                metroColors.get(temp.priority), retTup.ps, camera);
                    }
                } else {
                    if (tool.canUseToggle(Toggle.DrawKnotGradient)) {
                        ArrayList<Pair<Long, Long>> idTransform = lookupPairs(temp.k);
                        Drawing.drawGradientPath(temp.k, idTransform, colorLookup, knotGradientColors, camera,
                                Drawing.MIN_THICKNESS);
                    } else if (tool.canUseToggle(Toggle.DrawMetroDiagram)) {
                        Drawing.drawShell(temp.shell, true, Drawing.MIN_THICKNESS, metroColors.get(temp.priority),
                                retTup.ps, camera);
                    }

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

    private static Color getKnotColor(Knot k) {
        Color c = MainScene.stickyColor;
        if (tool.canUseToggle(Toggle.DrawKnotGradient)) {
            c = MainScene.getKnotGradientColorFlatten((Knot) k);
        } else if (tool.canUseToggle(Toggle.DrawMetroDiagram)) {
            c = MainScene.getMetroColorFlatten((Knot) k);
        }
        return c;
    }

    /**
     * Look up the gradient color assigned to the given knot.
     *
     * @param displayPoint knot to color
     * @return assigned gradient color, or {@link Color#IXDAR} if {@code displayPoint} is null
     */
    public static Color getKnotGradientColor(Knot displayPoint) {
        Knot smallestKnot = displayPoint;
        if (smallestKnot == null) {
            return Color.IXDAR;
        }
        return knotGradientColors.get(colorLookup.get((long) smallestKnot.id));
    }

    /**
     * Like {@link #getKnotGradientColor} but treats nested-knot wrappers
     * as their flattened representative.
     *
     * @param k knot to color
     * @return assigned gradient color, or {@link Color#IXDAR} if {@code k} is null
     */
    public static Color getKnotGradientColorFlatten(Knot k) {
        Knot smallestKnot = k;
        if (smallestKnot == null) {
            return Color.IXDAR;
        }
        return knotGradientColors.get(colorLookup.get((long) smallestKnot.id));
    }

    /**
     * Look up the metro-diagram color for {@code k}'s layer; when no
     * specific layer is being drawn ({@code knotDrawLayer < 0}) the
     * lookup keys off the same knot.
     *
     * @param displayPoint knot being drawn (currently unused but mirrors {@link #getKnotGradientColor})
     * @param k knot whose layer is looked up
     * @return color assigned to {@code k}'s layer
     */
    public static Color getMetroColor(Knot displayPoint, Knot k) {
        if (knotDrawLayer < 0) {
            Knot smallestKnot = k;
            return metroColors.get(knotLayerLookup.get(smallestKnot.id));
        } else {
            return metroColors.get(knotLayerLookup.get(k.id));
        }
    }

    /**
     * Like {@link #getMetroColor} but treats nested-knot wrappers as
     * their flattened representative; returns {@link Color#IXDAR} when
     * the knot or its layer is unknown.
     *
     * @param thickKnot knot whose layer color is requested
     * @return color assigned to its layer, or {@link Color#IXDAR} if not assigned
     */
    public static Color getMetroColorFlatten(Knot thickKnot) {
        Knot smallestKnot = thickKnot;
        if (smallestKnot == null) {
            return Color.IXDAR;
        }
        int knotLayer = knotLayerLookup.get(smallestKnot.id);
        if (knotLayer < 0) {
            return Color.IXDAR;
        }
        return metroColors.get(knotLayer);
    }

    /**
     * Build the per-segment color-lookup pairs used by gradient path
     * drawing — one (id, id) pair per manifold segment in {@code k}.
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
     * Capture a {@link SegmentBalanceException} for on-screen display:
     * record the offending top knot's points as {@link #resultShell},
     * dump the buffer's layer 0, and log the exception's stack until
     * the {@code cutKnot} frame.
     *
     * @param sbe exception thrown during knot solving
     */
    public static void segmentBalanceExceptionHandler(SegmentBalanceException sbe) {
        Shell result = new Shell();
        if (sbe.topKnot != null) {
            for (Knot p : sbe.topKnot.knotPoints) {
                result.add((p).p);
            }
            shell.buff.printLayer(0);
        }
        System.out.println();
        System.out.println(sbe);
        // StackTraceElement ste = sbe.getStackTrace()[0];
        for (StackTraceElement ste : sbe.getStackTrace()) {
            if (ste.getMethodName().equals("cutKnot")) {
                break;
            }
            System.out.println(
                    "ErrorSource: " + ste.getMethodName() + " " + ste.getFileName() + STR_2 + ste.getLineNumber());
        }
        System.out.println();
        resultShell = result;
    }

    /**
     * Rebuild {@link #knotsDisplayed} by walking the metro-paths queue
     * and selecting the entries whose layer matches {@link #knotDrawLayer}.
     * Called after {@link #knotDrawLayer} changes.
     */
    public static void updateKnotsDisplayed() {
        PriorityQueue<ShellPair> newQueue = new PriorityQueue<ShellPair>(new ShellComparator());
        PriorityQueue<ShellPair> metroPathsLayer = MainScene.metroPathsLayer;
        int size = metroPathsLayer.size();
        knotsDisplayed = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ShellPair temp = metroPathsLayer.remove();
            if (temp.priority == knotDrawLayer) {
                knotsDisplayed.add(temp.k);
            }
            newQueue.add(temp);
        }
        MainScene.metroPathsLayer = newQueue;
    }

    /**
     * Toggle this scene's input handling. When activating, automation
     * input is rebound and the underlying canvas's own input is
     * suspended; the inverse on deactivate.
     *
     * @param state true to activate the scene, false to suspend it
     */
    public static void activate(boolean state) {
        if (state) {
            Platform p = Platforms.get();
            bindAutomationIfAvailable(p, keys, mouse);
        }
        canvas.activate(!state);
        active = state;
        mouse.active = state;
        keys.active = state;
    }

    /**
     * Show a floating tooltip with the given text on the next frame.
     *
     * @param pointInfo formatted tooltip body
     */
    public static void setTooltipText(HyperString pointInfo) {
        toolTip = pointInfo;
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
     * Highlight {@code segment} on the next frame, fading {@code c}
     * toward 25%-transparent over a few frames.
     *
     * @param segment segment to highlight
     * @param c base color used for the lerp
     */
    public static void setHoverSegment(Segment segment, Color c) {
        hoverSegment = segment;
        showHoverSegment = true;
        hoverSegmentColor = new ColorLerp(c, Color.TRANSPARENT25, new byte[] { 0, 0, 0, 1 }, NUM_4);

    }

    /**
     * Stop highlighting any hovered segment.
     */
    public static void clearHoverSegment() {
        hoverSegment = null;
        showHoverSegment = false;
    }

    /**
     * Highlight {@code k} on the next frame, fading the knot's current
     * color toward 25%-transparent over a few frames.
     *
     * @param k knot to highlight
     */
    public static void setHoverKnot(Knot k) {
        hoverKnot = k;
        showHoverKnot = true;
        hoverKnotColor = new ColorLerp(getKnotColor(hoverKnot), Color.TRANSPARENT25, new byte[] { 0, 0, 0, 1 }, NUM_2);
    }

    /**
     * Stop highlighting any hovered knot.
     */
    public static void clearHoverKnot() {
        hoverKnot = null;
        showHoverKnot = false;
    }

    /**
     * Classify the screen-space point {@code (x, y)} into one of the
     * four panes (knot view, terminal, info, logo).
     *
     * @param x window-space x in pixels
     * @param y window-space y in pixels (top-down; flipped internally)
     * @return the {@link PaneTypes} containing the point, or {@link PaneTypes#None}
     */
    public static PaneTypes inView(float x, float y) {
        boolean inMainViewRightBound = x < MainScene.MAIN_VIEW_WIDTH + MainScene.MAIN_VIEW_OFFSET_X;
        boolean inMainViewLeftBound = x > MainScene.MAIN_VIEW_OFFSET_X;
        float invY = Platforms.get().getWindowHeight() - y;
        boolean inMainViewLowerBound = invY > MainScene.MAIN_VIEW_OFFSET_Y;
        boolean inMainViewUpperBound = invY < MainScene.MAIN_VIEW_HEIGHT + MainScene.MAIN_VIEW_OFFSET_Y;
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
     * Switch the draw layer to the layer that contains {@code k}, then
     * refresh {@link #knotsDisplayed}. Falls back to the top layer if
     * the knot is unknown.
     *
     * @param k knot whose layer becomes the new draw layer
     */
    public static void setDrawLevelToKnot(Knot k) {
        Knot smallestKnot = shell.pointMap.get(k.id);
        if (smallestKnot == null) {
            knotDrawLayer = totalLayers;
        } else {
            knotDrawLayer = knotLayerLookup.get(smallestKnot.id);
        }
        updateKnotsDisplayed();
    }

    /**
     * Toggle between drawing every layer (sentinel {@code -1}) and the
     * top layer, then refresh {@link #knotsDisplayed}.
     */
    public static void setDrawLevelMetro() {
        if (MainScene.knotDrawLayer != -1) {
            MainScene.knotDrawLayer = -1;
        } else {
            MainScene.knotDrawLayer = totalLayers;
        }
        updateKnotsDisplayed();
    }

    /**
     * Return {@code k} unchanged, or the first result knot when {@code k}
     * is null — used as a safe default for color/layer lookups.
     *
     * @param k candidate knot (may be null)
     * @return {@code k} or the first {@link #resultKnots} entry when null
     */
    public static Knot getKnotFlatten(Knot k) {
        Knot smallestKnot = k;
        if (smallestKnot == null) {
            Knot first = resultKnots.get(0);
            return (Knot) first;
        }
        return smallestKnot;
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
