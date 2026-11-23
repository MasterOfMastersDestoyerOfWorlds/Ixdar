package ixdar.scenes.ring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import ixdar.annotations.command.Subsystem;
import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.paths.geodesic.DirectAirRoutes;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.paths.query.SurfacePicker;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RegionClusterFit;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.LineSet;
import ixdar.graphics.render.model.MeshOverlayRuntime;
import ixdar.graphics.render.model.SurfaceLook;
import ixdar.graphics.render.model.SurfaceLook.Colour;
import ixdar.graphics.render.model.SurfaceLook.Light;
import ixdar.graphics.render.text.Font;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.ErrorToasts;
import ixdar.gui.ui.text.HyperString;
import ixdar.models.ModelChoice;
import ixdar.nodegraph.nodes.data.SurfaceCreasesNode;
import ixdar.nodegraph.nodes.data.SurfaceMetricNode;
import ixdar.nodegraph.nodes.selection.RingFitNode;
import ixdar.nodegraph.nodes.selection.SplineRingNode;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.tools.ControlHint;
import ixdar.tools.EditHistory;
import ixdar.tools.Item;
import ixdar.tools.Tool;
import ixdar.tools.connection.ConnectionTool;
import ixdar.tools.regions.RegionLayer;
import ixdar.tools.regions.RingRegionTool;
import ixdar.tools.ring.RingDslWriter;
import ixdar.tools.ring.RingTool;

/**
 * The mesh editing scene: the mesh viewer hosting the orbit, ring,
 * region-select and connection {@link Tool}s over the ring tool's rings.
 * Ctrl+R, Ctrl+T and Ctrl+J pick a tool; Esc goes to the active tool, else back
 * to orbit.
 */
@SceneAnnotation(id = "ring-tool")
public class RingScene extends MeshNodeViewerScene {

    public static final String LOG_PREFIX = "[mesh-edit] ";

    public static final String SAVE_RINGS_ACTION = "save-rings";

    public static final int COORDINATES_PER_POINT = 3;

    public static final float ANCHOR_HIT_RADIUS_PIXELS = 8f;

    public static final float HALF = 0.5f;

    public static final float LABEL_BORDER_PIXELS = 2f;

    public static final float LABEL_ROW_PIXELS = 26f;

    public static final float LABEL_CURSOR_GAP_PIXELS = 14f;

    public static final float ARROW_REACH_IN_EDGES = 3f;

    public static final float ARROW_HEAD_SHARE = 0.35f;

    public static final int PUSH_GROUPS = 9;

    public static final int PUSH_GROUPS_BEFORE_CROWDING = 5;

    public static final int WALL_EDGE_MARK_SLOT = 2;

    public static final float[] CROWDING_GROUP_BOUNDS = { 0.5f, 1f, 2f };

    public static final String TOGGLE_NUMBERS_ACTION = "toggle-numbers";

    public static final String TOGGLE_LABELS_ACTION = "toggle-labels";

    public static final String REGIONS_LOOK = "regions";

    public static final String PARTS_LOOK = "parts";

    public static final String CREASES_LOOK = "creases";

    public static final String ALONG_LOOK = "crease-along";

    public static final String CLUSTER_FIT_LOOK = "cluster-fit";

    public static final String DEBUG_FIT_STATEMENT = "fitted_rings";

    public static final String CAMERA_MOVING_REFUSAL = "the camera is still moving - click again once it stops";

    public static final String PICK_NOT_READY_REFUSAL = "the surface is not ready for picking yet - click again in a moment";

    public static final String MISSED_SURFACE_REFUSAL = "the click missed the mesh - click on the surface";

    public static final String NO_SURFACE_REFUSAL = "no mesh is shown yet - wait for the model to load, then click";

    /**
     * The hover-to-preview ring tool, which holds the rings every tool works over.
     */
    public final RingTool ringTool = new RingTool(this);

    /**
     * The region tool, which selects and extracts the regions the ring tool's rings
     * enclose.
     */
    public final RingRegionTool regionTool = new RingRegionTool(this);

    /**
     * The tool showing how two places are joined and the neck between them, to
     * ring.
     */
    public final ConnectionTool connectionTool = new ConnectionTool(this);

    /**
     * The region colours, shown in the region tool and, switched on, in every tool.
     */
    public final RegionLayer regionLayer = new RegionLayer(this);

    /** Id of the look G picks and back. */
    public String lookBeforeRegions = DEFAULT_LOOK;

    /**
     * Why the tools refused the last clicks and keys, shown at the top left until
     * they fade.
     */
    public final ErrorToasts errorToasts = new ErrorToasts(this);

    /**
     * The ring-labelled masks the graph left, drawn thick and numbered rather than
     * as edges.
     */
    public Map<String, boolean[]> ringMarksByLabel = new LinkedHashMap<>();

    /**
     * Whether every number is drawn, each ring's beside it and each region's on it;
     * N flips it.
     */
    public boolean showNumbers;

    /**
     * Whether every label is drawn, each named region's part name on it, the
     * regions then coloured by part in every tool; P flips it.
     */
    public boolean showLabels;

    /**
     * The shown number of the ring the active tool hovers, drawn beside the cursor
     * instead of on its loop; {@code null} while the tool hovers no numbered ring.
     */
    public HyperString hoveredRingNumber;

    /**
     * Whether the rings are drawn, in every tool, while the regions are assembled;
     * H flips it.
     */
    public boolean showRings = true;

