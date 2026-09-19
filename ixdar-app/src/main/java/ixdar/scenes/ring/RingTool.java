package ixdar.scenes.ring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.LimbAxis;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.GirdlingPlane;
import ixdar.geometry.mesh.data.paths.SplineAnchorFit;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfacePicker;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.nodes.selection.LoopThroughPointsNode;
import ixdar.geometry.mesh.nodes.selection.RingDslWriter;
import ixdar.geometry.mesh.nodes.selection.SelectRingNode;
import ixdar.geometry.mesh.nodes.selection.SplineRingNode;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.MeshOverlayRuntime;
import ixdar.platform.Platforms;

/**
 * Hover to preview the ring girdling the limb under the cursor, click to confirm it or to bend a
 * confirmed ring through one more anchor. A ring is a closed cubic spline through fitted anchors,
 * and confirming changes memory only: {@link #saveRings} alone writes the working .dsl.
 */
public final class RingTool {

    /** Status line the scene shows while the tool is active. */
    public static final String STATUS_LINE =
            "ring tool: hover to preview, click to confirm, click a ring to add an anchor, "
                    + "scroll to tilt, Ctrl+S to save, Esc to finish";

    /** Prefix of every line the tool logs. */
    public static final String LOG_PREFIX = "[ring-tool] ";

    /**
     * Colour the loop a click acts on draws in, distinct from every ring colour: the un-confirmed
     * preview, or the confirmed ring under the cursor.
     */
    public static final int PREVIEW_COLOR = 0xFF2D95;

    /** Colours rings take in turn, so neighbouring rings never share one. */
    public static final int[] RING_COLORS = {
        0x2ADF4F, 0x2E9BFF, 0xFFD60A, 0xFF9F0A, 0x9D7BFF, 0x00E5D0, 0xFF6B6B, 0xB6FF3B };

    /** Opening of every numbered ring label and statement id, before its optional underscore. */
    public static final String RING_LABEL_PREFIX = "ring";

    /** Ring labels the graph's nodes leave when a statement names none; these carry no number. */
    public static final String[] UNNUMBERED_RING_LABELS = {
        LoopThroughPointsNode.DEFAULT_MARK_LABEL, SplineRingNode.DEFAULT_MARK_LABEL,
        SelectRingNode.SELECTED_LABEL };

    /** Digits a ring number may run to, past which the label is not a ring number at all. */
    public static final int MAXIMUM_RING_DIGITS = 9;

    /**
     * Bisections a hovered ring is traced to. One below the confirmed depth, because the fit
     * traces once per anchor it inserts and the hover has a frame to do it in.
     */
    public static final int HOVER_DEPTH = SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH - 1;

    /** Colour every anchor dot draws in, so a dot reads against whatever colour its ring took. */
    public static final int ANCHOR_COLOR = 0xFFFFFF;

    /** How near a confirmed ring's polyline a click counts as landing on that ring, in pixels. */
    public static final float RING_HIT_PIXELS = 8f;

    /** Plane tilt one scroll tick applies, in radians. */
    public static final float TILT_RADIANS_PER_TICK = (float) Math.toRadians(4.0);

    /** Coordinates per point in every packed position here. */
    public static final int COORDINATES_PER_POINT = 3;

    /** Floats one drawn segment costs: its two packed xyz endpoints. */
    public static final int SEGMENT_FLOATS = 2 * COORDINATES_PER_POINT;


    /** One half: the field of view's half angle, and which end of an edge a crossing snaps to. */
    public static final float HALF = 0.5f;

    /** Scene the tool runs on, which owns the surface, the camera and the working graph. */
    public final RingScene scene;

    /** Rings confirmed in this session, in the order they were confirmed. */
    public final List<SurfaceSpline> confirmedRings = new ArrayList<>();

    /** Tracer behind each confirmed ring, so a click can insert an anchor and re-trace locally. */
    public final List<SurfaceSplineTracer> confirmedTracers = new ArrayList<>();

    /**
     * Statement id each confirmed ring was last saved under, {@code null} until a save writes it.
     */
    public final List<String> confirmedStatementIds = new ArrayList<>();

    /** Whether each confirmed ring carries changes the working .dsl does not hold yet. */
    public final List<Boolean> confirmedRingUnsaved = new ArrayList<>();

