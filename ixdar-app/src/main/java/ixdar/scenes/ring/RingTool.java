package ixdar.scenes.ring;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.LimbAxis;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.AuthoredSplineRing;
import ixdar.geometry.mesh.data.paths.GirdlingPlane;
import ixdar.geometry.mesh.data.paths.RingSegmentMode;
import ixdar.geometry.mesh.data.paths.SplineAnchorFit;
import ixdar.geometry.mesh.data.paths.SurfaceCreases;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.data.paths.TracedSurfacePath;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.data.SurfaceCreasesNode;
import ixdar.geometry.mesh.nodes.data.SurfaceMetricNode;
import ixdar.geometry.mesh.nodes.selection.LoopThroughPointsNode;
import ixdar.geometry.mesh.nodes.selection.RingDslWriter;
import ixdar.geometry.mesh.nodes.selection.SelectRingNode;
import ixdar.geometry.mesh.nodes.selection.SplineRingNode;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.LineSet;
import ixdar.graphics.render.model.MeshOverlayRuntime;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ControlHint;

/**
 * Hover to preview the ring girdling the limb under the cursor; click to turn it into a draft
 * whose authored anchors the user adds, selects, drags and deletes; Enter confirms the draft.
 * Confirming changes memory only: {@link #saveRings} alone writes the working .dsl.
 */
public final class RingTool implements EditTool {

    public static final String STATUS_LINE =
            "ring tool: hover to preview, click to draft, click to add an anchor, drag or Delete "
                    + "one, Delete with none selected removes the ring, Ctrl+Z undo, Ctrl+Shift+Z "
                    + "or Ctrl+Y redo, Enter confirm, X discard, Ctrl+S save, G region colours, "
                    + "Ctrl+T region tool, Esc back to orbit";

    public static final String LOG_PREFIX = "[ring-tool] ";

    public static final String TOOL_NAME = "ring tool";

    public static final String UNSAVED_RING_PREFIX = "ring #";

    public static final String REMOVE_SELECTED_ANCHOR_HINT = "remove anchor (or ring)";

    public static final String REDO_HINT = "redo ring edit";

    public static final String NO_SELECTED_ANCHOR = "no authored anchor is selected";

    public static final int PREVIEW_COLOR = 0xFF2D95;

    public static final int MARKED_RING_COLOR = 0xFF1A1A;

    public static final int[] RING_COLORS = {
        0x2ADF4F, 0x2E9BFF, 0xFFD60A, 0xFF9F0A, 0x9D7BFF, 0xD08A4A, 0xE8E8A0, 0xB6FF3B };

    public static final String RING_LABEL_PREFIX = "ring";

    public static final String[] UNNUMBERED_RING_LABELS = {
        LoopThroughPointsNode.DEFAULT_MARK_LABEL, SplineRingNode.DEFAULT_MARK_LABEL,
        SelectRingNode.SELECTED_LABEL };

    public static final int MAXIMUM_RING_DIGITS = 9;

    public static final int SUPPORTING_ANCHOR_COLOR = 0xFFFFFF;

    public static final int AUTHORED_ANCHOR_COLOR = 0x00E5FF;

    public static final int SELECTED_ANCHOR_COLOR = 0xFFE000;

    public static final float RING_HIT_PIXELS = 8f;

    public static final float SUPPORTING_ANCHOR_PIXELS = 3f;

    public static final float AUTHORED_ANCHOR_PIXELS = 5f;

    public static final float SELECTED_ANCHOR_PIXELS = 7f;

    public static final int COORDINATES_PER_POINT = 3;

    public static final int SEGMENT_FLOATS = 2 * COORDINATES_PER_POINT;

    public static final float HALF = 0.5f;

    public static final String SPLINE_RING_NODE = "spline_ring";

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

    /** Skeleton and curvature the preview plane's normal comes from, cached per model. */
    public final LimbAxis limbAxis = new LimbAxis();

    /** Geodesic engine over the current surface, built once and reused every frame. */
    public SurfaceGeodesics geodesics;

    /** Surface the geodesic engine could not be built on, which the tool leaves alone. */
    public MeshTopology refusedSurface;

    /** Whether the tool is taking the mouse. */
    public boolean active;

    /** The hover preview, a one-anchor ring through the vertex under the cursor, or null. */
    public SurfaceSpline previewSpline;

    /** Anchors the fit settled on for the preview. */
    public int previewAnchorCount;

    /** Mesh edges the girdling cut the preview's plane came from crosses. */
    public int previewGirdleEdgeCount;

    /** Whether the last frame found a loop under the cursor. */
    public boolean previewValid;

    /** Wall time the last frame that fitted a preview spent, pick included, in milliseconds. */
    public double previewMillis;

    /** Euclidean length of the previewed spline. */
    public double previewLength;

    /** Tolerance the last fit ran to, in model units. */
    public double previewTolerance;

    /** Largest distance the fitted spline still sits from its reference loop. */
    public double previewDeviation;

    /** Whether the fit ran out of anchors before it met the tolerance. */
    public boolean previewAnchorCapReached;

    /** Geodesics the last preview fit computed, the cost the hover budget is spent on. */
    public long previewGeodesicCount;

    /** Surface point under the cursor on the last frame, packed xyz. */
    public final float[] previewHitPoint = new float[COORDINATES_PER_POINT];

    /** Normal of the plane the preview was cut with, at the precision a save writes. */
    public final float[] previewPlaneNormal = new float[COORDINATES_PER_POINT];

