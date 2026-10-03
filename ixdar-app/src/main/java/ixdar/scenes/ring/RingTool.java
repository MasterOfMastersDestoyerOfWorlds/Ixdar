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
import ixdar.geometry.mesh.nodes.selection.LoopThroughPointsNode;
import ixdar.geometry.mesh.nodes.selection.RingDslWriter;
import ixdar.geometry.mesh.nodes.selection.SelectRingNode;
import ixdar.geometry.mesh.nodes.selection.SplineRingNode;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.MeshOverlayRuntime;
import ixdar.platform.Platforms;

/**
 * Hover to preview the ring girdling the limb under the cursor; click to turn it into a draft
 * whose authored anchors the user adds, selects, drags and deletes; Enter confirms the draft.
 * Confirming changes memory only: {@link #saveRings} alone writes the working .dsl.
 */
public final class RingTool {

    public static final String STATUS_LINE =
            "ring tool: hover to preview, click to draft, click to add an anchor, drag or Delete "
                    + "one, Ctrl+Z undo, Ctrl+Shift+Z or Ctrl+Y redo, Enter confirm, Esc discard "
                    + "or finish, Ctrl+S save";

    public static final String LOG_PREFIX = "[ring-tool] ";

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

    /** Graph ring labels turned into tool rings, no longer drawn from the graph's marks. */
    public final Set<String> convertedGraphLabels = new HashSet<>();

    /** Skeleton and curvature the preview plane's normal comes from, cached per model. */
    public final LimbAxis limbAxis = new LimbAxis();

    /** Geodesic engine over the current surface, built once and reused every frame. */
    public SurfaceGeodesics geodesics;

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

    /** Confirmed ring the draft re-opened, which Enter replaces and Esc restores, or -1. */
    public int draftSourceRing = -1;

    /** Graph ring label the draft was converted from, shown again if Esc discards, or null. */
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
    private float[] ringSegment = new float[0];
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

    /** Start the tool, or finish it when it is already running. */
    public void toggle() {
        if (active) {
            finish();
            return;
        }
        active = true;
        lastError = "";
        Platforms.get().log(LOG_PREFIX + STATUS_LINE);
    }

    /**
     * Leave the tool, keeping every confirmed ring, dropping an open draft and freeing the pick
     * copy.
     */
    public void finish() {
        if (draft != null) {
            discardDraft();
        }
        active = false;
        previewValid = false;
        previewSpline = null;
        hoveredRing = -1;
        hoveredGraphRing = null;
        previewAnchorCount = 0;
        overlayStale = true;
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        if (runtime != null) {
            runtime.uploadFacePickBuffer(null);
        }
        preparedSurface = null;
        Platforms.get().log(LOG_PREFIX + "finished with " + confirmedRings.size()
                + " confirmed ring(s), " + unsavedRingCount() + " unsaved");
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
    }

    /**
     * One frame of the tool: pick the surface under the cursor, then drag the grabbed anchor,
     * find what a click would act on, or fit the hover preview, and run a click that arrived
     * since the last frame against that fresh pick.
     */
    public void perFrame() {
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        MeshTopology surface = scene.halfEdgeSurface();
        if (!active || runtime == null || surface == null || surface.faceCount() == 0) {
            pendingClick = false;
            uploadOverlay();
            return;
        }
        long start = System.nanoTime();
        prepare(runtime, surface);
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
                int[] loop = orderedLoop(surface, scene.ringMarksByLabel.get(label));
                float[] loopXyz = positionsOf(loop);
                AuthoredSplineRing converted = new AuthoredSplineRing(geodesics);
                converted.tracer.maximumDepth = AuthoredSplineRing.SUPPORTING_FIT_DEPTH;
                int[] anchors = new int[0];
                if (loop.length < SplineAnchorFit.STARTING_ANCHORS) {
                    lastError = "graph ring " + label + " is not one closed edge loop";
                } else if (!converted.fit.fit(loopXyz, loop.length, loop, 0)) {
                    lastError = "no anchors fit graph ring " + label;
                } else {
                    anchors = Arrays.copyOf(converted.tracer.anchorVertexId,
                            converted.tracer.anchorCount);
                    if (!converted.trace(anchors, anchors.length, null,
                            AuthoredSplineRing.SUPPORTING_FIT_DEPTH)) {
                        lastError = "graph ring " + label + ": " + converted.failure;
                        anchors = new int[0];
                    }
                }
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
            confirmedRings.set(ring, draft);
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
        }
        SurfaceSpline spline = draft;
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

    /**
     * Esc: discard the draft when there is one, otherwise leave the tool keeping every ring.
     */
    public void escape() {
        if (draft != null) {
            discardDraft();
            return;
        }
        finish();
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
        return scene.ringMarksByLabel.size() + confirmedIndex;
    }