    /** Skeleton and curvature the preview plane's normal comes from, cached per model. */
    public final LimbAxis limbAxis = new LimbAxis();

    /** Geodesic engine over the current surface, built once and reused every frame. */
    public SurfaceGeodesics geodesics;

    /** Whether the tool is taking the mouse. */
    public boolean active;

    /** The previewed spline's points, packed xyz, closed by its last-to-first span. */
    public float[] previewPolyline = new float[0];

    /** Packed xyz of the previewed spline's anchors. */
    public float[] previewAnchorXyz = new float[0];

    /** Anchors the fit settled on for the preview. */
    public int previewAnchorCount;

    /** Mesh edges the girdling cut the preview was fitted to crosses. */
    public int previewGirdleEdgeCount;

    /** Whether the last frame found a loop under the cursor. */
    public boolean previewValid;

    /** Wall time the last preview frame spent, in milliseconds. */
    public double previewMillis;

    /** Euclidean length of the previewed spline. */
    public double previewLength;

    /** Tolerance the last fit ran to, in model units. */
    public double previewTolerance;

    /** Largest distance the fitted spline still sits from the girdling cut. */
    public double previewDeviation;

    /** Whether the fit ran out of anchors before it met the tolerance. */
    public boolean previewAnchorCapReached;

    /** Geodesics the last preview fit computed, the cost the hover budget is spent on. */
    public long previewGeodesicCount;

    /** Surface point under the cursor on the last frame, packed xyz. */
    public final float[] previewHitPoint = new float[COORDINATES_PER_POINT];

    /** Normal of the plane the girdling cut was taken with, packed xyz. */
    public final float[] previewPlaneNormal = new float[COORDINATES_PER_POINT];

    /** Limb axis estimated at the hit point, before the scroll tilt, packed xyz. */
    public final float[] previewLimbAxis = new float[COORDINATES_PER_POINT];

    /** Whether the loop kept this frame came from the skeleton estimate or the curvature one. */
    public boolean axisFromSkeleton;

    /** Confirmed ring under the cursor, whose next click inserts an anchor, or -1 for none. */
    public int hoveredRing = -1;

    /** Extra rotation of the cutting plane about the view axis, in radians. */
    public float tiltAboutView;

    /** Extra rotation of the cutting plane about the other surface tangent, in radians. */
    public float tiltAboutTangent;

    /** Number drawn beside each ring, in drawing order: the graph's rings then the confirmed. */
    public String[] drawnRingLabel = new String[0];

    /** Colour each drawn ring takes, parallel to {@link #drawnRingLabel}. */
    public int[] drawnRingColorRgb = new int[0];

    /** One-line summary of the last confirmed or edited ring, shown under the status line. */
    public String lastRow = "";

    /** Why the last confirm failed, or empty when it did not. */
    public String lastError = "";

