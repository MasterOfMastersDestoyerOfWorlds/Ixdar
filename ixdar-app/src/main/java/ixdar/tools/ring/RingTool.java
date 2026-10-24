package ixdar.tools.ring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.analysis.curvature.LimbAxis;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.AuthoredSplineRing;
import ixdar.geometry.mesh.data.rings.RingSegmentMode;
import ixdar.geometry.mesh.data.paths.spline.SplineAnchorFit;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.nodes.data.SurfaceCreasesNode;
import ixdar.nodegraph.nodes.data.SurfaceMetricNode;
import ixdar.nodegraph.nodes.selection.LoopThroughPointsNode;
import ixdar.nodegraph.nodes.selection.SelectRingNode;
import ixdar.nodegraph.nodes.selection.SplineCutNode;
import ixdar.nodegraph.nodes.selection.SplineRingNode;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palettes;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.LineSet;
import ixdar.graphics.render.model.MeshOverlayRuntime;
import ixdar.gui.ui.actions.Action;
import ixdar.nodegraph.syntax.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.input.KeyNames;
import ixdar.platform.input.Keys;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Draft;
import ixdar.tools.EditHistory;
import ixdar.tools.Item;
import ixdar.tools.Preview;
import ixdar.tools.Tool;

/**
 * The rings and bisectors its two sub-tools draw, {@link ClosedRingTool} and {@link BisectorTool},
 * and what both share: the confirmed rings, the draft, undo, the walls, the overlay and the save.
 * Confirming changes memory only: {@link #saveRings} alone writes the working .dsl.
 */
@ToolAnnotation(id = RingTool.TOOL_NAME)
public final class RingTool extends Tool {

    public static final String LOG_PREFIX = "[ring-tool] ";

    public static final String TOOL_NAME = "ring tool";

    public static final String UNSAVED_RING_PREFIX = "ring #";

    public static final String DRAFT_LABEL = "draft";

    public static final float BISECTOR_REACH_PIXELS = 40f;

    public static final String REMOVE_SELECTED_ANCHOR_HINT = "remove anchor (or ring)";

    public static final String REDO_HINT = "redo ring edit";

    public static final String REDO_ACTION = "redo";

    public static final String BISECT_ACTION = "bisect";

    public static final String NO_SELECTED_ANCHOR =
            "no anchor is selected - click a ring to open it, then click one of its anchors";

    public static final String NO_DRAFT_TO_DISCARD =
            "no draft is open, so X has nothing to discard - click a ring to draft one";

    public static final String CANNOT_TRACE_REFUSAL =
            "the ring tool cannot trace rings on this surface - load another model; the log says why";

    public static final String NO_SURFACE_TO_RING = "no surface to ring";

    public static final Color MARKED_RING_COLOR = Color.PINK_LIGHT;

    public static final Color GEODESIC_DRAFT_COLOR = Color.RED;

    public static final Color CREASE_DRAFT_COLOR = Color.PURPLE_BLUE;

    public static final String RING_LABEL_PREFIX = "ring";

    public static final String[] UNNUMBERED_RING_LABELS = {
        LoopThroughPointsNode.DEFAULT_MARK_LABEL, SplineRingNode.DEFAULT_MARK_LABEL,
        SelectRingNode.SELECTED_LABEL };

    public static final int MAXIMUM_RING_DIGITS = 9;

    public static final Color SUPPORTING_ANCHOR_COLOR = Color.WHITE;

    public static final Color AUTHORED_ANCHOR_COLOR = Color.TEAL_BLUE;

    public static final Color SELECTED_ANCHOR_COLOR = Color.YELLOW_ORANGE;

    public static final float RING_HIT_PIXELS = 8f;

    public static final float SUPPORTING_ANCHOR_PIXELS = 3f;

    public static final float AUTHORED_ANCHOR_PIXELS = 5f;

    public static final float SELECTED_ANCHOR_PIXELS = 7f;

    public static final int COORDINATES_PER_POINT = 3;

    public static final int SEGMENT_FLOATS = 2 * COORDINATES_PER_POINT;

    public static final float HALF = 0.5f;

    public static final String SPLINE_RING_NODE = "spline_ring";

    static final String ANCHOR_TAKEN =
            "an anchor already sits there - drag it to move it, or click elsewhere";

    /** Scene the tool runs on, which owns the surface, the camera and the working graph. */
    public final RingScene scene;

    /** Rings confirmed in this session, in the order they were confirmed. */
    public final List<SurfaceSpline> confirmedRings = new ArrayList<>();

    /** Authored anchors of each confirmed ring in ring order, what a save writes. */
    public final List<int[]> confirmedAuthoredVertexId = new ArrayList<>();

    /** Base normal each confirmed ring's plane leans toward, at the precision a save writes. */
    public final List<float[]> confirmedBaseNormal = new ArrayList<>();

    /** How each confirmed ring runs between its authored anchors, what a save writes as mode. */
    public final List<RingSegmentMode> confirmedMode = new ArrayList<>();

    /**
     * Statement id each confirmed ring was last saved under, {@code null} until a save writes it.
     */
    public final List<String> confirmedStatementIds = new ArrayList<>();

    /** Whether each confirmed ring carries changes the working .dsl does not hold yet. */
    public final List<Boolean> confirmedRingUnsaved = new ArrayList<>();

    /**
     * Graph ring label each confirmed ring stands for in the working .dsl, which draws as that
     * ring instead of from the graph's marks, or {@code null} for a ring only this session holds.
     */
    public final List<String> confirmedSourceLabel = new ArrayList<>();

    /** Whether each confirmed ring is deleted: it draws nothing and the next save drops it. */
    public final List<Boolean> confirmedRingDeleted = new ArrayList<>();

    /** Graph ring labels turned into tool rings, no longer drawn from the graph's marks. */
    public final Set<String> convertedGraphLabels = new HashSet<>();

    /**
     * Statement ids of the rings the working .dsl held for this tool when last read or saved; the
     * next save removes any no confirmed ring holds any more, so the file matches memory.
     */
    public final Set<String> knownRingStatementIds = new HashSet<>();

    /**
     * Labels of the rings the graph's {@code ring_candidates} statement proposed, in rank order,
     * which a save writes as one block; empty when the graph had no such statement.
     */
    public final List<String> candidateLabels = new ArrayList<>();

    /** The ring each proposed label was given at load; a proposed ring is unedited while it is. */
    public final Map<String, SurfaceSpline> candidateRingByLabel = new HashMap<>();

    /**
     * For each of {@link #candidateLabels}, the ring the working .dsl holds as last loaded or
     * saved, or {@code null} where it holds none; a save is due while memory differs.
     */
    public final List<SurfaceSpline> writtenCandidateRings = new ArrayList<>();

    /**
     * The {@code ring_candidates} line the working .dsl held before its rings were frozen, written
     * back when every proposed ring is live and unedited again, as after an undo.
     */
    public String candidatesLine;

    /** Whether the graph's rings changed since they were last given anchors. */
    public boolean graphRingsPending = true;

    /** Geodesic engine over the current surface, built once and reused every frame. */
    public SurfaceGeodesics geodesics;

    /** Surface the geodesic engine could not be built on, which the tool leaves alone. */
    public MeshTopology refusedSurface;

    /** Whether the tool is taking the mouse. */
    public boolean active;

    /** The hover preview, the ring or bisector a click would draft, or null. */
    public SurfaceSpline previewSpline;

    /** Whether the last frame found a ring or bisector under the cursor. */
    public boolean previewValid;

    /** Wall time the last frame that fitted a preview spent, in milliseconds. */
    public double previewMillis;

    /** Surface point under the cursor on the last frame, packed xyz. */
    public final float[] previewHitPoint = new float[COORDINATES_PER_POINT];

    /** Mesh vertex a click on the preview anchors: a ring's first anchor or a bisector's end. */
    public int previewAuthoredVertexId = -1;

    /** The draft ring the anchors are being edited on, or null when there is none. */
    public SurfaceSpline draft;

    /** The draft's authored anchors in ring order, the first leading the ring. */
    public int[] draftAuthoredVertexId = new int[0];

    /** Base normal the draft's plane leans toward, at the precision a save writes. */
    public final float[] draftBaseNormal = new float[COORDINATES_PER_POINT];

    /** How the draft runs between its authored anchors; T switches it. */
    public RingSegmentMode draftMode = RingSegmentMode.GEODESIC;

    /** Groove cost of the current surface the crease mode follows, measured once per model. */
    public SurfaceCreases creases;

    /** Confirmed ring the draft re-opened, which Enter replaces and X restores, or -1. */
    public int draftSourceRing = -1;

    /** Graph ring label the draft was converted from, shown again if X discards, or null. */
    public String draftSourceLabel;

    /** Authored anchor Delete and a drag act on, as its mesh vertex, or -1 for none. */
    public int selectedAnchorVertexId = -1;

    /** Authored anchor under the cursor, as its index in the draft's authored anchors, or -1. */
    public int hoveredAnchor = -1;

    /** Whether a press on an authored anchor is dragging it along the surface. */
    public boolean draggingAnchor;

    /**
     * Every anchor and ring edit since the model loaded, which Ctrl+Z and Ctrl+Shift+Z step
     * through; a save leaves it alone.
     */
    public final EditHistory<RingToolState> history = new EditHistory<>();

    /** Statement id each ring spline was saved under, which a redo past the save gets back. */
    public final Map<SurfaceSpline, String> savedStatementByRing = new IdentityHashMap<>();

    /** Authored anchors the working .dsl holds under each statement id this session saved. */
    public final Map<String, int[]> savedAuthoredByStatement = new HashMap<>();

    /** Base normal the working .dsl holds under each statement id this session saved. */
    public final Map<String, float[]> savedNormalByStatement = new HashMap<>();

    /** Segment mode the working .dsl holds under each statement id this session saved. */
    public final Map<String, RingSegmentMode> savedModeByStatement = new HashMap<>();

    /** Wall time the last draft re-trace spent, in milliseconds. */
    public double draftMillis;

    /** Depth the draft was last traced at, shallower while a drag is moving an anchor. */
    public int draftDepth;

    /** Number drawn beside each ring, in drawing order: the graph's rings then the confirmed. */
    public String[] drawnRingLabel = new String[0];

    /** Colour each drawn ring takes, parallel to {@link #drawnRingLabel}. */
    public Color[] drawnRingColors = new Color[0];

    /** Bumped whenever the rings change, so a tool built on them knows to rebuild. */
    public int ringRevision;

    /**
     * Rings drawn in {@link #MARKED_RING_COLOR} with a note after their number, such as one that
     * splits nothing, by live ring label.
     */
    public Map<String, String> ringNoteByLabel = Map.of();

    /** Live labels of the rings not drawn because every region they bound is hidden. */
    public Set<String> hiddenRingLabels = Set.of();

    /**
     * Identity of each confirmed ring in memory, which bisectors name their end walls by; it
     * outlives edits, saves and the re-adoption of the ring's statement.
     */
    public final List<Integer> confirmedRingSerial = new ArrayList<>();

    /**
     * Serials of the walls each confirmed bisector's first and last anchor sit on, or
     * {@code null} for a ring.
     */
    public final List<int[]> confirmedBisectorEnds = new ArrayList<>();

    /** Serial the next confirmed ring takes. */
    public int nextRingSerial;

    /** Serial a statement label's ring had before the graph re-ran, which its re-adoption keeps. */
    public final Map<String, Integer> retiredSerialByLabel = new HashMap<>();

    /** Serial of the wall the last snap landed on, or -1 when none lay within reach. */
    public int snappedWall = -1;

    /** The default sub-tool, which drafts and edits closed rings. */
    public final ClosedRingTool closedRing = new ClosedRingTool(this);

    /** The sub-tool K hands the input to, which draws bisectors between the rings. */
    public final BisectorTool bisector = new BisectorTool(this);

    /**
     * The sub-tool owning the open draft, which every draft edit is handed to; the rings while no
     * draft is open.
     */
    public RingSubTool draftOwner = closedRing;

    /** Face the hover preview was last fitted for, or -1 to fit it again on the next frame. */
    int previewedFace = -1;

    /** Whether the overlay is uploaded again on the next frame, after the rings or a hover changed. */
    boolean overlayStale = true;

    /** Surface point the hover preview was last fitted for, packed xyz. */
    final float[] previewedHit = new float[COORDINATES_PER_POINT];

    /** Segment endpoints of each graph ring drawn from the graph's marks, the ring hover's targets. */
    List<float[]> graphRingSegments = new ArrayList<>();

    /** Label of each of {@link #graphRingSegments}. */
    List<String> graphRingLabels = new ArrayList<>();

    private int[] wallSerialByVertex = new int[0];
    private int[] wallVertexStamp = new int[0];
    private int wallEpoch;
    private int wallsBuiltRevision = -1;
    private int[] snapVisitStamp = new int[0];
    private int[] snapQueue = new int[0];
    private int snapEpoch;
    private int uploadedSnap = -1;
    private final Vector3f scratchPosition = new Vector3f();
    private MeshTopology preparedSurface;
    private boolean pendingClick;
    private RingToolState dragBefore;
    private LineSet ringLines = new LineSet(0);
    private int[] ringSegmentStart = { 0 };
    private float[] ringLabelXyz = new float[0];
    private float[] ringLabelLift = new float[0];
    private boolean ringsStale = true;
    private SurfaceSpline uploadedPreview;
    private SurfaceSpline uploadedDraft;
    private int uploadedHoveredRing = -1;
    private String uploadedHoveredGraphRing;
    private int uploadedSelected = -1;

