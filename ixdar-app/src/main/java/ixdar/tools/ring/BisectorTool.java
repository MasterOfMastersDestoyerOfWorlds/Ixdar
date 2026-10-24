package ixdar.tools.ring;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.paths.query.SurfacePathCrossings;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingAttachment;
import ixdar.geometry.mesh.data.rings.RingSegmentMode;
import ixdar.graphics.render.color.Color;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

/**
 * The ring tool's bisector sub-tool: open splines across the surface whose ends snap onto rings
 * or other bisectors, in the ring tool's geodesic or crease mode. The ring tool holds the rings,
 * the draft, the walls and the overlay it works on; this holds only the bisector's own ends.
 */
public final class BisectorTool extends RingSubTool {

    public static final String TOOL_NAME = "bisector";

    public static final String BISECTOR_HINT = "bisecting: a spline through your anchors, traced "
            + "like a ring but open; each end snaps to the nearest wall vertex, and it may cross walls, "
            + "splitting every region it crosses";

    public static final String CLICK_HINT = "pick an end near a ring or bisector, then add "
            + "anchors that steer it";

    public static final String DRAFT_LABEL = "bisector draft";

    public static final Color CROSSING_COLOR = Color.BLACK;

    public static final String DELETE_POINT_HINT = "delete bisector point";

    public static final String POINT_DELETED = "bisector point deleted";

    public static final String LEFT_BISECTOR = "left the bisector";

    public static final String LEAVE = "leave the bisector";

    public static final String WALLS_NOTHING = "the bisector runs along its wall or does "
            + "not join its two walls; add an anchor to steer it off";

    /** Serials of the walls the draft's ends sit on when it is a bisector, else {@code null}. */
    public int[] draftEnds;

    /** Whether the bisector draft runs without crossing itself; Enter refuses it otherwise. */
    public boolean draftSimple = true;

    /** Wall vertex the first end was clicked onto, waiting for its second, or -1. */
    public int startVertexId = -1;

    /** Serial of the wall the waiting first end sits on, or -1. */
    public int startWall = -1;

    /** Whether the hover preview runs without crossing itself onto a wall. */
    public boolean previewSimple;

    /** Where the last bisector traced crosses or touches itself, when it does. */
    public final SurfacePathCrossings crossings = new SurfacePathCrossings();

    private final Vector3f scratchPosition = new Vector3f();

