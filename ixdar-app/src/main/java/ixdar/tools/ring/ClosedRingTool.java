package ixdar.tools.ring;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.analysis.curvature.LimbAxis;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;
import ixdar.geometry.mesh.data.paths.query.GirdlingPlane;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.AuthoredSplineRing;
import ixdar.geometry.mesh.data.rings.GeodesicRefit;
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

    public static final String DRAFT_LABEL = "draft";

    public static final String REMOVE_SELECTED_ANCHOR_HINT = "remove anchor (or ring)";

    public static final float RING_HIT_PIXELS = 8f;

    /** Curvature the preview plane's scan is seeded from, cached per model. */
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
    public final Vector3f previewPlaneNormal = new Vector3f();

    /** Limb axis estimated at the hit point. */
    public final Vector3f previewLimbAxis = new Vector3f();

    /** Confirmed ring under the cursor, which a click re-opens as the draft, or -1 for none. */
    public int hoveredRing = -1;

    /** Graph ring under the cursor, which a click turns into a draft, or null for none. */
    public String hoveredGraphRing;

    private AuthoredSplineRing draftRing;
    private final GirdlingPlane girdle = new GirdlingPlane();
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

    /** The rings take the input back: the controls list their rows, not the bisector's. */
    @Override
    public void activate() {
        ringTool.scene.refreshControls();
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
        controls.add(new ControlHint(Keys.CLICK, "draft ring / add or pick anchor"));
        controls.add(new ControlHint("discard-draft", Keys.X, "discard draft",
                ringTool.refusalShown(() -> discardDraft())));
        for (Keys key : new Keys[] { Keys.DELETE, Keys.BACKSPACE }) {
            controls.add(new ControlHint("remove-anchor", key, REMOVE_SELECTED_ANCHOR_HINT,
                    ringTool.refusalShown(this::removeSelected)));
        }
        controls.add(new ControlHint(RingTool.BISECT_ACTION, Keys.K, "bisect",
                ringTool.refusalShown(this::startBisecting)));
    }

    /** Remove the selected anchor, or the whole draft with none selected. */
    private void removeSelected() {
        if (ringTool.draft != null && ringTool.selectedIndex() < 0) {
            ringTool.deleteDraftRing();
        } else {
            ringTool.deleteSelectedAnchor();
        }
    }

    /** Switch to the bisector, refused while a ring draft is open. */
    private void startBisecting() {
        if (ringTool.draft != null) {
            ringTool.lastError = "confirm or discard the open ring draft before bisecting";
        } else {
            ringTool.switchSubTool(ringTool.bisector);
        }
    }

    /**
     * The confirmed ring under the cursor, else the graph ring under it, each labelled with the
     * number drawn beside it; a graph ring's own label is its detail.
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
        graphRing.label = Integer.toString(ringTool.unownedGraphRingLabels().indexOf(hoveredGraphRing));
        graphRing.detail = "graph ring " + hoveredGraphRing;
        graphRing.saved = true;
        return graphRing;
    }

    /**
     * The hover preview: where the cursor hit, whether it found a girdling ring, how long the trace
     * took, and as the detail the trace's counts, length and fit, or why the last frame fitted none.
     *
     * @return the preview, its detail always the last frame's
     */
    @Override
    public Preview preview() {
        RingScene scene = ringTool.scene;
        String unfitted = ringTool.draft != null ? "a draft is open: a click adds or picks an anchor"
                : scene != null && scene.cursorVertexId < 0 ? scene.cursorMissReason
                : hoveredRing >= 0 ? "over ring " + ringTool.drawnRingNumber(hoveredRing)
                        + ": a click re-opens it"
                : hoveredGraphRing != null ? "over graph ring " + hoveredGraphRing
                        + ": a click converts it into a draft" : "";
        String detail = !unfitted.isEmpty() ? unfitted : String.format(Locale.ROOT, "%d anchors, "
                + "%d girdle edges, length %.5f, tolerance %.5f, deviation %.5f, %d geodesics%s",
                previewAnchorCount, previewGirdleEdgeCount, previewLength, previewTolerance,
                previewDeviation, previewGeodesicCount,
                previewAnchorCapReached ? ", anchor cap reached" : "");
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
        Vector3f hitPoint = scene.cursorPoint;
        int faceId = scene.cursorFaceId;
        double reach = RING_HIT_PIXELS * scene.worldPerPixel();
        for (int ring = 0; ring < ringTool.confirmedRings.size(); ring++) {
            if (ringTool.confirmedRings.get(ring).deleted) {
                continue;
            }
            TracedSurfacePath path = ringTool.confirmedRings.get(ring).spline.path;
            double distance = SurfaceSpline.distanceToPolyline(path.positions, path.pointCount,
                    hitPoint.x, hitPoint.y, hitPoint.z);
            if (distance < reach) {
                reach = distance;
                hoveredRing = ring;
            }
        }
        double graphReach = RING_HIT_PIXELS * scene.worldPerPixel();
        List<float[]> graphSegments = ringTool.graphRingSegments;
        for (int ring = 0; hoveredRing < 0 && ring < graphSegments.size(); ring++) {
            float[] segments = graphSegments.get(ring);
            for (int base = 0; base + RingTool.SEGMENT_FLOATS <= segments.length;
                    base += RingTool.SEGMENT_FLOATS) {
                double spanX = segments[base + RingTool.COORDINATES_PER_POINT] - segments[base];
                double spanY = segments[base + RingTool.COORDINATES_PER_POINT + 1] - segments[base + 1];
                double spanZ = segments[base + RingTool.COORDINATES_PER_POINT + 2] - segments[base + 2];
                double toX = hitPoint.x - segments[base];
                double toY = hitPoint.y - segments[base + 1];
                double toZ = hitPoint.z - segments[base + 2];
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
                && hitPoint.equals(ringTool.previewedHit);
        if (hoveredRing >= 0 || hoveredGraphRing != null) {
            ringTool.previewValid = false;
            ringTool.previewedFace = -1;
            return;
        }
        if (cursorStill) {
            return;
        }
        MeshTopology surface = ringTool.geodesics.mesh;
        previewAnchorCount = 0;
        previewGirdleEdgeCount = 0;
        previewLength = 0.0;
        ringTool.previewSpline = null;
        Vector3f limbDirection = limbAxis.curvatureAxisAt(faceId);
        if (limbDirection != null) {
            previewLimbAxis.set(limbDirection);
        }
        girdle.viewDirection.set(scene.cursorRayDirection);
        boolean found = girdle.find(surface, faceId, new Vector3f(hitPoint), limbDirection);
        long geodesicsBefore = ringTool.geodesics.geodesicCount;
        if (found) {
            previewGirdleEdgeCount = girdle.cut.path.pointCount;
            previewPlaneNormal.set(RingTool.written(girdle.normal));
            if (previewRing == null || previewRing.tracer.geodesics != ringTool.geodesics) {
                previewRing = new AuthoredSplineRing(ringTool.geodesics);
            }
            geodesicsBefore = ringTool.geodesics.geodesicCount;
            found = ringTool.newDraftMode() != RingSegmentMode.CREASE
                    || ringTool.readyCreases(surface);
        }
        if (found) {
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
            found = previewAnchorCount >= SurfaceSplineTracer.MINIMUM_ANCHORS && previewRing.simple;
        }
        ringTool.previewValid = found;
        ringTool.previewedFace = faceId;
        ringTool.previewedHit.set(hitPoint);
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
            String label = hoveredGraphRing;
            AuthoredSplineRing converted = new AuthoredSplineRing(ringTool.geodesics);
            int[] anchors = ringTool.fitLoop(EdgeMarks.orderedLoop(ringTool.geodesics.mesh,
                    scene.ringMarksByLabel.get(label)), converted, "graph ring " + label);
            if (anchors.length > 0) {
                ringTool.draftBaseNormal.set(RingTool.written(converted.planeNormal));
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
        ringTool.draftSourceRing = -1;
        ringTool.draftSourceLabel = null;
        ringTool.draftMode = ringTool.newDraftMode();
        ringTool.draftBaseNormal.set(previewPlaneNormal);
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

    @Override
    public GeodesicRefit refitGeodesic() {
        holdDraft();
        draftRing.mode = RingSegmentMode.CREASE;
        long start = System.nanoTime();
        int[] held = ringTool.draftAuthoredVertexId;
        if (!draftRing.trace(held, held.length, ringTool.draftBaseNormal,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)
                || !draftRing.refitGeodesic(ringTool.draftBaseNormal,
                        SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
            ringTool.lastError = "geodesic fit refused: " + draftRing.failure
                    + " - the crease ring is unchanged";
            return null;
        }
        ringTool.draftMode = RingSegmentMode.GEODESIC;
        ringTool.draftAuthoredVertexId = draftRing.authoredVertexId;
        ringTool.draft = SurfaceSpline.of(draftRing.tracer);
        ringTool.draftDepth = SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH;
        ringTool.draftMillis = (System.nanoTime() - start) / 1e6;
        return draftRing.refit;
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
        return DRAFT_LABEL;
    }

    @Override
    public Vector3f draftCrossing() {
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

    @Override
    public boolean reopens(ConfirmedRing ring) {
        return !ring.isBisector();
    }

    /**
     * A closed ring draft walls nothing until confirmed.
     *
     * @return false
     */
    @Override
    public boolean wallsWhileDrafted() {
        return false;
    }

    @Override
    public boolean showsRegions() {
        return false;
    }

    @Override
    public String nothingToConfirm() {
        return "no draft to confirm - click a ring to draft one first";
    }

    /**
     * An edited ring stays open and the scene goes on to the orbit tool (CRAW-73).
     *
     * @return false: the scene takes the key
     */
    @Override
    public boolean escapeEditedDraft() {
        ringTool.lastRow = "draft kept with its edits: Ctrl+R returns to it, Enter confirms, "
                + "X discards";
        return false;
    }

    @Override
    public String escapeAfterUneditedDraft() {
        return "";
    }

    @Override
    public String escapeDescription() {
        return "close an unedited ring (an edited one stays open)";
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
            Vector3f at = draftRing.crossings.firstCrossingXyz;
            ringTool.lastError = String.format(Locale.ROOT, "refused: the ring would cross itself "
                    + "near %.4f,%.4f,%.4f - nothing changed; try another place", at.x, at.y, at.z);
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