    /**
     * Bind the tool to the scene it authors rings on. Its modes are {@link RingSegmentMode}'s, the
     * one the next click drafts a ring or bisector in, which T sets and undo leaves alone. Its
     * sub-tools are the {@link #closedRing}, active at first, and the {@link #bisector}.
     *
     * @param ringScene scene owning the surface, camera and working graph
     */
    public RingTool(RingScene ringScene) {
        super(RingSegmentMode.GEODESIC.label, RingSegmentMode.CREASE.label);
        this.scene = ringScene;
        subTools.add(closedRing);
        subTools.add(bisector);
        activeSubTool = closedRing;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    @Override
    public String desc() {
        return "Draw rings around the surface and edit their anchors, in geodesic or crease mode, and bisect between rings";
    }

    /**
     * The segment mode the tool's {@link #mode} index names, the one the next click drafts in.
     *
     * @return the mode
     */
    public RingSegmentMode newDraftMode() {
        return RingSegmentMode.values()[mode];
    }

    @Override
    public EditHistory<RingToolState> history() {
        return history;
    }

    /**
     * The open draft ring: its authored anchors then its supporting ones, the selected and hovered
     * authored anchor, the trace's edges, length and fingerprint, and the ring it re-opened.
     *
     * @return the draft, valid once its trace marks a ring of edges, or {@code null} when none is open
     */
    @Override
    public Draft draft() {
        if (draft == null) {
            return null;
        }
        Draft shown = new Draft();
        float[] authored = positionsOf(draftAuthoredVertexId);
        int authoredCount = authored.length / COORDINATES_PER_POINT;
        shown.points = Arrays.copyOf(authored, COORDINATES_PER_POINT * (authoredCount
                + draft.anchorCount - draft.authoredCount()));
        shown.pointLabels = new String[shown.points.length / COORDINATES_PER_POINT];
        Arrays.fill(shown.pointLabels, 0, authoredCount, "authored");
        int point = authoredCount;
        for (int anchor = 0; anchor < draft.anchorCount && point < shown.pointLabels.length; anchor++) {
            if (!draft.anchorAuthored[anchor]) {
                System.arraycopy(draft.anchorXyz, COORDINATES_PER_POINT * anchor, shown.points,
                        COORDINATES_PER_POINT * point, COORDINATES_PER_POINT);
                shown.pointLabels[point++] = "supporting";
            }
        }
        shown.selectedPoint = selectedIndex();
        shown.hoveredPoint = hoveredAnchor;
        shown.valid = draft.marks.markedEdgeCount > 0;
        shown.reopened = draftSourceRing < 0 || draftSourceRing >= confirmedRings.size() ? null
                : ringItem(draftSourceRing);
        fillFromTrace(shown, draft, String.format(Locale.ROOT, "%s mode, %.0f ms%s%s%s%s",
                draftMode.label, draftMillis, draftSourceLabel == null ? ""
                        : ", converted from " + draftSourceLabel,
                draggingAnchor ? ", dragging an anchor" : "", draftOwner.draftDetail(),
                draftOwner.draftCrossing() == null ? ", simple" : ", crosses itself"));
        shown.label = draftOwner.draftLabel();
        return shown;
    }

    /**
     * One confirmed ring as an item: the number drawn beside it, its centroid, marked edges,
     * length and fingerprint, whether it is saved or deleted, and its statement and anchors.
     *
     * @param index the ring's position in {@link #confirmedRings}
     * @return the ring's item
     */
    public Item ringItem(int index) {
        SurfaceSpline ring = confirmedRings.get(index);
        Item item = new Item();
        String statementId = index < confirmedStatementIds.size()
                ? confirmedStatementIds.get(index) : null;
        String sourceLabel = index < confirmedSourceLabel.size()
                ? confirmedSourceLabel.get(index) : null;
        fillFromTrace(item, ring, String.format(Locale.ROOT, "statement %s, source %s, %d anchors "
                + "(%d authored), mean radius %.5f, trace depth %d, %d unresolved gaps",
                statementId == null ? "" : statementId, sourceLabel == null ? "" : sourceLabel,
                ring.anchorCount, ring.authoredCount(), ring.path.meanRadius, ring.traceDepth,
                ring.marks.unresolvedGaps));
        item.label = Integer.toString(drawnRingNumber(index));
        item.saved = !(index < confirmedRingUnsaved.size() && confirmedRingUnsaved.get(index));
        item.deleted = index < confirmedRingDeleted.size() && confirmedRingDeleted.get(index);
        return item;
    }

    /**
     * Fill the parts an item takes from a traced ring: centroid, marked edges, length, fingerprint
     * and, after {@code detail}, its sharpest corner.
     *
     * @param item   the item to fill
     * @param ring   the traced ring
     * @param detail what the caller knows of the ring, which leads the detail line
     */
    private void fillFromTrace(Item item, SurfaceSpline ring, String detail) {
        for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
            item.location[axis] = (float) ring.path.centroid[axis];
        }
        item.size = ring.marks.markedEdgeCount;
        item.length = ring.path.length;
        MeshTopology surface = scene.halfEdgeSurface();
        item.fingerprint = surface == null ? ""
                : EdgeMarks.fingerprint(surface, ring.marks.markedByEdgeId);
        item.detail = detail + String.format(Locale.ROOT, ", sharpest corner %.1f deg",
                ring.minimumInteriorAngleDegrees);
    }

    /**
     * What the active sub-tool's hover is over.
     *
     * @return the hovered item, or {@code null} when the cursor is over none
     */
    @Override
    public Item hovered() {
        return activeSubTool.hovered();
    }

    /**
     * The active sub-tool's hover preview.
     *
     * @return the preview
     */
    @Override
    public Preview preview() {
        return activeSubTool.preview();
    }

    /**
     * Start the hover preview, and the active sub-tool's; clicks and anchor drags arrive through
     * the pointer callbacks.
     */
    @Override
    public void activate() {
        active = true;
        lastError = "";
        // Another tool may have drawn its own anchors over the draft's since this one uploaded.
        overlayStale = true;
        activeSubTool.activate();
    }

    /**
     * Stop the hover preview and hand the mouse back, keeping every confirmed ring, an open draft
     * and the active sub-tool, which the next activation goes on with.
     */
    @Override
    public void deactivate() {
        if (draggingAnchor) {
            releaseAnchor();
        }
        activeSubTool.deactivate();
        active = false;
        pendingClick = false;
        previewValid = false;
        previewSpline = null;
        hoveredAnchor = -1;
        overlayStale = true;
        Platforms.get().log(LOG_PREFIX + "inactive with " + confirmedRings.size()
                + " confirmed ring(s), " + unsavedRingCount() + " unsaved"
                + (draft == null ? "" : ", the draft kept"));
    }

