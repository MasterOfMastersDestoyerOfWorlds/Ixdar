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
import ixdar.geometry.mesh.data.paths.SplineAnchorFit;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfacePicker;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.data.paths.TracedSurfacePath;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
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
                    + "or Ctrl+Y redo, Enter confirm, X discard, Ctrl+S save, Esc back to orbit";

    public static final String LOG_PREFIX = "[ring-tool] ";

    public static final String TOOL_NAME = "ring tool";

    public static final String UNSAVED_RING_PREFIX = "ring #";

    public static final String REMOVE_SELECTED_ANCHOR_HINT = "remove anchor (or ring)";

    public static final String REDO_HINT = "redo ring edit";

    public static final int PREVIEW_COLOR = 0xFF2D95;

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

    public static final float ANCHOR_HIT_RADIUS_PIXELS = 8f;

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

    private final SurfacePicker picker = new SurfacePicker();
    private final GirdlingPlane girdle = new GirdlingPlane();
    private final float[] rayOrigin = new float[COORDINATES_PER_POINT];
    private final float[] rayDirection = new float[COORDINATES_PER_POINT];
    private final float[] hitPoint = new float[COORDINATES_PER_POINT];
    private final float[] limbDirection = new float[COORDINATES_PER_POINT];
    private final float[] previewedHit = new float[COORDINATES_PER_POINT];
    private final Vector3f scratchPosition = new Vector3f();
    private final float[] anchorPixel = new float[COORDINATES_PER_POINT];
    private AuthoredSplineRing previewRing;
    private AuthoredSplineRing draftRing;
    private MeshTopology preparedSurface;
    private boolean hitValid;
    private int hitVertexId = -1;
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
     * One frame of the tool: pick the surface under the cursor, then drag the grabbed anchor,
     * find what a click would act on, or fit the hover preview, and run a click that arrived
     * since the last frame against that fresh pick.
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
        // Pick the face under the cursor with the GPU id buffer, hit it with the view ray, and keep
        // the hit point and the face corner nearest it, the vertex a click anchors to.
        hitValid = false;
        hitVertexId = -1;
        int width = Platforms.get().getWindowWidth();
        int height = Platforms.get().getWindowHeight();
        int framebufferX = width <= 0 ? 0
                : Math.round(scene.orbitMouse.lastX * (float) Platforms.get().getFrameBufferWidth()
                        / width);
        int framebufferY = height <= 0 ? 0
                : Math.round(scene.orbitMouse.lastY
                        * (float) Platforms.get().getFrameBufferHeight() / height);
        int faceIndex = runtime.faceIndexAtPixel(scene.camera, framebufferX, framebufferY);
        int faceId = -1;
        if (faceIndex >= 0 && faceIndex < surface.faceCount()
                && runtime.rayThroughPixel(scene.camera, framebufferX, framebufferY, rayOrigin,
                        rayDirection)
                && picker.hitFace(surface, surface.faceIdAt(faceIndex), rayOrigin, rayDirection)) {
            faceId = surface.faceIdAt(faceIndex);
            hitPoint[0] = picker.pointX;
            hitPoint[1] = picker.pointY;
            hitPoint[2] = picker.pointZ;
            System.arraycopy(hitPoint, 0, previewHitPoint, 0, COORDINATES_PER_POINT);
            double nearestCorner = Double.POSITIVE_INFINITY;
            for (int corner = 0; corner < surface.faceVertexCount(faceId); corner++) {
                int vertexId = surface.faceVertexAt(faceId, corner);
                surface.vertexPosition(vertexId, scratchPosition);
                double distance = scratchPosition.distance(hitPoint[0], hitPoint[1], hitPoint[2]);
                if (distance < nearestCorner) {
                    nearestCorner = distance;
                    hitVertexId = vertexId;
                }
            }
            hitValid = hitVertexId >= 0;
        }
        hoveredRing = -1;
        hoveredGraphRing = null;
        hoveredAnchor = -1;
        if (draft != null) {
            previewValid = false;
            previewSpline = null;
            if (hitValid) {
                double nearestPixels = Double.POSITIVE_INFINITY;
                double eyeToHit =
                        scene.camera.position.distance(hitPoint[0], hitPoint[1], hitPoint[2]);
                for (int anchor = 0; anchor < draftAuthoredVertexId.length; anchor++) {
                    int vertexId = draftAuthoredVertexId[anchor];
                    surface.vertexPosition(vertexId, scratchPosition);
                    float diameter = anchorDiameterPixels(vertexId, true);
                    boolean onAnchorFace = false;
                    for (int corner = 0; corner < surface.faceVertexCount(faceId); corner++) {
                        onAnchorFace |= surface.faceVertexAt(faceId, corner) == vertexId;
                    }
                    if (!onAnchorFace && scene.camera.position.distance(scratchPosition)
                            > eyeToHit + diameter * worldPerPixel()) {
                        continue;
                    }
                    if (!runtime.projectToPixels(scene.camera, scratchPosition.x,
                            scratchPosition.y, scratchPosition.z, anchorPixel)) {
                        continue;
                    }
                    double pixels = Math.hypot(Math.floor(anchorPixel[0]) - framebufferX,
                            Math.floor(anchorPixel[1]) - framebufferY);
                    if (pixels <= Math.max(diameter * HALF, ANCHOR_HIT_RADIUS_PIXELS)
                            && pixels < nearestPixels) {
                        nearestPixels = pixels;
                        hoveredAnchor = anchor;
                    }
                }
                boolean vertexFree = true;
                for (int held : draftAuthoredVertexId) {
                    vertexFree &= held != hitVertexId;
                }
                int selected = selectedIndex();
                if (draggingAnchor && vertexFree && selected >= 0) {
                    // A drag re-traces at the fit's depth, the rate a frame allows; the whole
                    // drag is one edit, recorded on release.
                    int[] before = draftAuthoredVertexId;
                    int[] moved = Arrays.copyOf(before, before.length);
                    moved[selected] = hitVertexId;
                    if (retraceDraft(moved, AuthoredSplineRing.SUPPORTING_FIT_DEPTH)) {
                        selectedAnchorVertexId = hitVertexId;
                    } else {
                        retraceDraft(before, AuthoredSplineRing.SUPPORTING_FIT_DEPTH);
                    }
                }
            }
        } else if (hitValid) {
            hoveredRing = ringUnderCursor();
            double graphReach = RING_HIT_PIXELS * worldPerPixel();
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
                // Re-open the confirmed ring through the anchors and normal it was saved with.
                System.arraycopy(confirmedBaseNormal.get(hoveredRing), 0, draftBaseNormal, 0,
                        COORDINATES_PER_POINT);
                if (retraceDraft(confirmedAuthoredVertexId.get(hoveredRing),
                        SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
                    draftSourceRing = hoveredRing;
                    draftSourceLabel = null;
                    selectedAnchorVertexId = -1;
                    recordEdit("ring re-opened", stateBefore);
                    invalidateRings();
                    reportDraft("re-opened ring " + drawnRingNumber(hoveredRing) + " as the draft");
                } else {
                    draft = null;
                }
            } else if (hoveredGraphRing != null) {
                // Fit anchors to the graph ring's edge loop and make every one authored, with
                // the plane they fit as the base normal, so a save writes a spline_ring.
                String label = hoveredGraphRing;
                AuthoredSplineRing converted = new AuthoredSplineRing(geodesics);
                int[] anchors = fitGraphRing(surface, label, converted);
                if (anchors.length > 0) {
                    System.arraycopy(written(converted.planeNormal), 0, draftBaseNormal, 0,
                            COORDINATES_PER_POINT);
                }
                if (anchors.length > 0
                        && retraceDraft(anchors, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
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
                        SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
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
     * Add an authored anchor at a vertex; the ring re-fits through it, and a vertex the draft
     * already holds is refused.
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
        int[] before = draftAuthoredVertexId;
        int[] grown = Arrays.copyOf(before, before.length + 1);
        grown[before.length] = vertexId;
        if (!retraceDraft(grown, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
            retraceDraft(before, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH);
            return false;
        }
        recordEdit("anchor added", stateBefore);
        reportDraft("authored anchor added");
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
            lastError = "no authored anchor is selected";
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
        if (!retraceDraft(kept, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
            retraceDraft(before, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH);
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
        if (draft != null && draftDepth != SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH
                && retraceDraft(draftAuthoredVertexId, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
            reportDraft("authored anchor moved");
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
        if (draftDepth != SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH
                && !retraceDraft(draftAuthoredVertexId, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
            return false;
        }
        float[] normal = Arrays.copyOf(draftBaseNormal, COORDINATES_PER_POINT);
        int ring = draftSourceRing;
        if (ring >= 0) {
            boolean changed = !Arrays.equals(confirmedAuthoredVertexId.get(ring),
                    draftAuthoredVertexId)
                    || !Arrays.equals(confirmedBaseNormal.get(ring), normal);
            if (changed) {
                confirmedRings.set(ring, draft);
            }
            confirmedAuthoredVertexId.set(ring, draftAuthoredVertexId);
            confirmedBaseNormal.set(ring, normal);
            confirmedRingUnsaved.set(ring, confirmedRingUnsaved.get(ring) || changed);
        } else {
            ring = confirmedRings.size();
            confirmedRings.add(draft);
            confirmedAuthoredVertexId.add(draftAuthoredVertexId);
            confirmedBaseNormal.add(normal);
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
     * Trace the draft through authored anchors under the draft's base normal, keeping the old
     * draft and saying why when the anchors decide no ring.
     *
     * @return true when the draft now runs through {@code authored}
     */
    private boolean retraceDraft(int[] authored, int depth) {
        if (draftRing == null || draftRing.tracer.geodesics != geodesics) {
            draftRing = new AuthoredSplineRing(geodesics);
        }
        long start = System.nanoTime();
        if (!draftRing.trace(authored, authored.length, draftBaseNormal, depth)) {
            lastError = draftRing.failure;
            return false;
        }
        draftAuthoredVertexId = draftRing.authoredVertexId;
        draft = SurfaceSpline.of(draftRing.tracer);
        draftDepth = depth;
        draftMillis = (System.nanoTime() - start) / 1e6;
        return true;
    }

    /**
     * Put what the last draft edit did in the row under the status line, the one line the user
     * reads after every edit, with the ring's fingerprint in the log.
     */
    private void reportDraft(String what) {
        int authoredAnchors = draft.authoredCount();
        lastRow = String.format(Locale.ROOT,
                "draft %s: %d authored + %d supporting anchors, %d edges, length %.5f, "
                        + "sharpest corner %.1f deg, %.0f ms",
                what, authoredAnchors, draft.anchorCount - authoredAnchors,
                draft.markedEdgeCount, draft.length, draft.minimumInteriorAngleDegrees,
                draftMillis);
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
        double reach = RING_HIT_PIXELS * worldPerPixel();
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

    /** Model units one screen pixel spans at the hit point, the scale a pixel reach is in. */
    private double worldPerPixel() {
        int height = Platforms.get().getFrameBufferHeight();
        if (height <= 0) {
            return 0.0;
        }
        double eyeDistance =
                scene.camera.position.distance(hitPoint[0], hitPoint[1], hitPoint[2]);
        return 2.0 * eyeDistance * Math.tan(Math.toRadians(scene.camera.fov * HALF)) / height;
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
        axisFromSkeleton = limbAxis.skeletonAxisAt(hitPoint[0], hitPoint[1], hitPoint[2],
                limbDirection);
        boolean seeded = axisFromSkeleton || limbAxis.curvatureAxisAt(faceId, limbDirection);
        if (seeded) {
            System.arraycopy(limbDirection, 0, previewLimbAxis, 0, COORDINATES_PER_POINT);
        }
        System.arraycopy(rayDirection, 0, girdle.viewDirection, 0, COORDINATES_PER_POINT);
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
                                positionsOf(loop), loop.length));
                    } else {
                        int index = confirmedRings.indexOf(ring);
                        int[] authored = confirmedAuthoredVertexId.get(index);
                        block.add(RingDslWriter.splineStatement(label, output,
                                positionsOf(authored), authored.length,
                                confirmedBaseNormal.get(index)));
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
                float[] authoredXyz = positionsOf(authored);
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
            }
            // A statement written for a ring the tool no longer holds, such as one whose confirm
            // was undone after a save, goes too, so the file matches the rings in memory.
            for (PythonParser.ParsedNode statement : NodeGraphRuntime.fromSource(source).statements) {
                if (orphaned.contains(statement.id)) {
                    source = RingDslWriter.remove(source, statement.id);
                    deleted++;
                }
            }
            RingDslWriter.writeAtomically(path, source);
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
        if (!readyGeodesics(surface)) {
            invalidateRings();
            return;
        }
        NodeGraphRuntime graph = scene.getLastGraphRuntime();
        List<PythonParser.ParsedNode> statements = graph == null ? List.of() : graph.statements;
        long start = System.nanoTime();
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
                anchors = SurfaceWaypoints.snap(surface, points,
                        points.length / COORDINATES_PER_POINT);
            } else {
                anchors = fitGraphRing(surface, label, anchored);
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
     * Fit authored anchors to a graph ring's edge loop, every supporting anchor the fit settles
     * on made authored, and leave the plane they fit in {@code ring}'s plane normal.
     *
     * @param surface the surface the graph's marks index
     * @param label   the graph ring's mark label
     * @param ring    ring the fit and trace run on
     * @return the anchors, or an empty array with {@link #lastError} saying why
     */
    private int[] fitGraphRing(MeshTopology surface, String label, AuthoredSplineRing ring) {
        int[] loop = orderedLoop(surface, scene.ringMarksByLabel.get(label));
        ring.tracer.maximumDepth = AuthoredSplineRing.SUPPORTING_FIT_DEPTH;
        if (loop.length < SplineAnchorFit.STARTING_ANCHORS) {
            lastError = "graph ring " + label + " is not one closed edge loop";
            return new int[0];
        }
        if (!ring.fit.fit(positionsOf(loop), loop.length, loop, 0)) {
            lastError = "no anchors fit graph ring " + label;
            return new int[0];
        }
        int[] anchors = Arrays.copyOf(ring.tracer.anchorVertexId, ring.tracer.anchorCount);
        if (!ring.trace(anchors, anchors.length, null, AuthoredSplineRing.SUPPORTING_FIT_DEPTH)) {
            lastError = "graph ring " + label + ": " + ring.failure;
            return new int[0];
        }
        return anchors;
    }

    /** Build the pick buffer, the axis cache and the geodesic engine when the surface changed. */
    private void prepare(HalfEdgeMeshRuntime runtime, MeshTopology surface) {
        if (preparedSurface == surface && runtime.facePickReady()) {
            return;
        }
        long start = System.nanoTime();
        runtime.uploadFacePickBuffer(surface);
        preparedSurface = surface;
        if (!readyGeodesics(surface)) {
            return;
        }
        limbAxis.cacheFor(surface);
        Platforms.get().log(String.format(Locale.ROOT,
                "[ring-tool] prepared %d faces: pick buffer, %s axis and the intrinsic "
                        + "triangulation (mean edge %.5f) in %.0f ms",
                surface.faceCount(), limbAxis.skeleton == null ? "curvature" : "skeleton",
                geodesics.meanEdgeLength, (System.nanoTime() - start) / 1e6));
    }

    /**
     * Build the geodesic engine over a surface once, or refuse the surface with the reason in
     * {@link #lastError} and the log, leaving the scene running without the tool.
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
        try {
            geodesics = SurfaceGeodesics.over(surface);
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
     * The drawn diameter of one of the draft's anchors, which is also its click radius doubled.
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
     * confirmed here. A ring converted or re-opened as the draft keeps its place but draws nothing.
     */
    private void rebuildRings() {
        ringsStale = false;
        MeshTopology surface = scene.halfEdgeSurface();
        List<float[]> perRing = new ArrayList<>();
        List<LineSet> perRingLines = new ArrayList<>();
        Map<String, boolean[]> graphMarks = scene.ringMarksByLabel;
        graphRingSegments = new ArrayList<>();
        graphRingLabels = new ArrayList<>();
        List<String> unownedLabels = unownedGraphRingLabels();
        if (surface != null) {
            for (Map.Entry<String, boolean[]> entry : graphMarks.entrySet()) {
                if (!unownedLabels.contains(entry.getKey())) {
                    continue;
                }
                boolean converted = convertedGraphLabels.contains(entry.getKey());
                float[] ringSegments = converted ? new float[0]
                        : markedEdgeSegments(surface, entry.getValue());
                perRing.add(ringSegments);
                LineSet edges = new LineSet(ringSegments.length / SEGMENT_FLOATS);
                boolean[] marks = entry.getValue();
                for (int index = 0; !converted && index < surface.edgeCount(); index++) {
                    int edgeId = surface.edgeIdAt(index);
                    if (edgeId < marks.length && marks[edgeId]) {
                        edges.edge(surface, edgeId);
                    }
                }
                perRingLines.add(edges);
                if (!converted) {
                    graphRingSegments.add(ringSegments);
                    graphRingLabels.add(entry.getKey());
                }
            }
        }
        for (int ring = 0; ring < confirmedRings.size(); ring++) {
            boolean hidden = ring == draftSourceRing && draft != null
                    || confirmedRingDeleted.get(ring);
            SurfaceSpline spline = confirmedRings.get(ring);
            perRing.add(hidden ? new float[0] : closedPolylineSegments(spline.surfacePolyline));
            perRingLines.add(hidden ? new LineSet(0) : splineLines(surface, spline));
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
            drawnRingColorRgb[ring] = RING_COLORS[ring % RING_COLORS.length];
            if (ringSegments.length == 0 && ring < drawnRingLabel.length) {
                drawnRingLabel[ring] = "";
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
