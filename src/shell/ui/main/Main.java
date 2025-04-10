package shell.ui.main;

import static org.lwjgl.glfw.GLFW.glfwSetCharCallback;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;

import java.awt.Canvas;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import org.apache.commons.math3.util.Pair;

import shell.DistanceMatrix;
import shell.Toggle;
import shell.cameras.Camera;
import shell.cameras.Camera2D;
import shell.cuts.Manifold;
import shell.cuts.engines.CutEngine;
import shell.cuts.engines.FlattenEngine;
import shell.cuts.engines.InternalPathEngine;
import shell.cuts.engines.ManifoldEngine;
import shell.cuts.route.RouteInfo;
import shell.exceptions.SegmentBalanceException;
import shell.exceptions.TerminalParseException;
import shell.file.FileManagement;
import shell.file.PointSetPath;
import shell.knot.Knot;
import shell.knot.Point;
import shell.knot.Run;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.point.Grid;
import shell.point.PointND;
import shell.render.color.Color;
import shell.render.color.ColorBox;
import shell.render.color.ColorLerp;
import shell.render.color.ColorRGB;
import shell.render.sdf.SDFTexture;
import shell.render.text.Font;
import shell.render.text.HyperString;
import shell.shell.Shell;
import shell.shell.ShellComparator;
import shell.shell.ShellPair;
import shell.terminal.Terminal;
import shell.ui.Drawing;
import shell.ui.IxdarWindow;
import shell.ui.input.KeyActions;
import shell.ui.input.KeyGuy;
import shell.ui.input.MouseTrap;
import shell.ui.tools.FreeTool;
import shell.ui.tools.Tool;

public class Main {

    public static File file;
    public static File tempFile;
    static String fileName;
    static String tempFileName;

    public static Main main;
    public static Camera2D camera;

    public static FreeTool freeTool = new FreeTool();
    public static Tool tool = freeTool;

    public static Shell shell;
    public static PointSetPath retTup;
    public static Shell orgShell;
    public static ArrayList<Shell> subPaths = new ArrayList<>();
    public static Shell resultShell;
    public static ArrayList<VirtualPoint> result;
    public static SegmentBalanceException sbe;

    public static Knot manifoldKnot;
    public static int manifoldIdx = 0;
    public static ArrayList<Manifold> manifolds = new ArrayList<>();
    public static int knotDrawLayer = -1;
    static PriorityQueue<ShellPair> metroPathsHeight = new PriorityQueue<ShellPair>(new ShellComparator());
    public static PriorityQueue<ShellPair> metroPathsLayer = new PriorityQueue<ShellPair>(new ShellComparator());
    public static ArrayList<Knot> knotsDisplayed;

    public static Color stickyColor;
    public static ArrayList<Color> metroColors = new ArrayList<>();
    public static HashMap<Integer, Integer> knotLayerLookup = new HashMap<>();
    public static ArrayList<Color> knotGradientColors = new ArrayList<>();
    public static HashMap<Long, Integer> colorLookup = new HashMap<>();
    public static boolean active;
    public Canvas canvas;
    public static KeyGuy keys;
    public static MouseTrap mouse;
    private static HyperString toolTip;
    public SDFTexture logo;
    public Font font;

    final static int RIGHT_PANEL_SIZE = 195;
    final static int BOTTOM_PANEL_SIZE = 195;
    public static int MAIN_VIEW_OFFSET_X;
    public static int MAIN_VIEW_OFFSET_Y;
    public static int MAIN_VIEW_WIDTH;
    public static int MAIN_VIEW_HEIGHT;
    private static boolean showToolTip;
    public static Knot hoverKnot;
    public static boolean showHoverKnot;
    public static ColorLerp hoverKnotColor;
    public static Segment hoverSegment;
    public static boolean showHoverSegment;
    public static ColorLerp hoverSegmentColor;
    public static Terminal terminal;
    public static Info info;
    public static Grid grid;
    public static int totalLayers = -1;
    public static FlattenEngine flattenEngine;
    public static double tourLength;