    /**
     * The bisector of a ring tool, which hands it the clicks and the hover while it is the active
     * sub-tool.
     *
     * @param ringTool the ring tool owning it
     */
    public BisectorTool(RingTool ringTool) {
        super(ringTool);
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    @Override
    public String desc() {
        return "Bisect between rings: an open spline whose ends snap onto rings or bisectors";
    }

    /** K: start bisecting, the region colours shown. */
    @Override
    public void activate() {
        ringTool.lastError = "";
        ringTool.lastRow = BISECTOR_HINT;
        ringTool.previewedFace = -1;
        ringTool.invalidateRings();
        ringTool.scene.refreshControls();
        Platforms.get().log(RingTool.LOG_PREFIX + ringTool.lastRow);
    }

    /** Stop previewing ends; a waiting first end and an open bisector draft stay for later. */
    @Override
    public void deactivate() {
        ringTool.previewValid = false;
        ringTool.previewSpline = null;
        ringTool.previewAuthoredVertexId = -1;
        ringTool.previewedFace = -1;
        ringTool.invalidateRings();
        ringTool.scene.refreshControls();
    }

    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint(ControlHint.NO_KEY, "click", CLICK_HINT));
        for (int key : new int[] { Keys.X, Keys.DELETE, Keys.BACKSPACE }) {
            controls.add(new ControlHint("delete-bisector-point", key, key == Keys.X ? "X"
                    : key == Keys.DELETE ? "del" : "backspace", DELETE_POINT_HINT,
                    ringTool.refusalShown(() -> deletePoint())));
        }
        // K is the one bisect row: it enters the bisector from a ring and leaves it here.
        controls.add(new ControlHint(RingTool.BISECT_ACTION, Keys.K, "K", LEAVE, ringTool.refusalShown(() -> {
            if (ringTool.draft != null) {
                ringTool.lastError = "confirm the open bisector with Enter, or delete its points with "
                        + "X, before leaving the bisector";
            } else {
                escapePressed();
            }
        })));
    }

    /**
     * One frame with no draft open and the surface under the cursor: the wall vertex a click puts
     * an end on and, with a first end waiting, the bisector it would trace there, invalid off a
     * wall or when it crosses itself.
     */
    @Override
    public void perFrame() {
        RingScene scene = ringTool.scene;
        float[] hitPoint = scene.cursorPoint;
        int faceId = scene.cursorFaceId;
        if (ringTool.draft != null || scene.cursorVertexId < 0 || faceId == ringTool.previewedFace && Arrays.equals(hitPoint, ringTool.previewedHit)) {
            return;
        }
        long start = System.nanoTime();
        int hitVertexId = scene.cursorVertexId;
        ringTool.previewAuthoredVertexId = ringTool.snapToWall(hitVertexId, hitPoint);
        int startRing = ringTool.ringOfSerial(startWall);
        int endRing = ringTool.ringOfSerial(ringTool.snappedWall);
        int target = ringTool.previewAuthoredVertexId >= 0 ? ringTool.previewAuthoredVertexId
                : hitVertexId;
        MeshTopology surface = ringTool.geodesics.mesh;
        boolean crease = ringTool.newDraftMode() == RingSegmentMode.CREASE
                && ringTool.readyCreases(surface);
        ringTool.previewValid = startVertexId >= 0 && startRing >= 0 && target != startVertexId;
        ringTool.previewSpline = !ringTool.previewValid ? null
                : SurfaceSpline.bisector(ringTool.geodesics, crease ? ringTool.creases : null,
                        new int[] { startVertexId, target },
                        ringTool.confirmedRings.get(startRing).marks.markedByEdgeId,
                        endRing < 0 ? new boolean[0]
                                : ringTool.confirmedRings.get(endRing).marks.markedByEdgeId);
        previewSimple = ringTool.previewValid && endRing >= 0
                && ringTool.previewSpline.marks.markedEdgeCount > 0
                && crossings.isSimple(surface, ringTool.previewSpline.path);
        ringTool.previewedFace = faceId;
        System.arraycopy(hitPoint, 0, ringTool.previewedHit, 0, RingTool.COORDINATES_PER_POINT);
        ringTool.previewMillis = (System.nanoTime() - start) / 1e6;
    }

    /**
     * A click the ring tool did not take to pick an anchor: with no draft it puts an end on the
     * nearest wall vertex, the second opening the bisector as the draft; on a draft it steers it.
     *
     * @param x framebuffer x of the click, unused: the ring tool's pick places it
     * @param y framebuffer y of the click, unused
     * @return true when an end or an anchor was placed
     */
    @Override
    public boolean onClick(float x, float y) {
        RingScene scene = ringTool.scene;
        int vertexId = scene.cursorVertexId;
        if (ringTool.draft != null) {
            return addAnchor(vertexId);
        }
        int snapped = ringTool.snapToWall(vertexId, scene.cursorPoint);
        if (snapped < 0) {
            ringTool.lastError = String.format(Locale.ROOT, "refused: no ring or bisector within "
                    + "%.0f px; a bisector's ends sit on walls, so click on or beside one",
                    RingTool.BISECTOR_REACH_PIXELS);
            return false;
        }
        if (snapped == startVertexId) {
            ringTool.lastError = "refused: the second end lands on the first; click another wall "
                    + "vertex";
            return false;
        }
        RingToolState stateBefore = new RingToolState(ringTool);
        int snappedWall = ringTool.snappedWall;
        String wall = ringTool.liveLabel(ringTool.ringOfSerial(snappedWall));
        if (startVertexId < 0) {
            startVertexId = snapped;
            startWall = snappedWall;
            ringTool.previewedFace = -1;
            ringTool.overlayStale = true;
            ringTool.recordEdit("bisector end placed", stateBefore);
            ringTool.lastRow = "bisector: first end on " + wall
                    + "; click near a wall for the second end";
            Platforms.get().log(RingTool.LOG_PREFIX + ringTool.lastRow);
            return true;
        }
        // The lower vertex leads, so which end was clicked first does not change the trace.
        boolean startLeads = startVertexId < snapped;
        draftEnds = startLeads ? new int[] { startWall, snappedWall }
                : new int[] { snappedWall, startWall };
        ringTool.draftMode = ringTool.newDraftMode();
        ringTool.draftSourceRing = -1;
        ringTool.draftSourceLabel = null;
        if (!retrace(startLeads ? new int[] { startVertexId, snapped }
                : new int[] { snapped, startVertexId }, false)) {
            draftEnds = null;
            return false;
        }
        startVertexId = -1;
        startWall = -1;
        ringTool.draftOwner = this;
        ringTool.selectedAnchorVertexId = snapped;
        ringTool.recordEdit("bisector drafted", stateBefore);
        ringTool.reportDraft("bisector drafted, second end on " + wall);
        scene.refreshControls();
        return true;
    }

    /**
     * Take a steering anchor in the span where the traced bisector comes out shortest without
     * crossing itself, never before its first end or after its last; click order does not decide
     * the place.
     *
     * @param vertexId mesh vertex the anchor sits on
     * @return true when the bisector took the anchor
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
        long start = System.nanoTime();
        int[] held = ringTool.draftAuthoredVertexId;
        int[] kept = null;
        SurfaceSpline shortest = null;
        for (int before = 0; before + 1 < held.length; before++) {
            int[] grown = new int[held.length + 1];
            System.arraycopy(held, 0, grown, 0, before + 1);
            grown[before + 1] = vertexId;
            System.arraycopy(held, before + 1, grown, before + 2, held.length - before - 1);
            SurfaceSpline traced = traced(grown, draftEnds, ringTool.draftMode);
            if (traced != null && traced.marks.markedEdgeCount > 0
                    && (shortest == null || traced.path.length < shortest.path.length)
                    && crossings.isSimple(ringTool.geodesics.mesh, traced.path)) {
                shortest = traced;
                kept = grown;
            }
        }
        if (shortest == null) {
            ringTool.lastError = "anchor refused: in every span the bisector would cross itself or "
                    + "lose its walls - click another place";
            return false;
        }
        ringTool.draft = shortest;
        ringTool.draftAuthoredVertexId = kept;
        draftSimple = true;
        ringTool.draftDepth = SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH;
        ringTool.draftMillis = (System.nanoTime() - start) / 1e6;
        ringTool.selectedAnchorVertexId = vertexId;
        ringTool.invalidateRings();
        ringTool.recordEdit("anchor added", stateBefore);
        ringTool.reportDraft("authored anchor added");
        return true;
    }

    /**
     * Trace the bisector draft whole at full depth through anchors, its ends kept on their walls;
     * one that crosses itself is drawn as invalid, and Enter refuses it.
     *
     * @param authored   anchor vertices end to end
     * @param keepSimple whether to refuse anchors that would make a simple bisector cross itself
     * @return true when the draft now runs through {@code authored}
     */
    @Override
    public boolean retrace(int[] authored, boolean keepSimple) {
        long start = System.nanoTime();
        int[] anchors = authored.clone();
        SurfaceSpline bisector = traced(anchors, draftEnds, ringTool.draftMode);
        if (bisector == null) {
            return false;
        }
        boolean simple = crossings.isSimple(ringTool.geodesics.mesh, bisector.path);
        if (keepSimple && draftSimple && ringTool.draft != null && !simple) {
            float[] at = crossings.firstCrossingXyz;
            ringTool.lastError = String.format(Locale.ROOT, "refused: the bisector would cross "
                    + "itself near %.4f,%.4f,%.4f - nothing changed; try another place", at[0],
                    at[1], at[2]);
            return false;
        }
        ringTool.draftAuthoredVertexId = anchors;
        ringTool.draft = bisector;
        draftSimple = simple;
        ringTool.draftDepth = SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH;
        ringTool.draftMillis = (System.nanoTime() - start) / 1e6;
        ringTool.invalidateRings();
        return true;
    }

    /**
     * A bisector through anchors, each end first moved onto the nearest vertex of its wall as the
     * wall stands now unless already on it, so a bisector follows an edited wall.
     *
     * @param anchors anchor vertices end to end; the two ends are re-attached in place
     * @param ends    serials of the walls the first and the last anchor sit on
     * @param mode    how it runs between its anchors
     * @return the bisector, with no marked edge when it walls nothing ({@link #WALLS_NOTHING}), or
     *         {@code null} with the ring tool's last error saying which wall is gone
     */
    public SurfaceSpline traced(int[] anchors, int[] ends, RingSegmentMode mode) {
        MeshTopology surface = ringTool.geodesics.mesh;
        if (mode == RingSegmentMode.CREASE && !ringTool.readyCreases(surface)) {
            return null;
        }
        boolean[][] marks = new boolean[2][];
        for (int end = 0; end < 2; end++) {
            int ring = ringTool.ringOfSerial(ends[end]);
            int anchor = end == 0 ? 0 : anchors.length - 1;
            marks[end] = ring < 0 || ringTool.confirmedRingDeleted.get(ring) ? null
                    : ringTool.confirmedRings.get(ring).marks.markedByEdgeId;
            boolean onWall = false;
            for (int spoke = 0; marks[end] != null
                    && spoke < surface.vertexEdgeCount(anchors[anchor]); spoke++) {
                int edgeId = surface.vertexEdgeAt(anchors[anchor], spoke);
                onWall |= edgeId < marks[end].length && marks[end][edgeId];
            }
            if (marks[end] != null && !onWall) {
                surface.vertexPosition(anchors[anchor], scratchPosition);
                anchors[anchor] = RingAttachment.attach(surface, marks[end], scratchPosition.x,
                        scratchPosition.y, scratchPosition.z);
            }
            if (marks[end] == null || anchors[anchor] < 0) {
                ringTool.lastError = "refused: the wall the bisector's " + (end == 0 ? "first"
                        : "last") + " end sat on is gone";
                return null;
            }
        }
        return SurfaceSpline.bisector(ringTool.geodesics,
                mode == RingSegmentMode.CREASE ? ringTool.creases : null, anchors, marks[0],
                marks[1]);
    }

    /**
     * X, Delete or Backspace: delete the selected point, else the last in path order, an inner
     * anchor before the second end; deleting an end leaves the other waiting, or deletes a
     * confirmed bisector re-opened as the draft.
     *
     * @return true when a point was deleted
     */
    @Override
    public boolean deletePoint() {
        ringTool.lastError = "";
        RingToolState stateBefore = new RingToolState(ringTool);
        if (ringTool.draft == null || draftEnds == null) {
            if (startVertexId < 0) {
                ringTool.lastError = "no bisector point to delete; click near a wall to start one, "
                        + "Esc leaves the bisector";
                return false;
            }
            startVertexId = -1;
            startWall = -1;
            ringTool.previewValid = false;
            ringTool.previewSpline = null;
            ringTool.previewedFace = -1;
            ringTool.overlayStale = true;
            ringTool.recordEdit(POINT_DELETED, stateBefore);
            ringTool.lastRow = "deleted the bisector's first end; click near a wall to start again";
            return true;
        }
        int[] held = ringTool.draftAuthoredVertexId;
        int selected = ringTool.selectedIndex();
        int doomed = selected >= 0 ? selected : held.length > 2 ? held.length - 2 : held.length - 1;
        boolean end = doomed == 0 || doomed == held.length - 1;
        if (end && held.length > 2) {
            ringTool.lastError = "refused: inner anchors hold the bisector between its ends; delete "
                    + "them first, or drag the end along a wall";
            return false;
        }
        if (end && ringTool.draftSourceRing >= 0) {
            // A confirmed bisector down to its two ends goes whole, as Delete takes a ring.
            ringTool.deleteDraftRing();
            return ringTool.lastError.isEmpty();
        }
        if (end) {
            int other = doomed == 0 ? 1 : 0;
            startVertexId = held[other];
            startWall = draftEnds[other];
            ringTool.clearDraft();
            ringTool.previewedFace = -1;
            ringTool.invalidateRings();
            ringTool.recordEdit(POINT_DELETED, stateBefore);
            ringTool.lastRow = "deleted a bisector end; the other waits for a new second end";
            Platforms.get().log(RingTool.LOG_PREFIX + ringTool.lastRow);
            return true;
        }
        int[] kept = new int[held.length - 1];
        System.arraycopy(held, 0, kept, 0, doomed);
        System.arraycopy(held, doomed + 1, kept, doomed, kept.length - doomed);
        if (!retrace(kept, false)) {
            return false;
        }
        ringTool.recordEdit(POINT_DELETED, stateBefore);
        ringTool.selectedAnchorVertexId = -1;
        ringTool.reportDraft(POINT_DELETED);
        return true;
    }

    /**
     * Esc or K with no draft open: leave the bisector for the ring tool's rings, a first end still
     * waiting dropped as one edit.
     *
     * @return true: the bisector always takes the key
     */
    @Override
    public boolean escapePressed() {
        RingToolState stateBefore = new RingToolState(ringTool);
        startVertexId = -1;
        startWall = -1;
        ringTool.switchSubTool(ringTool.closedRing);
        ringTool.recordEdit("bisector left", stateBefore);
        ringTool.lastRow = LEFT_BISECTOR;
        Platforms.get().log(RingTool.LOG_PREFIX + ringTool.lastRow);
        return true;
    }

    @Override
    public String escapeDescription() {
        return LEAVE;
    }

    /**
     * One step of a drag: an inner anchor moves to the vertex, an end onto the wall vertex nearest
     * it on any wall, the bisector re-traced whole.
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
        int[] endsBefore = draftEnds;
        boolean end = selected == 0 || selected == moved.length - 1;
        if (end) {
            moved[selected] = ringTool.snapToWall(vertexId,
                    ringTool.positionsOf(new int[] { vertexId }));
            if (moved[selected] < 0) {
                ringTool.lastError = "refused: a bisector's end stays on a wall; no wall within "
                        + "reach";
                return false;
            }
            draftEnds = Arrays.copyOf(endsBefore, 2);
            draftEnds[selected == 0 ? 0 : 1] = ringTool.snappedWall;
        }
        if (!retrace(moved, true)) {
            draftEnds = endsBefore;
            return false;
        }
        ringTool.selectedAnchorVertexId = end ? ringTool.draftAuthoredVertexId[selected] : vertexId;
        return true;
    }

    /**
     * Enter refuses a bisector that crosses itself or walls nothing.
     *
     * @return true when the bisector may be confirmed
     */
    @Override
    public boolean confirmable() {
        if (!draftSimple) {
            float[] crossing = crossings.firstCrossingXyz;
            ringTool.lastError = String.format(Locale.ROOT, "refused: the bisector crosses itself "
                    + "near %.4f,%.4f,%.4f; move or delete an anchor first", crossing[0],
                    crossing[1], crossing[2]);
            return false;
        }
        if (ringTool.draft.marks.markedEdgeCount == 0) {
            ringTool.lastError = "refused: " + WALLS_NOTHING;
            return false;
        }
        return true;
    }

    @Override
    public String draftDescription() {
        return "bisector, the open " + (ringTool.draftMode == RingSegmentMode.CREASE ? "crease path"
                : "geodesic spline") + " through its anchors from "
                + ringTool.liveLabel(ringTool.ringOfSerial(draftEnds[0])) + " to "
                + ringTool.liveLabel(ringTool.ringOfSerial(draftEnds[1]));
    }

    @Override
    public String draftDetail() {
        int firstWall = ringTool.ringOfSerial(draftEnds[0]);
        int lastWall = ringTool.ringOfSerial(draftEnds[1]);
        return firstWall < 0 || lastWall < 0 ? "" : ", bisector from "
                + ringTool.liveLabel(firstWall) + " to " + ringTool.liveLabel(lastWall);
    }

    @Override
    public String draftLabel() {
        return DRAFT_LABEL;
    }

    @Override
    public float[] draftCrossing() {
        return draftSimple ? null : crossings.firstCrossingXyz;
    }

    /**
     * A bisector draws as invalid while it crosses itself or walls nothing, and its preview while
     * it crosses itself or ends off a wall.
     *
     * @return true to draw it in {@link #CROSSING_COLOR}
     */
    @Override
    public boolean drawsInvalid() {
        return ringTool.draft != null ? !draftSimple || ringTool.draft.marks.markedEdgeCount == 0
                : !previewSimple;
    }

    /**
     * With no draft open, the waiting first end and the wall vertex a click would put an end on.
     *
     * @return those vertices that are placed
     */
    @Override
    public int[] markedVertices() {
        return ringTool.draft != null ? new int[0]
                : Arrays.stream(new int[] { startVertexId, ringTool.previewAuthoredVertexId })
                        .filter(vertexId -> vertexId >= 0).toArray();
    }

    @Override
    public void draftClosed() {
        draftEnds = null;
        draftSimple = true;
    }
}