    /**
     * The rows both sub-tools share: anchor drags, undo and redo, Enter and the T mode switch;
     * then the active sub-tool's own rows.
     *
     * @param controls the scene's control list
     */
    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint(ControlHint.NO_KEY, "drag anchor", "move it"));
        controls.add(new ControlHint(REDO_ACTION, Keys.Z, true, true, "ctrl+shift+Z", REDO_HINT,
                refusalShown(() -> redo())));
        controls.add(new ControlHint("undo", Keys.Z, true, "ctrl+Z", "undo ring edit",
                refusalShown(() -> undo())));
        controls.add(new ControlHint(REDO_ACTION, Keys.Y, true, "ctrl+Y", REDO_HINT, refusalShown(() -> redo())));
        controls.add(new ControlHint("confirm-draft", Keys.ENTER, "enter", "confirm draft",
                refusalShown(() -> confirmDraft())));
        // T switches between the geodesic spline and the crease path: the open draft, as one
        // edit, and the drafts clicks open from now on.
        controls.add(new ControlHint("switch-mode", Keys.T, "T", "mode: "
                + (draft != null ? draftMode : newDraftMode()).label
                + ", switch to " + switchedMode().label, refusalShown(() -> {
            RingSegmentMode next = switchedMode();
            if (next == RingSegmentMode.CREASE && !readyCreases(scene.halfEdgeSurface())) {
                return;
            }
            if (draft != null) {
                RingToolState stateBefore = new RingToolState(this);
                RingSegmentMode previous = draftMode;
                draftMode = next;
                if (!draftOwner.retrace(draftAuthoredVertexId, false)) {
                    draftMode = previous;
                    return;
                }
                recordEdit("segment mode " + next.label, stateBefore);
                reportDraft("now in " + next.label + " mode");
            } else {
                lastRow = "a click drafts in " + next.label + " mode";
            }
            mode = next.ordinal();
            previewedFace = -1;
            scene.refreshControls();
        })));
        activeSubTool.addControls(controls);
    }

    /**
     * The segment mode T switches to: the other one than the open draft's, or than the next
     * draft's when none is open.
     *
     * @return the mode T would switch to now
     */
    private RingSegmentMode switchedMode() {
        return (draft != null ? draftMode : newDraftMode()) == RingSegmentMode.GEODESIC
                ? RingSegmentMode.CREASE : RingSegmentMode.GEODESIC;
    }

    /**
     * A key's action whose refusal is shown: {@link #lastError} is cleared first, and whatever
     * the action leaves there goes to the scene's refusal toasts.
     *
     * @param action the key's effect
     * @return the effect, then its refusal shown
     */
    public Action refusalShown(Action action) {
        return () -> {
            lastError = "";
            action.perform();
            scene.errorToasts.show(TOOL_NAME, lastError);
        };
    }

    /**
     * Ready {@link #creases} on a surface: the working graph's own surface_creases output when
     * one was built on it, else measured here once per model, timed and logged.
     *
     * @param surface the surface rings are traced on
     * @return true when {@link #creases} lies on {@code surface}; {@link #lastError} says why not
     */
    public boolean readyCreases(MeshTopology surface) {
        if (creases != null && creases.sourceMesh == surface) {
            return true;
        }
        if (surface == null) {
            lastError = "no surface to measure grooves on";
            return false;
        }
        NodeGraphRuntime graph = scene.graph;
        for (GraphNodeContext record : graph == null ? List.<GraphNodeContext>of() : graph.root.children) {
            if (record.getOutput(SurfaceCreasesNode.CREASES.name)
                    instanceof SurfaceCreases built && built.sourceMesh == surface) {
                creases = built;
                return true;
            }
        }
        creases = SurfaceCreases.of(surface);
        Platforms.get().log(String.format(Locale.ROOT,
                LOG_PREFIX + "measured the grooves of %d faces in %.0f ms, kept for this model",
                surface.faceCount(), creases.buildMillis));
        return true;
    }

    /**
     * The mark labels the graph left that name rings, so they draw thick with the tool's own.
     * Marks whose label is not a ring keep the thin feature-edge path.
     *
     * @param label a mark label
     * @return true when the label names a ring
     */
    public static boolean isRingLabel(String label) {
        if (label == null) {
            return false;
        }
        for (String unnumbered : UNNUMBERED_RING_LABELS) {
            if (unnumbered.equals(label)) {
                return true;
            }
        }
        return isNumberedRingLabel(label);
    }

    /**
     * Whether a label is {@code ring} or {@code ring_} closed by digits, such as {@code ring_03}
     * or the writer's {@code ring7}, which is what tells a ring mask from a {@code spring_edges}.
     *
     * @param label a mark label or statement id
     * @return true when the label is the ring prefix followed only by digits
     */
    private static boolean isNumberedRingLabel(String label) {
        if (!label.startsWith(RING_LABEL_PREFIX)) {
            return false;
        }
        int firstDigit = RING_LABEL_PREFIX.length();
        if (firstDigit < label.length() && label.charAt(firstDigit) == '_') {
            firstDigit++;
        }
        int digits = label.length() - firstDigit;
        if (digits < 1 || digits > MAXIMUM_RING_DIGITS) {
            return false;
        }
        for (int index = firstDigit; index < label.length(); index++) {
            char character = label.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The number drawn beside each ring: its 0-based position in the overlay, so the first
     * {@code ring_candidates} ring draws 0 and matches its {@code rings-list} row.
     *
     * @param graphRingLabels the graph's ring mark labels, in statement order, drawn first
     * @param confirmedRingCount rings confirmed in the tool, drawn after the graph's in confirm
     *                           order
     * @return one text per ring, in drawing order
     */
    public static String[] ringNumberTexts(Collection<String> graphRingLabels,
            int confirmedRingCount) {
        String[] texts = new String[graphRingLabels.size() + confirmedRingCount];
        for (int ring = 0; ring < texts.length; ring++) {
            texts[ring] = String.valueOf(ring);
        }
        return texts;
    }

    /** Rebuild the ring overlay on the next frame, after the graph's marks changed. */
    public void invalidateRings() {
        ringsStale = true;
        overlayStale = true;
        ringRevision++;
    }

    /**
     * Draw some rings in {@link #MARKED_RING_COLOR} with a note after their number and leave others
     * undrawn; the rings themselves do not change.
     *
     * @param noteByLabel  note by live ring label, as {@link #liveRingMarks} names them; empty
     *                     marks none
     * @param hiddenLabels live labels of rings not drawn, those lying only on hidden regions
     */
    public void markRings(Map<String, String> noteByLabel, Set<String> hiddenLabels) {
        if (noteByLabel.equals(ringNoteByLabel) && hiddenLabels.equals(hiddenRingLabels)) {
            return;
        }
        ringNoteByLabel = noteByLabel;
        hiddenRingLabels = hiddenLabels;
        ringsStale = true;
        overlayStale = true;
    }

    /**
     * The rings as they stand, each as its edge mask: the graph's unconverted rings no confirmed
     * ring stands for, then every confirmed ring or bisector not deleted, an open draft's original
     * included, then a new bisector being drafted when it runs simple, which the region colours
     * preview.
     *
     * @return masks by ring label: the graph or statement label, or {@code ring #N} by drawn
     *         number for a ring only this session holds
     */
    public Map<String, boolean[]> liveRingMarks() {
        Map<String, boolean[]> marks = new LinkedHashMap<>();
        for (Map.Entry<String, SurfaceSpline> ring : liveRingSplines().entrySet()) {
            marks.put(ring.getKey(), ring.getValue() == null
                    ? scene.ringMarksByLabel.get(ring.getKey())
                    : ring.getValue().marks.markedByEdgeId);
        }
        return marks;
    }

    /**
     * The rings of {@link #liveRingMarks}, in its order and under its labels, as their traced
     * splines, which a region extraction cuts along.
     *
     * @return splines by ring label, {@code null} for a graph ring with no confirmed spline
     */
    public Map<String, SurfaceSpline> liveRingSplines() {
        Map<String, SurfaceSpline> splines = new LinkedHashMap<>();
        for (String label : unownedGraphRingLabels()) {
            if (!convertedGraphLabels.contains(label)) {
                splines.put(label, null);
            }
        }
        for (int ring = 0; ring < confirmedRings.size(); ring++) {
            if (confirmedRingDeleted.get(ring)) {
                continue;
            }
            String label = confirmedSourceLabel.get(ring);
            splines.put(liveLabel(ring), confirmedRings.get(ring));
        }
        if (draft != null && draftOwner == bisector && draftSourceRing < 0
                && !bisector.drawsInvalid()) {
            splines.put(BisectorTool.DRAFT_LABEL, draft);
        }
        return splines;
    }

    /**
     * The label a confirmed ring goes by while the tool runs: its statement or graph label, or
     * {@code ring #N} by drawn number for one only this session holds.
     *
     * @param ring index in {@link #confirmedRings}
     * @return the live label
     */
    public String liveLabel(int ring) {
        String label = confirmedSourceLabel.get(ring);
        return label != null ? label : UNSAVED_RING_PREFIX + drawnRingNumber(ring);
    }

    /**
     * The confirmed ring holding a serial.
     *
     * @param serial a ring's {@link #confirmedRingSerial}
     * @return its index in {@link #confirmedRings}, or -1 when no ring holds it
     */
    public int ringOfSerial(int serial) {
        return confirmedRingSerial.indexOf(serial);
    }

    /**
     * One frame of the tool: pick under the cursor, then drag an anchor, fit the hover preview or
     * run a waiting click. Nothing is picked while the camera is dragged or zoomed.
     */
    public void perFrame() {
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        MeshTopology surface = scene.halfEdgeSurface();
        if (graphRingsPending && surface != null && surface.faceCount() > 0) {
            adoptGraphRings(surface);
        }
        if (!active || runtime == null || surface == null || surface.faceCount() == 0) {
            if (pendingClick) {
                lastError = RingScene.NO_SURFACE_REFUSAL;
                scene.errorToasts.show(TOOL_NAME, lastError);
            }
            pendingClick = false;
            uploadOverlay();
            return;
        }
        prepare(runtime, surface);
        if (geodesics == null || geodesics.mesh != surface) {
            if (pendingClick) {
                lastError = CANNOT_TRACE_REFUSAL;
                scene.errorToasts.show(TOOL_NAME, lastError);
            }
            pendingClick = false;
            uploadOverlay();
            return;
        }
        boolean hitValid = scene.pickCursor(runtime, surface);
        int hitVertexId = scene.cursorVertexId;
        if (hitValid) {
            System.arraycopy(scene.cursorPoint, 0, previewHitPoint, 0, COORDINATES_PER_POINT);
        }
        hoveredAnchor = -1;
        if (draft != null) {
            previewValid = false;
            previewSpline = null;
            if (hitValid) {
                hoveredAnchor = scene.anchorUnderCursor(runtime, surface, draftAuthoredVertexId);
                boolean vertexFree = true;
                for (int held : draftAuthoredVertexId) {
                    vertexFree &= held != hitVertexId;
                }
                if (draggingAnchor && vertexFree && selectedIndex() >= 0) {
                    moveSelectedAnchor(hitVertexId);
                }
            }
        } else if (!hitValid) {
            previewValid = false;
            previewedFace = -1;
        }
        activeSubTool.perFrame();
        if (pendingClick) {
            // The click against the fresh pick: on a draft it selects the authored anchor under
            // the cursor; anything else is the active sub-tool's.
            pendingClick = false;
            lastError = "";
            if (!hitValid) {
                lastError = scene.cursorMissReason;
            } else if (draft != null && hoveredAnchor >= 0) {
                selectedAnchorVertexId = draftAuthoredVertexId[hoveredAnchor];
                lastRow = "selected authored anchor " + hoveredAnchor + " of "
                        + draftAuthoredVertexId.length;
            } else {
                activeSubTool.onClick(scene.cursorFramebufferX, scene.cursorFramebufferY);
            }
            scene.errorToasts.show(TOOL_NAME, lastError);
            scene.refreshControls();
        }
        SurfaceSpline shownPreview = previewValid ? previewSpline : null;
        String hoveredGraphRing = closedRing.hoveredGraphRing;
        if (shownPreview != uploadedPreview || draft != uploadedDraft
                || previewAuthoredVertexId != uploadedSnap
                || closedRing.hoveredRing != uploadedHoveredRing
                || selectedAnchorVertexId != uploadedSelected
                || (hoveredGraphRing == null ? uploadedHoveredGraphRing != null
                        : !hoveredGraphRing.equals(uploadedHoveredGraphRing))) {
            overlayStale = true;
        }
        uploadOverlay();
    }

    /**
     * Ask for a click to be acted on at the next frame, once that frame has picked the surface
     * under the cursor the click was made at.
     */
    public void requestClick() {
        pendingClick = active;
    }

    /**
     * The wall vertex nearest a surface point within {@link #BISECTOR_REACH_PIXELS}, found by a
     * walk out from the vertex under it; {@link #snappedWall} names its wall. Walls are the live
     * confirmed rings and bisectors, the draft's own original aside.
     *
     * @param vertexId mesh vertex under the point
     * @param xyz      the point, packed xyz
     * @return the wall vertex, or -1 when no wall lies within reach
     */
    public int snapToWall(int vertexId, float[] xyz) {
        MeshTopology surface = geodesics.mesh;
        int vertexIdBound = geodesics.metric.vertexIdBound;
        if (wallVertexStamp.length != vertexIdBound) {
            wallVertexStamp = new int[vertexIdBound];
            wallSerialByVertex = new int[vertexIdBound];
            snapVisitStamp = new int[vertexIdBound];
            snapQueue = new int[vertexIdBound];
            wallsBuiltRevision = -1;
        }
        if (wallsBuiltRevision != ringRevision) {
            wallsBuiltRevision = ringRevision;
            wallEpoch++;
            for (int ring = 0; ring < confirmedRings.size(); ring++) {
                if (confirmedRingDeleted.get(ring) || ring == draftSourceRing && draft != null) {
                    continue;
                }
                for (int edgeId : confirmedRings.get(ring).marks.markedEdgeIds) {
                    int halfEdge = surface.edgeHalfEdge(edgeId);
                    for (int end : new int[] { surface.halfEdgeVertex(halfEdge),
                        surface.halfEdgeEndVertex(halfEdge) }) {
                        if (wallVertexStamp[end] != wallEpoch) {
                            wallVertexStamp[end] = wallEpoch;
                            wallSerialByVertex[end] = confirmedRingSerial.get(ring);
                        }
                    }
                }
            }
        }
        snappedWall = -1;
        if (vertexId < 0 || vertexId >= vertexIdBound) {
            return -1;
        }
        double reach = BISECTOR_REACH_PIXELS * scene.worldPerPixel();
        int stamp = ++snapEpoch;
        int head = 0;
        int tail = 0;
        snapQueue[tail++] = vertexId;
        snapVisitStamp[vertexId] = stamp;
        double nearest = Double.POSITIVE_INFINITY;
        int snapped = -1;
        while (head < tail) {
            int here = snapQueue[head++];
            surface.vertexPosition(here, scratchPosition);
            double distance = scratchPosition.distance(xyz[0], xyz[1], xyz[2]);
            if (wallVertexStamp[here] == wallEpoch && distance < nearest) {
                nearest = distance;
                snapped = here;
            }
            for (int spoke = 0; spoke < surface.vertexEdgeCount(here); spoke++) {
                int other = surface.edgeOtherVertex(surface.vertexEdgeAt(here, spoke), here);
                if (snapVisitStamp[other] == stamp) {
                    continue;
                }
                snapVisitStamp[other] = stamp;
                surface.vertexPosition(other, scratchPosition);
                if (scratchPosition.distance(xyz[0], xyz[1], xyz[2]) <= reach) {
                    snapQueue[tail++] = other;
                }
            }
        }
        snappedWall = snapped < 0 ? -1 : wallSerialByVertex[snapped];
        return snapped;
    }

    /**
     * Re-open a confirmed ring as the draft through the anchors and normal it was confirmed with,
     * as one undoable edit; a click on the ring with no draft open lands here, and a bisector
     * re-opens in the {@link #bisector}.
     *
     * @param ring index in {@link #confirmedRings}
     * @return true when the ring is now the draft
     */
    public boolean reopenRing(int ring) {
        if (draft != null || ring < 0 || ring >= confirmedRings.size()
                || confirmedRingDeleted.get(ring)) {
            lastError = "ring " + ring + " is not a live confirmed ring to re-open";
            return false;
        }
        RingToolState stateBefore = new RingToolState(this);
        System.arraycopy(confirmedBaseNormal.get(ring), 0, draftBaseNormal, 0,
                COORDINATES_PER_POINT);
        draftMode = confirmedMode.get(ring);
        // A confirmed ring with end walls is a bisector, and re-opens as the bisector's draft.
        bisector.draftEnds = confirmedBisectorEnds.get(ring);
        draftOwner = bisector.draftEnds != null ? bisector : closedRing;
        if (draftMode == RingSegmentMode.CREASE && !readyCreases(scene.halfEdgeSurface())
                || !draftOwner.retrace(confirmedAuthoredVertexId.get(ring), false)) {
            draft = null;
            draftMode = newDraftMode();
            draftOwner.draftClosed();
            draftOwner = closedRing;
            return false;
        }
        draftSourceRing = ring;
        draftSourceLabel = null;
        selectedAnchorVertexId = -1;
        switchSubTool(draftOwner);
        recordEdit("ring re-opened", stateBefore);
        invalidateRings();
        reportDraft("re-opened ring " + drawnRingNumber(ring) + " as the draft");
        return true;
    }

    /**
     * Add an authored anchor at a vertex, between the neighbours where it adds the least surface
     * length, the held anchors keeping their order; a vertex already held, or an anchor that only
     * fits by making the ring cross itself, is refused.
     *
     * @param vertexId mesh vertex the anchor sits on
     * @return true when the draft took the anchor
     */
    public boolean addAuthoredAnchor(int vertexId) {
        if (draft == null || vertexId < 0) {
            lastError = "no draft to add an anchor to";
            return false;
        }
        return draftOwner.addAnchor(vertexId);
    }

    /**
     * One step of a drag: the selected authored anchor moves to a vertex keeping its place in the
     * ring, re-traced at the fit's depth, the rate a frame allows; a move that would make a simple
     * ring cross itself is refused. The whole drag is one edit, recorded on release.
     *
     * @param vertexId mesh vertex the anchor moves to
     * @return true when the draft took the move
     */
    public boolean moveSelectedAnchor(int vertexId) {
        int selected = selectedIndex();
        if (draft == null || selected < 0) {
            lastError = NO_SELECTED_ANCHOR;
            return false;
        }
        return draftOwner.moveAnchor(selected, vertexId);
    }

    /**
     * Remove the selected authored anchor, as the sub-tool owning the draft removes one.
     *
     * @return true when an anchor was removed
     */
    public boolean deleteSelectedAnchor() {
        lastError = "";
        if (draft == null || selectedIndex() < 0) {
            lastError = NO_SELECTED_ANCHOR;
            return false;
        }
        return draftOwner.deletePoint();
    }


    /**
     * Delete the ring open as the draft, as one undoable edit. A confirmed or graph ring is kept
     * as a deleted confirmed ring, which the next save drops from the working .dsl; a draft never
     * confirmed is simply discarded.
     *
     * @return the deleted ring's index in {@link #confirmedRings}, or -1 when nothing was kept
     */
    public int deleteDraftRing() {
        lastError = "";
        if (draft == null) {
            lastError = "no ring is open to delete";
            return -1;
        }
        int ring = draftSourceRing;
        if (ring < 0 && draftSourceLabel == null) {
            discardDraft();
            lastRow = "deleted the draft, which was never confirmed";
            return -1;
        }
        int serial = ring >= 0 ? confirmedRingSerial.get(ring) : -1;
        for (int other = 0; serial >= 0 && other < confirmedRings.size(); other++) {
            int[] ends = confirmedBisectorEnds.get(other);
            if (other != ring && ends != null && !confirmedRingDeleted.get(other)
                    && (ends[0] == serial || ends[1] == serial)) {
                lastError = "bisector " + liveLabel(other) + " ends on " + liveLabel(ring)
                        + "; delete that bisector first";
                return -1;
            }
        }
        RingToolState stateBefore = new RingToolState(this);
        if (ring < 0) {
            ring = confirmedRings.size();
            confirmedRings.add(draft);
            confirmedAuthoredVertexId.add(draftAuthoredVertexId);
            confirmedBaseNormal.add(Arrays.copyOf(draftBaseNormal, COORDINATES_PER_POINT));
            confirmedMode.add(draftMode);
            confirmedStatementIds.add(null);
            confirmedRingUnsaved.add(false);
            confirmedSourceLabel.add(draftSourceLabel);
            confirmedRingDeleted.add(false);
            confirmedBisectorEnds.add(bisector.draftEnds);
            confirmedRingSerial.add(nextRingSerial++);
        }
        clearDraft();
        deleteRing(ring);
        recordEdit("ring deleted", stateBefore);
        return ring;
    }

    /**
     * Mark one confirmed ring deleted: it keeps its place and data, so an undo restores it
     * exactly, until a save drops it.
     *
     * @param ring index in {@link #confirmedRings}
     * @return true when the ring was live and is now deleted
     */
    public boolean deleteRing(int ring) {
        if (ring < 0 || ring >= confirmedRings.size() || confirmedRingDeleted.get(ring)
                || ring == draftSourceRing && draft != null) {
            lastError = "ring " + ring + " is not a live confirmed ring to delete";
            return false;
        }
        confirmedRingDeleted.set(ring, true);
        lastRow = "deleted ring " + drawnRingNumber(ring)
                + (confirmedSourceLabel.get(ring) == null ? "" : " (" + confirmedSourceLabel.get(ring)
                        + ")")
                + "; Ctrl+S removes it from the working .dsl";
        invalidateRings();
        return true;
    }

    /**
     * Step back over the last edit, restoring the rings and the draft exactly as they were.
     *
     * @return true when an edit was undone
     */
    public boolean undo() {
        return stepHistory(true);
    }

    /**
     * Re-apply the edit the last undo stepped back over.
     *
     * @return true when an edit was redone
     */
    public boolean redo() {
        return stepHistory(false);
    }

    /**
     * One undo or redo: restore the snapshot the history hands back and say which edit moved.
     * Refused while a drag is still moving an anchor, since the drag is not recorded yet.
     */
    private boolean stepHistory(boolean backward) {
        lastError = "";
        if (!active) {
            lastError = "the ring tool is not running - Ctrl+R switches to it";
            return false;
        }
        if (draggingAnchor) {
            lastError = "an anchor is being dragged - release it first";
            return false;
        }
        String what = backward ? history.nextUndoName() : history.nextRedoName();
        RingToolState restored = backward ? history.undo() : history.redo();
        if (restored == null) {
            lastError = backward ? "nothing to undo" : "nothing to redo";
            return false;
        }
        restored.restore(this);
        scene.refreshControls();
        lastRow = String.format(Locale.ROOT, "%s %s: %d to undo, %d to redo, %d ring(s), %s",
                backward ? "undid" : "redid", what, history.undoDepth, history.redoDepth(),
                confirmedRings.size(), draft == null ? "no draft"
                        : "draft with " + draftAuthoredVertexId.length + " authored anchor(s)");
        Platforms.get().log(LOG_PREFIX + lastRow + (draft == null ? "" : ", draft fingerprint "
                + EdgeMarks.fingerprint(scene.halfEdgeSurface(), draft.marks.markedByEdgeId)));
        return true;
    }

    /**
     * Push an edit onto the history when it changed the rings or the draft.
     *
     * @param what   the edit's name, as undo reports it
     * @param before the tool's state when the edit began
     */
    public void recordEdit(String what, RingToolState before) {
        RingToolState after = new RingToolState(this);
        if (!before.sameEdit(after)) {
            history.push(what, before, after);
        }
    }

    /**
     * A press on one of the draft's authored anchors selects it and keeps the drag for moving it
     * rather than orbiting the camera; Shift leaves the drag to the camera's pan.
     *
     * @param x    framebuffer x of the press, unused: the frame's surface pick places the anchor
     * @param y    framebuffer y of the press, unused
     * @param mods modifier-key bitmask of the press
     * @return true when the tool took the drag
     */
    @Override
    public boolean onPress(float x, float y, int mods) {
        if (!active || draft == null || hoveredAnchor < 0 || (mods & KeyNames.MOD_SHIFT) != 0) {
            return false;
        }
        selectedAnchorVertexId = draftAuthoredVertexId[hoveredAnchor];
        dragBefore = new RingToolState(this);
        draggingAnchor = true;
        return true;
    }

    /** The drag ended: trace the moved ring at the confirmed depth and record the whole drag. */
    public void releaseAnchor() {
        if (!draggingAnchor) {
            return;
        }
        draggingAnchor = false;
        if (draft != null && draftDepth != SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH) {
            if (draftOwner.retrace(draftAuthoredVertexId, true)) {
                reportDraft("authored anchor moved");
            } else if (dragBefore != null) {
                String refusal = lastError;
                dragBefore.restore(this);
                lastError = refusal;
                scene.errorToasts.show(TOOL_NAME, lastError);
            }
        }
        if (dragBefore != null && draft != null
                && !Arrays.equals(dragBefore.draftAuthoredVertexId, draftAuthoredVertexId)) {
            recordEdit("anchor dragged", dragBefore);
        }
        dragBefore = null;
    }

    /**
     * End an anchor drag, then queue a click for the next frame, which acts on it once it has
     * picked the surface under the cursor.
     *
     * @param x     framebuffer x of the release, unused: the frame's surface pick places the click
     * @param y     framebuffer y of the release, unused
     * @param click true when the press did not move
     * @return {@code click}: every click is the tool's
     */
    @Override
    public boolean onRelease(float x, float y, boolean click) {
        releaseAnchor();
        if (click) {
            requestClick();
        }
        return click;
    }

    /**
     * Put the draft into the confirmed rings: in the place of the ring it re-opened, marked
     * unsaved only if its anchors changed, or after the others.
     *
     * @return true when a draft was confirmed
     */
    public boolean confirmDraft() {
        lastError = "";
        if (!active || draft == null) {
            lastError = activeSubTool == bisector
                    ? "no bisector to confirm - click near a wall for each end"
                    : "no draft to confirm - click a ring to draft one first";
            return false;
        }
        if (!draftOwner.confirmable()) {
            return false;
        }
        int[] bisectorEnds = bisector.draftEnds;
        RingSubTool owner = draftOwner;
        long start = System.nanoTime();
        RingToolState stateBefore = new RingToolState(this);
        draggingAnchor = false;
        if (draftDepth != SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH
                && !draftOwner.retrace(draftAuthoredVertexId, true)) {
            return false;
        }
        float[] normal = Arrays.copyOf(draftBaseNormal, COORDINATES_PER_POINT);
        int ring = draftSourceRing;
        if (ring >= 0) {
            boolean changed = !Arrays.equals(confirmedAuthoredVertexId.get(ring),
                    draftAuthoredVertexId)
                    || !Arrays.equals(confirmedBaseNormal.get(ring), normal)
                    || confirmedMode.get(ring) != draftMode;
            if (changed) {
                confirmedRings.set(ring, draft);
            }
            confirmedAuthoredVertexId.set(ring, draftAuthoredVertexId);
            confirmedBaseNormal.set(ring, normal);
            confirmedMode.set(ring, draftMode);
            confirmedRingUnsaved.set(ring, confirmedRingUnsaved.get(ring) || changed);
            // The bisectors ending on an edited ring follow it: each end re-attaches to the ring's
            // nearest vertex, as the bisector's statement does on reload.
            int serial = confirmedRingSerial.get(ring);
            for (int other = 0; changed && other < confirmedRings.size(); other++) {
                int[] ends = confirmedBisectorEnds.get(other);
                if (ends == null || confirmedRingDeleted.get(other)
                        || ends[0] != serial && ends[1] != serial) {
                    continue;
                }
                int[] anchors = confirmedAuthoredVertexId.get(other).clone();
                SurfaceSpline followed = bisector.traced(anchors, ends, confirmedMode.get(other));
                lastError = followed != null && followed.marks.markedEdgeCount == 0
                        ? BisectorTool.WALLS_NOTHING : lastError;
                if (followed == null || followed.marks.markedEdgeCount == 0) {
                    lastError = "bisector " + liveLabel(other) + " keeps its old shape: "
                            + lastError;
                    continue;
                }
                confirmedRings.set(other, followed);
                confirmedRingUnsaved.set(other, confirmedRingUnsaved.get(other)
                        || !Arrays.equals(anchors, confirmedAuthoredVertexId.get(other)));
                confirmedAuthoredVertexId.set(other, anchors);
            }
        } else {
            ring = confirmedRings.size();
            confirmedRings.add(draft);
            confirmedAuthoredVertexId.add(draftAuthoredVertexId);
            confirmedBaseNormal.add(normal);
            confirmedMode.add(draftMode);
            confirmedStatementIds.add(null);
            confirmedRingUnsaved.add(true);
            confirmedSourceLabel.add(draftSourceLabel);
            confirmedRingDeleted.add(false);
            confirmedBisectorEnds.add(bisectorEnds);
            confirmedRingSerial.add(nextRingSerial++);
        }
        SurfaceSpline spline = confirmedRings.get(ring);
        clearDraft();
        recordEdit("draft confirmed", stateBefore);
        lastRow = String.format(Locale.ROOT,
                "%s %d: %d authored + %d supporting anchors, %d edges, length %.5f, "
                        + "centroid %.5f,%.5f,%.5f, sharpest corner %.1f deg, %.0f ms, %s",
                owner.toolName(), drawnRingNumber(ring),
                spline.authoredCount(),
                spline.anchorCount - spline.authoredCount(), spline.marks.markedEdgeCount,
                spline.path.length, spline.path.centroid[0], spline.path.centroid[1],
                spline.path.centroid[2], spline.minimumInteriorAngleDegrees,
                (System.nanoTime() - start) / 1e6,
                confirmedRingUnsaved.get(ring) ? "unsaved" : "saved");
        Platforms.get().log(LOG_PREFIX + lastRow + ", fingerprint "
                + EdgeMarks.fingerprint(scene.halfEdgeSurface(), spline.marks.markedByEdgeId));
        invalidateRings();
        return true;
    }

    /**
     * Esc: a draft untouched since the click that opened it closes, that click taken back off the
     * history; an edited one stays open, and the scene goes on to the orbit tool. With no draft
     * the active sub-tool takes the key.
     *
     * @return true when an unedited draft closed or the sub-tool took the key
     */
    @Override
    public boolean escapePressed() {
        if (draft == null) {
            return activeSubTool.escapePressed();
        }
        lastError = "";
        int applied = history.undoDepth;
        if (draggingAnchor || applied == 0 || history.statesBefore.get(applied - 1).draft != null
                || !history.statesAfter.get(applied - 1).sameEdit(new RingToolState(this))) {
            lastRow = "draft kept with its edits: Ctrl+R returns to it, Enter confirms, X discards";
            Platforms.get().log(LOG_PREFIX + lastRow);
            return false;
        }
        String opening = history.nextUndoName();
        history.retract().restore(this);
        scene.refreshControls();
        previewedFace = -1;
        lastRow = "closed the unedited ring (" + opening + " taken back, nothing recorded)";
        Platforms.get().log(LOG_PREFIX + lastRow);
        return true;
    }

    @Override
    public String escapeDescription() {
        String own = "close an unedited ring (an edited one stays open)";
        String sub = activeSubTool.escapeDescription();
        return sub.isEmpty() ? own : own + ", else " + sub;
    }

    /** Drop the draft; a re-opened ring stays as it was and a converted graph ring shows again. */
    public void discardDraft() {
        if (draft == null) {
            lastError = NO_DRAFT_TO_DISCARD;
            return;
        }
        RingToolState stateBefore = new RingToolState(this);
        if (draftSourceLabel != null) {
            convertedGraphLabels.remove(draftSourceLabel);
        }
        clearDraft();
        recordEdit("draft dropped", stateBefore);
        lastRow = "draft discarded";
        invalidateRings();
    }

    /**
     * Close the draft without recording it: no anchors, no ring it re-opened, nothing selected,
     * the next draft in the tool's mode.
     */
    public void clearDraft() {
        draft = null;
        draftMode = newDraftMode();
        draftOwner.draftClosed();
        draftOwner = closedRing;
        scene.refreshControls();
        draftAuthoredVertexId = new int[0];
        draftSourceRing = -1;
        draftSourceLabel = null;
        selectedAnchorVertexId = -1;
        hoveredAnchor = -1;
        draggingAnchor = false;
        dragBefore = null;
    }

    /**
     * The vertices of a closed loop of marked edges in walking order, or an empty array when the
     * marks do not form exactly one simple cycle.
     *
     * @param mesh          surface the mask indexes by edge id
     * @param marksByEdgeId edge-id-indexed mask
     * @return the loop's vertices, each once
     */
    public static int[] orderedLoop(MeshTopology mesh, boolean[] marksByEdgeId) {
        if (mesh == null || marksByEdgeId == null) {
            return new int[0];
        }
        int marked = 0;
        int firstEdgeId = -1;
        for (int index = 0; index < mesh.edgeCount(); index++) {
            int edgeId = mesh.edgeIdAt(index);
            if (edgeId < marksByEdgeId.length && marksByEdgeId[edgeId]) {
                marked++;
                firstEdgeId = firstEdgeId < 0 ? edgeId : firstEdgeId;
            }
        }
        if (firstEdgeId < 0) {
            return new int[0];
        }
        int[] loop = new int[marked];
        int halfEdge = mesh.edgeHalfEdge(firstEdgeId);
        int start = mesh.halfEdgeVertex(halfEdge);
        int vertexId = mesh.halfEdgeEndVertex(halfEdge);
        int cameFrom = firstEdgeId;
        loop[0] = start;
        int length = 1;
        while (vertexId != start) {
            if (length >= marked) {
                return new int[0];
            }
            loop[length++] = vertexId;
            int next = -1;
            for (int side = 0; side < mesh.vertexEdgeCount(vertexId); side++) {
                int edgeId = mesh.vertexEdgeAt(vertexId, side);
                if (edgeId != cameFrom && edgeId < marksByEdgeId.length
                        && marksByEdgeId[edgeId]) {
                    next = edgeId;
                    break;
                }
            }
            if (next < 0) {
                return new int[0];
            }
            int nextHalfEdge = mesh.edgeHalfEdge(next);
            vertexId = mesh.halfEdgeVertex(nextHalfEdge) == vertexId
                    ? mesh.halfEdgeEndVertex(nextHalfEdge)
                    : mesh.halfEdgeVertex(nextHalfEdge);
            cameFrom = next;
        }
        return length == marked ? loop : new int[0];
    }

    /**
     * Put what the last draft edit did in the row under the status line, the one line the user
     * reads after every edit, with the ring's fingerprint in the log.
     *
     * @param what the edit, as the row names it
     */
    public void reportDraft(String what) {
        int authoredAnchors = draft.authoredCount();
        float[] crossing = draftOwner.draftCrossing();
        lastRow = String.format(Locale.ROOT,
                "draft %s: %s, %d authored + %d supporting anchors, %d edges, length %.5f, "
                        + "sharpest corner %.1f deg, %.0f ms%s",
                what, draftOwner.draftDescription(),
                authoredAnchors, draft.anchorCount - authoredAnchors,
                draft.marks.markedEdgeCount, draft.path.length, draft.minimumInteriorAngleDegrees,
                draftMillis, crossing == null ? "" : String.format(Locale.ROOT,
                        ", crosses itself near %.4f,%.4f,%.4f", crossing[0], crossing[1],
                        crossing[2]));
        Platforms.get().log(LOG_PREFIX + lastRow + ", fingerprint "
                + EdgeMarks.fingerprint(scene.halfEdgeSurface(), draft.marks.markedByEdgeId));
    }

    /**
     * Where the selected authored anchor sits in the draft's authored anchors.
     *
     * @return its index, or -1 when none is selected
     */
    public int selectedIndex() {
        for (int anchor = 0; anchor < draftAuthoredVertexId.length; anchor++) {
            if (draftAuthoredVertexId[anchor] == selectedAnchorVertexId) {
                return anchor;
            }
        }
        return -1;
    }

    /**
     * A normal as a save writes it and a reload reads it back, so the live ring is traced from
     * exactly the numbers its statement will hold.
     *
     * @param normal packed xyz
     * @return the normal after one round trip through the statement's text format
     */
    public static float[] written(float[] normal) {
        return SurfaceWaypoints.parse(SurfaceWaypoints.format(normal, 1));
    }

    /**
     * The number drawn beside one confirmed ring, which is its position in the overlay: the
     * graph's ring statements draw first, the rings confirmed here after them in confirm order.
     *
     * @param confirmedIndex the ring's position in {@link #confirmedRings}
     * @return the 0-based number the overlay draws beside it
     */
    public int drawnRingNumber(int confirmedIndex) {
        return unownedGraphRingLabels().size() + confirmedIndex;
    }

    /**
     * The graph's ring labels no confirmed ring stands for, which draw from the graph's marks
     * ahead of the confirmed rings.
     *
     * @return those labels in the graph's order
     */
    public List<String> unownedGraphRingLabels() {
        List<String> unowned = new ArrayList<>();
        for (String label : scene.ringMarksByLabel.keySet()) {
            if (!confirmedSourceLabel.contains(label)) {
                unowned.add(label);
            }
        }
        return unowned;
    }

    /**
     * How much work a save would write.
     *
     * @return confirmed rings whose current shape the working .dsl does not hold, deleted rings
     *         and statements of rings no longer held included
     */
    public int unsavedRingCount() {
        int unsaved = 0;
        for (String statementId : knownRingStatementIds) {
            if (!confirmedStatementIds.contains(statementId)) {
                unsaved++;
            }
        }
        for (int ring = 0; ring < confirmedRingUnsaved.size(); ring++) {
            if (!candidateLabels.contains(confirmedSourceLabel.get(ring))
                    && (confirmedRingUnsaved.get(ring) || confirmedRingDeleted.get(ring))) {
                unsaved++;
            }
        }
        List<SurfaceSpline> current = currentCandidateRings();
        for (int candidate = 0; candidate < current.size(); candidate++) {
            if (current.get(candidate) != writtenCandidateRings.get(candidate)) {
                unsaved++;
            }
        }
        return unsaved;
    }

    /**
     * For each proposed label, the live ring standing for it now.
     *
     * @return one entry per {@link #candidateLabels} label: its confirmed ring, or {@code null}
     *         where that ring is deleted
     */
    public List<SurfaceSpline> currentCandidateRings() {
        List<SurfaceSpline> current = new ArrayList<>();
        for (String label : candidateLabels) {
            SurfaceSpline live = null;
            for (int ring = 0; ring < confirmedRings.size(); ring++) {
                if (label.equals(confirmedSourceLabel.get(ring)) && !confirmedRingDeleted.get(ring)) {
                    live = confirmedRings.get(ring);
                }
            }
            current.add(live);
        }
        return current;
    }

    /**
     * Packed xyz of a list of mesh vertices, the form a statement stores anchors in.
     *
     * @param vertexIds mesh vertices
     * @return their positions, three floats each
     */
    public float[] positionsOf(int[] vertexIds) {
        float[] xyz = new float[COORDINATES_PER_POINT * vertexIds.length];
        MeshTopology surface = scene.halfEdgeSurface();
        for (int anchor = 0; surface != null && anchor < vertexIds.length; anchor++) {
            surface.vertexPosition(vertexIds[anchor], scratchPosition);
            xyz[COORDINATES_PER_POINT * anchor] = scratchPosition.x;
            xyz[COORDINATES_PER_POINT * anchor + 1] = scratchPosition.y;
            xyz[COORDINATES_PER_POINT * anchor + 2] = scratchPosition.z;
        }
        return xyz;
    }

    /**
     * Packed xyz of a vertex loop, the reference a fit runs against and the path a ring authored
     * as an edge mask follows.
     */
    private double[] loopPositionsOf(int[] loop) {
        double[] xyz = new double[COORDINATES_PER_POINT * loop.length];
        MeshTopology surface = scene.halfEdgeSurface();
        for (int point = 0; surface != null && point < loop.length; point++) {
            surface.vertexPosition(loop[point], scratchPosition);
            xyz[COORDINATES_PER_POINT * point] = scratchPosition.x;
            xyz[COORDINATES_PER_POINT * point + 1] = scratchPosition.y;
            xyz[COORDINATES_PER_POINT * point + 2] = scratchPosition.z;
        }
        return xyz;
    }

    /**
     * Write every unsaved ring to the working .dsl in confirm order, appending a
     * {@code spline_ring} statement of its authored anchors and base normal for a ring never
     * saved and rewriting in place the statement of one edited since.
     *
     * @return true when the file was rewritten
     */
    public boolean saveRings() {
        lastError = "";
        Set<String> orphaned = new HashSet<>(knownRingStatementIds);
        orphaned.removeAll(confirmedStatementIds);
        int unsaved = unsavedRingCount();
        if (unsaved == 0) {
            lastRow = "nothing to save: the working .dsl holds every confirmed ring";
            return false;
        }
        String target = scene.workingDslPath();
        if (target == null) {
            lastError = "this scene has no working .dsl; " + unsaved + " ring(s) stay unsaved";
            Platforms.get().log(LOG_PREFIX + lastError);
            return false;
        }
        Path path = Path.of(target);
        String[] savedId = new String[confirmedRings.size()];
        int appended = 0;
        int deleted = 0;
        try {
            String source = Files.exists(path)
                    ? new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                    : "";
            if (source.isBlank()) {
                lastError = target + " holds no graph for the ring to read its geometry from";
                Platforms.get().log(LOG_PREFIX + lastError);
                return false;
            }
            Set<String> liveLabels = scene.ringMarksByLabel.keySet();
            // The proposed rings are written as one block: the ring_candidates line while every
            // one is live and unedited, otherwise each live ring frozen under its own label, an
            // unedited one as its exact edge loop and an edited one as a spline ring.
            List<SurfaceSpline> current = currentCandidateRings();
            if (!current.equals(writtenCandidateRings)) {
                PythonParser.ParsedNode candidates = RingDslWriter.candidatesStatement(
                        PythonParser.parse(source));
                if (candidates != null) {
                    candidatesLine = RingDslWriter.statementLine(source, candidates.id);
                }
                if (candidatesLine == null) {
                    throw new IllegalArgumentException("the working .dsl no longer holds the "
                            + RingDslWriter.CANDIDATES_NODE
                            + " statement the proposed rings came from");
                }
                PythonParser.ParsedNode original = PythonParser.parse(candidatesLine).get(0);
                String upstream = RingDslWriter.geometryInput(original);
                boolean unedited = true;
                for (int candidate = 0; candidate < current.size(); candidate++) {
                    unedited &= current.get(candidate)
                            == candidateRingByLabel.get(candidateLabels.get(candidate));
                    deleted += current.get(candidate) == null
                            && writtenCandidateRings.get(candidate) != null ? 1 : 0;
                }
                List<String> block = new ArrayList<>();
                String output = upstream;
                if (unedited) {
                    block.add(candidatesLine);
                    output = original.id + "." + RingDslWriter.geometryPort(original.type);
                }
                for (int candidate = 0; !unedited && candidate < current.size(); candidate++) {
                    SurfaceSpline ring = current.get(candidate);
                    String label = candidateLabels.get(candidate);
                    if (ring == null && !candidateRingByLabel.containsKey(label)) {
                        throw new IllegalArgumentException("proposed ring " + label
                                + " got no anchors at load, so freezing would drop it");
                    }
                    if (ring == null) {
                        continue;
                    }
                    if (ring == candidateRingByLabel.get(label)) {
                        int[] loop = orderedLoop(scene.halfEdgeSurface(),
                                ring.marks.markedByEdgeId);
                        if (loop.length == 0) {
                            throw new IllegalArgumentException("proposed ring " + label
                                    + " is not one closed edge loop, so it cannot be frozen");
                        }
                        block.add(RingDslWriter.exactLoopStatement(label, output,
                                SurfaceWaypoints.resolvingPoints(
                                        geodesics.metric.nearestVertex, loop, loop.length),
                                loop.length));
                    } else {
                        int index = confirmedRings.indexOf(ring);
                        int[] authored = confirmedAuthoredVertexId.get(index);
                        block.add(RingDslWriter.withMode(RingDslWriter.splineStatement(label,
                                output, SurfaceWaypoints.resolvingPoints(
                                        geodesics.metric.nearestVertex, authored,
                                        authored.length),
                                authored.length, confirmedBaseNormal.get(index)), label,
                                confirmedMode.get(index)));
                    }
                    output = label + "." + RingDslWriter.DEFAULT_UPSTREAM_PORT;
                }
                Set<String> blockIds = new HashSet<>(candidateLabels);
                blockIds.add(original.id);
                source = RingDslWriter.replaceBlock(source, blockIds, block, upstream, output);
            }
            for (int ring = 0; ring < confirmedRings.size(); ring++) {
                savedId[ring] = confirmedStatementIds.get(ring);
                boolean deleting = confirmedRingDeleted.get(ring);
                if (!confirmedRingUnsaved.get(ring) && !deleting
                        || candidateLabels.contains(confirmedSourceLabel.get(ring))) {
                    continue;
                }
                // A ring the file holds under another statement, such as a frozen proposed ring,
                // is removed with it, or rewritten in its place as a spline ring.
                String sourceLabel = confirmedSourceLabel.get(ring);
                PythonParser.ParsedNode owner = null;
                if (savedId[ring] == null && sourceLabel != null) {
                    owner = RingDslWriter.labelledStatement(PythonParser.parse(source), sourceLabel);
                }
                if (deleting) {
                    String statementId = owner != null ? owner.id : savedId[ring];
                    if (statementId != null) {
                        source = RingDslWriter.remove(source, statementId);
                    }
                    savedId[ring] = null;
                    deleted++;
                    continue;
                }
                int[] authored = confirmedAuthoredVertexId.get(ring);
                float[] authoredXyz = SurfaceWaypoints.resolvingPoints(
                        geodesics.metric.nearestVertex, authored, authored.length);
                if (confirmedBisectorEnds.get(ring) != null) {
                    // Written below, after every wall.
                    savedId[ring] = owner != null ? owner.id : savedId[ring];
                    continue;
                }
                float[] normal = confirmedBaseNormal.get(ring);
                if (owner != null) {
                    savedId[ring] = owner.id;
                    source = RingDslWriter.replace(source, owner.id,
                            List.of(RingDslWriter.splineStatement(owner.id,
                                    RingDslWriter.geometryInput(owner), authoredXyz,
                                    authored.length, normal)),
                            owner.id + "." + RingDslWriter.geometryPort(SPLINE_RING_NODE));
                } else if (savedId[ring] == null) {
                    savedId[ring] = RingDslWriter.nextRingId(source, liveLabels);
                    source = RingDslWriter.appendSpline(source, authoredXyz, authored.length,
                            normal, liveLabels);
                    appended++;
                } else {
                    source = RingDslWriter.replaceSpline(source, savedId[ring], authoredXyz,
                            authored.length, normal);
                }
                source = RingDslWriter.withMode(source, savedId[ring], confirmedMode.get(ring));
            }
            // A bisector names its walls by label, resolved only now, and reads them from the
            // chain, so it must follow them. Once any bisector or a wall one ends on changed,
            // every live bisector is written again at the end, each after the bisectors it ends
            // on, under the id it already had.
            List<Integer> pending = new ArrayList<>();
            boolean bisectorsChanged = false;
            for (int ring = 0; ring < confirmedRings.size(); ring++) {
                int[] ends = confirmedBisectorEnds.get(ring);
                if (ends == null || confirmedRingDeleted.get(ring)) {
                    continue;
                }
                pending.add(ring);
                bisectorsChanged |= confirmedRingUnsaved.get(ring);
                for (int end : ends) {
                    int wall = ringOfSerial(end);
                    bisectorsChanged |= wall >= 0 && confirmedRingUnsaved.get(wall);
                }
            }
            for (int ring : bisectorsChanged ? pending : List.<Integer>of()) {
                if (savedId[ring] != null) {
                    source = RingDslWriter.remove(source, savedId[ring]);
                }
            }
            while (bisectorsChanged && !pending.isEmpty()) {
                boolean wrote = false;
                for (Iterator<Integer> waiting = pending.iterator(); waiting.hasNext();) {
                    int ring = waiting.next();
                    int[] ends = confirmedBisectorEnds.get(ring);
                    String[] walls = new String[2];
                    for (int end = 0; end < 2; end++) {
                        int wall = ringOfSerial(ends[end]);
                        walls[end] = wall < 0 || pending.contains(wall) ? null
                                : savedId[wall] != null ? savedId[wall]
                                        : confirmedSourceLabel.get(wall);
                    }
                    if (walls[0] == null || walls[1] == null) {
                        continue;
                    }
                    appended += savedId[ring] == null ? 1 : 0;
                    savedId[ring] = savedId[ring] != null ? savedId[ring]
                            : RingDslWriter.nextRingId(source, liveLabels);
                    int[] authored = confirmedAuthoredVertexId.get(ring);
                    source = RingDslWriter.withMode(RingDslWriter.appendBisector(source,
                            savedId[ring], SurfaceWaypoints.resolvingPoints(
                                    geodesics.metric.nearestVertex, authored, authored.length),
                            authored.length, walls), savedId[ring], confirmedMode.get(ring));
                    waiting.remove();
                    wrote = true;
                }
                if (!wrote) {
                    throw new IllegalArgumentException(pending.size() + " bisector(s) end on "
                            + "each other in a cycle or on a wall that is gone, so none can be "
                            + "written after its walls");
                }
            }
            // A statement written for a ring the tool no longer holds, such as one whose confirm
            // was undone after a save, goes too, so the file matches the rings in memory.
            for (PythonParser.ParsedNode statement : PythonParser.parse(source)) {
                if (orphaned.contains(statement.id)) {
                    source = RingDslWriter.remove(source, statement.id);
                    deleted++;
                }
            }
            RingDslWriter.writeAtomically(path, RingDslWriter.wireRingInputs(source));
        } catch (IOException | RuntimeException failure) {
            lastError = "could not write " + target + ": " + failure.getMessage();
            Platforms.get().log(LOG_PREFIX + lastError);
            return false;
        }
        // The graph's marks still hold the labels the file gave up; drop them so they do not draw
        // again, and drop the deleted rings, whose statements are gone.
        for (int ring = confirmedRings.size() - 1; ring >= 0; ring--) {
            String sourceLabel = confirmedSourceLabel.get(ring);
            if (candidateLabels.contains(sourceLabel)) {
                // A proposed ring keeps its label, whichever form the block wrote it in.
                confirmedRingUnsaved.set(ring, false);
                if (confirmedRingDeleted.get(ring)) {
                    scene.ringMarksByLabel.remove(sourceLabel);
                    removeConfirmedRing(ring);
                }
                continue;
            }
            if (!confirmedRingUnsaved.get(ring) && !confirmedRingDeleted.get(ring)) {
                continue;
            }
            if (sourceLabel != null && !sourceLabel.equals(savedId[ring])) {
                scene.ringMarksByLabel.remove(sourceLabel);
                convertedGraphLabels.remove(sourceLabel);
            }
            if (confirmedRingDeleted.get(ring)) {
                removeConfirmedRing(ring);
                continue;
            }
            confirmedStatementIds.set(ring, savedId[ring]);
            confirmedSourceLabel.set(ring, savedId[ring]);
            confirmedRingUnsaved.set(ring, false);
            savedStatementByRing.put(confirmedRings.get(ring), savedId[ring]);
            savedAuthoredByStatement.put(savedId[ring], confirmedAuthoredVertexId.get(ring));
            savedNormalByStatement.put(savedId[ring], confirmedBaseNormal.get(ring));
            savedModeByStatement.put(savedId[ring], confirmedMode.get(ring));
        }
        knownRingStatementIds.clear();
        for (String statementId : confirmedStatementIds) {
            if (statementId != null) {
                knownRingStatementIds.add(statementId);
            }
        }
        List<SurfaceSpline> written = currentCandidateRings();
        writtenCandidateRings.clear();
        writtenCandidateRings.addAll(written);
        invalidateRings();
        lastRow = String.format(Locale.ROOT,
                "saved %d ring(s) to %s: %d appended, %d rewritten in place, %d deleted",
                unsaved, path, appended, unsaved - appended - deleted, deleted);
        Platforms.get().log(LOG_PREFIX + lastRow);
        return true;
    }

    /**
     * Forget every confirmed ring and the draft, for a model switch that leaves them describing a
     * surface which is no longer on screen, saying how many unsaved rings went with them.
     *
     * @param reason what dropped the rings, for the log line
     */
    public void discardConfirmedRings(String reason) {
        clearDraft();
        convertedGraphLabels.clear();
        history.clear();
        savedStatementByRing.clear();
        savedAuthoredByStatement.clear();
        savedNormalByStatement.clear();
        savedModeByStatement.clear();
        candidateLabels.clear();
        candidateRingByLabel.clear();
        writtenCandidateRings.clear();
        candidatesLine = null;
        if (confirmedRings.isEmpty()) {
            return;
        }
        Platforms.get().log(LOG_PREFIX + reason + " discarded " + confirmedRings.size()
                + " confirmed ring(s), " + unsavedRingCount() + " of them unsaved");
        confirmedRings.clear();
        confirmedAuthoredVertexId.clear();
        confirmedBaseNormal.clear();
        confirmedMode.clear();
        confirmedStatementIds.clear();
        confirmedRingUnsaved.clear();
        confirmedSourceLabel.clear();
        confirmedRingDeleted.clear();
        confirmedBisectorEnds.clear();
        confirmedRingSerial.clear();
        retiredSerialByLabel.clear();
        knownRingStatementIds.clear();
        closedRing.hoveredRing = -1;
        lastRow = "";
        invalidateRings();
    }

    /**
     * The graph showed new ring marks: drop the confirmed rings that only stood for the old
     * graph's rings, keeping unsaved edits and deletions, and give the new rings anchors on the
     * next frame.
     */
    public void graphRingsChanged() {
        if (draft != null) {
            discardDraft();
        }
        for (int ring = confirmedRings.size() - 1; ring >= 0; ring--) {
            if (confirmedSourceLabel.get(ring) != null && !confirmedRingUnsaved.get(ring)
                    && !confirmedRingDeleted.get(ring)) {
                knownRingStatementIds.remove(confirmedStatementIds.get(ring));
                // Its label is adopted again under the same serial, so bisectors keep their walls.
                retiredSerialByLabel.put(confirmedSourceLabel.get(ring),
                        confirmedRingSerial.get(ring));
                removeConfirmedRing(ring);
            }
        }
        convertedGraphLabels.retainAll(confirmedSourceLabel);
        graphRingsPending = true;
        invalidateRings();
    }

    private void removeConfirmedRing(int ring) {
        confirmedRings.remove(ring);
        confirmedAuthoredVertexId.remove(ring);
        confirmedBaseNormal.remove(ring);
        confirmedMode.remove(ring);
        confirmedStatementIds.remove(ring);
        confirmedRingUnsaved.remove(ring);
        confirmedSourceLabel.remove(ring);
        confirmedRingDeleted.remove(ring);
        confirmedBisectorEnds.remove(ring);
        confirmedRingSerial.remove(ring);
    }

    /**
     * Turn each unowned graph ring into a confirmed ring with authored anchors: a
     * {@code spline_ring}'s own, or anchors fitted to a proposed ring, which still draws its exact
     * loop until edited.
     *
     * @param surface the surface the graph's marks index
     * @throws IllegalArgumentException when the graph has more than one ring_candidates node
     */
    public void adoptGraphRings(MeshTopology surface) {
        graphRingsPending = false;
        long start = System.nanoTime();
        if (!readyGeodesics(surface)) {
            invalidateRings();
            return;
        }
        NodeGraphRuntime graph = scene.graph;
        List<GraphNodeContext> statements = graph == null ? List.of() : graph.root.declared;
        int adopted = 0;
        List<String> refused = new ArrayList<>();
        // A new graph either proposes rings itself, whose block the next freeze rewrites, or has
        // none, and any frozen rings are then statements like any other.
        int candidateStatements = 0;
        for (GraphNodeContext statement : statements) {
            if (RingDslWriter.CANDIDATES_NODE.equals(statement.type)) {
                candidateStatements++;
            }
        }
        if (candidateStatements > 1) {
            throw new IllegalArgumentException("the working graph has more than one "
                    + RingDslWriter.CANDIDATES_NODE + " statement, so a proposed ring has no one owner");
        }
        boolean proposes = candidateStatements == 1;
        candidateLabels.clear();
        candidateRingByLabel.clear();
        writtenCandidateRings.clear();
        candidatesLine = null;
        for (Map.Entry<String, boolean[]> entry : scene.ringMarksByLabel.entrySet()) {
            String label = entry.getKey();
            GraphNodeContext statement = null;
            for (GraphNodeContext labelled : statements) {
                if (label.equals(labelled.inputSources.get(RingDslWriter.LABEL_ARGUMENT))) {
                    statement = labelled;
                }
            }
            boolean proposed = proposes && statement == null;
            if (proposed) {
                candidateLabels.add(label);
                writtenCandidateRings.add(null);
            }
            if (confirmedSourceLabel.contains(label) || convertedGraphLabels.contains(label)) {
                continue;
            }
            if (statement != null && SplineCutNode.NODE_ID.equals(statement.type)) {
                // A bisector attaches to walls adopted before it, which in graph order are its
                // walls; from here on it names them by serial, not by label.
                float[] points = SurfaceWaypoints.parse(
                        String.valueOf(statement.inputSources.get(SplineCutNode.POINTS.name)));
                String[] endLabels = String.valueOf(
                        statement.inputSources.get(SplineCutNode.ENDS.name))
                        .split(SplineCutNode.END_SEPARATOR);
                int[] ends = new int[endLabels.length];
                for (int end = 0; end < ends.length; end++) {
                    int wall = confirmedSourceLabel.indexOf(endLabels[end].strip());
                    ends[end] = wall < 0 ? -1 : confirmedRingSerial.get(wall);
                }
                int[] anchors = SurfaceWaypoints.snap(geodesics.metric.nearestVertex, points,
                        points.length / COORDINATES_PER_POINT);
                Object modeArgument = statement.inputSources.get(SplineCutNode.MODE.name);
                RingSegmentMode bisectorMode = RingSegmentMode.named(
                        modeArgument instanceof String text ? text : null);
                lastError = "it needs two points and two end walls";
                SurfaceSpline traced = ends.length == 2 && anchors.length >= 2
                        ? bisector.traced(anchors, ends, bisectorMode) : null;
                lastError = traced != null && traced.marks.markedEdgeCount == 0
                        ? BisectorTool.WALLS_NOTHING : lastError;
                if (traced == null || traced.marks.markedEdgeCount == 0) {
                    refused.add(label + ": " + lastError);
                    continue;
                }
                confirmedRings.add(traced);
                confirmedAuthoredVertexId.add(anchors);
                confirmedBaseNormal.add(new float[COORDINATES_PER_POINT]);
                confirmedMode.add(bisectorMode);
                confirmedStatementIds.add(statement.nodeAssignmentId());
                knownRingStatementIds.add(statement.nodeAssignmentId());
                confirmedRingUnsaved.add(false);
                confirmedSourceLabel.add(label);
                confirmedRingDeleted.add(false);
                confirmedBisectorEnds.add(ends);
                Integer retired = retiredSerialByLabel.remove(label);
                confirmedRingSerial.add(retired != null ? retired : nextRingSerial++);
                adopted++;
                continue;
            }
            AuthoredSplineRing anchored = new AuthoredSplineRing(geodesics);
            boolean spline = statement != null
                    && SPLINE_RING_NODE.equals(statement.type);
            float[] normal = null;
            int[] anchors;
            if (spline) {
                float[] points = SurfaceWaypoints.parse(
                        String.valueOf(statement.inputSources.get(SplineRingNode.POINTS.name)));
                float[] written = SurfaceWaypoints.parse(
                        String.valueOf(statement.inputSources.get(SplineRingNode.NORMAL.name)));
                normal = written.length == COORDINATES_PER_POINT ? written : null;
                Object modeArgument = statement.inputSources.get(SplineRingNode.MODE.name);
                anchored.mode = RingSegmentMode.named(
                        modeArgument instanceof String text ? text : null);
                if (anchored.mode == RingSegmentMode.CREASE && readyCreases(surface)) {
                    anchored.creases = creases;
                }
                anchors = SurfaceWaypoints.snap(geodesics.metric.nearestVertex, points,
                        points.length / COORDINATES_PER_POINT);
            } else {
                anchors = fitLoop(orderedLoop(surface, entry.getValue()), anchored,
                        "graph ring " + label);
                normal = anchors.length > 0 ? written(anchored.planeNormal) : null;
            }
            if (anchors.length == 0 || !anchored.trace(anchors, anchors.length, normal,
                    SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
                refused.add(label + (anchors.length == 0 ? "" : ": " + anchored.failure));
                continue;
            }
            SurfaceSpline traced = SurfaceSpline.of(anchored.tracer);
            if (!spline) {
                int[] loop = orderedLoop(surface, entry.getValue());
                int[] noEdge = new int[loop.length];
                double[] noFraction = new double[loop.length];
                Arrays.fill(noEdge, -1);
                Arrays.fill(noFraction, -1.0);
                traced.path = new TracedSurfacePath(loopPositionsOf(loop), loop, noEdge,
                        noFraction, loop.length, true).measure();
                traced.marks = new EdgeMarks(entry.getValue(), 0);
            }
            confirmedRings.add(traced);
            confirmedAuthoredVertexId.add(anchored.authoredVertexId);
            confirmedBaseNormal.add(normal == null ? written(anchored.planeNormal) : normal);
            confirmedMode.add(anchored.mode);
            confirmedStatementIds.add(spline ? statement.nodeAssignmentId() : null);
            if (spline) {
                knownRingStatementIds.add(statement.nodeAssignmentId());
            }
            confirmedRingUnsaved.add(false);
            confirmedSourceLabel.add(label);
            confirmedRingDeleted.add(false);
            confirmedBisectorEnds.add(null);
            Integer retired = retiredSerialByLabel.remove(label);
            confirmedRingSerial.add(retired != null ? retired : nextRingSerial++);
            if (proposed) {
                candidateRingByLabel.put(label, traced);
                writtenCandidateRings.set(candidateLabels.size() - 1, traced);
            }
            adopted++;
        }
        if (adopted > 0 || !refused.isEmpty()) {
            lastRow = String.format(Locale.ROOT, "gave %d graph ring(s) anchors in %.0f ms",
                    adopted, (System.nanoTime() - start) / 1e6);
            lastError = refused.isEmpty() ? "" : "no anchors for graph ring(s) " + refused;
        }
        invalidateRings();
    }

    /**
     * Fit authored anchors to a closed vertex loop, every supporting anchor the fit settles on
     * made authored, and leave the plane they fit in {@code ring}'s plane normal.
     *
     * @param loop the loop's vertex ids in walking order
     * @param ring ring the fit and trace run on
     * @param name what the loop is, as {@link #lastError} names it
     * @return the anchors, or an empty array with {@link #lastError} saying why
     */
    public int[] fitLoop(int[] loop, AuthoredSplineRing ring, String name) {
        ring.tracer.maximumDepth = AuthoredSplineRing.SUPPORTING_FIT_DEPTH;
        if (loop.length < SplineAnchorFit.STARTING_ANCHORS) {
            lastError = name + " is not one closed edge loop";
            return new int[0];
        }
        if (!ring.fit.fit(loopPositionsOf(loop), loop.length, loop, 0)) {
            lastError = "no anchors fit " + name;
            return new int[0];
        }
        int[] anchors = Arrays.copyOf(ring.tracer.anchorVertexId, ring.tracer.anchorCount);
        if (!ring.trace(anchors, anchors.length, null, AuthoredSplineRing.SUPPORTING_FIT_DEPTH)) {
            lastError = name + ": " + ring.failure;
            return new int[0];
        }
        return anchors;
    }

    /**
     * Confirm a closed vertex loop, such as a neck's cross-section, as a new unsaved ring through
     * anchors fitted to it, as one undoable edit.
     *
     * @param loop the loop's vertex ids in walking order
     * @param what the edit's name in the undo history and the log
     * @return the new ring's index in {@link #confirmedRings}, or -1 with {@link #lastError}
     *         saying why
     */
    public int confirmLoop(int[] loop, String what) {
        lastError = "";
        MeshTopology surface = scene.halfEdgeSurface();
        if (surface == null || surface.faceCount() == 0 || !readyGeodesics(surface)) {
            lastError = lastError.isEmpty() ? NO_SURFACE_TO_RING : lastError;
            return -1;
        }
        AuthoredSplineRing anchored = new AuthoredSplineRing(geodesics);
        int[] anchors = fitLoop(loop, anchored, what);
        if (anchors.length == 0) {
            return -1;
        }
        float[] normal = written(anchored.planeNormal);
        if (!anchored.trace(anchors, anchors.length, normal,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
            lastError = what + ": " + anchored.failure;
            return -1;
        }
        return addConfirmedRing(anchored, normal, what);
    }

    /**
     * Append a traced ring to the confirmed rings as a new unsaved geodesic ring, one undoable
     * edit, and put its row under the status line.
     *
     * @param anchored the traced ring, its authored anchors in ring order
     * @param normal   the base normal its statement will hold, as {@link #written} returns it
     * @param what     the edit's name in the undo history and the log
     * @return the new ring's index in {@link #confirmedRings}
     */
    private int addConfirmedRing(AuthoredSplineRing anchored, float[] normal, String what) {
        RingToolState stateBefore = new RingToolState(this);
        SurfaceSpline spline = SurfaceSpline.of(anchored.tracer);
        confirmedRings.add(spline);
        confirmedAuthoredVertexId.add(anchored.authoredVertexId);
        confirmedBaseNormal.add(normal);
        confirmedMode.add(RingSegmentMode.GEODESIC);
        confirmedStatementIds.add(null);
        confirmedRingUnsaved.add(true);
        confirmedSourceLabel.add(null);
        confirmedRingDeleted.add(false);
        confirmedBisectorEnds.add(null);
        confirmedRingSerial.add(nextRingSerial++);
        recordEdit(what, stateBefore);
        invalidateRings();
        int ring = confirmedRings.size() - 1;
        lastRow = String.format(Locale.ROOT, "ring %d from %s: %d authored anchors, %d edges, "
                + "length %.5f, unsaved", drawnRingNumber(ring), what, spline.authoredCount(),
                spline.marks.markedEdgeCount, spline.path.length);
        Platforms.get().log(LOG_PREFIX + lastRow);
        return ring;
    }

    /** Build the pick buffer, the axis cache and the geodesic engine when the surface changed. */
    private void prepare(HalfEdgeMeshRuntime runtime, MeshTopology surface) {
        if (preparedSurface == surface && runtime.facePickReady()) {
            return;
        }
        long start = System.nanoTime();
        runtime.uploadFacePickBuffer(surface, scene.regionLayer.hiddenByActiveFace);
        preparedSurface = surface;
        if (!readyGeodesics(surface)) {
            return;
        }
        LimbAxis limbAxis = closedRing.limbAxis;
        limbAxis.cacheFor(surface);
        Platforms.get().log(String.format(Locale.ROOT,
                "[ring-tool] prepared %d faces: pick buffer, %s axis and the intrinsic "
                        + "triangulation (mean edge %.5f) in %.0f ms",
                surface.faceCount(), limbAxis.skeleton == null ? "curvature" : "skeleton",
                geodesics.metric.meanEdgeLength, (System.nanoTime() - start) / 1e6));
    }

    /**
     * Ready the geodesic engine over a surface: on the graph's own surface_metric when one was
     * measured on it, else on a metric measured here. A surface that cannot be measured is refused
     * with the reason in {@link #lastError} and the log, leaving the scene running without the tool.
     *
     * @param surface the surface rings are traced on
     * @return true when {@link #geodesics} runs on {@code surface}
     */
    private boolean readyGeodesics(MeshTopology surface) {
        if (geodesics != null && geodesics.mesh == surface) {
            return true;
        }
        if (surface == refusedSurface) {
            return false;
        }
        NodeGraphRuntime graph = scene.graph;
        SurfaceMetric metric = null;
        for (GraphNodeContext record : graph == null ? List.<GraphNodeContext>of() : graph.root.children) {
            if (record.getOutput(SurfaceMetricNode.METRIC.name)
                    instanceof SurfaceMetric measured && measured.sourceMesh == surface) {
                metric = measured;
            }
        }
        try {
            geodesics = SurfaceGeodesics.over(metric != null ? metric : SurfaceMetric.of(surface));
            return true;
        } catch (RuntimeException failure) {
            refusedSurface = surface;
            geodesics = null;
            lastError = "the ring tool cannot trace rings on this surface: "
                    + failure.getMessage();
            Platforms.get().log(LOG_PREFIX + lastError);
            return false;
        }
    }

    /**
     * Hand the runtime what it draws: every ring's loop as its own coloured line group, the
     * anchors as markers in their two classes, and each ring's number as a label at its centroid.
     * Runs when a ring, the draft, the hover or the graph's marks changed, not per frame.
     */
    public void uploadOverlay() {
        if (!overlayStale
                || !(scene.surfaceRuntime() instanceof MeshOverlayRuntime overlay)) {
            return;
        }
        if (ringsStale) {
            rebuildRings();
        }
        overlayStale = false;
        SurfaceSpline editing = draft != null ? draft : previewValid ? previewSpline : null;
        uploadedPreview = previewValid ? previewSpline : null;
        uploadedDraft = draft;
        int hoveredRing = closedRing.hoveredRing;
        String hoveredGraphRing = closedRing.hoveredGraphRing;
        uploadedHoveredRing = hoveredRing;
        uploadedHoveredGraphRing = hoveredGraphRing;
        uploadedSelected = selectedAnchorVertexId;
        int rings = drawnRingColors.length;
        uploadedSnap = previewAuthoredVertexId;
        boolean drawingEditing = editing != null && editing.path.pointCount >= 2;
        LineSet edited = drawingEditing ? splineLines(scene.halfEdgeSurface(), editing)
                : new LineSet(0);
        LineSet lines = new LineSet(
                (ringLines.vertexCount() + edited.vertexCount()) / 2);
        System.arraycopy(ringLines.vertices, 0, lines.vertices, 0, ringLines.cursor);
        System.arraycopy(edited.vertices, 0, lines.vertices, ringLines.cursor, edited.cursor);
        lines.cursor = ringLines.cursor + edited.cursor;
        int[] groupStart = new int[rings + (drawingEditing ? 2 : 1)];
        System.arraycopy(ringSegmentStart, 0, groupStart, 0, rings + 1);
        Color[] groupColor = new Color[rings + (drawingEditing ? 1 : 0)];
        System.arraycopy(drawnRingColors, 0, groupColor, 0, rings);
        // The preview, the draft and the ring a click would re-open or convert are drawn in the
        // colour of the mode the draft runs in: red geodesic, purple crease, or black while the
        // sub-tool drawing it calls it invalid. Every sub-tool the tool holds is a ring sub-tool,
        // the draft's owner whenever a draft is open.
        RingSubTool drawing = (RingSubTool) activeSubTool;
        if (drawingEditing) {
            groupStart[rings + 1] = groupStart[rings] + edited.vertexCount() / 2;
            groupColor[rings] = drawing.drawsInvalid() ? BisectorTool.CROSSING_COLOR
                    : (draft != null ? draftMode : newDraftMode())
                            == RingSegmentMode.CREASE ? CREASE_DRAFT_COLOR : GEODESIC_DRAFT_COLOR;
        }
        if (hoveredRing >= 0 && drawnRingNumber(hoveredRing) < rings) {
            groupColor[drawnRingNumber(hoveredRing)] =
                    confirmedMode.get(hoveredRing) == RingSegmentMode.CREASE ? CREASE_DRAFT_COLOR
                            : GEODESIC_DRAFT_COLOR;
        }
        int graphIndex = 0;
        for (String graphLabel : unownedGraphRingLabels()) {
            if (graphLabel.equals(hoveredGraphRing) && graphIndex < rings) {
                groupColor[graphIndex] = GEODESIC_DRAFT_COLOR;
            }
            graphIndex++;
        }
        overlay.setLineGroups(lines, groupStart, groupColor);
        overlay.setLabels(ringLabelXyz, drawnRingLabel, drawnRingColors, ringLabelLift);
        // Only the draft shows its anchors, as fixed-pixel discs: supporting smallest and white,
        // authored larger and cyan, the selected one largest and yellow. Smaller classes go
        // first so a larger disc wins where two overlap.
        int anchors = draft == null ? 0 : draft.anchorCount;
        // Then the vertices the active sub-tool marks, such as a bisector's waiting end.
        int[] bisectorEnds = drawing.markedVertices();
        int discs = anchors + bisectorEnds.length;
        int[] anchorVertexId = new int[discs];
        Color[] anchorColor = new Color[discs];
        float[] anchorDiameter = new float[discs];
        int cursor = 0;
        for (float diameter : new float[] { SUPPORTING_ANCHOR_PIXELS, AUTHORED_ANCHOR_PIXELS,
            SELECTED_ANCHOR_PIXELS }) {
            for (int anchor = 0; anchor < anchors; anchor++) {
                int vertexId = draft.anchorVertexId[anchor];
                if (anchorDiameterPixels(vertexId, draft.anchorAuthored[anchor]) != diameter) {
                    continue;
                }
                anchorVertexId[cursor] = vertexId;
                anchorDiameter[cursor] = diameter;
                anchorColor[cursor++] = diameter == SUPPORTING_ANCHOR_PIXELS
                        ? SUPPORTING_ANCHOR_COLOR
                        : diameter == AUTHORED_ANCHOR_PIXELS ? AUTHORED_ANCHOR_COLOR
                                : SELECTED_ANCHOR_COLOR;
            }
        }
        for (int end : bisectorEnds) {
            anchorVertexId[cursor] = end;
            anchorDiameter[cursor] = SELECTED_ANCHOR_PIXELS;
            anchorColor[cursor++] = SELECTED_ANCHOR_COLOR;
        }
        overlay.setAnchorDiscs(anchorVertexId, anchorColor, anchorDiameter);
    }

    /**
     * The drawn diameter of one of the draft's anchors.
     *
     * @param vertexId mesh vertex the anchor sits on
     * @param authored whether the user placed the anchor rather than the fit
     * @return the disc's diameter in framebuffer pixels
     */
    public float anchorDiameterPixels(int vertexId, boolean authored) {
        if (!authored) {
            return SUPPORTING_ANCHOR_PIXELS;
        }
        return vertexId == selectedAnchorVertexId ? SELECTED_ANCHOR_PIXELS
                : AUTHORED_ANCHOR_PIXELS;
    }

    /**
     * Rebuild the per-ring arrays the overlay draws from, the graph's ring marks then the rings
     * confirmed here. A ring converted, hidden or re-opened as the draft keeps its place but draws
     * nothing.
     */
    private void rebuildRings() {
        ringsStale = false;
        MeshTopology surface = scene.halfEdgeSurface();
        List<float[]> perRing = new ArrayList<>();
        List<LineSet> perRingLines = new ArrayList<>();
        List<String> perRingLiveLabel = new ArrayList<>();
        Map<String, boolean[]> graphMarks = scene.ringMarksByLabel;
        graphRingSegments = new ArrayList<>();
        graphRingLabels = new ArrayList<>();
        List<String> unownedLabels = unownedGraphRingLabels();
        if (surface != null) {
            for (Map.Entry<String, boolean[]> entry : graphMarks.entrySet()) {
                if (!unownedLabels.contains(entry.getKey())) {
                    continue;
                }
                boolean undrawn = convertedGraphLabels.contains(entry.getKey())
                        || hiddenRingLabels.contains(entry.getKey());
                float[] ringSegments = undrawn ? new float[0]
                        : markedEdgeSegments(surface, entry.getValue());
                perRing.add(ringSegments);
                perRingLiveLabel.add(entry.getKey());
                LineSet edges = new LineSet(ringSegments.length / SEGMENT_FLOATS);
                boolean[] marks = entry.getValue();
                for (int index = 0; !undrawn && index < surface.edgeCount(); index++) {
                    int edgeId = surface.edgeIdAt(index);
                    if (edgeId < marks.length && marks[edgeId]) {
                        edges.edge(surface, edgeId);
                    }
                }
                perRingLines.add(edges);
                if (!undrawn) {
                    graphRingSegments.add(ringSegments);
                    graphRingLabels.add(entry.getKey());
                }
            }
        }
        for (int ring = 0; ring < confirmedRings.size(); ring++) {
            String sourceLabel = confirmedSourceLabel.get(ring);
            String liveLabel = sourceLabel != null ? sourceLabel
                    : UNSAVED_RING_PREFIX + drawnRingNumber(ring);
            boolean hidden = ring == draftSourceRing && draft != null
                    || confirmedRingDeleted.get(ring) || hiddenRingLabels.contains(liveLabel);
            SurfaceSpline spline = confirmedRings.get(ring);
            perRing.add(hidden ? new float[0] : closedPolylineSegments(
                    spline.path.drawnPolyline(spline.path.surfacePositions)));
            perRingLines.add(hidden ? new LineSet(0) : splineLines(surface, spline));
            perRingLiveLabel.add(liveLabel);
        }
        drawnRingLabel = ringNumberTexts(
                surface == null ? List.of() : unownedLabels, confirmedRings.size());
        int total = 0;
        for (LineSet lines : perRingLines) {
            total += lines.vertexCount() / 2;
        }
        ringLines = new LineSet(total);
        ringSegmentStart = new int[perRing.size() + 1];
        drawnRingColors = new Color[perRing.size()];
        ringLabelXyz = new float[COORDINATES_PER_POINT * perRing.size()];
        ringLabelLift = new float[perRing.size()];
        for (int ring = 0; ring < perRing.size(); ring++) {
            float[] ringSegments = perRing.get(ring);
            LineSet lines = perRingLines.get(ring);
            System.arraycopy(lines.vertices, 0, ringLines.vertices, ringLines.cursor,
                    lines.cursor);
            ringLines.cursor += lines.cursor;
            ringSegmentStart[ring + 1] = ringLines.vertexCount() / 2;
            String note = ringNoteByLabel.get(perRingLiveLabel.get(ring));
            drawnRingColors[ring] = note != null ? MARKED_RING_COLOR : Palettes.RING.color(ring);
            if (ringSegments.length == 0 && ring < drawnRingLabel.length) {
                drawnRingLabel[ring] = "";
            } else if (note != null && ring < drawnRingLabel.length) {
                drawnRingLabel[ring] += " " + note;
            }
            measureLoop(ringSegments, ring);
        }
    }

    /**
     * Place one ring's number: the length-weighted centroid of its segments goes in the label
     * positions, and the ring's outer radius sets how far the number floats toward the eye, which
     * brings it level with the loop's nearest point so the surface hides only a ring behind it.
     *
     * @param segments the ring's packed segment endpoints
     * @param ring     the ring's position in the overlay
     */
    private void measureLoop(float[] segments, int ring) {
        double weightedX = 0.0;
        double weightedY = 0.0;
        double weightedZ = 0.0;
        double totalSpan = 0.0;
        for (int base = 0; base + SEGMENT_FLOATS <= segments.length; base += SEGMENT_FLOATS) {
            double spanX = segments[base + COORDINATES_PER_POINT] - segments[base];
            double spanY = segments[base + COORDINATES_PER_POINT + 1] - segments[base + 1];
            double spanZ = segments[base + COORDINATES_PER_POINT + 2] - segments[base + 2];
            double span = Math.sqrt(spanX * spanX + spanY * spanY + spanZ * spanZ);
            weightedX += span * (segments[base] + HALF * spanX);
            weightedY += span * (segments[base + 1] + HALF * spanY);
            weightedZ += span * (segments[base + 2] + HALF * spanZ);
            totalSpan += span;
        }
        if (totalSpan <= 0.0) {
            return;
        }
        float centroidX = (float) (weightedX / totalSpan);
        float centroidY = (float) (weightedY / totalSpan);
        float centroidZ = (float) (weightedZ / totalSpan);
        ringLabelXyz[COORDINATES_PER_POINT * ring] = centroidX;
        ringLabelXyz[COORDINATES_PER_POINT * ring + 1] = centroidY;
        ringLabelXyz[COORDINATES_PER_POINT * ring + 2] = centroidZ;
        double outerRadius = 0.0;
        for (int base = 0; base + SEGMENT_FLOATS <= segments.length; base += SEGMENT_FLOATS) {
            double dx = segments[base] - centroidX;
            double dy = segments[base + 1] - centroidY;
            double dz = segments[base + 2] - centroidZ;
            outerRadius = Math.max(outerRadius, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        ringLabelLift[ring] = (float) outerRadius;
    }

    /**
     * A closed polyline as the packed endpoint pairs the line overlay draws, including the span
     * that closes the last point back onto the first.
     */
    private static float[] closedPolylineSegments(float[] polyline) {
        int points = polyline.length / COORDINATES_PER_POINT;
        if (points < 2) {
            return new float[0];
        }
        float[] segments = new float[SEGMENT_FLOATS * points];
        for (int point = 0; point < points; point++) {
            int next = (point + 1) % points;
            int target = SEGMENT_FLOATS * point;
            System.arraycopy(polyline, COORDINATES_PER_POINT * point, segments, target,
                    COORDINATES_PER_POINT);
            System.arraycopy(polyline, COORDINATES_PER_POINT * next, segments,
                    target + COORDINATES_PER_POINT, COORDINATES_PER_POINT);
        }
        return segments;
    }

    /**
     * A spline's loop as overlay lines on the surface: each span between consecutive points runs
     * over the face both points bound and carries that face's normal, so the far side drops out.
     *
     * @param mesh   the surface the spline's vertex and edge ids index, or {@code null}
     * @param spline the spline whose path's surface positions are drawn
     * @return the closed loop's segments, empty without a surface
     */
    public static LineSet splineLines(MeshTopology mesh, SurfaceSpline spline) {
        TracedSurfacePath path = spline.path;
        int spans = path.closed ? path.pointCount : path.pointCount - 1;
        if (mesh == null || spans < 1) {
            return new LineSet(0);
        }
        LineSet lines = new LineSet(spans);
        for (int point = 0; point < spans; point++) {
            lines.pathStep(mesh, path.surfacePositions, path.vertexId, path.edgeId, path.faceId,
                    point, (point + 1) % path.pointCount);
        }
        return lines;
    }

    /**
     * Every marked mesh edge as one packed endpoint pair, the shape the thick overlay draws, so a
     * ring authored as an edge mask needs no ordering to render.
     *
     * @param mesh          surface the mask indexes by edge id
     * @param marksByEdgeId edge-id-indexed mask
     * @return packed segment endpoints, six floats per marked edge
     */
    public static float[] markedEdgeSegments(MeshTopology mesh, boolean[] marksByEdgeId) {
        int marked = 0;
        for (int index = 0; marksByEdgeId != null && index < mesh.edgeCount(); index++) {
            int edgeId = mesh.edgeIdAt(index);
            if (edgeId < marksByEdgeId.length && marksByEdgeId[edgeId]) {
                marked++;
            }
        }
        float[] segments = new float[SEGMENT_FLOATS * marked];
        Vector3f tail = new Vector3f();
        Vector3f head = new Vector3f();
        int cursor = 0;
        for (int index = 0; marksByEdgeId != null && index < mesh.edgeCount(); index++) {
            int edgeId = mesh.edgeIdAt(index);
            if (edgeId >= marksByEdgeId.length || !marksByEdgeId[edgeId]) {
                continue;
            }
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), tail);
            mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), head);
            segments[cursor++] = tail.x;
            segments[cursor++] = tail.y;
            segments[cursor++] = tail.z;
            segments[cursor++] = head.x;
            segments[cursor++] = head.y;
            segments[cursor++] = head.z;
        }
        return segments;
    }
}