    public Main(String fileName) throws TerminalParseException {
        metroPathsHeight = new PriorityQueue<ShellPair>(new ShellComparator());
        metroPathsLayer = new PriorityQueue<ShellPair>(new ShellComparator());
        manifolds = new ArrayList<>();
        knotLayerLookup = new HashMap<>();
        knotGradientColors = new ArrayList<>();
        knotsDisplayed = new ArrayList<>();
        colorLookup = new HashMap<>();
        metroColors = new ArrayList<>();
        subPaths = new ArrayList<>();
        info = new Info();
        tempFile = FileManagement.getTempFile(fileName);
        Main.tempFileName = tempFile.getName();
        PointND.resetIds();
        if (fileName.isBlank()) {
            retTup = FileManagement.importFromFile(tempFile);
            terminal = new Terminal(tempFile);
        } else {
            Main.fileName = fileName;
            file = FileManagement.getTestFile(fileName);
            retTup = FileManagement.importFromFile(file);
            terminal = new Terminal(file);
        }
        for (String comment : retTup.comments) {
            terminal.history.addLine(comment, Color.BLUE_WHITE);
        }
        IxdarWindow.setTitle("Ixdar : " + fileName);

        int wWidth = (int) IxdarWindow.getWidth();
        int wHeight = (int) IxdarWindow.getHeight();
        camera = new Camera2D(wWidth - RIGHT_PANEL_SIZE, wHeight - BOTTOM_PANEL_SIZE, 0.9f, 0, BOTTOM_PANEL_SIZE,
                retTup.ps);

        Toggle.Manifold.value = !retTup.manifolds.isEmpty();

        Toggle.setPanelFocus(PanelTypes.KnotView);
        grid = retTup.grid;
        keys = new KeyGuy(this, fileName, camera);
        mouse = new MouseTrap(this, camera, false);
        activate(true);
        tool = new FreeTool();
        logo = new SDFTexture("decal_sdf_small.png", Color.DARK_IXDAR, 0.6f, 0f, true);
    }