    /**
     * Whether the rings are drawn while the region tool's view is exploded, where
     * they would float at the assembled positions; H flips this one then, and
     * collapsing returns to {@link #showRings}.
     */
    public boolean showRingsExploded;

    /** Surface point under the cursor at the active tool's last {@link #pickCursor}. */
    public Vector3f cursorPoint = new Vector3f();

    /**
     * The labels drawn this frame over the surface: ring numbers and region labels
     * as shown.
     */
    public final List<HyperString> shownLabels = new ArrayList<>();

    /**
     * Whether the camera's view is the one the last frame had: labels keep their
     * anchors while it moves and are placed afresh once it holds still.
     */
    public boolean cameraStill;

    /** Direction of the view ray through the cursor at the last pick. */
    public Vector3f cursorRayDirection = new Vector3f();

    /** Face the last pick hit, or -1 when it missed or the camera was moving. */
    public int cursorFaceId = -1;

    /**
     * Corner of {@link #cursorFaceId} nearest {@link #cursorPoint}, the vertex a
     * click anchors to.
     */
    public int cursorVertexId = -1;

    /**
     * Why the last {@link #pickCursor} found no surface point, as a refusal says
     * it, or empty.
     */
    public String cursorMissReason = "";

    /** Framebuffer pixel the last pick read, x. */
    public int cursorFramebufferX;

    /** Framebuffer pixel the last pick read, y. */
    public int cursorFramebufferY;

    /**
     * The clustering fit the cluster-fit look shows, run on the loaded rings when
     * first shown.
     */
    public RegionClusterFit clusterFit;

    /**
     * The bundle {@link #clusterFit} was run on; another bundle shown means the fit
     * is stale.
     */
    public GeometryBundle clusterFitBundle;

    /** The pass of {@link #clusterFit} the look shows, from 0. */
    public int clusterFitPass;

    /**
     * The fit's pushes at the shown pass, in five line groups: walls, seams, arrows where shares
     * of air decide, arrows where pressure decides, ticks where a wall holds. Null before a fit.
     */
    public LineSet pushLines;

    /** First segment of each group of {@link #pushLines}, plus a closing total. */
    public int[] pushGroupStart;

    /** The pass {@link #pushLines} was built for, -1 for none. */
    public int pushLinesPass = -1;

    /** Whether the overlay holds {@link #pushLines} in place of the ring tool's lines. */
    public boolean pushLinesShown;

    private final SurfacePicker cursorPicker = new SurfacePicker();
    private final Matrix4f lastFrameView = new Matrix4f();
    private final Vector3f cursorRayOrigin = new Vector3f();
    private final Vector3f anchorPixel = new Vector3f();
    private final Vector3f anchorPosition = new Vector3f();

    /**
     * Host the ring, region and connection tools on ctrl+R, ctrl+T and ctrl+J,
     * after the model scene's.
     */
    @Override
    public void setTools() {
        super.setTools();
        addToolSwitch(ringTool, new ControlHint("ring-tool", Keys.R, true, "draw and edit rings",
                () -> switchTool(ringTool)));
        addToolSwitch(regionTool, new ControlHint("region-tool", Keys.T, true,
                RingRegionTool.TOOL_PURPOSE, () -> switchTool(regionTool)));
        addToolSwitch(connectionTool, new ControlHint("connection-tool", Keys.J, true,
                "find the neck joining two places", () -> switchTool(connectionTool)));
    }

    @Override
    public String windowTitle() {
        return "Ixdar : Mesh Edit";
    }

    /**
     * The model scene's looks, then the region and part colours and the two crease
     * looks.
     *
     * @param looks the scene's looks
     */
    @Override
    public void setLooks(List<SurfaceLook> looks) {
        super.setLooks(looks);
        looks.add(new SurfaceLook(REGIONS_LOOK, "each ring region in its own colour, in every tool", Colour.SURFACE,
                Light.VIEW, null));
        looks.add(new SurfaceLook(PARTS_LOOK, "each region in its part's colour, unlabelled ones grey", Colour.SURFACE,
                Light.VIEW, null));
        looks.add(new SurfaceLook(CREASES_LOOK, "groove strength, the field crease rings follow", Light.SOFT_CLAY,
                () -> shownCreases() == null ? null : shownCreases().strengthField()));
        looks.add(new SurfaceLook(ALONG_LOOK,
                "bend along the groove: low convex (a saddle-shaped groove, as round a limb), mid flat, high concave",
                Light.SOFT_CLAY,
                () -> shownCreases() == null ? null : shownCreases().alongBendField(), -1f, 1f));
        looks.add(new SurfaceLook(CLUSTER_FIT_LOOK, "the region clustering fit of the loaded rings at the shown pass: "
                + "each region its own shade, the rings darkest; Y steps a pass on, shift+Y back", Light.SOFT_CLAY,
                this::clusterFitField));
    }

