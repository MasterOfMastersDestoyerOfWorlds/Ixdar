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
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Draft;
import ixdar.tools.Item;
import ixdar.tools.Preview;
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

    public static final int CANDIDATE_SPANS = 3;

    public static final float END_REACH_PIXELS = 40f;

    public static final String DELETE_POINT_HINT = "delete bisector point";

    public static final String POINT_DELETED = "bisector point deleted";

    public static final String LEFT_BISECTOR = "left the bisector";

    public static final String LEAVE = "leave the bisector";

    public static final String ESCAPE_HINT = "leave the bisector: an unedited draft closes, an "
            + "edited one is confirmed, or dropped when Enter refuses it";

    public static final String WALLS_NOTHING = "the bisector runs along its wall or does "
            + "not join its two walls; add an anchor to steer it off";

    public static final String DISCARD_ACTION = "discard-draft";

    public static final String DISCARD_HINT = "discard the bisector draft, or its waiting first end";

    public static final String NOTHING_TO_DISCARD = "no bisector draft or waiting first end to "
            + "discard - click near a wall to start one";

    public static final String WAITING_END = "waiting end";

    public static final String WAITING_LABEL = "bisector first end";

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

    /** Serial of the wall the last snap landed on, or -1 when none lay within reach. */
    public int snappedWall = -1;

    private final Vector3f scratchPosition = new Vector3f();
    private int[] wallSerialByVertex = new int[0];
    private int[] wallVertexStamp = new int[0];
    private int wallEpoch;
    private int wallsBuiltRevision = -1;
    private int[] snapVisitStamp = new int[0];
    private int[] snapQueue = new int[0];
    private int snapEpoch;

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
        // Shift+X is listed before X, which also fires with Shift held.
        controls.add(new ControlHint(DISCARD_ACTION, Keys.X, false, true, "shift+X", DISCARD_HINT,
                ringTool.refusalShown(() -> discardDraft())));
        for (int key : new int[] { Keys.X, Keys.DELETE, Keys.BACKSPACE }) {
            controls.add(new ControlHint("delete-bisector-point", key, key == Keys.X ? "X"
                    : key == Keys.DELETE ? "del" : "backspace", DELETE_POINT_HINT,
                    ringTool.refusalShown(() -> deletePoint())));
        }
        // K is the one bisect row: it enters the bisector from a ring and leaves it here, as Esc.
        controls.add(new ControlHint(RingTool.BISECT_ACTION, Keys.K, "K", LEAVE,
                ringTool.refusalShown(() -> ringTool.escapePressed())));
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
        ringTool.previewAuthoredVertexId = snapToWall(hitVertexId, hitPoint,
                END_REACH_PIXELS);
        int startRing = ringTool.ringOfSerial(startWall);
        int endRing = ringTool.ringOfSerial(snappedWall);
        int target = ringTool.previewAuthoredVertexId >= 0 ? ringTool.previewAuthoredVertexId
                : hitVertexId;
        MeshTopology surface = ringTool.geodesics.mesh;
        boolean crease = ringTool.newDraftMode() == RingSegmentMode.CREASE
                && ringTool.readyCreases(surface);
        ringTool.previewValid = startVertexId >= 0 && startRing >= 0 && target != startVertexId;
        ringTool.previewSpline = !ringTool.previewValid ? null
                : SurfaceSpline.bisector(ringTool.geodesics, crease ? ringTool.creases : null,
                        new int[] { startVertexId, target },
                        ringTool.confirmedRings.get(startRing).spline.marks.markedByEdgeId,
                        endRing < 0 ? new boolean[0]
                                : ringTool.confirmedRings.get(endRing).spline.marks.markedByEdgeId);
        previewSimple = ringTool.previewValid && endRing >= 0
                && ringTool.previewSpline.marks.markedEdgeCount > 0
                && crossings.isSimple(surface, ringTool.previewSpline.path);
        ringTool.previewedFace = faceId;
        System.arraycopy(hitPoint, 0, ringTool.previewedHit, 0, RingTool.COORDINATES_PER_POINT);
        ringTool.previewMillis = (System.nanoTime() - start) / 1e6;
    }

    /**
     * The wall a click would put an end on now: the ring or bisector the cursor's snap reached.
     *
     * @return that wall's ring item, or {@code null} with a draft open or no wall within reach
     */
    @Override
    public Item hovered() {
        int ring = ringTool.ringOfSerial(snappedWall);
        return ringTool.draft != null || ringTool.scene.cursorVertexId < 0 || ring < 0 ? null
                : ringTool.ringItem(ring);
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
        int snapped = snapToWall(vertexId, scene.cursorPoint,
                END_REACH_PIXELS);
        // A refused click leaves a waiting first end in place, so the refusal names it.
        int waitingRing = ringTool.ringOfSerial(startWall);
        String waiting = startVertexId < 0 || waitingRing < 0 ? "" : "; the first end still waits "
                + "on " + ringTool.liveLabel(waitingRing) + " - Shift+X drops it";
        if (snapped < 0) {
            ringTool.lastError = String.format(Locale.ROOT, "refused: no ring or bisector within "
                    + "%.0f px; a bisector's ends sit on walls, so click on or beside one%s",
                    END_REACH_PIXELS, waiting);
            return false;
        }
        if (snapped == startVertexId) {
            ringTool.lastError = "refused: the second end lands on the first; click another wall "
                    + "vertex" + waiting;
            return false;
        }
        RingToolState stateBefore = new RingToolState(ringTool);
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
     * Take a steering anchor in the span, of the {@link #CANDIDATE_SPANS} it detours least from,
     * where the traced bisector comes out shortest without crossing itself, never outside its
     * ends; click order does not decide the place.
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
        // Each trial traces the whole bisector, so only the spans the anchor adds least chord
        // length to are traced, nearest first, and the rest only while none of those takes it.
        float[] heldXyz = ringTool.positionsOf(held);
        float[] at = ringTool.positionsOf(new int[] { vertexId });
        double[] detour = new double[held.length - 1];
        for (int span = 0; span < detour.length; span++) {
            int from = RingTool.COORDINATES_PER_POINT * span;
            int to = from + RingTool.COORDINATES_PER_POINT;
            detour[span] = Vector3f.distance(heldXyz[from], heldXyz[from + 1], heldXyz[from + 2],
                    at[0], at[1], at[2])
                    + Vector3f.distance(at[0], at[1], at[2], heldXyz[to], heldXyz[to + 1],
                            heldXyz[to + 2])
                    - Vector3f.distance(heldXyz[from], heldXyz[from + 1], heldXyz[from + 2],
                            heldXyz[to], heldXyz[to + 1], heldXyz[to + 2]);
        }
        boolean[] tried = new boolean[detour.length];
        for (int attempt = 0; attempt < detour.length
                && (attempt < CANDIDATE_SPANS || shortest == null); attempt++) {
            int before = -1;
            for (int span = 0; span < detour.length; span++) {
                before = !tried[span] && (before < 0 || detour[span] < detour[before]) ? span
                        : before;
            }
            tried[before] = true;
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
     * The wall vertex nearest a surface point within a reach on screen, found by a walk out from
     * the vertex under it; {@link #snappedWall} names its wall. Walls are the live confirmed rings
     * and bisectors, the draft's own original aside.
     *
     * @param vertexId    mesh vertex under the point
     * @param xyz         the point, packed xyz
     * @param reachPixels how far from the point a wall may lie, in framebuffer pixels:
     *                    {@link #END_REACH_PIXELS} for a click
     * @return the wall vertex, or -1 when no wall lies within reach
     */
    public int snapToWall(int vertexId, float[] xyz, float reachPixels) {
        MeshTopology surface = ringTool.geodesics.mesh;
        int vertexIdBound = ringTool.geodesics.metric.vertexIdBound;
        if (wallVertexStamp.length != vertexIdBound) {
            wallVertexStamp = new int[vertexIdBound];
            wallSerialByVertex = new int[vertexIdBound];
            snapVisitStamp = new int[vertexIdBound];
            snapQueue = new int[vertexIdBound];
            wallsBuiltRevision = -1;
        }
        if (wallsBuiltRevision != ringTool.ringRevision) {
            wallsBuiltRevision = ringTool.ringRevision;
            wallEpoch++;
            for (int ring = 0; ring < ringTool.confirmedRings.size(); ring++) {
                ConfirmedRing wall = ringTool.confirmedRings.get(ring);
                if (wall.deleted || ring == ringTool.draftSourceRing && ringTool.draft != null) {
                    continue;
                }
                for (int edgeId : wall.spline.marks.markedEdgeIds) {
                    int halfEdge = surface.edgeHalfEdge(edgeId);
                    for (int end : new int[] { surface.halfEdgeVertex(halfEdge),
                        surface.halfEdgeEndVertex(halfEdge) }) {
                        if (wallVertexStamp[end] != wallEpoch) {
                            wallVertexStamp[end] = wallEpoch;
                            wallSerialByVertex[end] = wall.serial;
                        }
                    }
                }
            }
        }
        snappedWall = -1;
        if (vertexId < 0 || vertexId >= vertexIdBound) {
            return -1;
        }
        double reach = reachPixels * ringTool.scene.worldPerPixel();
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
            marks[end] = ring < 0 || ringTool.confirmedRings.get(ring).deleted ? null
                    : ringTool.confirmedRings.get(ring).spline.marks.markedByEdgeId;
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
     * X, Delete or Backspace: delete the selected point, else the last inner anchor; a deleted end
     * hands its place to the anchor next to it when that lies on a wall.
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
        if (end && held.length == 2 && ringTool.draftSourceRing >= 0) {
            // A confirmed bisector down to its two ends goes whole, as Delete takes a ring.
            ringTool.deleteDraftRing();
            return ringTool.lastError.isEmpty();
        }
        if (end && held.length == 2) {
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
        // An end goes only when the inner anchor next to it lies on a wall, its selected disc
        // touching one, and takes its place; else the refusal names the anchors in the way of
        // the nearest one on a wall.
        int inward = doomed == 0 ? 1 : -1;
        int neighbour = doomed + inward;
        if (end) {
            int onWall = -1;
            for (int anchor = neighbour; onWall < 0 && anchor > 0 && anchor < held.length - 1;
                    anchor += inward) {
                onWall = snapToWall(held[anchor],
                        ringTool.positionsOf(new int[] { held[anchor] }),
                        RingTool.SELECTED_ANCHOR_PIXELS) >= 0 ? anchor : -1;
            }
            if (onWall != neighbour) {
                int blocking = onWall >= 0 ? onWall - inward : doomed == 0 ? held.length - 2 : 1;
                boolean one = blocking == neighbour;
                String them = one ? "it" : "them";
                String taker = "the other end, and none lies on a wall to take its place; drag "
                        + "one onto a wall, or delete " + them;
                if (onWall >= 0) {
                    snapToWall(held[onWall], ringTool.positionsOf(new int[] { held[onWall] }),
                            RingTool.SELECTED_ANCHOR_PIXELS);
                    taker = "anchor " + onWall + " on "
                            + ringTool.liveLabel(ringTool.ringOfSerial(snappedWall))
                            + ", which would take its place; delete " + them + " first";
                }
                ringTool.lastError = "refused: " + (one ? "anchor " + neighbour + " lies"
                        : "anchors " + Math.min(neighbour, blocking) + " to "
                                + Math.max(neighbour, blocking) + " lie")
                        + " (of " + held.length + ", counted from 0) between this end and "
                        + taker;
                return false;
            }
        }
        int[] kept = new int[held.length - 1];
        System.arraycopy(held, 0, kept, 0, doomed);
        System.arraycopy(held, doomed + 1, kept, doomed, kept.length - doomed);
        int[] endsBefore = draftEnds;
        int promoted = doomed == 0 ? 0 : kept.length - 1;
        if (end) {
            kept[promoted] = snapToWall(kept[promoted],
                    ringTool.positionsOf(new int[] { kept[promoted] }),
                    RingTool.SELECTED_ANCHOR_PIXELS);
            draftEnds = Arrays.copyOf(endsBefore, 2);
            draftEnds[doomed == 0 ? 0 : 1] = snappedWall;
        }
        if (!retrace(kept, false)) {
            draftEnds = endsBefore;
            return false;
        }
        ringTool.recordEdit(POINT_DELETED, stateBefore);
        ringTool.selectedAnchorVertexId = end ? ringTool.draftAuthoredVertexId[promoted] : -1;
        ringTool.reportDraft(!end ? POINT_DELETED : "bisector end deleted, anchor " + neighbour
                + " on " + ringTool.liveLabel(ringTool.ringOfSerial(draftEnds[doomed == 0 ? 0 : 1]))
                + " is the end now");
        return true;
    }

    /**
     * Shift+X: drop the bisector draft, a re-opened bisector staying as it was, or with none open
     * the waiting first end; either is one undoable edit.
     */
    @Override
    public void discardDraft() {
        if (ringTool.draft != null) {
            ringTool.discardDraft();
        } else if (startVertexId >= 0) {
            deletePoint();
        } else {
            ringTool.lastError = NOTHING_TO_DISCARD;
        }
    }

    /**
     * The waiting first end, which the next click near a wall pairs with a second.
     *
     * @return the end as a one-point draft that is not valid, or {@code null} while none waits
     */
    @Override
    public Draft pendingDraft() {
        if (startVertexId < 0) {
            return null;
        }
        Draft waiting = new Draft();
        waiting.points = ringTool.positionsOf(new int[] { startVertexId });
        System.arraycopy(waiting.points, 0, waiting.location, 0, RingTool.COORDINATES_PER_POINT);
        waiting.pointLabels = new String[] { WAITING_END };
        waiting.size = 1;
        waiting.label = WAITING_LABEL;
        int wall = ringTool.ringOfSerial(startWall);
        waiting.detail = "on " + (wall < 0 ? "a wall that is gone" : ringTool.liveLabel(wall))
                + "; the next click near a wall puts the second end, Shift+X drops this one";
        return waiting;
    }

    /**
     * Where the cursor hit and the end a click would place there: on which wall, or why none.
     *
     * @return the preview, valid when a click would place an end, or the second end of a bisector
     *         that runs simple
     */
    @Override
    public Preview preview() {
        RingScene scene = ringTool.scene;
        int wall = ringTool.ringOfSerial(snappedWall);
        String detail = ringTool.draft != null ? "a bisector draft is open: a click steers it"
                : scene.cursorVertexId < 0 ? scene.cursorMissReason
                : ringTool.previewAuthoredVertexId < 0 || wall < 0
                        ? String.format(Locale.ROOT, "no ring or bisector within %.0f px",
                                END_REACH_PIXELS)
                : (startVertexId < 0 ? "a click puts the first end on " : "a click puts the second "
                        + "end on ") + ringTool.liveLabel(wall) + (startVertexId < 0 || previewSimple
                                ? "" : ", but that bisector crosses itself or walls nothing");
        boolean valid = ringTool.draft == null && scene.cursorVertexId >= 0
                && ringTool.previewAuthoredVertexId >= 0
                && (startVertexId < 0 || ringTool.previewValid && previewSimple);
        return new Preview(ringTool.previewHitPoint, valid, ringTool.previewMillis, detail);
    }

    /**
     * Esc or K: leave the bisector for the ring tool's rings. An edited draft is confirmed when
     * Enter would take it, else dropped; either is one undoable edit, as is dropping a waiting end.
     *
     * @return true: the bisector always takes the key
     */
    @Override
    public boolean escapePressed() {
        String left = LEFT_BISECTOR;
        if (ringTool.draft != null && ringTool.confirmDraft()) {
            left += ", the bisector confirmed";
        } else if (ringTool.draft != null) {
            left += ", the bisector dropped since Enter refuses it (" + ringTool.lastError
                    + "); Ctrl+Z brings it back";
            ringTool.discardDraft();
        }
        ringTool.lastError = "";
        RingToolState stateBefore = new RingToolState(ringTool);
        startVertexId = -1;
        startWall = -1;
        ringTool.switchSubTool(ringTool.closedRing);
        ringTool.recordEdit("bisector left", stateBefore);
        ringTool.lastRow = left;
        Platforms.get().log(RingTool.LOG_PREFIX + ringTool.lastRow);
        return true;
    }

    @Override
    public String escapeDescription() {
        return ESCAPE_HINT;
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
            moved[selected] = snapToWall(vertexId,
                    ringTool.positionsOf(new int[] { vertexId }), END_REACH_PIXELS);
            if (moved[selected] < 0) {
                ringTool.lastError = "refused: a bisector's end stays on a wall; no wall within "
                        + "reach";
                return false;
            }
            draftEnds = Arrays.copyOf(endsBefore, 2);
            draftEnds[selected == 0 ? 0 : 1] = snappedWall;
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
     * @return true to draw it in the palette's invalid draft colour
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

    @Override
    public boolean reopens(ConfirmedRing ring) {
        return ring.isBisector();
    }

    /**
     * A new bisector walls the regions it would split while it runs simple, as their colours
     * preview it.
     *
     * @return true unless it draws as invalid
     */
    @Override
    public boolean wallsWhileDrafted() {
        return !drawsInvalid();
    }

    @Override
    public boolean showsRegions() {
        return true;
    }

    @Override
    public String nothingToConfirm() {
        return "no bisector to confirm - click near a wall for each end";
    }

    /**
     * Esc on an edited bisector leaves it, confirmed or dropped as {@link #escapePressed} says.
     *
     * @return true
     */
    @Override
    public boolean escapeEditedDraft() {
        return escapePressed();
    }

    /**
     * A new bisector's waiting first end goes too, so Esc always leaves the bisector.
     *
     * @return the leaving note for the closing row
     */
    @Override
    public String escapeAfterUneditedDraft() {
        escapePressed();
        return "; " + LEFT_BISECTOR;
    }
}