    /** Limb axis estimated at the hit point, packed xyz. */
    public final float[] previewLimbAxis = new float[COORDINATES_PER_POINT];

    /** Mesh vertex a click on the preview makes the draft's first authored anchor. */
    public int previewAuthoredVertexId = -1;

    /** Whether the loop kept this frame came from the skeleton estimate or the curvature one. */
    public boolean axisFromSkeleton;

    /** Confirmed ring under the cursor, which a click re-opens as the draft, or -1 for none. */
    public int hoveredRing = -1;

    /** Graph ring under the cursor, which a click turns into a draft, or null for none. */
    public String hoveredGraphRing;

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
    public int[] drawnRingColorRgb = new int[0];

    /** One-line summary of the last edit, shown under the status line. */
    public String lastRow = "";

    /** Why the last action failed, or empty when it did not. */
    public String lastError = "";

    /** Bumped whenever the rings change, so a tool built on them knows to rebuild. */
    public int ringRevision;

    /**
     * Rings drawn in {@link #MARKED_RING_COLOR} with a note after their number, such as one that
     * loops a handle, by live ring label.
     */
    public Map<String, String> ringNoteByLabel = Map.of();

    /** Live labels of the rings not drawn because every region they bound is hidden. */
    public Set<String> hiddenRingLabels = Set.of();

    private final GirdlingPlane girdle = new GirdlingPlane();
    private final float[] limbDirection = new float[COORDINATES_PER_POINT];
    private final float[] previewedHit = new float[COORDINATES_PER_POINT];
    private final Vector3f scratchPosition = new Vector3f();
    private AuthoredSplineRing previewRing;
    private AuthoredSplineRing draftRing;
    private MeshTopology preparedSurface;
    private int previewedFace = -1;
    private boolean pendingClick;
    private RingToolState dragBefore;
    private List<float[]> graphRingSegments = new ArrayList<>();
    private List<String> graphRingLabels = new ArrayList<>();
    private LineSet ringLines = new LineSet(0);
    private int[] ringSegmentStart = { 0 };
    private float[] ringLabelXyz = new float[0];
    private float[] ringLabelLift = new float[0];
    private boolean ringsStale = true;
    private boolean overlayStale = true;
    private SurfaceSpline uploadedPreview;
    private SurfaceSpline uploadedDraft;
    private int uploadedHoveredRing = -1;
    private String uploadedHoveredGraphRing;
    private int uploadedSelected = -1;

    /**
     * Bind the tool to the scene it authors rings on.
     *
     * @param ringScene scene owning the surface, camera and working graph
     */
    public RingTool(RingScene ringScene) {
        this.scene = ringScene;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    /** Take clicks and anchor drags from the orbit and start the hover preview. */
    @Override
    public void activate() {
        active = true;
        lastError = "";
        // Another tool may have drawn its own anchors over the draft's since this one uploaded.
        overlayStale = true;
        if (scene.orbitMouse != null) {
            scene.orbitMouse.toolClick = button -> requestClick();
            scene.orbitMouse.toolGrab = this::grabAnchor;
            scene.orbitMouse.toolRelease = this::releaseAnchor;
        }
        Platforms.get().log(LOG_PREFIX + STATUS_LINE);
    }

    /**
     * Stop the hover preview and hand the mouse back, keeping every confirmed ring and an open
     * draft, which the next activation edits on.
     */
    @Override
    public void deactivate() {
        if (draggingAnchor) {
            releaseAnchor();
        }
        active = false;
        pendingClick = false;
        previewValid = false;
        previewSpline = null;
        hoveredRing = -1;
        hoveredGraphRing = null;
        hoveredAnchor = -1;
        previewAnchorCount = 0;
        overlayStale = true;
        if (scene.orbitMouse != null) {
            scene.orbitMouse.toolClick = null;
            scene.orbitMouse.toolGrab = null;
            scene.orbitMouse.toolRelease = null;
        }
        Platforms.get().log(LOG_PREFIX + "inactive with " + confirmedRings.size()
                + " confirmed ring(s), " + unsavedRingCount() + " unsaved"
                + (draft == null ? "" : ", the draft kept"));
    }

    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint("click", "draft ring / add or pick anchor"));
        controls.add(new ControlHint("drag anchor", "move it"));
        controls.add(new ControlHint(Keys.X, "X", "discard draft", () -> discardDraft()));
        controls.add(new ControlHint(Keys.DELETE, "del", REMOVE_SELECTED_ANCHOR_HINT,
                () -> deletePressed()));
        controls.add(new ControlHint(Keys.BACKSPACE, "backspace", REMOVE_SELECTED_ANCHOR_HINT,
                () -> deletePressed()));
        controls.add(new ControlHint(Keys.Z, true, true, "ctrl+shift+Z", REDO_HINT,
                () -> redo()));
        controls.add(new ControlHint(Keys.Z, true, "ctrl+Z", "undo ring edit", () -> undo()));
        controls.add(new ControlHint(Keys.Y, true, "ctrl+Y", REDO_HINT, () -> redo()));
        controls.add(new ControlHint(Keys.ENTER, "enter", "confirm draft",
                () -> confirmDraft()));
        // T switches the draft between the geodesic spline and the crease path, as one edit.
        controls.add(new ControlHint(Keys.T, "T", "mode: " + draftMode.label, () -> {
            lastError = "";
            if (!active || draft == null) {
                lastError = "no draft to change the segment mode of";
                return;
            }
            RingSegmentMode next = draftMode == RingSegmentMode.GEODESIC ? RingSegmentMode.CREASE
                    : RingSegmentMode.GEODESIC;
            if (next == RingSegmentMode.CREASE && !readyCreases(scene.halfEdgeSurface())) {
                return;
            }
            RingToolState stateBefore = new RingToolState(this);
            RingSegmentMode previous = draftMode;
            draftMode = next;
            if (!retraceDraft(draftAuthoredVertexId, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH,
                    false)) {
                draftMode = previous;
                return;
            }
            recordEdit("segment mode " + next.label, stateBefore);
            reportDraft("now in " + next.label + " mode");
            scene.refreshControls();
        }));
        // V drafts the ring along the groove nearest the hovered point, on the preview's plane.
        controls.add(new ControlHint(Keys.V, "V", "groove ring at the cursor", () -> {
            lastError = "";
            if (!active || draft != null || !previewValid || previewAuthoredVertexId < 0) {
                lastError = draft != null ? "confirm or discard the draft before ringing a groove"
                        : "no preview under the cursor to ring a groove at";
                return;
            }
            if (!readyCreases(scene.halfEdgeSurface())) {
                return;
            }
            RingToolState stateBefore = new RingToolState(this);
            holdDraft();
            long start = System.nanoTime();
            if (!draftRing.traceGroove(previewAuthoredVertexId, previewPlaneNormal,
                    SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
                lastError = "groove ring refused: " + draftRing.failure;
                Platforms.get().log(LOG_PREFIX + lastError);
                return;
            }
            System.arraycopy(draftRing.grooveNormal, 0, draftBaseNormal, 0,
                    COORDINATES_PER_POINT);
            draftMode = RingSegmentMode.CREASE;
            draftAuthoredVertexId = draftRing.authoredVertexId;
            draft = SurfaceSpline.of(draftRing.tracer);
            draftDepth = SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH;
            draftMillis = (System.nanoTime() - start) / 1e6;
            draftSourceRing = -1;
            draftSourceLabel = null;
            selectedAnchorVertexId = -1;
            recordEdit("groove ring drafted", stateBefore);
            reportDraft(String.format(Locale.ROOT, "along the groove, %.0f%% of it in a groove",
                    AuthoredSplineRing.PERCENT * draftRing.grooveFraction));
            scene.refreshControls();
        }));
    }

