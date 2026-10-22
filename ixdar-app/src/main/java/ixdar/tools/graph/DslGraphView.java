package ixdar.tools.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.joml.Vector2f;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.runtime.NodeGroup;
import ixdar.geometry.tsp.points.PointND;
import ixdar.geometry.tsp.points.PointSet;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.color.Palettes;
import ixdar.graphics.render.sdf.SDFBezier;
import ixdar.graphics.render.sdf.SDFCircle;
import ixdar.graphics.render.sdf.SDFLine;
import ixdar.graphics.render.sdf.SDFRoundedRect;
import ixdar.graphics.render.text.Font;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.menu.MenuScrollBar;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.Scene;
import ixdar.scenes.model.ModelScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

/**
 * The model scenes' graph tool: draws the node tree of the scene's graph in place of the 3D view,
 * time-tinted boxes, expandable groups, bezier arrows and the critical path, on its own
 * pan-and-zoom {@link Camera2D} with scroll bars.
 */
@ToolAnnotation(id = DslGraphView.TOOL_NAME)
public class DslGraphView extends Tool {
    public static final Palette TINT_BY_SHARE = Palettes.DSL_TIME_SHARE;
    public static final double[] TINT_SHARE_FLOOR = { 0.0, 0.02, 0.1, 0.3 };
    public static final Color BOX_BORDER = Color.BLUE_GREY;
    public static final Color CRITICAL = Color.YELLOW_ORANGE;
    public static final Color EDGE = Color.BLUE_GREY;
    public static final Color PORT = Color.TEAL;
    public static final Color TITLE_TEXT = Color.WHITE;
    public static final Color TYPE_TEXT = Color.TEAL_BLUE_LIGHT;
    public static final Color TIMING_TEXT = Color.YELLOW_ORANGE;
    public static final Color PORT_TEXT = Color.TEAL;
    public static final Color LITERAL_TEXT = Color.BLUE_GREY;

    public static final float MIN_EDGE_PX = 1.5f;
    public static final float FRAME_MARGIN = 0.05f;
    public static final float FOCUS_PADDING_BOXES = 1.5f;
    public static final float CORNER_RADIUS = 8f;
    public static final float BORDER_WIDTH = 1.5f;
    public static final float CRITICAL_BORDER_WIDTH = 3f;
    public static final float EDGE_WIDTH = 1.2f;
    public static final float CRITICAL_EDGE_WIDTH = 2.6f;
    public static final float PORT_RADIUS = 4.5f;
    public static final float CURVE_TANGENT_SHARE = 0.35f;
    public static final float LANE_RAMP = DslGraphLayout.COLUMN_GAP * 0.4f;
    public static final float STRAIGHT_EDGE_PX = 1.5f;
    public static final double ZOOM_PER_SCROLL_UNIT = 1.15;
    public static final float SCROLL_STEP_PX = 40f;
    public static final float SCROLL_PAN_PX_PER_UNIT = 30f;
    public static final int HOTSPOT_COUNT = 10;
    public static final Color HOTSPOT_TEXT = Color.YELLOW_ORANGE;
    public static final Color GROUP_FRAME = Color.TEAL_BLUE_LIGHT;
    public static final Color GROUP_TEXT = Color.TEAL_BLUE_LIGHT;
    public static final float GROUP_BORDER_WIDTH = 2f;
    public static final String TOOL_NAME = "graph";

    /** The scene this view draws over, whose cursor anchors zoom and whose keys pick the mode. */
    public final ModelScene scene;

    /** Whether the tool is active, so its view replaces the scene's 3D view. */
    public boolean active;

    /**
     * Record paths of the slowest leaf nodes of the run, group bodies included, slowest first, at
     * most {@link #HOTSPOT_COUNT}, all timed.
     */
    public String[] hotspotPaths = new String[0];

    /** Index into {@link #hotspotPaths} that {@code [} and {@code ]} last framed, or -1 for none. */
    public int hotspot = -1;