    /**
     * The clustering fit's regions and rings at {@link #clusterFitPass}, per active
     * vertex: each vertex its first face's region, a vertex on a ring -1. The fit
     * runs on the loaded graph's rings the first time the look asks.
     *
     * @return the field, or {@code null} while the loaded graph carries no rings on
     *         the shown surface
     */
    private float[] clusterFitField() {
        MeshTopology surface = halfEdgeSurface();
        // A fit of another bundle (a model loaded or rings edited since) is stale: fit
        // again.
        if (graph != null && surface != null
                && graph.lastOutput(NodeGraphRuntime.GEOMETRY) instanceof GeometryBundle bundle
                && bundle.mesh() == surface && (clusterFit == null || clusterFitBundle != bundle)) {
            clusterFit = null;
            clusterFitBundle = bundle;
            pushLinesPass = -1;
            // Ring DSLs name their surface_creases statement after its port; without one,
            // measure.
            String port = SurfaceCreasesNode.CREASES.name;
            SurfaceCreases creases = graph.getNodeOutput(port, port) instanceof SurfaceCreases measured
                    ? measured
                    : SurfaceCreases.of(surface);
            try {
                clusterFit = RingFitNode.fitOf(bundle, creases);
            } catch (IllegalArgumentException noRings) {
                activeTool.lastRow = noRings.getMessage();
                return null;
            }
            clusterFit.recordPasses = true;
            clusterFit.run();
            clusterFitPass = clusterFit.loopsByPass.size() - 1;
            activeTool.lastRow = "clustering fit: " + clusterFit.passes + " passes, " + clusterFit.moves
                    + " ring moves, " + clusterFit.refusals + " moves refused";
        }
        if (clusterFit == null || clusterFit.mesh != surface || clusterFit.loopsByPass.isEmpty()) {
            // No fit of this surface: the pushes drawn belong to another model's.
            pushLines = null;
            pushLinesPass = -1;
            return null;
        }
        int shownPass = Math.max(0, Math.min(clusterFitPass, clusterFit.loopsByPass.size() - 1));
        int[][] snapshot = clusterFit.loopsByPass.get(shownPass);
        if (clusterFit.airFlow && pushLinesPass != shownPass) {
            pushLinesPass = shownPass;
            buildPushLines(surface, snapshot, shownPass);
        }
        int[] regionOfFace = snapshot[snapshot.length - 1];
        boolean[] onRing = new boolean[surface.vertexIdBound()];
        for (int ring = 0; ring < snapshot.length - 1; ring++) {
            for (int edge : snapshot[ring]) {
                int halfEdge = surface.edgeHalfEdge(edge);
                onRing[surface.halfEdgeVertex(halfEdge)] = true;
                onRing[surface.halfEdgeEndVertex(halfEdge)] = true;
            }
        }
        float[] field = new float[surface.vertexCount()];
        for (int active = 0; active < field.length; active++) {
            int vertex = surface.vertexIdAt(active);
            field[active] = onRing[vertex] || surface.vertexFaceCount(vertex) == 0 ? -1
                    : regionOfFace[surface.vertexFaceAt(vertex, 0)];
        }
        return field;
    }

    /**
     * Draws a {@code ring_fit} the debugger is stepping in the cluster-fit look, at its newest
     * pass; anything else goes to the viewer, which keeps that fit while its surface shows.
     *
     * @param value   a node's output, or the fit a {@code ring_fit} is stepping
     * @param overlay what the node's last inner step changed, which the fit's look does not need
     * @return false when the scene cannot draw that kind of value
     */
    @Override
    public boolean showGraphValue(Object value, Object overlay) {
        if (!(value instanceof RegionClusterFit stepped)) {
            boolean shown = super.showGraphValue(value, overlay);
            if (shown && clusterFit != null && clusterFit.mesh == halfEdgeSurface()) {
                clusterFitBundle = (GeometryBundle) value;
            }
            return shown;
        }
        super.showGraphValue(stepped.drawn, null);
        clusterFit = stepped;
        clusterFitBundle = graph.lastOutput(NodeGraphRuntime.GEOMETRY) instanceof GeometryBundle last ? last : null;
        clusterFitPass = stepped.loopsByPass.size() - 1;
        pushLinesPass = -1;
        if (!looks.isPicked(CLUSTER_FIT_LOOK)) {
            setLook(CLUSTER_FIT_LOOK, headlight);
        }
        rereadField();
        return true;
    }

    /**
     * The file's statements, then a breakpoint and a {@code ring_fit} of its rings when it draws
     * rings and fits none, so the look's fit can be stepped.
     *
     * @param choice the open graph model
     * @throws IllegalArgumentException when the graph's file cannot be read
     * @return the parsed graph, not executed
     */
    @Override
    public NodeGraphRuntime debugGraph(ModelChoice choice) {
        String source;
        try {
            source = choice.readSource();
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("cannot read graph " + choice.path, unreadable);
        }
        NodeGraphRuntime parsed = NodeGraphRuntime.fromSource(source);
        String metric = null;
        String creases = null;
        boolean rings = false;
        for (GraphNodeContext statement : parsed.root.declared) {
            if (statement.node instanceof RingFitNode) {
                return parsed;
            }
            metric = statement.node instanceof SurfaceMetricNode ? statement.nodeAssignmentId() : metric;
            creases = statement.node instanceof SurfaceCreasesNode ? statement.nodeAssignmentId() : creases;
            rings |= statement.node instanceof SplineRingNode;
        }
        if (metric == null || !rings) {
            return parsed;
        }
        return NodeGraphRuntime.fromSource(source + "\nbreakpoint(\"before the ring fit\")\n" + DEBUG_FIT_STATEMENT
                + " = " + RingFitNode.ID + '(' + RingFitNode.GEOMETRY.name + '=' + parsed.lastId() + '.'
                + NodeGraphRuntime.GEOMETRY + ", " + RingFitNode.METRIC.name + '=' + metric + '.'
                + SurfaceMetricNode.METRIC.name + (creases == null ? "" : ", " + RingFitNode.CREASES.name + '='
                        + creases + '.' + SurfaceCreasesNode.CREASES.name)
                + ")\n");
    }