    /**
     * Ready {@link #creases} on a surface: the working graph's own surface_creases output when
     * one was built on it, else measured here once per model, timed and logged.
     *
     * @param surface the surface rings are traced on
     * @return true when {@link #creases} lies on {@code surface}; {@link #lastError} says why not
     */
    private boolean readyCreases(MeshTopology surface) {
        if (creases != null && creases.sourceMesh == surface) {
            return true;
        }
        if (surface == null) {
            lastError = "no surface to measure grooves on";
            return false;
        }
        NodeGraphRuntime graph = scene.getLastGraphRuntime();
        for (PythonParser.ParsedNode statement : graph == null ? List.<PythonParser.ParsedNode>of()
                : graph.statements) {
            if (graph.getNodeOutput(statement.id, SurfaceCreasesNode.CREASES.name)
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
     * ring stands for, then every confirmed ring not deleted, an open draft's original included.
     *
     * @return masks by ring label: the graph or statement label, or {@code ring #N} by drawn
     *         number for a ring only this session holds
     */
    public Map<String, boolean[]> liveRingMarks() {
        Map<String, boolean[]> marks = new LinkedHashMap<>();
        for (Map.Entry<String, SurfaceSpline> ring : liveRingSplines().entrySet()) {
            marks.put(ring.getKey(), ring.getValue() == null
                    ? scene.ringMarksByLabel.get(ring.getKey())
                    : ring.getValue().markedByEdgeId);
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
            splines.put(label != null ? label : UNSAVED_RING_PREFIX + drawnRingNumber(ring),
                    confirmedRings.get(ring));
        }
        return splines;
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
            pendingClick = false;
            uploadOverlay();
            return;
        }
        long start = System.nanoTime();
        prepare(runtime, surface);
        if (geodesics == null || geodesics.mesh != surface) {
            pendingClick = false;
            uploadOverlay();
            return;
        }
        boolean hitValid = scene.pickCursor(runtime, surface);
        int hitVertexId = scene.cursorVertexId;
        int faceId = scene.cursorFaceId;
        float[] hitPoint = scene.cursorPoint;
        if (hitValid) {
            System.arraycopy(hitPoint, 0, previewHitPoint, 0, COORDINATES_PER_POINT);
        }
        hoveredRing = -1;
        hoveredGraphRing = null;
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
        } else if (hitValid) {
            hoveredRing = ringUnderCursor();
            double graphReach = RING_HIT_PIXELS * scene.worldPerPixel();
            for (int ring = 0; hoveredRing < 0 && ring < graphRingSegments.size(); ring++) {
                float[] segments = graphRingSegments.get(ring);
                for (int base = 0; base + SEGMENT_FLOATS <= segments.length;
                        base += SEGMENT_FLOATS) {
                    double spanX = segments[base + COORDINATES_PER_POINT] - segments[base];
                    double spanY = segments[base + COORDINATES_PER_POINT + 1] - segments[base + 1];
                    double spanZ = segments[base + COORDINATES_PER_POINT + 2] - segments[base + 2];
                    double toX = hitPoint[0] - segments[base];
                    double toY = hitPoint[1] - segments[base + 1];
                    double toZ = hitPoint[2] - segments[base + 2];
                    double squared = spanX * spanX + spanY * spanY + spanZ * spanZ;
                    double along = squared <= 0.0 ? 0.0
                            : Math.max(0.0, Math.min(1.0,
                                    (toX * spanX + toY * spanY + toZ * spanZ) / squared));
                    double dx = toX - along * spanX;
                    double dy = toY - along * spanY;
                    double dz = toZ - along * spanZ;
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (distance < graphReach) {
                        graphReach = distance;
                        hoveredGraphRing = graphRingLabels.get(ring);
                    }
                }
            }
            boolean cursorStill = faceId == previewedFace && Arrays.equals(hitPoint, previewedHit);
            if (hoveredRing >= 0 || hoveredGraphRing != null) {
                previewValid = false;
                previewedFace = -1;
            } else if (!cursorStill) {
                previewValid = previewAt(surface, faceId);
                previewedFace = faceId;
                System.arraycopy(hitPoint, 0, previewedHit, 0, COORDINATES_PER_POINT);
                previewMillis = (System.nanoTime() - start) / 1e6;
            }
        } else {
            previewValid = false;
            previewedFace = -1;
        }
        if (pendingClick) {
            // The click against the fresh pick: on a draft it selects the authored anchor under
            // the cursor or adds one; without one it re-opens the ring under the cursor,
            // converts a graph ring, or turns the preview into a draft.
            pendingClick = false;
            lastError = "";
            RingToolState stateBefore = new RingToolState(this);
            if (!hitValid) {
                lastError = "the click missed the surface";
            } else if (draft != null && hoveredAnchor >= 0) {
                selectedAnchorVertexId = draftAuthoredVertexId[hoveredAnchor];
                lastRow = "selected authored anchor " + hoveredAnchor + " of "
                        + draftAuthoredVertexId.length;
            } else if (draft != null) {
                addAuthoredAnchor(hitVertexId);
            } else if (hoveredRing >= 0) {
                reopenRing(hoveredRing);
            } else if (hoveredGraphRing != null) {
                // Fit anchors to the graph ring's edge loop and make every one authored, with
                // the plane they fit as the base normal, so a save writes a spline_ring.
                String label = hoveredGraphRing;
                AuthoredSplineRing converted = new AuthoredSplineRing(geodesics);
                int[] anchors = fitLoop(orderedLoop(surface, scene.ringMarksByLabel.get(label)),
                        converted, "graph ring " + label);
                if (anchors.length > 0) {
                    System.arraycopy(written(converted.planeNormal), 0, draftBaseNormal, 0,
                            COORDINATES_PER_POINT);
                }
                if (anchors.length > 0
                        && retraceDraft(anchors, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH, false)) {
                    convertedGraphLabels.add(label);
                    draftSourceRing = -1;
                    draftSourceLabel = label;
                    selectedAnchorVertexId = -1;
                    recordEdit("graph ring converted", stateBefore);
                    invalidateRings();
                    reportDraft("converted graph ring " + label + " into the draft");
                }
            } else if (!previewValid || previewAuthoredVertexId < 0) {
                lastError = "no preview loop under the cursor";
            } else {
                draftSourceRing = -1;
                draftSourceLabel = null;
                System.arraycopy(previewPlaneNormal, 0, draftBaseNormal, 0,
                        COORDINATES_PER_POINT);
                if (retraceDraft(new int[] { previewAuthoredVertexId },
                        SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH, true)) {
                    selectedAnchorVertexId = -1;
                    recordEdit("draft opened", stateBefore);
                    reportDraft("drafted");
                }
            }
        }
        SurfaceSpline shownPreview = previewValid ? previewSpline : null;
        if (shownPreview != uploadedPreview || draft != uploadedDraft
                || hoveredRing != uploadedHoveredRing
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
     * Re-open a confirmed ring as the draft through the anchors and normal it was confirmed with,
     * as one undoable edit; a click on the ring with no draft open lands here.
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
        if (draftMode == RingSegmentMode.CREASE && !readyCreases(scene.halfEdgeSurface())
                || !retraceDraft(confirmedAuthoredVertexId.get(ring),
                        SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH, false)) {
            draft = null;
            draftMode = RingSegmentMode.GEODESIC;
            return false;
        }
        draftSourceRing = ring;
        draftSourceLabel = null;
        selectedAnchorVertexId = -1;
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
        for (int held : draftAuthoredVertexId) {
            if (held == vertexId) {
                lastError = "that vertex already carries an authored anchor";
                return false;
            }
        }
        RingToolState stateBefore = new RingToolState(this);
        holdDraft();
        long start = System.nanoTime();
        if (!draftRing.insert(vertexId, draftBaseNormal, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
            lastError = draftRing.failure;
            return false;
        }
        draftAuthoredVertexId = draftRing.authoredVertexId;
        draft = SurfaceSpline.of(draftRing.tracer);
        draftDepth = SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH;
        draftMillis = (System.nanoTime() - start) / 1e6;
        recordEdit("anchor added", stateBefore);
        reportDraft("authored anchor added");
        return true;
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
        int[] moved = Arrays.copyOf(draftAuthoredVertexId, draftAuthoredVertexId.length);
        moved[selected] = vertexId;
        if (!retraceDraft(moved, AuthoredSplineRing.SUPPORTING_FIT_DEPTH, true)) {
            return false;
        }
        selectedAnchorVertexId = vertexId;
        return true;
    }

    /**
     * Remove the selected authored anchor; the ring re-fits without it, and removing the last one
     * discards the draft.
     *
     * @return true when an anchor was removed
     */
    public boolean deleteSelectedAnchor() {
        lastError = "";
        int selected = selectedIndex();
        if (draft == null || selected < 0) {
            lastError = NO_SELECTED_ANCHOR;
            return false;
        }
        int[] before = draftAuthoredVertexId;
        if (before.length == 1) {
            discardDraft();
            lastRow = "removed the last authored anchor: draft discarded";
            return true;
        }
        RingToolState stateBefore = new RingToolState(this);
        int[] kept = new int[before.length - 1];
        System.arraycopy(before, 0, kept, 0, selected);
        System.arraycopy(before, selected + 1, kept, selected, kept.length - selected);
        if (!retraceDraft(kept, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH, true)) {
            return false;
        }
        recordEdit("anchor deleted", stateBefore);
        selectedAnchorVertexId = -1;
        reportDraft("authored anchor removed");
        return true;
    }

    /**
     * Delete or Backspace: remove the selected authored anchor, or the whole ring open as the
     * draft when no anchor is selected.
     *
     * @return true when an anchor or a ring was removed
     */
    public boolean deletePressed() {
        if (draft != null && selectedIndex() < 0) {
            deleteDraftRing();
            return lastError.isEmpty();
        }
        return deleteSelectedAnchor();
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
        RingToolState stateBefore = new RingToolState(this);
        if (ring < 0) {
            ring = confirmedRings.size();
            confirmedRings.add(draft);
            confirmedAuthoredVertexId.add(draftAuthoredVertexId);
            confirmedBaseNormal.add(Arrays.copyOf(draftBaseNormal, COORDINATES_PER_POINT));
            confirmedStatementIds.add(null);
            confirmedRingUnsaved.add(false);
            confirmedSourceLabel.add(draftSourceLabel);
            confirmedRingDeleted.add(false);
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
            lastError = "the ring tool is not running";
            return false;
        }
        if (draggingAnchor) {
            lastError = "finish the drag first";
            return false;
        }
        String what = backward ? history.nextUndoName() : history.nextRedoName();
        RingToolState restored = backward ? history.undo() : history.redo();
        if (restored == null) {
            lastError = backward ? "nothing to undo" : "nothing to redo";
            return false;
        }
        restored.restore(this);
        lastRow = String.format(Locale.ROOT, "%s %s: %d to undo, %d to redo, %d ring(s), %s",
                backward ? "undid" : "redid", what, history.undoDepth, history.redoDepth(),
                confirmedRings.size(), draft == null ? "no draft"
                        : "draft with " + draftAuthoredVertexId.length + " authored anchor(s)");
        Platforms.get().log(LOG_PREFIX + lastRow + (draft == null ? "" : ", draft fingerprint "
                + EdgeMarks.fingerprint(scene.halfEdgeSurface(), draft.markedByEdgeId)));
        return true;
    }

    /**
     * Push an edit onto the history when it changed the rings or the draft.
     *
     * @param what   the edit's name, as undo reports it
     * @param before the tool's state when the edit began
     */
    private void recordEdit(String what, RingToolState before) {
        RingToolState after = new RingToolState(this);
        if (!before.sameEdit(after)) {
            history.push(what, before, after);
        }
    }

    /**
     * A press landed: when it is on one of the draft's authored anchors, select it and keep the
     * drag for moving it rather than orbiting the camera.
     *
     * @return true when the tool took the drag
     */
    public boolean grabAnchor() {
        if (!active || draft == null || hoveredAnchor < 0) {
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
            if (retraceDraft(draftAuthoredVertexId, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH,
                    true)) {
                reportDraft("authored anchor moved");
            } else if (dragBefore != null) {
                String refusal = lastError;
                dragBefore.restore(this);
                lastError = refusal;
            }
        }
        if (dragBefore != null && draft != null
                && !Arrays.equals(dragBefore.draftAuthoredVertexId, draftAuthoredVertexId)) {
            recordEdit("anchor dragged", dragBefore);
        }
        dragBefore = null;
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
            lastError = "no draft to confirm";
            return false;
        }
        long start = System.nanoTime();
        RingToolState stateBefore = new RingToolState(this);
        draggingAnchor = false;
        if (draftDepth != SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH && !retraceDraft(
                draftAuthoredVertexId, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH, true)) {
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
        }
        SurfaceSpline spline = confirmedRings.get(ring);
        clearDraft();
        recordEdit("draft confirmed", stateBefore);
        lastRow = String.format(Locale.ROOT,
                "ring %d: %d authored + %d supporting anchors, %d edges, length %.5f, "
                        + "centroid %.5f,%.5f,%.5f, sharpest corner %.1f deg, %.0f ms, %s",
                drawnRingNumber(ring), spline.authoredCount(),
                spline.anchorCount - spline.authoredCount(), spline.markedEdgeCount, spline.length,
                spline.centroidX, spline.centroidY, spline.centroidZ,
                spline.minimumInteriorAngleDegrees, (System.nanoTime() - start) / 1e6,
                confirmedRingUnsaved.get(ring) ? "unsaved" : "saved");
        Platforms.get().log(LOG_PREFIX + lastRow + ", fingerprint "
                + EdgeMarks.fingerprint(scene.halfEdgeSurface(), spline.markedByEdgeId));
        invalidateRings();
        return true;
    }

    /**
     * Esc: a draft untouched since the click that opened it closes, that click taken back off the
     * history, and the tool stays on the new-ring picker. An edited draft stays open, and the
     * scene goes on to the orbit tool.
     *
     * @return true when an unedited draft was closed
     */
    @Override
    public boolean escapePressed() {
        if (draft == null) {
            return false;
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
        previewedFace = -1;
        lastRow = "closed the unedited ring (" + opening + " taken back, nothing recorded)";
        Platforms.get().log(LOG_PREFIX + lastRow);
        return true;
    }

    @Override
    public String escapeDescription() {
        return "close an unedited ring (an edited one stays open)";
    }

    /** Drop the draft; a re-opened ring stays as it was and a converted graph ring shows again. */
    public void discardDraft() {
        if (draft == null) {
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

    private void clearDraft() {
        draft = null;
        draftMode = RingSegmentMode.GEODESIC;
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
     * Trace the draft through authored anchors in ring order under the draft's base normal,
     * keeping the old draft and saying why when the anchors decide no ring, or, with
     * {@code keepSimple}, when they would make a simple draft cross itself.
     *
     * @return true when the draft now runs through {@code authored}
     */
    private boolean retraceDraft(int[] authored, int depth, boolean keepSimple) {
        holdDraft();
        boolean wasSimple = draft == null || draftRing.simple;
        long start = System.nanoTime();
        if (!draftRing.trace(authored, authored.length, draftBaseNormal, depth)) {
            lastError = draftRing.failure;
            return false;
        }
        if (keepSimple && wasSimple && !draftRing.simple) {
            float[] at = draftRing.crossings.firstCrossingXyz;
            lastError = String.format(Locale.ROOT,
                    "refused: the ring would cross itself near %.4f,%.4f,%.4f", at[0], at[1], at[2]);
            return false;
        }
        draftAuthoredVertexId = draftRing.authoredVertexId;
        draft = SurfaceSpline.of(draftRing.tracer);
        draftDepth = depth;
        draftMillis = (System.nanoTime() - start) / 1e6;
        return true;
    }

    /**
     * Make {@link #draftRing} hold the draft as it stands, re-tracing it when an undo, a redo or a
     * refused edit left it behind, so the next edit starts from the draft's own anchor cycle.
     */
    private void holdDraft() {
        if (draftRing == null || draftRing.tracer.geodesics != geodesics) {
            draftRing = new AuthoredSplineRing(geodesics);
        }
        boolean behind = draftRing.mode != draftMode
                || !Arrays.equals(draftRing.authoredVertexId, draftAuthoredVertexId);
        draftRing.mode = draftMode;
        draftRing.creases = creases;
        if (draft != null && behind) {
            draftRing.trace(draftAuthoredVertexId, draftAuthoredVertexId.length, draftBaseNormal,
                    draftDepth);
        }
    }

    /**
     * Put what the last draft edit did in the row under the status line, the one line the user
     * reads after every edit, with the ring's fingerprint in the log.
     */
    private void reportDraft(String what) {
        int authoredAnchors = draft.authoredCount();
        float[] crossing = draftRing.crossings.firstCrossingXyz;
        lastRow = String.format(Locale.ROOT,
                "draft %s: %s, %d authored + %d supporting anchors, %d edges, length %.5f, "
                        + "sharpest corner %.1f deg, %.0f ms%s",
                what, draftMode.label, authoredAnchors, draft.anchorCount - authoredAnchors,
                draft.markedEdgeCount, draft.length, draft.minimumInteriorAngleDegrees,
                draftMillis, draftRing.simple ? "" : String.format(Locale.ROOT,
                        ", crosses itself near %.4f,%.4f,%.4f", crossing[0], crossing[1],
                        crossing[2]));
        Platforms.get().log(LOG_PREFIX + lastRow + ", fingerprint "
                + EdgeMarks.fingerprint(scene.halfEdgeSurface(), draft.markedByEdgeId));
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
     * The confirmed ring whose traced polyline passes within {@link #RING_HIT_PIXELS} of the
     * cursor, which a click re-opens instead of starting a new ring.
     */
    private int ringUnderCursor() {
        double reach = RING_HIT_PIXELS * scene.worldPerPixel();
        float[] hitPoint = scene.cursorPoint;
        int nearest = -1;
        double nearestDistance = reach;
        for (int ring = 0; ring < confirmedRings.size(); ring++) {
            if (confirmedRingDeleted.get(ring)) {
                continue;
            }
            float[] polyline = confirmedRings.get(ring).polyline;
            double distance = SurfaceSpline.distanceToPolyline(polyline,
                    polyline.length / COORDINATES_PER_POINT, hitPoint[0], hitPoint[1],
                    hitPoint[2]);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = ring;
            }
        }
        return nearest;
    }

    /**
     * Find the girdling plane through the hit point and trace the one-anchor ring through the
     * vertex under the cursor on it, exactly the ring a click would draft.
     */
    private boolean previewAt(MeshTopology surface, int faceId) {
        previewAnchorCount = 0;
        previewGirdleEdgeCount = 0;
        previewLength = 0.0;
        previewSpline = null;
        float[] hitPoint = scene.cursorPoint;
        int hitVertexId = scene.cursorVertexId;
        axisFromSkeleton = limbAxis.skeletonAxisAt(hitPoint[0], hitPoint[1], hitPoint[2],
                limbDirection);
        boolean seeded = axisFromSkeleton || limbAxis.curvatureAxisAt(faceId, limbDirection);
        if (seeded) {
            System.arraycopy(limbDirection, 0, previewLimbAxis, 0, COORDINATES_PER_POINT);
        }
        System.arraycopy(scene.cursorRayDirection, 0, girdle.viewDirection, 0,
                COORDINATES_PER_POINT);
        if (!girdle.find(surface, faceId, hitPoint, seeded ? limbDirection : null)) {
            return false;
        }
        previewGirdleEdgeCount = girdle.cut.stepCount;
        System.arraycopy(written(girdle.normal), 0, previewPlaneNormal, 0, COORDINATES_PER_POINT);
        if (previewRing == null || previewRing.tracer.geodesics != geodesics) {
            previewRing = new AuthoredSplineRing(geodesics);
        }
        long geodesicsBefore = geodesics.geodesicCount;
        if (!previewRing.trace(new int[] { hitVertexId }, 1, previewPlaneNormal,
                AuthoredSplineRing.SUPPORTING_FIT_DEPTH)) {
            return false;
        }
        previewAuthoredVertexId = hitVertexId;
        previewGeodesicCount = geodesics.geodesicCount - geodesicsBefore;
        previewTolerance = previewRing.fit.tolerance;
        previewDeviation = previewRing.fit.deviation;
        previewAnchorCapReached = previewRing.fit.anchorCapReached;
        previewAnchorCount = previewRing.tracer.anchorCount;
        previewSpline = SurfaceSpline.unsnapped(previewRing.tracer);
        previewLength = previewSpline.length;
        return previewAnchorCount >= SurfaceSplineTracer.MINIMUM_ANCHORS;
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
                        NodeGraphRuntime.fromSource(source).statements);
                if (candidates != null) {
                    candidatesLine = RingDslWriter.statementLine(source, candidates.id);
                }
                if (candidatesLine == null) {
                    throw new IllegalArgumentException("the working .dsl no longer holds the "
                            + RingDslWriter.CANDIDATES_NODE
                            + " statement the proposed rings came from");
                }
                PythonParser.ParsedNode original = NodeGraphRuntime.fromSource(candidatesLine)
                        .statements.get(0);
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
                        int[] loop = orderedLoop(scene.halfEdgeSurface(), ring.markedByEdgeId);
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
                    owner = RingDslWriter.labelledStatement(
                            NodeGraphRuntime.fromSource(source).statements, sourceLabel);
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
            // A statement written for a ring the tool no longer holds, such as one whose confirm
            // was undone after a save, goes too, so the file matches the rings in memory.
            for (PythonParser.ParsedNode statement : NodeGraphRuntime.fromSource(source).statements) {
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
        knownRingStatementIds.clear();
        hoveredRing = -1;
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
    }

    /**
     * Turn each unowned graph ring into a confirmed ring with authored anchors: a
     * {@code spline_ring}'s own, or anchors fitted to a proposed ring, which still draws its exact
     * loop until edited.
     *
     * @param surface the surface the graph's marks index
     */
    public void adoptGraphRings(MeshTopology surface) {
        graphRingsPending = false;
        long start = System.nanoTime();
        if (!readyGeodesics(surface)) {
            invalidateRings();
            return;
        }
        NodeGraphRuntime graph = scene.getLastGraphRuntime();
        List<PythonParser.ParsedNode> statements = graph == null ? List.of() : graph.statements;
        int adopted = 0;
        List<String> refused = new ArrayList<>();
        // A new graph either proposes rings itself, whose block the next freeze rewrites, or has
        // none, and any frozen rings are then statements like any other.
        boolean proposes = RingDslWriter.candidatesStatement(statements) != null;
        candidateLabels.clear();
        candidateRingByLabel.clear();
        writtenCandidateRings.clear();
        candidatesLine = null;
        for (Map.Entry<String, boolean[]> entry : scene.ringMarksByLabel.entrySet()) {
            String label = entry.getKey();
            PythonParser.ParsedNode statement = RingDslWriter.labelledStatement(statements, label);
            boolean proposed = proposes && statement == null;
            if (proposed) {
                candidateLabels.add(label);
                writtenCandidateRings.add(null);
            }
            if (confirmedSourceLabel.contains(label) || convertedGraphLabels.contains(label)) {
                continue;
            }
            AuthoredSplineRing anchored = new AuthoredSplineRing(geodesics);
            boolean spline = statement != null
                    && SPLINE_RING_NODE.equals(statement.type);
            float[] normal = null;
            int[] anchors;
            if (spline) {
                float[] points = SurfaceWaypoints.parse(
                        String.valueOf(statement.arguments.get(SplineRingNode.POINTS.name)));
                float[] written = SurfaceWaypoints.parse(
                        String.valueOf(statement.arguments.get(SplineRingNode.NORMAL.name)));
                normal = written.length == COORDINATES_PER_POINT ? written : null;
                Object mode = statement.arguments.get(SplineRingNode.MODE.name);
                anchored.mode = RingSegmentMode.named(mode instanceof String text ? text : null);
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
                float[] loopXyz = positionsOf(loop);
                double[] loopPositions = new double[loopXyz.length];
                for (int coordinate = 0; coordinate < loopXyz.length; coordinate++) {
                    loopPositions[coordinate] = loopXyz[coordinate];
                }
                traced.followPath(surface, new TracedSurfacePath(loopPositions, loop, noEdge,
                        noFraction, loop.length, true));
                traced.markedByEdgeId = entry.getValue();
                traced.markedEdgeCount = loop.length;
            }
            confirmedRings.add(traced);
            confirmedAuthoredVertexId.add(anchored.authoredVertexId);
            confirmedBaseNormal.add(normal == null ? written(anchored.planeNormal) : normal);
            confirmedMode.add(anchored.mode);
            confirmedStatementIds.add(spline ? statement.id : null);
            if (spline) {
                knownRingStatementIds.add(statement.id);
            }
            confirmedRingUnsaved.add(false);
            confirmedSourceLabel.add(label);
            confirmedRingDeleted.add(false);
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
    private int[] fitLoop(int[] loop, AuthoredSplineRing ring, String name) {
        ring.tracer.maximumDepth = AuthoredSplineRing.SUPPORTING_FIT_DEPTH;
        if (loop.length < SplineAnchorFit.STARTING_ANCHORS) {
            lastError = name + " is not one closed edge loop";
            return new int[0];
        }
        if (!ring.fit.fit(positionsOf(loop), loop.length, loop, 0)) {
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
            lastError = lastError.isEmpty() ? "no surface to ring" : lastError;
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
        recordEdit(what, stateBefore);
        invalidateRings();
        int ring = confirmedRings.size() - 1;
        lastRow = String.format(Locale.ROOT, "ring %d from %s: %d authored anchors, %d edges, "
                + "length %.5f, unsaved", drawnRingNumber(ring), what, spline.authoredCount(),
                spline.markedEdgeCount, spline.length);
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
        NodeGraphRuntime graph = scene.getLastGraphRuntime();
        SurfaceMetric metric = null;
        for (PythonParser.ParsedNode statement : graph == null ? List.<PythonParser.ParsedNode>of()
                : graph.statements) {
            if (graph.getNodeOutput(statement.id, SurfaceMetricNode.METRIC.name)
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
        uploadedHoveredRing = hoveredRing;
        uploadedHoveredGraphRing = hoveredGraphRing;
        uploadedSelected = selectedAnchorVertexId;
        int rings = drawnRingColorRgb.length;
        boolean drawingEditing = editing != null
                && editing.polyline.length >= 2 * COORDINATES_PER_POINT;
        LineSet edited = drawingEditing ? splineLines(scene.halfEdgeSurface(), editing)
                : new LineSet(0);
        LineSet lines = new LineSet(
                (ringLines.vertexCount() + edited.vertexCount()) / 2);
        System.arraycopy(ringLines.vertices, 0, lines.vertices, 0, ringLines.cursor);
        System.arraycopy(edited.vertices, 0, lines.vertices, ringLines.cursor, edited.cursor);
        lines.cursor = ringLines.cursor + edited.cursor;
        int[] groupStart = new int[rings + (drawingEditing ? 2 : 1)];
        System.arraycopy(ringSegmentStart, 0, groupStart, 0, rings + 1);
        int[] groupColor = new int[rings + (drawingEditing ? 1 : 0)];
        System.arraycopy(drawnRingColorRgb, 0, groupColor, 0, rings);
        if (drawingEditing) {
            groupStart[rings + 1] = groupStart[rings] + edited.vertexCount() / 2;
            groupColor[rings] = PREVIEW_COLOR;
        }
        if (hoveredRing >= 0 && drawnRingNumber(hoveredRing) < rings) {
            groupColor[drawnRingNumber(hoveredRing)] = PREVIEW_COLOR;
        }
        int graphIndex = 0;
        for (String graphLabel : unownedGraphRingLabels()) {
            if (graphLabel.equals(hoveredGraphRing) && graphIndex < rings) {
                groupColor[graphIndex] = PREVIEW_COLOR;
            }
            graphIndex++;
        }
        overlay.setLineGroups(lines, groupStart, groupColor);
        overlay.setLabels(ringLabelXyz, drawnRingLabel, drawnRingColorRgb, ringLabelLift);
        // Only the draft shows its anchors, as fixed-pixel discs: supporting smallest and white,
        // authored larger and cyan, the selected one largest and yellow. Smaller classes go
        // first so a larger disc wins where two overlap.
        int anchors = draft == null ? 0 : draft.anchorCount;
        int[] anchorVertexId = new int[anchors];
        int[] anchorColor = new int[anchors];
        float[] anchorDiameter = new float[anchors];
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
            perRing.add(hidden ? new float[0] : closedPolylineSegments(spline.surfacePolyline));
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
        drawnRingColorRgb = new int[perRing.size()];
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
            drawnRingColorRgb[ring] = note != null ? MARKED_RING_COLOR
                    : RING_COLORS[ring % RING_COLORS.length];
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
     * @param spline the spline whose {@link SurfaceSpline#surfacePolyline} is drawn
     * @return the closed loop's segments, empty without a surface
     */
    public static LineSet splineLines(MeshTopology mesh, SurfaceSpline spline) {
        int points = spline.surfacePolyline.length / COORDINATES_PER_POINT;
        if (mesh == null || points < 2) {
            return new LineSet(0);
        }
        LineSet lines = new LineSet(points - 1);
        for (int point = 0; point + 1 < points; point++) {
            lines.pathStep(mesh, spline.surfacePolyline, spline.pointVertexId,
                    spline.pointEdgeId, spline.pointFaceId, point, point + 1);
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