    /** Paths of the group calls shown as their body; kept across runs, so a reload stays open. */
    public final Set<String> expandedGroups = new HashSet<>();

    /** The runtime, its tree root and the runtime's revision {@link #layout} was built from. */
    public NodeGraphRuntime shownGraph;
    public GraphNodeContext shownRoot;
    public int shownRevision;

    /** The "hotspot k/N" label drawn above the framed hotspot's box, and its position. */
    public final ArrayList<HyperString> hotspotLabel = new ArrayList<>();
    public final ArrayList<Vector2f> hotspotLabelPosition = new ArrayList<>();

    /** Graph of the last run, or {@code null} before one has run. */
    public DslGraphLayout layout;

    /**
     * The home frame in layout units: every box and group frame, padded by the margin
     * {@link #frameAll} leaves, so frame-all shows exactly this box along its tighter axis.
     */
    public float homeLeft;
    public float homeBottom;
    public float homeRight;
    public float homeTop;

    /**
     * What the scroll bars and scroll input span, in layout units: the home frame and the view as
     * the last drag-pan step, zoom, focus or frame-all left it; scrolling moves only within it.
     */
    public float rangeLeft;
    public float rangeBottom;
    public float rangeRight;
    public float rangeTop;

    /** Pans and zooms the graph; its point set is the graph's bounding box. */
    public final Camera2D camera;

    /** Bar along the bottom edge: the graph's width against the window's, scrolled by the pan. */
    public final MenuScrollBar horizontalBar;

    /** Bar down the right edge, above the horizontal one: the graph's height against the window's. */
    public final MenuScrollBar verticalBar;

    public final SDFRoundedRect box = new SDFRoundedRect();
    public final SDFBezier curve = new SDFBezier();
    public final SDFLine line = new SDFLine();
    public final SDFCircle port = new SDFCircle();

    /** One single-line string per {@link DslGraphLayout#textString}, in its role's color. */
    public final ArrayList<HyperString> texts = new ArrayList<>();
    public final ArrayList<Vector2f> textPositions = new ArrayList<>();

    /** The bar a press landed on, which takes the drag and release, or {@code null} for a pan. */
    public MenuScrollBar pressedBar;

    private float pressX;
    private float pressY;

