package ixdar.tools.ring;

import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.rings.RingSegmentMode;

/**
 * One ring or bisector the {@link RingTool} confirmed: its traced spline and what a save writes
 * and an undo restores about it. A bisector is a confirmed ring with {@link #bisectorEnds}.
 */
public final class ConfirmedRing {

    /** The traced spline; the tool replaces it on an edit rather than mutating it. */
    public SurfaceSpline spline;

    /** Authored anchors in ring order, what a save writes. */
    public int[] authoredVertexId;

    /** Base normal the ring's plane leans toward, at the precision a save writes. */
    public float[] baseNormal;

    /** How the ring runs between its authored anchors, what a save writes as mode. */
    public RingSegmentMode mode;

    /** Statement id the ring was last saved under, {@code null} until a save writes it. */
    public String statementId;

    /** Whether the ring carries changes the working .dsl does not hold yet. */
    public boolean unsaved;

    /**
     * Graph ring label the ring stands for in the working .dsl, which draws as this ring instead
     * of from the graph's marks, or {@code null} for a ring only this session holds.
     */
    public String sourceLabel;

    /** Whether the ring is deleted: it draws nothing and the next save drops it. */
    public boolean deleted;

    /**
     * Identity of the ring in memory, which bisectors name their end walls by; it outlives
     * edits, saves and the re-adoption of the ring's statement.
     */
    public int serial;

    /** Serials of the walls a bisector's first and last anchor sit on, {@code null} for a ring. */
    public int[] bisectorEnds;

    /**
     * A confirmed ring holding every fact a save and an undo need.
     *
     * @param spline           the traced spline
     * @param authoredVertexId authored anchors in ring order
     * @param baseNormal       base normal at the precision a save writes
     * @param mode             segment mode
     * @param statementId      statement id it was saved under, or {@code null}
     * @param unsaved          whether the working .dsl lacks its current shape
     * @param sourceLabel      graph ring label it stands for, or {@code null}
     * @param serial           identity bisectors name it by
     * @param bisectorEnds     wall serials a bisector ends on, or {@code null} for a ring
     */
    public ConfirmedRing(SurfaceSpline spline, int[] authoredVertexId, float[] baseNormal,
            RingSegmentMode mode, String statementId, boolean unsaved, String sourceLabel, int serial,
            int[] bisectorEnds) {
        this.spline = spline;
        this.authoredVertexId = authoredVertexId;
        this.baseNormal = baseNormal;
        this.mode = mode;
        this.statementId = statementId;
        this.unsaved = unsaved;
        this.sourceLabel = sourceLabel;
        this.serial = serial;
        this.bisectorEnds = bisectorEnds;
    }

    /**
     * A copy an undo capture keeps, so later saves and deletions do not reach it; the spline and
     * arrays are shared, since the tool replaces rather than mutates them.
     *
     * @param other the ring to copy
     */
    public ConfirmedRing(ConfirmedRing other) {
        this(other.spline, other.authoredVertexId, other.baseNormal, other.mode, other.statementId,
                other.unsaved, other.sourceLabel, other.serial, other.bisectorEnds);
        deleted = other.deleted;
    }

    /**
     * Whether this is a bisector rather than a closed ring.
     *
     * @return true when it ends on two walls
     */
    public boolean isBisector() {
        return bisectorEnds != null;
    }
}