    /**
     * Build {@link #pushLines} for one pass of the fit: stand the fit at the pass, then draw its
     * walls, its seams, and what pushes each seam vertex.
     *
     * @param surface  the fitted surface
     * @param snapshot the pass's seams, one array of edge ids per ring, then the regions
     * @param pass     the pass's index
     */
    private void buildPushLines(MeshTopology surface, int[][] snapshot, int pass) {
        clusterFit.standAtPass(pass);
        boolean[] wall = clusterFit.closedWalls ? clusterFit.closedBottom : clusterFit.grooveBottom;
        List<Integer> wallEdges = new ArrayList<>();
        // Graded air has no walls to draw: every crease slows it, none stops it.
        for (int active = 0; !clusterFit.gradedWalls && active < surface.edgeCount(); active++) {
            int edge = surface.edgeIdAt(active);
            int halfEdge = surface.edgeHalfEdge(edge);
            if (wall[surface.halfEdgeVertex(halfEdge)] && wall[surface.halfEdgeEndVertex(halfEdge)]) {
                wallEdges.add(edge);
            }
        }
        List<List<Vector3f[]>> marks = new ArrayList<>();
        for (int group = 2; group < PUSH_GROUPS; group++) {
            marks.add(new ArrayList<>());
        }
        float reach = (float) surface.meanEdgeLength();
        int seamEdges = 0;
        for (int ring = 0; ring < snapshot.length - 1; ring++) {
            seamEdges += snapshot[ring].length;
            for (double[] row : clusterFit.pushesAlong(ring)) {
                int vertex = (int) row[0];
                Vector3f at = surface.vertexPosition(vertex, new Vector3f());
                Vector3f normal = surface.faceNormal(surface.vertexFaceAt(vertex, 0), new Vector3f());
                Vector3f[] into = new Vector3f[2];
                for (int side = 0; side < into.length; side++) {
                    int from = RegionClusterFit.PUSH_ROW_BEFORE_DIRECTIONS + COORDINATES_PER_POINT * side;
                    into[side] = new Vector3f((float) row[from], (float) row[from + 1], (float) row[from + 2]);
                }
                if (row[COORDINATES_PER_POINT] > 0) {
                    // Held: a tick across the seam.
                    marks.get(2).add(new Vector3f[] { new Vector3f(into[0]).mul(HALF * reach).add(at),
                            new Vector3f(into[1]).mul(HALF * reach).add(at), normal });
                    continue;
                }
                double harder = Math.max(row[1], row[2]);
                if (row[1] == row[2] || harder <= 0) {
                    continue;
                }
                // The seam gives way into the side that pushes less; the arrow is longer the more
                // one-sided the push.
                Vector3f way = into[row[1] < row[2] ? 0 : 1];
                float length = reach * (1 + ARROW_REACH_IN_EDGES * (float) (Math.abs(row[1] - row[2]) / harder));
                Vector3f tip = new Vector3f(way).mul(length).add(at);
                Vector3f across = new Vector3f(normal).cross(way).normalize().mul(ARROW_HEAD_SHARE * HALF * length);
                Vector3f neck = new Vector3f(way).mul(length * (1 - ARROW_HEAD_SHARE)).add(at);
                List<Vector3f[]> arrows = marks.get(row[COORDINATES_PER_POINT + 1] > 0 ? 0 : 1);
                arrows.add(new Vector3f[] { at, tip, normal });
                arrows.add(new Vector3f[] { tip, new Vector3f(neck).add(across), normal });
                arrows.add(new Vector3f[] { tip, new Vector3f(neck).sub(across), normal });
            }
        }
        // The air itself: an arrow on every face the way its air drifts, longer where it drifts
        // faster (by the square root, so the slow middle of a region still shows).
        // Direct routes: an arrow from every face's centre to the edge its air leaves by, in a
        // group by how crowded that way out is.
        DirectAirRoutes routes = clusterFit.routes;
        for (int active = 0; routes != null && active < surface.faceCount(); active++) {
            int face = surface.faceIdAt(active);
            if (routes.exitEdge[face] < 0) {
                continue;
            }
            Vector3f centre = new Vector3f();
            for (int at = 0; at < surface.faceEdgeCount(face); at++) {
                centre.add(surface.vertexPosition(
                        surface.halfEdgeVertex(surface.edgeHalfEdge(surface.faceEdgeAt(face, at))), new Vector3f()));
            }
            centre.div(surface.faceEdgeCount(face));
            int exit = surface.edgeHalfEdge(routes.exitEdge[face]);
            Vector3f tip = surface.vertexPosition(surface.halfEdgeVertex(exit), new Vector3f())
                    .add(surface.vertexPosition(surface.halfEdgeEndVertex(exit), new Vector3f())).mul(HALF);
            Vector3f normal = surface.faceNormal(face, new Vector3f());
            Vector3f way = new Vector3f(tip).sub(centre);
            Vector3f across = new Vector3f(normal).cross(way).mul(ARROW_HEAD_SHARE * HALF);
            Vector3f neck = new Vector3f(way).mul(-ARROW_HEAD_SHARE).add(tip);
            int crowd = 0;
            for (float bound : CROWDING_GROUP_BOUNDS) {
                crowd += routes.crowding[face] >= bound ? 1 : 0;
            }
            List<Vector3f[]> arrows = marks.get(COORDINATES_PER_POINT + crowd);
            arrows.add(new Vector3f[] { centre, tip, normal });
            arrows.add(new Vector3f[] { tip, new Vector3f(neck).add(across), normal });
            arrows.add(new Vector3f[] { tip, new Vector3f(neck).sub(across), normal });
        }
        float[] drift = routes == null ? clusterFit.airDrift : new float[0];
        float fastest = 0;
        for (int at = 0; at < drift.length; at += COORDINATES_PER_POINT) {
            fastest = Math.max(fastest, new Vector3f(drift[at], drift[at + 1], drift[at + 2]).length());
        }
        for (int active = 0; active < surface.faceCount() && fastest > 0; active++) {
            int face = surface.faceIdAt(active);
            int from = COORDINATES_PER_POINT * face;
            Vector3f way = new Vector3f(drift[from], drift[from + 1], drift[from + 2]);
            float speed = way.length();
            if (speed <= 0) {
                continue;
            }
            Vector3f centre = new Vector3f();
            for (int at = 0; at < surface.faceEdgeCount(face); at++) {
                centre.add(surface.vertexPosition(
                        surface.halfEdgeVertex(surface.edgeHalfEdge(surface.faceEdgeAt(face, at))), new Vector3f()));
            }
            centre.div(surface.faceEdgeCount(face));
            Vector3f normal = surface.faceNormal(face, new Vector3f());
            float length = reach * (float) Math.sqrt(speed / fastest);
            way.div(speed);
            Vector3f tail = new Vector3f(way).mul(-HALF * length).add(centre);
            Vector3f tip = new Vector3f(way).mul(HALF * length).add(centre);
            Vector3f across = new Vector3f(normal).cross(way).normalize().mul(ARROW_HEAD_SHARE * HALF * length);
            Vector3f neck = new Vector3f(way).mul(-ARROW_HEAD_SHARE * length).add(tip);
            marks.get(COORDINATES_PER_POINT).add(new Vector3f[] { tail, tip, normal });
            marks.get(COORDINATES_PER_POINT).add(new Vector3f[] { tip, new Vector3f(neck).add(across), normal });
            marks.get(COORDINATES_PER_POINT).add(new Vector3f[] { tip, new Vector3f(neck).sub(across), normal });
        }
        int markCount = 0;
        for (List<Vector3f[]> group : marks) {
            markCount += group.size();
        }
        LineSet lines = new LineSet(wallEdges.size() + seamEdges + markCount);
        for (int edge : wallEdges) {
            lines.edge(surface, edge);
        }
        for (int ring = 0; ring < snapshot.length - 1; ring++) {
            for (int edge : snapshot[ring]) {
                lines.edge(surface, edge);
            }
        }
        pushGroupStart = new int[PUSH_GROUPS + 1];
        pushGroupStart[1] = wallEdges.size();
        pushGroupStart[2] = wallEdges.size() + seamEdges;
        for (int group = 0; group < marks.size(); group++) {
            for (Vector3f[] mark : marks.get(group)) {
                lines.segment(mark[0], mark[1], mark[2], mark[2]);
            }
            pushGroupStart[group + COORDINATES_PER_POINT] = pushGroupStart[group + 2] + marks.get(group).size();
        }
        pushLines = lines;
    }