    private final SurfacePicker picker = new SurfacePicker();
    private final GirdlingPlane girdle = new GirdlingPlane();
    private final float[] rayOrigin = new float[COORDINATES_PER_POINT];
    private final float[] rayDirection = new float[COORDINATES_PER_POINT];
    private final float[] hitPoint = new float[COORDINATES_PER_POINT];
    private final float[] limbDirection = new float[COORDINATES_PER_POINT];
    private final Vector3f scratchPosition = new Vector3f();
    private SurfaceSplineTracer previewTracer;
    private SplineAnchorFit previewFit;
    private int[] girdleVertexId = new int[0];
    private MeshTopology preparedSurface;
    private float[] ringSegment = new float[0];
    private int[] ringSegmentStart = { 0 };
    private float[] ringLabelXyz = new float[0];
    private float[] ringLabelLift = new float[0];
    private boolean ringsStale = true;
    private boolean overlayStale = true;
    private boolean uploadedPreview;
    private int uploadedHoveredRing = -1;
    private float[] uploadedPreviewPolyline = new float[0];

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
        Platforms.get().log(LOG_PREFIX +STATUS_LINE);
    }

    /** Leave the tool, keeping every ring confirmed while it ran and freeing the pick copy. */
    public void finish() {
        active = false;
        previewValid = false;
        hoveredRing = -1;
        previewAnchorCount = 0;
        previewPolyline = new float[0];
        previewAnchorXyz = new float[0];
        overlayStale = true;
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        if (runtime != null) {
            runtime.uploadFacePickBuffer(null);
        }
        preparedSurface = null;
        Platforms.get().log("[ring-tool] finished with " + confirmedRings.size()
                + " confirmed ring(s), " + unsavedRingCount() + " unsaved");
    }

    /**
     * Tilt the cutting plane, for the limbs whose estimated axis is wrong.
     *
     * @param ticks             signed scroll ticks
     * @param aboutOtherTangent true to tilt about the surface tangent rather than the view axis
     */
    public void tilt(double ticks, boolean aboutOtherTangent) {
        if (aboutOtherTangent) {
            tiltAboutTangent += (float) (TILT_RADIANS_PER_TICK * ticks);
        } else {
            tiltAboutView += (float) (TILT_RADIANS_PER_TICK * ticks);
        }
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
     * One frame of the tool: pick the face under the cursor, and either highlight the confirmed
     * ring the cursor sits on or fit a fresh spline to the girdling cut through the hit point.
     * Uploads the overlay only when what it draws changed.
     */
    public void perFrame() {
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        MeshTopology surface = scene.halfEdgeSurface();
        if (!active || runtime == null || surface == null || surface.faceCount() == 0) {
            uploadOverlay();
            return;
        }
        long start = System.nanoTime();
        prepare(runtime, surface);
        previewValid = false;
        previewAnchorCount = 0;
        previewGirdleEdgeCount = 0;
        previewLength = 0.0;
        hoveredRing = -1;
        int width = Platforms.get().getWindowWidth();
        int height = Platforms.get().getWindowHeight();
        int framebufferX = width <= 0 ? 0
                : Math.round(scene.orbitMouse.lastX * (float) Platforms.get().getFrameBufferWidth()
                        / width);
        int framebufferY = height <= 0 ? 0
                : Math.round(scene.orbitMouse.lastY
                        * (float) Platforms.get().getFrameBufferHeight() / height);
        int faceIndex = runtime.faceIndexAtPixel(scene.camera, framebufferX, framebufferY);
        if (faceIndex >= 0 && faceIndex < surface.faceCount()
                && runtime.rayThroughPixel(scene.camera, framebufferX, framebufferY, rayOrigin,
                        rayDirection)) {
            int faceId = surface.faceIdAt(faceIndex);
            if (picker.hitFace(surface, faceId, rayOrigin, rayDirection)) {
                hitPoint[0] = picker.pointX;
                hitPoint[1] = picker.pointY;
                hitPoint[2] = picker.pointZ;
                System.arraycopy(hitPoint, 0, previewHitPoint, 0, COORDINATES_PER_POINT);
                hoveredRing = ringUnderCursor();
                previewValid = hoveredRing < 0 && previewAt(surface, faceId);
            }
        }
        previewMillis = (System.nanoTime() - start) / 1e6;
        if (previewValid != uploadedPreview || hoveredRing != uploadedHoveredRing
                || (previewValid && !Arrays.equals(previewPolyline, uploadedPreviewPolyline))) {
            overlayStale = true;
        }
        uploadOverlay();
    }

    /**
     * Place a ring where the preview is, or add an anchor to the confirmed ring under the cursor.
     * The ring is held in memory only: nothing reaches the working .dsl until {@link #saveRings}.
     *
     * @return true when a ring was confirmed or edited
     */
    public boolean confirm() {
        lastError = "";
        if (!active) {
            lastError = "the ring tool is not running";
            return false;
        }
        if (hoveredRing >= 0) {
            return insertAnchorInto(hoveredRing);
        }
        if (!previewValid || previewAnchorCount < SurfaceSplineTracer.MINIMUM_ANCHORS) {
            lastError = "no preview loop under the cursor";
            return false;
        }
        long start = System.nanoTime();
        previewTracer.maximumDepth = SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH;
        previewTracer.retraceAll();
        SurfaceSpline spline = SurfaceSpline.of(previewTracer);
        confirmedRings.add(spline);
        confirmedTracers.add(previewTracer);
        previewTracer = null;
        confirmedStatementIds.add(null);
        confirmedRingUnsaved.add(true);
        lastRow = String.format(Locale.ROOT,
                "ring %d: %d anchors, %d edges, length %.5f, centroid %.5f,%.5f,%.5f, "
                        + "sharpest corner %.1f deg, %.0f ms, unsaved",
                drawnRingNumber(confirmedRings.size() - 1), spline.anchorCount,
                spline.markedEdgeCount, spline.length,
                spline.centroidX, spline.centroidY, spline.centroidZ,
                spline.minimumInteriorAngleDegrees, (System.nanoTime() - start) / 1e6);
        Platforms.get().log(LOG_PREFIX +lastRow + ", fingerprint "
                + EdgeMarks.fingerprint(scene.halfEdgeSurface(), spline.markedByEdgeId));
        invalidateRings();
        uploadOverlay();
        return true;
    }

    /**
     * Bend one confirmed ring through the point under the cursor, re-tracing only the segments
     * whose control polygon the new anchor moved and marking the ring unsaved.
     */
    private boolean insertAnchorInto(int ring) {
        SurfaceSplineTracer tracer = confirmedTracers.get(ring);
        long start = System.nanoTime();
        int vertexId = tracer.nearestTracedVertex(hitPoint[0], hitPoint[1], hitPoint[2]);
        if (vertexId < 0 || tracer.nearestSegment < 0
                || !tracer.insertAnchor(tracer.nearestSegment + 1, vertexId)) {
            lastError = "that point already carries an anchor";
            return false;
        }
        SurfaceSpline spline = SurfaceSpline.of(tracer);
        confirmedRings.set(ring, spline);
        confirmedRingUnsaved.set(ring, true);
        lastRow = String.format(Locale.ROOT,
                "ring %d: anchor inserted, now %d anchors, %d edges, length %.5f, "
                        + "sharpest corner %.1f deg, %.0f ms, unsaved",
                drawnRingNumber(ring), spline.anchorCount, spline.markedEdgeCount, spline.length,
                spline.minimumInteriorAngleDegrees, (System.nanoTime() - start) / 1e6);
        Platforms.get().log(LOG_PREFIX +lastRow + ", fingerprint "
                + EdgeMarks.fingerprint(scene.halfEdgeSurface(), spline.markedByEdgeId));
        invalidateRings();
        uploadOverlay();
        return true;
    }

    /**
     * The confirmed ring whose traced polyline passes within {@link #RING_HIT_PIXELS} of the
     * cursor, which a click edits instead of starting a new ring.
     */
    private int ringUnderCursor() {
        double reach = RING_HIT_PIXELS * worldPerPixel();
        int nearest = -1;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int ring = 0; ring < confirmedTracers.size(); ring++) {
            SurfaceSplineTracer tracer = confirmedTracers.get(ring);
            if (tracer.nearestTracedVertex(hitPoint[0], hitPoint[1], hitPoint[2]) < 0) {
                continue;
            }
            if (tracer.nearestDistance < reach && tracer.nearestDistance < nearestDistance) {
                nearestDistance = tracer.nearestDistance;
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
     * Cut the girdling plane through the hit point and fit spline anchors to it, leaving the
     * tracer holding the previewed ring.
     */
    private boolean previewAt(MeshTopology surface, int faceId) {
        axisFromSkeleton = limbAxis.skeletonAxisAt(hitPoint[0], hitPoint[1], hitPoint[2],
                limbDirection);
        boolean seeded = axisFromSkeleton || limbAxis.curvatureAxisAt(faceId, limbDirection);
        if (seeded) {
            System.arraycopy(limbDirection, 0, previewLimbAxis, 0, COORDINATES_PER_POINT);
        }
        girdle.tiltAboutView = tiltAboutView;
        girdle.tiltAboutTangent = tiltAboutTangent;
        System.arraycopy(rayDirection, 0, girdle.viewDirection, 0, COORDINATES_PER_POINT);
        if (!girdle.find(surface, faceId, hitPoint, seeded ? limbDirection : null)) {
            return false;
        }
        System.arraycopy(girdle.normal, 0, previewPlaneNormal, 0, COORDINATES_PER_POINT);
        previewGirdleEdgeCount = girdle.cut.stepCount;
        if (girdleVertexId.length < girdle.cut.stepCount) {
            girdleVertexId = new int[girdle.cut.stepCount];
        }
        int firstPoint = 0;
        double nearest = Double.POSITIVE_INFINITY;
        for (int step = 0; step < girdle.cut.stepCount; step++) {
            int halfEdge = surface.edgeHalfEdge(girdle.cut.edgeId[step]);
            girdleVertexId[step] = girdle.cut.crossingFraction[step] <= HALF
                    ? surface.halfEdgeVertex(halfEdge)
                    : surface.halfEdgeEndVertex(halfEdge);
            int base = COORDINATES_PER_POINT * step;
            double dx = girdle.polyline[base] - hitPoint[0];
            double dy = girdle.polyline[base + 1] - hitPoint[1];
            double dz = girdle.polyline[base + 2] - hitPoint[2];
            double squared = dx * dx + dy * dy + dz * dz;
            if (squared < nearest) {
                nearest = squared;
                firstPoint = step;
            }
        }
        previewTracer = new SurfaceSplineTracer(geodesics);
        previewTracer.maximumDepth = HOVER_DEPTH;
        previewFit = new SplineAnchorFit(previewTracer);
        long geodesicsBefore = geodesics.geodesicCount;
        if (!previewFit.fit(girdle.polyline, girdle.cut.stepCount, girdleVertexId, firstPoint)) {
            return false;
        }
        previewGeodesicCount = geodesics.geodesicCount - geodesicsBefore;
        previewTolerance = previewFit.tolerance;
        previewDeviation = previewFit.deviation;
        previewAnchorCapReached = previewFit.anchorCapReached;
        previewAnchorCount = previewTracer.anchorCount;
        packPreview(surface);
        return previewAnchorCount >= SurfaceSplineTracer.MINIMUM_ANCHORS;
    }

    /** The tracer's segments as one closed polyline plus the anchor positions, ready to draw. */
    private void packPreview(MeshTopology surface) {
        int points = 0;
        for (int segment = 0; segment < previewTracer.anchorCount; segment++) {
            points += previewTracer.segmentVertexId[segment].length;
        }
        previewPolyline = new float[COORDINATES_PER_POINT * points];
        int cursor = 0;
        for (int segment = 0; segment < previewTracer.anchorCount; segment++) {
            double[] xyz = previewTracer.segmentXyz[segment];
            for (int value = 0; value < xyz.length; value++) {
                previewPolyline[cursor++] = (float) xyz[value];
            }
        }
        previewLength = 0.0;
        for (int point = 0; point < points; point++) {
            int here = COORDINATES_PER_POINT * point;
            int there = COORDINATES_PER_POINT * ((point + 1) % points);
            double dx = previewPolyline[there] - previewPolyline[here];
            double dy = previewPolyline[there + 1] - previewPolyline[here + 1];
            double dz = previewPolyline[there + 2] - previewPolyline[here + 2];
            previewLength += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        previewAnchorXyz = new float[COORDINATES_PER_POINT * previewTracer.anchorCount];
        for (int anchor = 0; anchor < previewTracer.anchorCount; anchor++) {
            surface.vertexPosition(previewTracer.anchorVertexId[anchor], scratchPosition);
            previewAnchorXyz[COORDINATES_PER_POINT * anchor] = scratchPosition.x;
            previewAnchorXyz[COORDINATES_PER_POINT * anchor + 1] = scratchPosition.y;
            previewAnchorXyz[COORDINATES_PER_POINT * anchor + 2] = scratchPosition.z;
        }
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
     * Write every unsaved ring to the working .dsl in confirm order, appending a
     * {@code spline_ring} statement for a ring that has never been saved and rewriting in place
     * the statement of one edited since. This is the only path that touches the file.
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
                SurfaceSpline spline = confirmedRings.get(ring);
                if (savedId[ring] == null) {
                    savedId[ring] = RingDslWriter.nextRingId(source, liveLabels);
                    source = RingDslWriter.appendSpline(source, spline.anchorXyz,
                            spline.anchorCount, liveLabels);
                    appended++;
                } else {
                    source = RingDslWriter.replaceSpline(source, savedId[ring], spline.anchorXyz,
                            spline.anchorCount);
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
        }
        lastRow = String.format(Locale.ROOT,
                "saved %d ring(s) to %s: %d appended, %d rewritten in place",
                unsaved, path, appended, unsaved - appended);
        Platforms.get().log(LOG_PREFIX +lastRow);
        return true;
    }

    /**
     * Forget every confirmed ring, for a model switch that leaves them describing a surface which
     * is no longer on screen, saying how many unsaved rings went with them.
     *
     * @param reason what dropped the rings, for the log line
     */
    public void discardConfirmedRings(String reason) {
        if (confirmedRings.isEmpty()) {
            return;
        }
        Platforms.get().log(LOG_PREFIX +reason + " discarded " + confirmedRings.size()
                + " confirmed ring(s), " + unsavedRingCount() + " of them unsaved");
        confirmedRings.clear();
        confirmedTracers.clear();
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
     * anchors as markers, and each ring's number as a label at its centroid. Runs when a ring, the
     * hover or the graph's marks changed, not per frame.
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
        uploadedPreview = previewValid;
        uploadedHoveredRing = hoveredRing;
        uploadedPreviewPolyline = previewPolyline;
        int rings = drawnRingColorRgb.length;
        boolean hovering = previewValid && previewPolyline.length >= 2 * COORDINATES_PER_POINT;
        float[] preview = hovering ? closedPolylineSegments(previewPolyline) : new float[0];
        float[] segments = new float[ringSegment.length + preview.length];
        System.arraycopy(ringSegment, 0, segments, 0, ringSegment.length);
        System.arraycopy(preview, 0, segments, ringSegment.length, preview.length);
        int[] groupStart = new int[rings + (hovering ? 2 : 1)];
        System.arraycopy(ringSegmentStart, 0, groupStart, 0, rings + 1);
        int[] groupColor = new int[rings + (hovering ? 1 : 0)];
        System.arraycopy(drawnRingColorRgb, 0, groupColor, 0, rings);
        if (hovering) {
            groupStart[rings + 1] = groupStart[rings] + preview.length / SEGMENT_FLOATS;
            groupColor[rings] = PREVIEW_COLOR;
        }
        if (hoveredRing >= 0 && drawnRingNumber(hoveredRing) < rings) {
            groupColor[drawnRingNumber(hoveredRing)] = PREVIEW_COLOR;
        }
        overlay.setLineGroups(segments, groupStart, groupColor);
        overlay.setMarkers(anchorPositions(), ANCHOR_COLOR);
        overlay.setLabels(ringLabelXyz, drawnRingLabel, drawnRingColorRgb, ringLabelLift);
    }

    /**
     * Rebuild the per-ring arrays the overlay draws from: the graph's ring marks first, then the
     * rings confirmed here, each with its number, its colour, the length-weighted centroid its
     * number is drawn at and the mean radius that number must float over.
     */
    private void rebuildRings() {
        ringsStale = false;
        MeshTopology surface = scene.halfEdgeSurface();
        List<float[]> perRing = new ArrayList<>();
        Map<String, boolean[]> graphMarks = scene.ringMarksByLabel;
        if (surface != null) {
            for (Map.Entry<String, boolean[]> entry : graphMarks.entrySet()) {
                perRing.add(markedEdgeSegments(surface, entry.getValue()));
            }
        }
        for (SurfaceSpline confirmed : confirmedRings) {
            perRing.add(closedPolylineSegments(confirmed.polyline));
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
     * Every anchor the overlay marks: the confirmed rings' anchors, and the previewed ring's while
     * the cursor holds a preview.
     *
     * @return packed xyz of every anchor drawn
     */
    private float[] anchorPositions() {
        int confirmed = 0;
        for (SurfaceSpline ring : confirmedRings) {
            confirmed += ring.anchorCount;
        }
        boolean hovering = previewValid && previewAnchorCount > 0;
        float[] anchorXyz = new float[COORDINATES_PER_POINT
                * (confirmed + (hovering ? previewAnchorCount : 0))];
        int cursor = 0;
        for (SurfaceSpline spline : confirmedRings) {
            System.arraycopy(spline.anchorXyz, 0, anchorXyz, COORDINATES_PER_POINT * cursor,
                    COORDINATES_PER_POINT * spline.anchorCount);
            cursor += spline.anchorCount;
        }
        if (hovering) {
            System.arraycopy(previewAnchorXyz, 0, anchorXyz, COORDINATES_PER_POINT * cursor,
                    COORDINATES_PER_POINT * previewAnchorCount);
        }
        return anchorXyz;
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