    /**
     * Build the view over {@code scene}'s window; the scene hands it the pointer while it is the
     * active tool.
     *
     * @param scene scene whose window the view covers
     */
    public DslGraphView(ModelScene scene) {
        this.scene = scene;
        PointSet bounds = new PointSet();
        bounds.add(new PointND.Double(0.0, 0.0));
        bounds.add(new PointND.Double(1.0, 1.0));
        camera = new Camera2D(Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(),
                1.0f, 0.0f, 0.0f, bounds);
        camera.initCamera(scene.webViews, Scene.DEFAULT_VIEW);
        horizontalBar = new MenuScrollBar(new Bounds(0, 0, 0, 0, "DSL_GRAPH_HORIZONTAL_BAR"), true, SCROLL_STEP_PX);
        verticalBar = new MenuScrollBar(new Bounds(0, 0, 0, 0, "DSL_GRAPH_VERTICAL_BAR"), false, SCROLL_STEP_PX);
        hotspotLabel.add(new HyperString());
        hotspotLabelPosition.add(new Vector2f());
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    /** Take the window's pointer and draw the scene's graph in place of the 3D view. */
    @Override
    public void activate() {
        active = true;
        refresh();
    }

    /** Hand the window back to the scene's view, keeping the zoom, pan, hotspot and expansions. */
    @Override
    public void deactivate() {
        active = false;
    }

    /** Nothing runs per frame; {@link #drawView} follows the scene's graph through {@link #refresh}. */
    @Override
    public void perFrame() {
    }

    /**
     * Every key and gesture of the graph, which replaces the scene's own while the tool is active:
     * {@code [} and {@code ]} cycle hotspots here, not a scene's models.
     *
     * @param controls the scene's control list, after the tool switches
     */
    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint("drag", "pan the graph anywhere; the scroll bars grow to where it is"));
        controls.add(new ControlHint("scroll", "scroll within the graph's frame and the panned-to view: wheel"
                + " and swipe up/down vertically, sideways swipe or shift+wheel horizontally"));
        controls.add(new ControlHint("ctrl+scroll", "zoom about the cursor, smoothly (stands in for pinch)"));
        controls.add(new ControlHint("click", "a box: focus it"));
        controls.add(new ControlHint(Scene.FRAME_ALL_CLICK, "frame the whole graph, as F does", scene::frameAll));
        controls.add(new ControlHint("scroll bars", "drag a thumb, click an arrow or the track to scroll"));
        controls.add(new ControlHint(Keys.E, "E", "expand the group box under the cursor; on a node or frame"
                + " inside a group, collapse it", () -> toggleGroup(null)));
        String selected = hotspot < 0 ? "" : " (hotspot " + (hotspot + 1) + "/" + hotspotPaths.length + ")";
        controls.add(new ControlHint(Keys.RIGHT_BRACKET, "]", "next hotspot, slowest node first, groups"
                + " opened to it" + selected, () -> cycleHotspot(1)));
        controls.add(new ControlHint(Keys.LEFT_BRACKET, "[", "previous hotspot; the scene's own [ ] wait",
                () -> cycleHotspot(-1)));
        controls.add(new ControlHint(Keys.I, true, "ctrl+I", "menu", () -> scene.sceneModelMenu.toggle()));
        controls.add(new ControlHint(Keys.ESCAPE, "esc", scene.escapeDescription(), scene::escapePressed));
    }

    /**
     * The graph draws in place of the scene's 3D view and lists every key itself.
     *
     * @return true
     */
    @Override
    public boolean replacesView() {
        return true;
    }

    @Override
    public void drawView() {
        refresh();
        draw();
    }

    /**
     * Frame the home frame, the whole graph, and shrink the scroll range back to it.
     *
     * @return true: the graph is the tool's own view
     */
    @Override
    public boolean frameAll() {
        refresh();
        if (layout != null) {
            focus(layout.minX, layout.minY, layout.maxX, layout.maxY);
        }
        return true;
    }

    /**
     * Frame the next or previous of {@link #hotspotPaths}, wrapping, expanding the groups around
     * it, and show its rank on its box and in the controls. From no selection, next is the slowest.
     *
     * @param step +1 for the next slower-to-faster hotspot, -1 for the previous
     */
    public void cycleHotspot(int step) {
        refresh();
        int count = hotspotPaths.length;
        if (count == 0) {
            return;
        }
        hotspot = hotspot < 0 ? (step > 0 ? 0 : count - 1) : Math.floorMod(hotspot + step, count);
        String path = hotspotPaths[hotspot];
        boolean opened = false;
        for (GraphNodeContext group = shownGraph.node(path).parent; group != shownRoot; group = group.parent) {
            opened |= expandedGroups.add(group.path);
        }
        if (opened) {
            rebuild();
        }
        focusNode(layout.nodeIndex(path));
        HyperString label = new HyperString();
        label.addWord("hotspot " + (hotspot + 1) + "/" + count, HOTSPOT_TEXT);
        hotspotLabel.set(0, label);
        scene.refreshControls();
    }

    /**
     * Expand a collapsed group box in place and frame its body; on a box or frame inside an
     * expanded group, collapse the innermost such group back to its box and frame that.
     *
     * @param path record path of a box or expanded group, or {@code null} for the box or frame
     *             under the cursor, else the framed hotspot
     */
    public void toggleGroup(String path) {
        refresh();
        if (layout == null) {
            return;
        }
        camera.calculateCameraTransform(camera.ps);
        float x = camera.screenTransformX(scene.mouse.normalizedPosX);
        float y = camera.screenTransformY(scene.mouse.normalizedPosY);
        int node = path == null ? layout.nodeAt(x, y) : layout.nodeIndex(path);
        String collapse = path != null && layout.groupIndex(path) >= 0 ? path : null;
        if (node < 0 && path == null) {
            for (int group = 0; group < layout.groupCount && collapse == null; group++) {
                if (x >= layout.groupFrameX[group] && x <= layout.groupFrameX[group] + layout.groupFrameWidth[group]
                        && y >= layout.groupFrameY[group] && y <= layout.groupFrameY[group] + layout.groupFrameHeight[group]) {
                    collapse = layout.groupPath[group];
                }
            }
            if (collapse == null && hotspot >= 0) {
                node = layout.nodeIndex(hotspotPaths[hotspot]);
            }
        }
        if (node >= 0 && layout.nodeExpandable[node]) {
            String expand = layout.nodeId[node];
            expandedGroups.add(expand);
            rebuild();
            int group = layout.groupIndex(expand);
            focus(layout.groupFrameX[group], layout.groupFrameY[group],
                    layout.groupFrameX[group] + layout.groupFrameWidth[group],
                    layout.groupFrameY[group] + layout.groupFrameHeight[group]);
            return;
        }
        if (node >= 0 && layout.nodeRecord[node].parent != shownRoot) {
            collapse = layout.nodeRecord[node].parent.path;
        }
        if (collapse != null) {
            expandedGroups.remove(collapse);
            rebuild();
            focusNode(layout.nodeIndex(collapse));
        }
    }

    /**
     * Zoom and pan onto one box with {@link #FOCUS_PADDING_BOXES} of its size around it.
     *
     * @param node the node whose box to frame
     */
    public void focusNode(int node) {
        float padX = layout.boxWidth[node] * FOCUS_PADDING_BOXES;
        float padY = layout.boxHeight[node] * FOCUS_PADDING_BOXES;
        focus(layout.boxX[node] - padX, layout.boxY[node] - padY,
                layout.boxX[node] + layout.boxWidth[node] + padX, layout.boxY[node] + layout.boxHeight[node] + padY);
    }

    /**
     * Follow the scene's {@link ModelScene#graph}: when it is another run, or its revision moved
     * since the layout was built (a pull evaluated more of it), lay it out again; a new run also
     * re-ranks the hotspots and frames the graph whole.
     */
    public void refresh() {
        NodeGraphRuntime graph = scene.graph;
        if (graph == null) {
            layout = null;
            shownGraph = null;
            return;
        }
        boolean newRun = graph != shownGraph || graph.root != shownRoot;
        if (!newRun && graph.revision == shownRevision) {
            return;
        }
        shownGraph = graph;
        shownRoot = graph.root;
        shownRevision = graph.revision;
        List<GraphNodeContext> leaves = new ArrayList<>();
        for (GraphNodeContext record : graph.treeRecords()) {
            if (!(record.node instanceof NodeGroup) && record.elapsedMs() > 0) {
                leaves.add(record);
            }
        }
        leaves.sort((first, second) -> Long.compare(second.elapsedNanos, first.elapsedNanos));
        hotspotPaths = new String[Math.min(HOTSPOT_COUNT, leaves.size())];
        for (int rank = 0; rank < hotspotPaths.length; rank++) {
            hotspotPaths[rank] = leaves.get(rank).path;
        }
        if (newRun) {
            hotspot = -1;
        }
        rebuild();
        if (newRun) {
            // The camera's base frame is the first layout of a run, so later rebuilds keep the view.
            PointSet bounds = new PointSet();
            bounds.add(new PointND.Double(layout.minX, layout.minY));
            bounds.add(new PointND.Double(layout.maxX, layout.maxY));
            camera.ps = bounds;
            frameAll();
        }
    }

    /**
     * Lay out {@link #shownRoot} with the current {@link #expandedGroups}, keeping the camera's
     * zoom and pan, and reset the scroll range to the new home frame and the view.
     */
    public void rebuild() {
        layout = new DslGraphLayout(shownRoot, expandedGroups);
        texts.clear();
        textPositions.clear();
        Font font = Drawing.getDrawing().font;
        layout.place(text -> font.fontHeight == 0 ? 0
                : DslGraphLayout.TEXT_HEIGHT / font.fontHeight * font.getWidth(text + " "));
        Color[] roleColors = { TITLE_TEXT, TYPE_TEXT, TIMING_TEXT, PORT_TEXT, PORT_TEXT, LITERAL_TEXT, GROUP_TEXT };
        for (int text = 0; text < layout.textString.length; text++) {
            HyperString string = new HyperString();
            string.addWord(layout.textString[text], roleColors[layout.textRole[text]]);
            texts.add(string);
            textPositions.add(new Vector2f());
        }
        float marginShare = FRAME_MARGIN / (1 - 2 * FRAME_MARGIN);
        float padX = (layout.maxX - layout.minX) * marginShare;
        float padY = (layout.maxY - layout.minY) * marginShare;
        homeLeft = layout.minX - padX;
        homeBottom = layout.minY - padY;
        homeRight = layout.maxX + padX;
        homeTop = layout.maxY + padY;
        resetScrollRange();
    }

    /**
     * Zoom and pan so a layout rectangle fits the window with a margin, centred.
     *
     * @param left layout x of the rectangle's left edge
     * @param bottom layout y of its bottom edge
     * @param right layout x of its right edge
     * @param top layout y of its top edge
     */
    public void focus(float left, float bottom, float right, float top) {
        float viewWidth = scene.paneBounds.viewWidth;
        float viewHeight = scene.paneBounds.viewHeight;
        camera.calculateCameraTransform(camera.ps);
        float pixelsPerUnitAtUnitScale = camera.Width / camera.rangeX;
        camera.ScaleFactor = Math.min(viewWidth / (right - left), viewHeight / (top - bottom))
                * (1 - 2 * FRAME_MARGIN) / pixelsPerUnitAtUnitScale;
        camera.calculateCameraTransform(camera.ps);
        float pixelsPerUnit = pixelsPerUnitAtUnitScale * camera.ScaleFactor;
        camera.PanX = viewWidth / 2 - ((left + right) / 2 - camera.minX) * pixelsPerUnit;
        camera.PanY = viewHeight / 2 - ((bottom + top) / 2 - camera.minY) * pixelsPerUnit;
        camera.calculateCameraTransform(camera.ps);
        resetScrollRange();
    }

    /**
     * Shrink the scroll range to the home frame and the view, as every move of the view but a
     * scroll does: a drag-pan step, a zoom, a focus or frame-all.
     */
    public void resetScrollRange() {
        rangeLeft = homeLeft;
        rangeBottom = homeBottom;
        rangeRight = homeRight;
        rangeTop = homeTop;
        updateScrollBars();
    }

    /**
     * Point the scroll bars at the window's edges and at the scroll range, first grown to hold the
     * view: content is the range's on-screen extent rounded to whole pixels, offset the view's
     * distance into it from its left or top.
     */
    public void updateScrollBars() {
        Bounds pane = scene.paneBounds;
        float barWidth = MenuScrollBar.WIDTH_PIXELS;
        horizontalBar.bounds.update(pane.offsetX, pane.offsetY, pane.viewWidth - barWidth, pane.viewHeight);
        verticalBar.bounds.update(pane.offsetX, pane.offsetY + barWidth, pane.viewWidth, pane.viewHeight - barWidth);
        camera.calculateCameraTransform(camera.ps);
        if (layout == null) {
            return;
        }
        float viewLeft = camera.screenTransformX(0);
        float viewTop = camera.screenTransformY(pane.viewHeight);
        rangeLeft = Math.min(rangeLeft, viewLeft);
        rangeBottom = Math.min(rangeBottom, camera.screenTransformY(0));
        rangeRight = Math.max(rangeRight, camera.screenTransformX(pane.viewWidth));
        rangeTop = Math.max(rangeTop, viewTop);
        // Not pointTransformX(1) - pointTransformX(0): far into a zoom both are ~1e5 px and their
        // float difference is off by tenths of a percent, which a scroll then turns into a jump.
        float pixelsPerUnit = camera.width / camera.rangeX;
        horizontalBar.contentLength = Math.round((rangeRight - rangeLeft) * pixelsPerUnit);
        horizontalBar.visibleLength = pane.viewWidth;
        horizontalBar.scrollOffset = (viewLeft - rangeLeft) * pixelsPerUnit;
        verticalBar.contentLength = Math.round((rangeTop - rangeBottom) * pixelsPerUnit);
        verticalBar.visibleLength = pane.viewHeight;
        verticalBar.scrollOffset = (rangeTop - viewTop) * pixelsPerUnit;
    }

    /**
     * Pan the camera so the view sits in the scroll range where the bars' offsets say, the
     * inverse of {@link #updateScrollBars}.
     */
    public void panToScrollBars() {
        camera.calculateCameraTransform(camera.ps);
        camera.drag(-horizontalBar.scrollOffset - camera.pointTransformX(rangeLeft),
                scene.paneBounds.viewHeight + verticalBar.scrollOffset - camera.pointTransformY(rangeTop));
        camera.calculateCameraTransform(camera.ps);
    }

    /**
     * Draw the graph over the window: arrows, then boxes, ports and text, then the scroll bars,
     * leaving the scene's 2D camera in front of everything drawn.
     */
    public void draw() {
        camera.resetZIndex();
        camera.setZIndex(scene.camera);
        camera.updateView(Scene.DEFAULT_VIEW);
        updateScrollBars();
        if (layout != null) {
            float pixelsPerUnit = camera.width / camera.rangeX;
            for (int group = layout.groupCount - 1; group >= 0; group--) {
                box.draw(camera.pointTransformX(layout.groupFrameX[group]),
                        camera.pointTransformY(layout.groupFrameY[group]),
                        layout.groupFrameWidth[group] * pixelsPerUnit, layout.groupFrameHeight[group] * pixelsPerUnit,
                        CORNER_RADIUS * pixelsPerUnit, GROUP_BORDER_WIDTH * pixelsPerUnit, Color.TRANSPARENT,
                        GROUP_FRAME, camera);
            }
            for (int edge = 0; edge < layout.edgeCount; edge++) {
                boolean critical = layout.edgeCritical[edge];
                float width = Math.max(MIN_EDGE_PX, (critical ? CRITICAL_EDGE_WIDTH : EDGE_WIDTH) * pixelsPerUnit);
                Color color = critical ? CRITICAL : EDGE;
                int sourcePort = layout.edgeSourcePort[edge];
                int targetPort = layout.edgeTargetPort[edge];
                Vector2f from = new Vector2f(camera.pointTransformX(layout.outputPortX[sourcePort]),
                        camera.pointTransformY(layout.outputPortY[sourcePort]));
                Vector2f to = new Vector2f(camera.pointTransformX(layout.inputPortX[targetPort]),
                        camera.pointTransformY(layout.inputPortY[targetPort]));
                float laneY = layout.edgeLaneY[edge];
                if (Float.isNaN(laneY)) {
                    drawSCurve(from, to, width, color);
                    continue;
                }
                float lane = camera.pointTransformY(laneY);
                float ramp = LANE_RAMP * pixelsPerUnit;
                Vector2f laneStart = new Vector2f(from.x + ramp, lane);
                Vector2f laneEnd = new Vector2f(to.x - ramp, lane);
                drawSCurve(from, laneStart, width, color);
                line.setStroke(width, false);
                line.draw(laneStart, laneEnd, color, camera);
                drawSCurve(laneEnd, to, width, color);
            }

            long total = Math.max(1, layout.totalMs);
            boolean[] onPath = new boolean[layout.nodeCount];
            for (int node : layout.criticalPath) {
                onPath[node] = true;
            }
            for (int node = 0; node < layout.nodeCount; node++) {
                double share = layout.nodeMs[node] / (double) total;
                int tint = 0;
                while (tint + 1 < TINT_BY_SHARE.size() && share >= TINT_SHARE_FLOOR[tint + 1]) {
                    tint++;
                }
                float x = camera.pointTransformX(layout.boxX[node]);
                float y = camera.pointTransformY(layout.boxY[node]);
                box.draw(x, y, layout.boxWidth[node] * pixelsPerUnit, layout.boxHeight[node] * pixelsPerUnit,
                        CORNER_RADIUS * pixelsPerUnit,
                        (onPath[node] ? CRITICAL_BORDER_WIDTH : BORDER_WIDTH) * pixelsPerUnit,
                        TINT_BY_SHARE.color(tint), onPath[node] ? CRITICAL : BOX_BORDER, camera);
            }
            float portRadius = PORT_RADIUS * pixelsPerUnit;
            for (int input = 0; input < layout.inputPortX.length; input++) {
                port.draw(new Vector2f(camera.pointTransformX(layout.inputPortX[input]),
                        camera.pointTransformY(layout.inputPortY[input])), portRadius, PORT, camera);
            }
            for (int output = 0; output < layout.outputPortX.length; output++) {
                port.draw(new Vector2f(camera.pointTransformX(layout.outputPortX[output]),
                        camera.pointTransformY(layout.outputPortY[output])), portRadius, PORT, camera);
            }
            for (int text = 0; text < texts.size(); text++) {
                textPositions.get(text).set(camera.pointTransformX(layout.textCenterX[text]),
                        camera.pointTransformY(layout.textCenterY[text]));
            }
            Drawing.getDrawing().font.drawHyperStrings(texts, textPositions,
                    DslGraphLayout.TEXT_HEIGHT * pixelsPerUnit, camera);
            int node = hotspot < 0 ? -1 : layout.nodeIndex(hotspotPaths[hotspot]);
            if (node >= 0) {
                hotspotLabelPosition.get(0).set(camera.pointTransformX(layout.boxX[node] + layout.boxWidth[node] / 2),
                        camera.pointTransformY(layout.boxY[node] + layout.boxHeight[node] + DslGraphLayout.TEXT_HEIGHT));
                Drawing.getDrawing().font.drawHyperStrings(hotspotLabel, hotspotLabelPosition,
                        DslGraphLayout.TEXT_HEIGHT * pixelsPerUnit, camera);
            }
            horizontalBar.draw(camera);
            verticalBar.draw(camera);
            camera.updateView(Scene.DEFAULT_VIEW);
        }
        scene.camera2D.setZIndex(camera);
    }

    /**
     * Draw an arrow leaving {@code from} and entering {@code to} horizontally: two quadratic
     * beziers meeting at the midpoint with a shared tangent, or a straight line when level.
     *
     * @param from start point in screen pixels
     * @param to end point in screen pixels
     * @param width stroke width in pixels
     * @param color stroke color
     */
    private void drawSCurve(Vector2f from, Vector2f to, float width, Color color) {
        if (Math.abs(to.y - from.y) < STRAIGHT_EDGE_PX) {
            line.setStroke(width, false);
            line.draw(from, to, color, camera);
            return;
        }
        float tangent = (to.x - from.x) * CURVE_TANGENT_SHARE;
        Vector2f middle = new Vector2f(from).add(to).mul(0.5f);
        curve.lineWidth = width;
        curve.draw(from, new Vector2f(from.x + tangent, from.y), middle, color, color, camera);
        curve.draw(middle, new Vector2f(to.x - tangent, to.y), to, color, color, camera);
    }

    /**
     * Scroll each bar by {@link #SCROLL_PAN_PX_PER_UNIT} per unit of its own axis, clamped to the
     * scroll range, Shift turning the wheel sideways; with Control held, zoom by
     * {@link #ZOOM_PER_SCROLL_UNIT} to the power of the vertical delta about the cursor.
     *
     * @param deltaX summed horizontal delta, positive toward the left
     * @param deltaY summed vertical delta, positive away from the user
     * @return true: the wheel never reaches the hidden 3D camera
     */
    @Override
    public boolean onScroll(double deltaX, double deltaY) {
        if (layout == null) {
            return true;
        }
        camera.calculateCameraTransform(camera.ps);
        if (!scene.keys.controlMask) {
            boolean sideways = scene.keys.shiftMask && deltaX == 0;
            updateScrollBars();
            horizontalBar.scrollBy((float) (-(sideways ? deltaY : deltaX) * SCROLL_PAN_PX_PER_UNIT));
            verticalBar.scrollBy((float) (-(sideways ? 0 : deltaY) * SCROLL_PAN_PX_PER_UNIT));
            panToScrollBars();
            return true;
        }
        float cursorX = scene.mouse.normalizedPosX;
        float cursorY = scene.mouse.normalizedPosY;
        float graphX = camera.screenTransformX(cursorX);
        float graphY = camera.screenTransformY(cursorY);
        camera.ScaleFactor = (float) Math.max(Camera2D.MIN_SCALE,
                camera.ScaleFactor * Math.pow(ZOOM_PER_SCROLL_UNIT, deltaY));
        camera.calculateCameraTransform(camera.ps);
        camera.drag(cursorX - camera.pointTransformX(graphX), cursorY - camera.pointTransformY(graphY));
        resetScrollRange();
        return true;
    }

    /**
     * Start a pan or a click at the press point, or, on a scroll bar, hand the press to it.
     *
     * @param x    framebuffer x of the press
     * @param y    framebuffer y of the press, y up
     * @param mods modifier-key bitmask, unused: every press pans or clicks the graph
     * @return true: the graph takes every drag, so the hidden 3D camera never moves
     */
    @Override
    public boolean onPress(float x, float y, int mods) {
        pressX = x;
        pressY = y;
        pressedBar = null;
        if (layout == null) {
            return true;
        }
        updateScrollBars();
        for (MenuScrollBar bar : new MenuScrollBar[] { horizontalBar, verticalBar }) {
            if (bar.contains(x, y)) {
                pressedBar = bar;
                bar.press(x, y);
                panToScrollBars();
            }
        }
        return true;
    }

    /**
     * Pan the graph with the cursor, without limit, the scroll range following to the home frame
     * and the view; or move a grabbed scroll bar thumb.
     *
     * @param x framebuffer x of the cursor
     * @param y framebuffer y of the cursor, y up
     */
    @Override
    public void onDrag(float x, float y) {
        if (pressedBar != null) {
            pressedBar.dragTo(x, y);
            panToScrollBars();
            return;
        }
        camera.drag(x - pressX, y - pressY);
        pressX = x;
        pressY = y;
        if (layout != null) {
            resetScrollRange();
        }
    }

    /**
     * Let go of a scroll bar; otherwise a click on a box focuses that box with its neighbourhood,
     * and one on empty space counts toward the scene's double-click, which frames the whole graph.
     *
     * @param x framebuffer x of the release
     * @param y framebuffer y of the release, y up
     * @param click true when the press did not move
     * @return true: the graph took the press, so no overlay link sees it
     */
    @Override
    public boolean onRelease(float x, float y, boolean click) {
        if (pressedBar != null) {
            pressedBar.release();
            pressedBar = null;
            return true;
        }
        if (!click || layout == null) {
            return true;
        }
        camera.calculateCameraTransform(camera.ps);
        int node = layout.nodeAt(camera.screenTransformX(x), camera.screenTransformY(y));
        if (node < 0) {
            scene.emptySpaceClicked(x, y);
            return true;
        }
        focusNode(node);
        return true;
    }
}