    /**
     * Step the clustering fit's shown pass and show it.
     *
     * @param step passes to step, negative for back
     */
    private void stepClusterFitPass(int step) {
        clusterFitPass = clusterFit == null ? 0
                : Math.max(0, Math.min(clusterFitPass + step,
                        clusterFit.loopsByPass.size() - 1));
        setLook(CLUSTER_FIT_LOOK, headlight);
        rereadField();
        if (clusterFit != null) {
            activeTool.lastRow = "clustering fit pass " + (clusterFitPass + 1) + " of " + clusterFit.loopsByPass.size();
        }
    }

    /**
     * The ring tool's one crease lookup on the shown surface, measured the first
     * time a crease look asks.
     *
     * @return the creases, or {@code null} while there is no surface or they cannot
     *         be measured
     */
    private SurfaceCreases shownCreases() {
        MeshTopology surface = halfEdgeSurface();
        return surface != null && ringTool.readyCreases(surface) ? ringTool.creases : null;
    }

    /**
     * Whether the picked look is one of the region looks, which colour the regions
     * in every tool.
     *
     * @return true while regions or parts is picked
     */
    public boolean regionLookPicked() {
        return looks.isPicked(REGIONS_LOOK) || looks.isPicked(PARTS_LOOK);
    }

    /**
     * Draw the surface through a runtime that also draws general overlays, since
     * the rings are point sets and coloured line groups over it; it also tells the
     * camera what hides a point, for the ring numbers anchored in 3D.
     *
     * @return the installed overlay runtime
     */
    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        meshRuntime = new MeshOverlayRuntime();
        camera.pointVisibility = meshRuntime;
        return meshRuntime;
    }

    /**
     * Run one frame of every tool and the region layer, then gather the labels
     * shown: ring numbers while numbered and shown, the hovered ring's set aside
     * for the cursor, and region labels while the regions are assembled and
     * current.
     */
    @Override
    public void updateScene() {
        super.updateScene();
        cameraStill = camera.view.equals(lastFrameView);
        lastFrameView.set(camera.view);
        regionLayer.perFrame();
        shownLabels.clear();
        hoveredRingNumber = null;
        if (showNumbers && ringsShown()) {
            Item hovered = activeTool.hovered();
            for (HyperString number : ringTool.ringNumbers) {
                if (hovered != null && hovered.label.equals(String.valueOf(number.getData()))) {
                    hoveredRingNumber = number;
                } else {
                    shownLabels.add(number);
                }
            }
        }
        if (!regionLayer.explosion.exploded() && !regionTool.showingExtraction
                && regionLayer.regions != null && !regionLayer.building()) {
            shownLabels.addAll(regionLayer.regionLabels);
        }
    }

    /**
     * Why a tool cannot read the face under the cursor now: the camera is moving,
     * the view itself refuses picks ({@link #pickRefusal()}), or the surface's
     * face-pick buffer is not uploaded.
     *
     * @param runtime the surface's runtime, or {@code null} before one is created
     * @return the refusal a click gets, or empty when a pick can run
     */
    public String cursorPickRefusal(HalfEdgeMeshRuntime runtime) {
        if (cameraMoving()) {
            return CAMERA_MOVING_REFUSAL;
        }
        String viewRefusal = pickRefusal();
        if (!viewRefusal.isEmpty()) {
            return viewRefusal;
        }
        return runtime == null || !runtime.facePickReady() ? PICK_NOT_READY_REFUSAL : "";
    }

    /**
     * Pick the surface under the cursor for the active tool: the face from the GPU
     * id buffer, the point the view ray hits on it and the face corner nearest that
     * point. Nothing is picked while the camera moves; {@link #cursorMissReason}
     * says why nothing was.
     *
     * @param runtime the surface's runtime, its face-pick buffer uploaded
     * @param surface the shown surface
     * @return true when {@link #cursorVertexId} names a vertex under the cursor
     */
    public boolean pickCursor(HalfEdgeMeshRuntime runtime, MeshTopology surface) {
        cursorFaceId = -1;
        cursorVertexId = -1;
        cursorMissReason = cursorPickRefusal(runtime);
        int width = Platforms.get().getWindowWidth();
        int height = Platforms.get().getWindowHeight();
        cursorFramebufferX = width <= 0 ? 0
                : Math.round(mouse.lastX * (float) Platforms.get().getFrameBufferWidth()
                        / width);
        cursorFramebufferY = height <= 0 ? 0
                : Math.round(mouse.lastY * (float) Platforms.get().getFrameBufferHeight()
                        / height);
        int faceIndex = !cursorMissReason.isEmpty() ? -1
                : runtime.faceIndexAtPixel(camera, cursorFramebufferX, cursorFramebufferY);
        if (faceIndex < 0 || faceIndex >= surface.faceCount()
                || !runtime.rayThroughPixel(camera, cursorFramebufferX, cursorFramebufferY,
                        cursorRayOrigin, cursorRayDirection)
                || !cursorPicker.pickNear(surface, surface.faceIdAt(faceIndex), cursorRayOrigin,
                        cursorRayDirection)) {
            cursorMissReason = cursorMissReason.isEmpty() ? MISSED_SURFACE_REFUSAL
                    : cursorMissReason;
            return false;
        }
        cursorFaceId = cursorPicker.faceId;
        cursorPoint = cursorPicker.point;
        cursorVertexId = cursorPicker.nearestCornerVertexId(surface);
        return cursorVertexId >= 0;
    }

    /**
     * The viewer's refusal, or the region tool's view when it does not draw the
     * surface where it lies: an exploded view or a shown extraction.
     *
     * @return the reason, or empty when the shown surface can be picked
     */
    @Override
    public String pickRefusal() {
        if (regionTool.showingExtraction) {
            return "the region tool shows an extraction; E returns to the surface";
        }
        if (regionLayer.explosion.exploded()) {
            return "collapse the exploded view (X in the region tool, or xp 0) to pick the surface";
        }
        return super.pickRefusal();
    }

    /**
     * The anchor the cursor is over at the last pick: the nearest whose projection
     * lies within {@link #ANCHOR_HIT_RADIUS_PIXELS} of the cursor and which the
     * surface does not hide.
     *
     * @param runtime        the surface's runtime, which projects anchors to pixels
     * @param surface        the shown surface
     * @param anchorVertexId the anchors, each a vertex of {@code surface}
     * @return the anchor's index in {@code anchorVertexId}, or -1 for none
     */
    public int anchorUnderCursor(HalfEdgeMeshRuntime runtime, MeshTopology surface,
            int[] anchorVertexId) {
        if (cursorVertexId < 0) {
            return -1;
        }
        int hovered = -1;
        double nearestPixels = Double.POSITIVE_INFINITY;
        double eyeToHit = camera.position.distance(cursorPoint);
        double depthReach = ANCHOR_HIT_RADIUS_PIXELS * worldPerPixel();
        for (int anchor = 0; anchor < anchorVertexId.length; anchor++) {
            int vertexId = anchorVertexId[anchor];
            surface.vertexPosition(vertexId, anchorPosition);
            boolean onCursorFace = false;
            for (int corner = 0; corner < surface.faceVertexCount(cursorFaceId); corner++) {
                onCursorFace |= surface.faceVertexAt(cursorFaceId, corner) == vertexId;
            }
            Vector3f projected = !onCursorFace && camera.position.distance(anchorPosition) > eyeToHit + depthReach
                    ? null : runtime.projectToPixels(camera, anchorPosition);
            if (projected == null) {
                continue;
            }
            anchorPixel.set(projected);
            double pixels = Math.hypot(Math.floor(anchorPixel.x) - cursorFramebufferX,
                    Math.floor(anchorPixel.y) - cursorFramebufferY);
            if (pixels <= ANCHOR_HIT_RADIUS_PIXELS && pixels < nearestPixels) {
                nearestPixels = pixels;
                hovered = anchor;
            }
        }
        return hovered;
    }

    /**
     * Model units one screen pixel spans at the cursor's surface point, the scale a
     * pixel reach is in.
     *
     * @return the span, or 0 before the framebuffer has a size
     */
    public double worldPerPixel() {
        return camera.worldPerPixel(cursorPoint);
    }

    /**
     * Draw the surface, then the ring overlays and their markers over it; while the
     * region tool shows an extraction, draw that mesh on its own instead.
     */
    @Override
    public void renderScene() {
        if (regionTool.showingExtraction && regionTool.extractedRuntime != null) {
            camera.resetView();
            regionTool.extractedRuntime.render(camera);
            return;
        }
        super.renderScene();
        if (surfaceRuntime() instanceof MeshOverlayRuntime overlay) {
            // The fit's look draws its walls, seams and pushes where the ring tool draws its rings.
            boolean pushes = looks.isPicked(CLUSTER_FIT_LOOK) && pushLines != null;
            if (pushes) {
                Color[] groupColors = new Color[PUSH_GROUPS];
                System.arraycopy(palette.categories, 0, groupColors, 0, PUSH_GROUPS_BEFORE_CROWDING);
                System.arraycopy(palette.scalar, 0, groupColors, PUSH_GROUPS_BEFORE_CROWDING,
                        PUSH_GROUPS - PUSH_GROUPS_BEFORE_CROWDING);
                // The walls are the first group, in the edge marks' pink, which no arrow or seam shares.
                groupColors[0] = Palette.entry(palette.edgeMark, WALL_EDGE_MARK_SLOT);
                overlay.setLineGroups(pushLines, pushGroupStart, groupColors);
            } else if (pushLinesShown) {
                ringTool.invalidateRings();
                ringTool.uploadOverlay();
            }
            pushLinesShown = pushes;
            overlay.showLineGroups = pushes || ringsShown();
            overlay.renderOverlays(camera);
            overlay.renderHighlights(camera);
        }
    }

    /**
     * Drop the confirmed rings before the switch lands, since they describe a
     * surface that is about to leave the screen.
     */
    @Override
    public void applyPendingModel() {
        if (pendingModel != null) {
            ringTool.discardConfirmedRings("loading " + pendingModel.path);
        }
        super.applyPendingModel();
    }

    /**
     * Draw the labels {@link #updateScene} gathered at their anchors, each ring
     * number hidden where the surface is in front of it, and the hovered ring's
     * number just right of the cursor; then the refusals at the top left.
     */
    @Override
    public void drawSceneOverlayText() {
        if (surfaceRuntime() != null) {
            if (showNumbers && ringsShown()) {
                ringTool.placeRingNumbers(camera);
            }
            Font font = Drawing.getDrawing().font;
            font.drawAnchoredStrings(shownLabels, LABEL_ROW_PIXELS, camera2D);
            if (hoveredRingNumber != null) {
                float halfWidth = HALF * LABEL_ROW_PIXELS / font.fontHeight
                        * font.getWidth(hoveredRingNumber.plainLines().get(0));
                font.drawHyperString(hoveredRingNumber, mouse.normalizedPosX + LABEL_CURSOR_GAP_PIXELS
                        + halfWidth, mouse.normalizedPosY, LABEL_ROW_PIXELS, camera2D);
            }
        }
        errorToasts.draw(camera2D, 0f);
    }

    /**
     * Peel the graph's ring labels into the thick numbered overlay and leave every
     * other label to the viewer's feature-edge drawing.
     *
     * @param marksByLabel edge-id-indexed masks by label; empty leaves the view
     *                     untouched
     */
    @Override
    public void showEdgeMarks(Map<String, boolean[]> marksByLabel) {
        HalfEdgeMeshRuntime runtime = surfaceRuntime();
        if (runtime == null || halfEdgeSurface() == null) {
            return;
        }
        if (marksByLabel.isEmpty()) {
            if (!ringMarksByLabel.isEmpty()) {
                ringMarksByLabel = new LinkedHashMap<>();
                ringTool.graphRingsChanged();
            }
            return;
        }
        ringMarksByLabel = new LinkedHashMap<>();
        Map<String, boolean[]> featureMarks = new LinkedHashMap<>();
        for (Map.Entry<String, boolean[]> entry : marksByLabel.entrySet()) {
            if (RingTool.isRingLabel(entry.getKey())) {
                ringMarksByLabel.put(entry.getKey(), entry.getValue());
            } else {
                featureMarks.put(entry.getKey(), entry.getValue());
            }
        }
        ringTool.graphRingsChanged();
        Platforms.get().log(RingTool.LOG_PREFIX + "ring overlay: " + ringMarksByLabel.keySet());
        if (featureMarks.isEmpty()) {
            runtime.setFeatureEdgeOverlay(List.of());
            return;
        }
        super.showEdgeMarks(featureMarks);
    }

    /**
     * Write the rings the tool holds, then the region tool's part labels as the
     * .dsl's closing block of label statements. Ctrl+S or a click on the menu row
     * reaches here; a bare S does not, and nothing else touches the working .dsl. A
     * refused save is shown.
     */
    public void saveRingsPressed() {
        boolean ringsDue = ringTool.active || ringTool.unsavedRingCount() > 0;
        if (ringsDue) {
            ringTool.saveRings();
            errorToasts.show(ringTool.toolName(), ringTool.lastError);
        }
        if (!regionLayer.labelsUnsaved || ringsDue && !ringTool.lastError.isEmpty()) {
            return;
        }
        String target = workingDslPath();
        regionTool.lastError = target == null ? "this scene has no working .dsl; "
                + regionLayer.partLabels.names.size() + " part label(s) stay unsaved" : "";
        try {
            if (target != null) {
                Path path = Path.of(target);
                RingDslWriter.writeAtomically(path, RingDslWriter.withLabels(
                        new String(Files.readAllBytes(path), StandardCharsets.UTF_8),
                        regionLayer.partLabels));
                regionLayer.labelsUnsaved = false;
                regionTool.lastRow = "saved " + regionLayer.partLabels.names.size()
                        + " part label(s) to " + target + "; " + regionLayer.partsSummary();
                Platforms.get().log(RingRegionTool.LOG_PREFIX + regionTool.lastRow);
            }
        } catch (IOException | RuntimeException failure) {
            regionTool.lastError = "could not write the part labels to " + target + ": "
                    + failure.getMessage();
        }
        errorToasts.show(regionTool.toolName(), regionTool.lastError);
    }

    /**
     * Undo or redo the active tool's own history while it has a step, else the ring
     * tool's, which holds ring edits and A's collapse; refusals show as toasts.
     *
     * @param backward true for an undo, false for a redo
     */
    @Override
    public void stepHistory(boolean backward) {
        EditHistory<?> own = activeTool.history();
        boolean ownStep = own != null && (backward ? own.undoDepth > 0 : own.redoDepth() > 0);
        stepHistoryOf(ownStep ? activeTool : ringTool, backward);
        errorToasts.show(activeTool.toolName(), activeTool.lastError);
    }

    /**
     * The hints every tool shares, after the tool keys and the active tool's own
     * hints the scene lists first. Ctrl+I and Esc come from the model scene, so no
     * tool may bind either; a tool takes Esc through {@link Tool#escapePressed}
     * instead.
     *
     * @param controls the scene's section
     */
    @Override
    public void setControls(List<ControlHint> controls) {
        controls.add(new ControlHint("cluster-fit-back", Keys.Y, false, true, "clustering fit: a pass back",
                () -> stepClusterFitPass(-1)));
        controls.add(new ControlHint("cluster-fit-on", Keys.Y, "clustering fit: a pass on (shows its look)",
                () -> stepClusterFitPass(1)));
        controls.add(new ControlHint("toggle-region-colours", Keys.G, "region colours in every tool",
                this::toggleRegionColours));
        controls.add(new ControlHint("collapse-slivers", Keys.A,
                "collapse slivers: one ring keeps each lens, the other becomes bisectors", this::collapseSlivers));
        controls.add(new ControlHint("show-hidden-regions", Keys.U, "show hidden regions", regionLayer::showAll));
        controls.add(new ControlHint(SAVE_RINGS_ACTION, Keys.S, true, "save rings", this::saveRingsPressed));
        controls.add(new ControlHint("toggle-rings", Keys.H, "show / hide rings", this::toggleRings));
        controls.add(new ControlHint(TOGGLE_NUMBERS_ACTION, Keys.N, "all numbers: rings and regions (R3)",
                this::toggleNumbers));
        controls.add(new ControlHint(TOGGLE_LABELS_ACTION, Keys.P, "all labels: part names, coloured by part",
                this::toggleLabels));
        super.setControls(controls);
    }

    /**
     * pick the regions look, or back to the look picked before it; each toggle here
     * reports its new state as the active tool's last row, which the reply carries.
     */
    private void toggleRegionColours() {
        boolean on = !regionLookPicked();
        if (on) {
            lookBeforeRegions = looks.picked.id;
        }
        setLook(on ? REGIONS_LOOK : lookBeforeRegions, headlight);
        refreshControls();
        activeTool.lastRow = ControlHint.toggled("region colours outside the region tool", on);
    }

    /** collapse the sliver lenses between rings, from any tool. */
    private void collapseSlivers() {
        ringTool.collapseSlivers();
        activeTool.lastRow = ringTool.lastRow;
        errorToasts.show(ringTool.toolName(), ringTool.lastError);
    }

    /**
     * flip the ring visibility that applies now, the exploded view's or the
     * assembled one.
     */
    private void toggleRings() {
        if (regionLayer.explosion.exploded()) {
            showRingsExploded = !showRingsExploded;
        } else {
            showRings = !showRings;
        }
        activeTool.lastRow = ControlHint.toggled("rings shown", ringsShown());
    }

    /** show or hide every ring and region number; off at the start. */
    private void toggleNumbers() {
        showNumbers = !showNumbers;
        regionLayer.labelsShownChanged();
        activeTool.lastRow = ControlHint.toggled("numbers shown", showNumbers);
    }

    /**
     * show or hide every part name; part colours go with the names, so the regions
     * recolour.
     */
    private void toggleLabels() {
        showLabels = !showLabels;
        regionLayer.labelsShownChanged();
        regionLayer.redraw();
        activeTool.lastRow = ControlHint.toggled("labels shown", showLabels);
    }

    /**
     * Whether the ring lines and numbers are drawn this frame: the exploded view's
     * own setting while the regions are exploded, else the viewer's.
     *
     * @return true when the rings are drawn
     */
    public boolean ringsShown() {
        return regionLayer.explosion.exploded() ? showRingsExploded : showRings;
    }

    /**
     * The ring scene shows its ring regions besides the model and the view.
     *
     * @param subsystem a scene-held subsystem
     * @return {@link #regionLayer} for {@link Subsystem#RINGS}, else what the model
     *         scene shows
     */
    @Override
    public Object dataset(Subsystem subsystem) {
        return subsystem == Subsystem.RINGS ? regionLayer : super.dataset(subsystem);
    }
}