    public static void main(String[] args) throws TerminalParseException {
        main = new Main(args[0]);

        int wWidth = (int) IxdarWindow.getWidth();
        int wHeight = (int) IxdarWindow.getHeight();
        MAIN_VIEW_WIDTH = wWidth - RIGHT_PANEL_SIZE;
        MAIN_VIEW_HEIGHT = wHeight - BOTTOM_PANEL_SIZE;
        MAIN_VIEW_OFFSET_X = 0;
        MAIN_VIEW_OFFSET_Y = BOTTOM_PANEL_SIZE;
        camera.initCamera();
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
        totalLayers = -1;
        shell = orgShell.copyShallow();

        CutEngine.resetMetrics();

        shell.knotName = fileName;

        Collections.shuffle(shell);
        long startTimeKnotFinding = System.currentTimeMillis();
        if (Toggle.CalculateKnot.value) {
            result = new ArrayList<>(shell.slowSolve(shell, d, 40));
        } else {
            result = new ArrayList<>();
        }

        long endTimeKnotFinding = System.currentTimeMillis() - startTimeKnotFinding;
        double knotFindingSeconds = ((double) endTimeKnotFinding) / 1000.0;

        long startTimeKnotCutting = System.currentTimeMillis();
        if (Toggle.CutKnot.value) {
            calculateSubPaths();
        }
        long endTimeKnotCutting = System.currentTimeMillis() - startTimeKnotCutting;
        flattenEngine = shell.cutEngine.flattenEngine;
        Collection<Knot> flatKnots = flattenEngine.flatKnots.values();
        if (flatKnots.size() > 0) {
            manifoldKnot = flatKnots.iterator().next();
        }
        for (Knot f : flatKnots) {
            if (f.knotPointsFlattened.size() > manifoldKnot.size()) {
                manifoldKnot = f;
            }
        }
        manifolds = retTup.manifolds;
        for (Manifold m : manifolds) {
            m.manifoldKnot = manifoldKnot;
            try {
                m.loadCutMatch(shell);
            } catch (SegmentBalanceException e) {
                sbe = e;
            }
        }

        if (tool.canUseToggle(Toggle.Manifold) && sbe == null) {

            if (result.size() > 1) {
                Toggle.Manifold.value = false;
                Toggle.CalculateKnot.value = true;
            }

            manifolds.parallelStream().forEach((m) -> {
                try {
                    m.calculateManifoldCutMatch(shell, manifoldKnot);
                } catch (SegmentBalanceException sbe) {
                    segmentBalanceExceptionHandler(sbe);
                }
            });

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
            Knot orgKnot = (Knot) shell.pointMap.get(flattenEngine.flatKnotToKnot.get(k.id));
            for (VirtualPoint vp : orgKnot.knotPoints) {
                if (!vp.isKnot) {
                    colorLookup.put((long) vp.id, i);
                }
            }
            i++;
        }

        if (totalLayers == -1) {
            totalLayers = shell.cutEngine.totalLayers;
        }
        knotDrawLayer = totalLayers;
        Set<Integer> knotIds = flattenEngine.flatKnots.keySet();
        HashMap<Integer, Knot> flatKnotMap = flattenEngine.flatKnots;
        HashMap<Integer, Integer> flatKnotsHeight = flattenEngine.flatKnotsHeight;
        HashMap<Integer, Integer> flatKnotsLayer = flattenEngine.flatKnotsLayer;
        for (Integer id : knotIds) {
            Knot k = flatKnotMap.get(id);
            int heightNum = flatKnotsHeight.get(id);
            int layerNum = flatKnotsLayer.get(id);
            Shell knotShell = new Shell();
            for (VirtualPoint p : k.knotPointsFlattened) {
                knotShell.add(((Point) p).p);
            }
            if (totalLayers - layerNum == knotDrawLayer) {
                knotsDisplayed.add(k);
            }
            metroPathsHeight.add(new ShellPair(knotShell, k, heightNum));
            metroPathsLayer.add(new ShellPair(knotShell, k, totalLayers - layerNum));
            knotLayerLookup.put(k.id, totalLayers - layerNum);
        }

        float startHueM = colorSeed.nextFloat();
        float stepM = 1.0f / ((float) totalLayers);
        for (int j = 0; j <= totalLayers; j++) {
            metroColors.add(Color.getHSBColor((startHueM + stepM * j) % 1.0f, 1.0f, 1.0f));
        }

        Drawing.initDrawingSizes(shell, camera, d);
        tourLength = shell.getLength();
        double knotCuttingSeconds = ((double) endTimeKnotCutting) / 1000.0;
        double ixdarSeconds = ((double) InternalPathEngine.totalTimeIxdar) / 1000.0;
        double ixdarProfileSeconds = ((double) InternalPathEngine.profileTimeIxdar) / 1000.0;
        System.out.println(result);
        System.out.println("Knot-finding time: " + knotFindingSeconds);
        System.out.println("Knot-cutting time: " + knotCuttingSeconds);
        System.out.println("Ixdar counted: " + ManifoldEngine.countCalculated);
        System.out.println("Ixdar skip: " + ManifoldEngine.countSkipped);
        System.out.println("Ixdar Calls:" + InternalPathEngine.ixdarCalls);

        System.out.println("routeMapCopy %: "
                + 100 * ((double) ManifoldEngine.routeMapsCopied) / ((double) ManifoldEngine.totalRoutes));
        System.out.println("maxSettledSize: " + RouteInfo.maxSettledSize);

        System.out.println("Ixdar comparisons " + String.format("%,d", InternalPathEngine.comparisons));
        System.out.println("N " + shell.size());

        System.out.println("Knot-finding time: " + knotFindingSeconds);
        System.out.println("Knot-cutting time: " + knotCuttingSeconds);
        System.out.println("Knot-cutting %: " + 100 * (knotCuttingSeconds / (knotCuttingSeconds + knotFindingSeconds)));
        System.out.println("ree Time: " + CutEngine.reeTotalTimeSeconds);
        System.out.println("ree %: " + 100 * (CutEngine.reeTotalTimeSeconds / (knotCuttingSeconds)));
        System.out.println("Clockwork Time: " + CutEngine.clockworkTotalTimeSeconds);
        System.out.println("Clockwork %: " + 100 * (CutEngine.clockworkTotalTimeSeconds / (knotCuttingSeconds)));
        System.out.println("Flatten Time: " + FlattenEngine.flattenTotalTimeSeconds);
        System.out.println("Flatten %: " + 100 * (FlattenEngine.flattenTotalTimeSeconds / (knotCuttingSeconds)));
        System.out.println("Manifold Time: " + ManifoldEngine.cutMatchListTime);
        System.out.println("Manifold %: " + 100 * (ManifoldEngine.cutMatchListTime / (knotCuttingSeconds)));
        System.out.println("Route Map Copy Time: " + ManifoldEngine.routeMapCopyTime);
        System.out.println("Route Map Copy %: " + 100 * (ManifoldEngine.routeMapCopyTime / (knotCuttingSeconds)));
        System.out.println("Ixdar time: " + ixdarSeconds);
        System.out.println("Ixdar %: " + 100 * (ixdarSeconds / (knotCuttingSeconds)));
        System.out.println("Ixdar profile time: " + ixdarProfileSeconds);
        System.out.println("Ixdar Profile %: " + 100 * (ixdarProfileSeconds / (ixdarSeconds)));
        System.out.println("Ixdar profile continue count: " + String.format("%,d", InternalPathEngine.continueCount));
        System.out.println("Ixdar Profile continue %: " + 100 * (((double) InternalPathEngine.continueCount)
                / ((double) (InternalPathEngine.continueCount + InternalPathEngine.noncontinueCount))));
        System.out.println("Saved Answer Length: " + orgShell.getLength());
        System.out.println("Calculated Length: " + tourLength);
        System.out.println("===============================================");

        System.out.println(flattenEngine.flatKnots);
        stickyColor = new ColorRGB(colorSeed.nextFloat(), colorSeed.nextFloat(), colorSeed.nextFloat());
        stickyColor = Color.CYAN;

    }

