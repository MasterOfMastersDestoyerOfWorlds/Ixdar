package ixdar.tools.ring;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import ixdar.geometry.mesh.data.analysis.curvature.LimbAxis;
import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;
import ixdar.geometry.mesh.data.paths.query.GirdlingPlane;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.AuthoredSplineRing;
import ixdar.geometry.mesh.data.rings.RingSegmentMode;
import ixdar.platform.input.Keys;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Item;
import ixdar.tools.Preview;

/**
 * The ring tool's default sub-tool: hover previews the ring girdling the limb under the cursor, a
 * click drafts it, re-opens a confirmed ring or converts a graph ring. The ring tool holds the
 * rings and the draft.
 */
public final class ClosedRingTool extends RingSubTool {

    public static final String TOOL_NAME = "ring";

    /** Skeleton and curvature the preview plane's normal comes from, cached per model. */
    public final LimbAxis limbAxis = new LimbAxis();

    /** Euclidean length of the previewed spline. */
    public double previewLength;

    /** Tolerance the last fit ran to, in model units. */
    public double previewTolerance;

    /** Largest distance the fitted spline still sits from its reference loop. */
    public double previewDeviation;

    /** Whether the fit ran out of anchors before it met the tolerance. */
    public boolean previewAnchorCapReached;

    /** Normal of the plane the preview was cut with, at the precision a save writes. */
    public final float[] previewPlaneNormal = new float[RingTool.COORDINATES_PER_POINT];

    /** Limb axis estimated at the hit point, packed xyz. */
    public final float[] previewLimbAxis = new float[RingTool.COORDINATES_PER_POINT];

    /** Whether the loop kept this frame came from the skeleton estimate or the curvature one. */
    public boolean axisFromSkeleton;

    /** Confirmed ring under the cursor, which a click re-opens as the draft, or -1 for none. */
    public int hoveredRing = -1;

    /** Graph ring under the cursor, which a click turns into a draft, or null for none. */
    public String hoveredGraphRing;

    private AuthoredSplineRing draftRing;
    private final GirdlingPlane girdle = new GirdlingPlane();
    private final float[] limbDirection = new float[RingTool.COORDINATES_PER_POINT];
    private AuthoredSplineRing previewRing;
    private int previewAnchorCount;
    private int previewGirdleEdgeCount;
    private long previewGeodesicCount;