    /**
     * How much work a save would write.
     *
     * @return confirmed rings whose current shape the working .dsl does not hold
     */
    public int unsavedRingCount() {
        int unsaved = 0;
        for (Boolean dirty : confirmedRingUnsaved) {
            if (dirty) {
                unsaved++;
            }
        }
        return unsaved;
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
            for (int ring = 0; ring < confirmedRings.size(); ring++) {
                savedId[ring] = confirmedStatementIds.get(ring);
                if (!confirmedRingUnsaved.get(ring)) {
                    continue;
                }
                int[] authored = confirmedAuthoredVertexId.get(ring);
                float[] authoredXyz = positionsOf(authored);
                float[] normal = confirmedBaseNormal.get(ring);
                if (savedId[ring] == null) {
                    savedId[ring] = RingDslWriter.nextRingId(source, liveLabels);
                    source = RingDslWriter.appendSpline(source, authoredXyz, authored.length,
                            normal, liveLabels);
                    appended++;
                } else {
                    source = RingDslWriter.replaceSpline(source, savedId[ring], authoredXyz,
                            authored.length, normal);
                }
            }
            RingDslWriter.writeAtomically(path, source);
        } catch (IOException | RuntimeException failure) {
            lastError = "could not write " + target + ": " + failure.getMessage();
            Platforms.get().log(LOG_PREFIX + lastError);
            return false;
        }
        for (int ring = 0; ring < confirmedRings.size(); ring++) {
            confirmedStatementIds.set(ring, savedId[ring]);
            confirmedRingUnsaved.set(ring, false);
            savedStatementByRing.put(confirmedRings.get(ring), savedId[ring]);
            savedAuthoredByStatement.put(savedId[ring], confirmedAuthoredVertexId.get(ring));
            savedNormalByStatement.put(savedId[ring], confirmedBaseNormal.get(ring));
        }
        lastRow = String.format(Locale.ROOT,
                "saved %d ring(s) to %s: %d appended, %d rewritten in place",
                unsaved, path, appended, unsaved - appended);
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
        hoveredRing = -1;
        lastRow = "";
        invalidateRings();
    }

    /** Build the pick buffer, the axis cache and the geodesic engine when the surface changed. */
    private void prepare(HalfEdgeMeshRuntime runtime, MeshTopology surface) {
        if (preparedSurface == surface && runtime.facePickReady()) {
            return;
        }
        long start = System.nanoTime();
        runtime.uploadFacePickBuffer(surface);
        limbAxis.cacheFor(surface);
        geodesics = SurfaceGeodesics.over(surface);
        preparedSurface = surface;
        Platforms.get().log(String.format(Locale.ROOT,
                "[ring-tool] prepared %d faces: pick buffer, %s axis and the intrinsic "
                        + "triangulation (mean edge %.5f) in %.0f ms",
                surface.faceCount(), limbAxis.skeleton == null ? "curvature" : "skeleton",
                geodesics.meanEdgeLength, (System.nanoTime() - start) / 1e6));
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
        float[] edited = drawingEditing ? closedPolylineSegments(editing.polyline) : new float[0];
        float[] segments = new float[ringSegment.length + edited.length];
        System.arraycopy(ringSegment, 0, segments, 0, ringSegment.length);
        System.arraycopy(edited, 0, segments, ringSegment.length, edited.length);
        int[] groupStart = new int[rings + (drawingEditing ? 2 : 1)];
        System.arraycopy(ringSegmentStart, 0, groupStart, 0, rings + 1);
        int[] groupColor = new int[rings + (drawingEditing ? 1 : 0)];
        System.arraycopy(drawnRingColorRgb, 0, groupColor, 0, rings);
        if (drawingEditing) {
            groupStart[rings + 1] = groupStart[rings] + edited.length / SEGMENT_FLOATS;
            groupColor[rings] = PREVIEW_COLOR;
        }
        if (hoveredRing >= 0 && drawnRingNumber(hoveredRing) < rings) {
            groupColor[drawnRingNumber(hoveredRing)] = PREVIEW_COLOR;
        }
        int graphIndex = 0;
        for (String graphLabel : scene.ringMarksByLabel.keySet()) {
            if (graphLabel.equals(hoveredGraphRing) && graphIndex < rings) {
                groupColor[graphIndex] = PREVIEW_COLOR;
            }
            graphIndex++;
        }
        overlay.setLineGroups(segments, groupStart, groupColor);
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
        Map<String, boolean[]> graphMarks = scene.ringMarksByLabel;
        graphRingSegments = new ArrayList<>();
        graphRingLabels = new ArrayList<>();
        if (surface != null) {
            for (Map.Entry<String, boolean[]> entry : graphMarks.entrySet()) {
                boolean converted = convertedGraphLabels.contains(entry.getKey());
                float[] ringSegments = converted ? new float[0]
                        : markedEdgeSegments(surface, entry.getValue());
                perRing.add(ringSegments);
                if (!converted) {
                    graphRingSegments.add(ringSegments);
                    graphRingLabels.add(entry.getKey());
                }
            }
        }
        for (int ring = 0; ring < confirmedRings.size(); ring++) {
            perRing.add(ring == draftSourceRing && draft != null ? new float[0]
                    : closedPolylineSegments(confirmedRings.get(ring).polyline));
        }
        drawnRingLabel = ringNumberTexts(
                surface == null ? List.of() : graphMarks.keySet(), confirmedRings.size());
        int total = 0;
        for (float[] ringSegments : perRing) {
            total += ringSegments.length;
        }
        ringSegment = new float[total];
        ringSegmentStart = new int[perRing.size() + 1];
        drawnRingColorRgb = new int[perRing.size()];
        ringLabelXyz = new float[COORDINATES_PER_POINT * perRing.size()];
        ringLabelLift = new float[perRing.size()];
        int cursor = 0;
        for (int ring = 0; ring < perRing.size(); ring++) {
            float[] ringSegments = perRing.get(ring);
            System.arraycopy(ringSegments, 0, ringSegment, cursor, ringSegments.length);
            cursor += ringSegments.length;
            ringSegmentStart[ring + 1] = cursor / SEGMENT_FLOATS;
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