    public void draw(Camera camera3D) {
        try {
            int wWidth = (int) IxdarWindow.getWidth();
            int wHeight = (int) IxdarWindow.getHeight();
            MAIN_VIEW_WIDTH = wWidth - RIGHT_PANEL_SIZE;
            MAIN_VIEW_HEIGHT = wHeight - BOTTOM_PANEL_SIZE;
            MAIN_VIEW_OFFSET_X = 0;
            MAIN_VIEW_OFFSET_Y = BOTTOM_PANEL_SIZE;
            camera.updateView(MAIN_VIEW_OFFSET_X, MAIN_VIEW_OFFSET_Y, MAIN_VIEW_WIDTH, MAIN_VIEW_HEIGHT);
            float SHIFT_MOD = 1;
            if (keys != null && KeyActions.DoubleSpeed.keyPressed(keys.pressedKeys)) {
                SHIFT_MOD = 2;
            }
            if (keys != null) {
                keys.paintUpdate(SHIFT_MOD);
            }
            if (mouse != null) {
                mouse.paintUpdate(SHIFT_MOD);
            }
            camera.setZIndex(camera3D);
            camera.calculateCameraTransform();

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
            if (tool.canUseToggle(Toggle.DrawCutMatch) && tool.canUseToggle(Toggle.Manifold) && manifolds != null
                    && manifolds.get(manifoldIdx).cutMatchList != null) {
                Manifold m = manifolds.get(manifoldIdx);
                Drawing.drawCutMatch(m.cutMatchList, m.manifoldCutSegment1, m.manifoldCutSegment2, m.manifoldExSegment1,
                        m.manifoldExSegment2, m.manifoldKnot, Drawing.MIN_THICKNESS * 2, retTup.ps, camera);
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
            camera.updateView(wWidth - RIGHT_PANEL_SIZE, 0, RIGHT_PANEL_SIZE, BOTTOM_PANEL_SIZE);
            logo.draw(0, 0, RIGHT_PANEL_SIZE, BOTTOM_PANEL_SIZE, Color.IXDAR, camera);

            camera.updateView(wWidth - RIGHT_PANEL_SIZE, BOTTOM_PANEL_SIZE, RIGHT_PANEL_SIZE,
                    wHeight - BOTTOM_PANEL_SIZE);
            info.draw(camera);

            camera.updateView(0, 0, MAIN_VIEW_WIDTH, BOTTOM_PANEL_SIZE);
            terminal.draw(camera);

            if (toolTip != null && showToolTip) {
                float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
                int isRight = mouse.normalizedPosX > wWidth / 2 ? 1 : 0;

                int isTop = mouse.normalizedPosY > wHeight / 2 ? 1 : 0;
                toolTip.setLineOffsetFromTopRow(camera, 0, 0, rowHeight, Drawing.font);

                float toolTipWidth = toolTip.getWidthPixels();
                int toolTipHeight = toolTip.getHeightPixels();
                camera.updateView((int) (mouse.normalizedPosX - (isRight * toolTipWidth)),
                        (int) mouse.normalizedPosY - (isTop * toolTipHeight), (int) Math.ceil(toolTipWidth),
                        (int) (toolTip.getLines() * rowHeight));

                new ColorBox().draw(Color.DARK_GRAY, camera);
                Drawing.font.drawHyperStringRows(toolTip, 0, 0, rowHeight, camera);
            }
            camera3D.setZIndex(camera);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void drawDisplayedKnots(Camera2D camera) {
        if (knotDrawLayer == totalLayers) {
            if (tool.canUseToggle(Toggle.DrawKnotGradient) && manifoldKnot != null) {
                for (Integer id : knotLayerLookup.keySet()) {
                    if (knotLayerLookup.get(id) == totalLayers) {
                        Knot drawKnot = flattenEngine.flatKnots.get(id);
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
        Color c = Main.stickyColor;
        if (tool.canUseToggle(Toggle.DrawKnotGradient)) {
            c = Main.getKnotGradientColorFlatten((Knot) k);
        } else if (tool.canUseToggle(Toggle.DrawMetroDiagram)) {
            c = Main.getMetroColorFlatten((Knot) k);
        }
        return c;
    }

    public static Color getKnotGradientColor(VirtualPoint displayPoint) {
        Knot smallestKnot = flattenEngine.flatKnots.get(shell.smallestKnotLookup[displayPoint.id]);
        if (smallestKnot == null) {
            return Color.IXDAR;
        }
        return knotGradientColors.get(colorLookup.get((long) smallestKnot.id));
    }

    public static Color getKnotGradientColorFlatten(Knot k) {
        Knot smallestKnot = flattenEngine.flatKnots.get(flattenEngine.knotToFlatKnot.get(k.id));
        if (smallestKnot == null) {
            return Color.IXDAR;
        }
        return knotGradientColors.get(colorLookup.get((long) smallestKnot.id));
    }

    public static Color getMetroColor(VirtualPoint displayPoint, Knot k) {
        if (knotDrawLayer < 0) {
            Knot smallestKnot = flattenEngine.flatKnots.get(shell.smallestKnotLookup[displayPoint.id]);
            return metroColors.get(knotLayerLookup.get(smallestKnot.id));
        } else {
            return metroColors.get(knotLayerLookup.get(k.id));
        }
    }

    public static Color getMetroColorFlatten(Knot thickKnot) {
        Knot smallestKnot = flattenEngine.flatKnots.get(flattenEngine.knotToFlatKnot.get(thickKnot.id));
        if (smallestKnot == null) {
            return Color.IXDAR;
        }
        int knotLayer = knotLayerLookup.get(smallestKnot.id);
        if (knotLayer < 0) {
            return Color.IXDAR;
        }
        return metroColors.get(knotLayer);
    }

    public static ArrayList<Pair<Long, Long>> lookupPairs(Knot k) {

        ArrayList<Pair<Long, Long>> idTransform = new ArrayList<>();
        for (int i = 0; i < k.manifoldSegments.size(); i++) {
            Segment s = k.manifoldSegments.get(i);
            VirtualPoint vp1 = s.first;
            VirtualPoint vp2 = s.last;

            Knot smallestKnot1 = flattenEngine.flatKnots.get(shell.smallestKnotLookup[vp1.id]);

            Knot smallestKnot2 = flattenEngine.flatKnots.get(shell.smallestKnotLookup[vp2.id]);
            idTransform.add(new Pair<Long, Long>((long) smallestKnot1.id, (long) smallestKnot2.id));
        }
        return idTransform;
    }

    public static void calculateSubPaths() {
        try {

            for (int i = 0; i < result.size(); i++) {
                VirtualPoint vp = result.get(i);
                if (vp.isKnot) {
                    System.out.println("Next Knot: " + vp);
                    Shell temp = shell.cutKnot((Knot) vp);
                    System.out.println("Knot: " + temp + " Length: " + temp.getLength());
                    subPaths.add(temp);
                    if (vp.getHeight() > totalLayers) {
                        totalLayers = vp.getHeight();
                    }
                }
                if (vp.isRun) {
                    Run run = (Run) vp;
                    for (VirtualPoint sub : run.knotPoints) {
                        if (sub.isKnot) {
                            System.out.println("Next Knot: " + sub);
                            Shell temp = shell.cutKnot((Knot) sub);
                            subPaths.add(temp);
                            System.out.println("Knot: " + temp + " Length: " + temp.getLength());
                            if (sub.getHeight() > totalLayers) {
                                totalLayers = sub.getHeight();
                            }
                        }

                    }
                }
            }
        } catch (SegmentBalanceException sbe) {
            segmentBalanceExceptionHandler(sbe);
        }
    }

    public static void segmentBalanceExceptionHandler(SegmentBalanceException sbe) {
        Shell result = new Shell();
        if (sbe.topKnot != null) {
            for (VirtualPoint p : sbe.topKnot.knotPoints) {
                result.add(((Point) p).p);
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
                    "ErrorSource: " + ste.getMethodName() + " " + ste.getFileName() + ":" + ste.getLineNumber());
        }
        System.out.println();
        resultShell = result;
    }

    public static void updateKnotsDisplayed() {
        PriorityQueue<ShellPair> newQueue = new PriorityQueue<ShellPair>(new ShellComparator());
        PriorityQueue<ShellPair> metroPathsLayer = Main.metroPathsLayer;
        int size = metroPathsLayer.size();
        knotsDisplayed = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ShellPair temp = metroPathsLayer.remove();
            if (temp.priority == knotDrawLayer) {
                knotsDisplayed.add(temp.k);
            }
            newQueue.add(temp);
        }
        Main.metroPathsLayer = newQueue;
    }

    public static void activate(boolean state) {
        if (state) {
            glfwSetKeyCallback(IxdarWindow.window,
                    (window, key, scancode, action, mods) -> keys.keyCallback(window, key, scancode, action, mods));

            glfwSetCharCallback(IxdarWindow.window, (window, codepoint) -> keys.charCallback(window, codepoint));

            glfwSetMouseButtonCallback(IxdarWindow.window,
                    (window, button, action, mods) -> mouse.clickCallback(window, button, action, mods));

            glfwSetCursorPosCallback(IxdarWindow.window, (window, x, y) -> mouse.moveCallback(window, x, y));

            glfwSetScrollCallback(IxdarWindow.window, (window, x, y) -> mouse.mouseScrollCallback(window, y));
        }
        active = state;
        mouse.active = state;
        keys.active = state;
    }

    public static void setTooltipText(HyperString pointInfo) {
        toolTip = pointInfo;
        showToolTip = true;

    }

    public static void clearTooltipText() {
        toolTip = null;
        showToolTip = false;
    }

    public static void setHoverSegment(Segment segment, Color c) {
        hoverSegment = segment;
        showHoverSegment = true;
        hoverSegmentColor = new ColorLerp(c, Color.TRANSPARENT25, new byte[] { 0, 0, 0, 1 }, 4f);

    }

    public static void clearHoverSegment() {
        hoverSegment = null;
        showHoverSegment = false;
    }

    public static void setHoverKnot(Knot k) {
        hoverKnot = k;
        showHoverKnot = true;
        hoverKnotColor = new ColorLerp(getKnotColor(hoverKnot), Color.TRANSPARENT25, new byte[] { 0, 0, 0, 1 }, 2f);
    }

    public static void clearHoverKnot() {
        hoverKnot = null;
        showHoverKnot = false;
    }

    public static PanelTypes inView(float x, float y) {
        boolean inMainViewRightBound = x < Main.MAIN_VIEW_WIDTH + Main.MAIN_VIEW_OFFSET_X;
        boolean inMainViewLeftBound = x > Main.MAIN_VIEW_OFFSET_X;
        float invY = IxdarWindow.getHeight() - y;
        boolean inMainViewLowerBound = invY > Main.MAIN_VIEW_OFFSET_Y;
        boolean inMainViewUpperBound = invY < Main.MAIN_VIEW_HEIGHT + Main.MAIN_VIEW_OFFSET_Y;
        if (inMainViewLeftBound && inMainViewRightBound && inMainViewLowerBound && inMainViewUpperBound) {
            return PanelTypes.KnotView;
        } else if (!inMainViewLowerBound && !inMainViewRightBound) {
            return PanelTypes.Logo;
        } else if (!inMainViewLowerBound && inMainViewRightBound && inMainViewLeftBound) {
            return PanelTypes.Terminal;
        } else if (inMainViewLowerBound && inMainViewUpperBound && !inMainViewRightBound) {
            return PanelTypes.Info;
        }
        return PanelTypes.None;
    }

    public static void setDrawLevelToKnot(Knot k) {
        Knot smallestKnot = flattenEngine.flatKnots.get(flattenEngine.knotToFlatKnot.get(k.id));
        if (smallestKnot == null) {
            knotDrawLayer = totalLayers;
        } else {
            knotDrawLayer = knotLayerLookup.get(smallestKnot.id);
        }
        updateKnotsDisplayed();
    }

    public static void setDrawLevelMetro() {
        if (Main.knotDrawLayer != -1) {
            Main.knotDrawLayer = -1;
        } else {
            Main.knotDrawLayer = totalLayers;
        }
        updateKnotsDisplayed();
    }

    public static Knot getKnotFlatten(Knot k) {
        Knot smallestKnot = flattenEngine.flatKnots.get(flattenEngine.knotToFlatKnot.get(k.id));
        if (smallestKnot == null) {
            VirtualPoint first = result.get(0);
            if (first.isRun) {
                return k;
            }
            return (Knot) first;
        }
        return smallestKnot;
    }

}