    /**
     * The closed-ring interaction of a ring tool, which it runs while no other sub-tool is active.
     *
     * @param ringTool the ring tool owning it
     */
    public ClosedRingTool(RingTool ringTool) {
        super(ringTool);
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    @Override
    public String desc() {
        return "Draw closed rings round the limbs and edit their anchors";
    }

    @Override
    public void activate() {
    }

    /** Drop the hover: no ring under the cursor and no preview anchors. */
    @Override
    public void deactivate() {
        hoveredRing = -1;
        hoveredGraphRing = null;
        previewAnchorCount = 0;
    }

    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint("click", "draft ring / add or pick anchor"));
        controls.add(new ControlHint(Keys.X, "X", "discard draft",
                ringTool.refusalShown(() -> ringTool.discardDraft())));
        // Delete or Backspace removes the selected anchor, or the whole ring with none selected.
        for (int key : new int[] { Keys.DELETE, Keys.BACKSPACE }) {
            controls.add(new ControlHint(key, key == Keys.DELETE ? "del" : "backspace",
                    RingTool.REMOVE_SELECTED_ANCHOR_HINT, ringTool.refusalShown(() -> {
                        if (ringTool.draft != null && ringTool.selectedIndex() < 0) {
                            ringTool.deleteDraftRing();
                        } else {
                            ringTool.deleteSelectedAnchor();
                        }
                    })));
        }
        controls.add(new ControlHint(Keys.K, "K", "bisect", ringTool.refusalShown(() -> {
            if (ringTool.draft != null) {
                ringTool.lastError = "confirm or discard the open ring draft before bisecting";
            } else {
                ringTool.switchSubTool(ringTool.bisector);
            }
        })));
    }

    /**
     * The confirmed ring under the cursor, else the graph ring under it by its label.
     *
     * @return the hovered ring, or {@code null} when the cursor is over none
     */
    @Override
    public Item hovered() {
        if (hoveredRing >= 0 && hoveredRing < ringTool.confirmedRings.size()) {
            return ringTool.ringItem(hoveredRing);
        }
        if (hoveredGraphRing == null) {
            return null;
        }
        Item graphRing = new Item();
        graphRing.label = hoveredGraphRing;
        graphRing.saved = true;
        return graphRing;
    }

    /**
     * The hover preview: where the cursor hit, whether it found a girdling ring, how long the trace
     * took, and the trace's counts, length and fit as the detail line.
     *
     * @return the preview
     */
    @Override
    public Preview preview() {
        String detail = String.format(Locale.ROOT, "%d anchors, %d girdle edges, length %.5f, "
                + "tolerance %.5f, deviation %.5f, %d geodesics%s%s", previewAnchorCount,
                previewGirdleEdgeCount, previewLength, previewTolerance, previewDeviation,
                previewGeodesicCount, previewAnchorCapReached ? ", anchor cap reached" : "",
                axisFromSkeleton ? ", axis from the skeleton" : "");
        return new Preview(ringTool.previewHitPoint, ringTool.previewValid, ringTool.previewMillis,
                detail);
    }

    /**
     * One frame after the ring tool's pick: with no draft open and the surface under the cursor,
     * the confirmed or graph ring under it, else the one-anchor ring a click would draft there,
     * fitted on the girdling plane through the hit point.
     */
    @Override
    public void perFrame() {
        hoveredRing = -1;
        hoveredGraphRing = null;
        RingScene scene = ringTool.scene;
        if (ringTool.draft != null || scene.cursorVertexId < 0) {
            return;
        }
        long start = System.nanoTime();
        float[] hitPoint = scene.cursorPoint;
        int faceId = scene.cursorFaceId;
        double reach = RingTool.RING_HIT_PIXELS * scene.worldPerPixel();
        for (int ring = 0; ring < ringTool.confirmedRings.size(); ring++) {
            if (ringTool.confirmedRingDeleted.get(ring)) {
                continue;
            }
            TracedSurfacePath path = ringTool.confirmedRings.get(ring).path;
            double distance = SurfaceSpline.distanceToPolyline(path.positions, path.pointCount,
                    hitPoint[0], hitPoint[1], hitPoint[2]);
            if (distance < reach) {
                reach = distance;
                hoveredRing = ring;
            }
        }
        double graphReach = RingTool.RING_HIT_PIXELS * scene.worldPerPixel();
        List<float[]> graphSegments = ringTool.graphRingSegments;
        for (int ring = 0; hoveredRing < 0 && ring < graphSegments.size(); ring++) {
            float[] segments = graphSegments.get(ring);
            for (int base = 0; base + RingTool.SEGMENT_FLOATS <= segments.length;
                    base += RingTool.SEGMENT_FLOATS) {
                double spanX = segments[base + RingTool.COORDINATES_PER_POINT] - segments[base];
                double spanY = segments[base + RingTool.COORDINATES_PER_POINT + 1] - segments[base + 1];
                double spanZ = segments[base + RingTool.COORDINATES_PER_POINT + 2] - segments[base + 2];
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
                    hoveredGraphRing = ringTool.graphRingLabels.get(ring);
                }
            }
        }
        boolean cursorStill = faceId == ringTool.previewedFace
                && Arrays.equals(hitPoint, ringTool.previewedHit);
        if (hoveredRing >= 0 || hoveredGraphRing != null) {
            ringTool.previewValid = false;
            ringTool.previewedFace = -1;
            return;
        }
        if (cursorStill) {
            return;
        }
        // The girdling plane through the hit point, and the one-anchor ring through the vertex
        // under the cursor on it, exactly the ring a click would draft, in the mode it would.
        MeshTopology surface = ringTool.geodesics.mesh;
        previewAnchorCount = 0;
        previewGirdleEdgeCount = 0;
        previewLength = 0.0;
        ringTool.previewSpline = null;
        axisFromSkeleton = limbAxis.skeletonAxisAt(hitPoint[0], hitPoint[1], hitPoint[2],
                limbDirection);
        boolean seeded = axisFromSkeleton || limbAxis.curvatureAxisAt(faceId, limbDirection);
        if (seeded) {
            System.arraycopy(limbDirection, 0, previewLimbAxis, 0, RingTool.COORDINATES_PER_POINT);
        }
        System.arraycopy(scene.cursorRayDirection, 0, girdle.viewDirection, 0,
                RingTool.COORDINATES_PER_POINT);
        boolean found = girdle.find(surface, faceId, hitPoint, seeded ? limbDirection : null);
        long geodesicsBefore = ringTool.geodesics.geodesicCount;
        if (found) {
            previewGirdleEdgeCount = girdle.cut.path.pointCount;
            System.arraycopy(RingTool.written(girdle.normal), 0, previewPlaneNormal, 0,
                    RingTool.COORDINATES_PER_POINT);
            if (previewRing == null || previewRing.tracer.geodesics != ringTool.geodesics) {
                previewRing = new AuthoredSplineRing(ringTool.geodesics);
            }
            geodesicsBefore = ringTool.geodesics.geodesicCount;
            found = ringTool.newDraftMode() != RingSegmentMode.CREASE
                    || ringTool.readyCreases(surface);
        }
        if (found) {
            // In crease mode the loop away from the cursor is pulled into the grooves.
            previewRing.mode = ringTool.newDraftMode();
            previewRing.creases = ringTool.creases;
            found = previewRing.trace(new int[] { scene.cursorVertexId }, 1, previewPlaneNormal,
                    AuthoredSplineRing.SUPPORTING_FIT_DEPTH);
        }
        if (found) {
            ringTool.previewAuthoredVertexId = scene.cursorVertexId;
            previewGeodesicCount = ringTool.geodesics.geodesicCount - geodesicsBefore;
            previewTolerance = previewRing.fit.tolerance;
            previewDeviation = previewRing.fit.deviation;
            previewAnchorCapReached = previewRing.fit.anchorCapReached;
            previewAnchorCount = previewRing.tracer.anchorCount;
            ringTool.previewSpline = SurfaceSpline.unsnapped(previewRing.tracer);
            previewLength = ringTool.previewSpline.path.length;
            // A ring crossing itself is not offered, as the click refuses to draft one.
            found = previewAnchorCount >= SurfaceSplineTracer.MINIMUM_ANCHORS && previewRing.simple;
        }
        ringTool.previewValid = found;
        ringTool.previewedFace = faceId;
        System.arraycopy(hitPoint, 0, ringTool.previewedHit, 0, RingTool.COORDINATES_PER_POINT);
        ringTool.previewMillis = (System.nanoTime() - start) / 1e6;
    }

    /**
     * A click the ring tool did not take to pick an anchor: on a draft it adds an anchor; without
     * one it re-opens the ring under the cursor, converts a graph ring, or drafts the preview.
     *
     * @param x framebuffer x of the click, unused: the ring tool's pick places it
     * @param y framebuffer y of the click, unused
     * @return true when the click edited the rings or the draft
     */
    @Override
    public boolean onClick(float x, float y) {
        RingScene scene = ringTool.scene;
        if (ringTool.draft != null) {
            return addAnchor(scene.cursorVertexId);
        }
        if (hoveredRing >= 0) {
            return ringTool.reopenRing(hoveredRing);
        }
        RingToolState stateBefore = new RingToolState(ringTool);
        if (hoveredGraphRing != null) {
            // Fit anchors to the graph ring's edge loop and make every one authored, with the
            // plane they fit as the base normal, so a save writes a spline_ring.
            String label = hoveredGraphRing;
            AuthoredSplineRing converted = new AuthoredSplineRing(ringTool.geodesics);
            int[] anchors = ringTool.fitLoop(RingTool.orderedLoop(ringTool.geodesics.mesh,
                    scene.ringMarksByLabel.get(label)), converted, "graph ring " + label);
            if (anchors.length > 0) {
                System.arraycopy(RingTool.written(converted.planeNormal), 0,
                        ringTool.draftBaseNormal, 0, RingTool.COORDINATES_PER_POINT);
                ringTool.draftMode = RingSegmentMode.GEODESIC;
            }
            if (anchors.length == 0
                    || !trace(anchors, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH, false)) {
                return false;
            }
            ringTool.convertedGraphLabels.add(label);
            ringTool.draftSourceRing = -1;
            ringTool.draftSourceLabel = label;
            ringTool.draftOwner = this;
            ringTool.selectedAnchorVertexId = -1;
            ringTool.recordEdit("graph ring converted", stateBefore);
            ringTool.invalidateRings();
            ringTool.reportDraft("converted graph ring " + label + " into the draft");
            return true;
        }
        if (!ringTool.previewValid || ringTool.previewAuthoredVertexId < 0) {
            ringTool.lastError = String.format(Locale.ROOT, "no ring fits under the cursor - move "
                    + "along the limb until the %s preview ring shows, then click",
                    ringTool.newDraftMode() == RingSegmentMode.CREASE ? "purple" : "red");
            return false;
        }
        // The previewed ring at the full depth, in the mode it was previewed in. The hover shows
        // no ring that crosses itself, so the click refuses one only where the deeper trace
        // crosses and the preview's did not.
        ringTool.draftSourceRing = -1;
        ringTool.draftSourceLabel = null;
        ringTool.draftMode = ringTool.newDraftMode();
        System.arraycopy(previewPlaneNormal, 0, ringTool.draftBaseNormal, 0,
                RingTool.COORDINATES_PER_POINT);
        if (!trace(new int[] { ringTool.previewAuthoredVertexId },
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH, true)) {
            return false;
        }
        ringTool.draftOwner = this;
        ringTool.selectedAnchorVertexId = -1;
        ringTool.recordEdit("draft opened", stateBefore);
        ringTool.reportDraft("drafted");
        return true;
    }

    /**
     * Add an authored anchor to the ring draft, between the neighbours where it adds the least
     * surface length; a vertex already held, or one that makes the ring cross itself, is refused.
     *
     * @param vertexId mesh vertex the anchor sits on
     * @return true when the draft took the anchor
     */
    @Override
    public boolean addAnchor(int vertexId) {
        for (int held : ringTool.draftAuthoredVertexId) {
            if (held == vertexId) {
                ringTool.lastError = RingTool.ANCHOR_TAKEN;
                return false;
            }
        }
        RingToolState stateBefore = new RingToolState(ringTool);
        holdDraft();
        long start = System.nanoTime();
        if (!draftRing.insert(vertexId, ringTool.draftBaseNormal,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
            ringTool.lastError = "anchor refused: " + draftRing.failure + " - click another place";
            return false;
        }
        ringTool.draftAuthoredVertexId = draftRing.authoredVertexId;
        ringTool.draft = SurfaceSpline.of(draftRing.tracer);
        ringTool.draftDepth = SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH;
        ringTool.draftMillis = (System.nanoTime() - start) / 1e6;
        ringTool.recordEdit("anchor added", stateBefore);
        ringTool.reportDraft("authored anchor added");
        return true;
    }

    /**
     * Trace the ring draft at the confirmed depth.
     *
     * @param authored   anchor vertices in ring order
     * @param keepSimple whether to refuse anchors that make a simple ring cross itself
     * @return true when the draft now runs through {@code authored}
     */
    @Override
    public boolean retrace(int[] authored, boolean keepSimple) {
        return trace(authored, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH, keepSimple);
    }

    /**
     * A drag step: the selected anchor moves to the vertex keeping its place in the ring,
     * re-traced at the fit's depth, the rate a frame allows.
     *
     * @param selected index of the selected anchor in the draft's authored anchors
     * @param vertexId mesh vertex it moves to
     * @return true when the draft took the move
     */
    @Override
    public boolean moveAnchor(int selected, int vertexId) {
        int[] moved = Arrays.copyOf(ringTool.draftAuthoredVertexId,
                ringTool.draftAuthoredVertexId.length);
        moved[selected] = vertexId;
        if (!trace(moved, AuthoredSplineRing.SUPPORTING_FIT_DEPTH, true)) {
            return false;
        }
        ringTool.selectedAnchorVertexId = vertexId;
        return true;
    }

    /**
     * Remove the selected authored anchor; the ring re-fits without it, and removing the last one
     * discards the draft.
     *
     * @return true when an anchor was removed
     */
    @Override
    public boolean deletePoint() {
        int selected = ringTool.selectedIndex();
        int[] before = ringTool.draftAuthoredVertexId;
        if (before.length == 1) {
            ringTool.discardDraft();
            ringTool.lastRow = "removed the last authored anchor: draft discarded";
            return true;
        }
        RingToolState stateBefore = new RingToolState(ringTool);
        int[] kept = new int[before.length - 1];
        System.arraycopy(before, 0, kept, 0, selected);
        System.arraycopy(before, selected + 1, kept, selected, kept.length - selected);
        if (!retrace(kept, true)) {
            return false;
        }
        ringTool.recordEdit("anchor deleted", stateBefore);
        ringTool.selectedAnchorVertexId = -1;
        ringTool.reportDraft("authored anchor removed");
        return true;
    }

    /**
     * A ring draft is always confirmable; its trace already refused one that decided no ring.
     *
     * @return true
     */
    @Override
    public boolean confirmable() {
        return true;
    }

    @Override
    public String draftDescription() {
        return ringTool.draftMode.label;
    }

    @Override
    public String draftDetail() {
        return "";
    }

    @Override
    public String draftLabel() {
        return RingTool.DRAFT_LABEL;
    }

    @Override
    public float[] draftCrossing() {
        return draftRing == null || draftRing.simple ? null : draftRing.crossings.firstCrossingXyz;
    }

    @Override
    public boolean drawsInvalid() {
        return false;
    }

    @Override
    public int[] markedVertices() {
        return new int[0];
    }

    @Override
    public void draftClosed() {
    }

    /**
     * Trace the ring draft through authored anchors in ring order under the draft's base normal,
     * keeping the old draft and saying why when the anchors decide no ring, or, with
     * {@code keepSimple}, when they would make a simple draft cross itself.
     *
     * @param authored   anchor vertices in ring order
     * @param depth      bisections the ring is traced to
     * @param keepSimple whether to refuse anchors that make a simple ring cross itself
     * @return true when the draft now runs through {@code authored}
     */
    private boolean trace(int[] authored, int depth, boolean keepSimple) {
        holdDraft();
        boolean wasSimple = ringTool.draft == null || draftRing.simple;
        long start = System.nanoTime();
        if (!draftRing.trace(authored, authored.length, ringTool.draftBaseNormal, depth)) {
            ringTool.lastError = "ring refused: " + draftRing.failure
                    + " - nothing changed; try another place";
            return false;
        }
        if (keepSimple && wasSimple && !draftRing.simple) {
            float[] at = draftRing.crossings.firstCrossingXyz;
            ringTool.lastError = String.format(Locale.ROOT, "refused: the ring would cross itself "
                    + "near %.4f,%.4f,%.4f - nothing changed; try another place", at[0], at[1],
                    at[2]);
            return false;
        }
        ringTool.draftAuthoredVertexId = draftRing.authoredVertexId;
        ringTool.draft = SurfaceSpline.of(draftRing.tracer);
        ringTool.draftDepth = depth;
        ringTool.draftMillis = (System.nanoTime() - start) / 1e6;
        return true;
    }

    /**
     * Make {@link #draftRing} hold the draft as it stands, re-tracing it when an undo, a redo or a
     * refused edit left it behind, so the next edit starts from the draft's own anchor cycle.
     */
    private void holdDraft() {
        if (draftRing == null || draftRing.tracer.geodesics != ringTool.geodesics) {
            draftRing = new AuthoredSplineRing(ringTool.geodesics);
        }
        int[] held = ringTool.draftAuthoredVertexId;
        boolean behind = draftRing.mode != ringTool.draftMode
                || !Arrays.equals(draftRing.authoredVertexId, held);
        draftRing.mode = ringTool.draftMode;
        draftRing.creases = ringTool.creases;
        if (ringTool.draft != null && behind) {
            draftRing.trace(held, held.length, ringTool.draftBaseNormal, ringTool.draftDepth);
        }
    }
}
