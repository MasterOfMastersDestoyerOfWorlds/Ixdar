package ixdar.tools.ring;

import ixdar.tools.Draft;
import ixdar.tools.Tool;

/**
 * One sub-tool of the {@link RingTool}, which owns the draft it opens: the ring tool edits that
 * draft, whatever its kind, only through these operations, each sub-tool tracing its own kind.
 */
public abstract class RingSubTool extends Tool {

    /** The ring tool owning the rings, the draft and the overlay the sub-tool works on. */
    public final RingTool ringTool;

    /**
     * A sub-tool of a ring tool.
     *
     * @param ringTool the ring tool owning it
     */
    protected RingSubTool(RingTool ringTool) {
        this.ringTool = ringTool;
    }

    /**
     * Add an authored anchor to the draft this sub-tool owns, where its kind places one, as one
     * undoable edit; a vertex already held is refused.
     *
     * @param vertexId mesh vertex the anchor sits on
     * @return true when the draft took the anchor; the ring tool's last error says why not
     */
    public abstract boolean addAnchor(int vertexId);

    /**
     * Trace the draft through authored anchors, keeping the old draft and saying why when they
     * decide none, or, with {@code keepSimple}, when they would make a simple draft cross itself.
     *
     * @param authored   the anchors in the draft's order
     * @param keepSimple whether to refuse anchors that make a simple draft cross itself
     * @return true when the draft now runs through {@code authored}
     */
    public abstract boolean retrace(int[] authored, boolean keepSimple);

    /**
     * One step of a drag: the selected anchor moves to a vertex, the draft re-traced; the whole
     * drag is one edit, recorded on release.
     *
     * @param selected index of the selected anchor in the draft's authored anchors
     * @param vertexId mesh vertex it moves to
     * @return true when the draft took the move
     */
    public abstract boolean moveAnchor(int selected, int vertexId);

    /**
     * Delete or Backspace with an anchor selected: remove it from the draft as its kind does.
     *
     * @return true when a point was removed
     */
    public abstract boolean deletePoint();

    /**
     * Whether Enter may confirm the draft as it stands.
     *
     * @return true when it may; the ring tool's last error says why not
     */
    public abstract boolean confirmable();

    /**
     * What the draft is, as the row after each edit names it after the edit.
     *
     * @return the draft's kind and mode
     */
    public abstract String draftDescription();

    /**
     * What the draft's detail line adds for its kind, after its mode and timing.
     *
     * @return the addition, starting with a comma, or empty
     */
    public abstract String draftDetail();

    /**
     * The label the draft is exported under.
     *
     * @return the label
     */
    public abstract String draftLabel();

    /**
     * Where the draft crosses or touches itself.
     *
     * @return packed xyz of the first crossing, or {@code null} while it runs simple
     */
    public abstract float[] draftCrossing();

    /**
     * Whether the draft, or the hover preview with no draft open, is drawn as invalid.
     *
     * @return true to draw it in the invalid colour
     */
    public abstract boolean drawsInvalid();

    /**
     * Vertices the overlay marks as picked ends beside the draft's anchors.
     *
     * @return the vertex ids, empty for none
     */
    public abstract int[] markedVertices();

    /** The draft this sub-tool owned closed: drop what it kept about it. */
    public abstract void draftClosed();

    /**
     * Discard what this sub-tool is drafting, as one undoable edit: the open draft, a re-opened
     * ring staying as it was; the ring tool's last error says why when there is nothing.
     */
    public void discardDraft() {
        ringTool.discardDraft();
    }

    /**
     * What this sub-tool holds toward a draft before one opens, which the tool state reports while
     * no draft is open.
     *
     * @return the waiting points as a draft that is not valid, or {@code null} for none
     */
    public Draft pendingDraft() {
        return null;
    }

    /**
     * Whether this sub-tool owns a confirmed ring of its kind, and so re-opens it as its draft.
     *
     * @param ring a confirmed ring
     * @return true when the ring re-opens in this sub-tool
     */
    public abstract boolean reopens(ConfirmedRing ring);

    /**
     * Whether a new draft of this kind walls the regions while it is still being drafted.
     *
     * @return true when the region colours preview the draft as a wall
     */
    public abstract boolean wallsWhileDrafted();

    /**
     * Whether the region colours show while this sub-tool is active, in any tool's view.
     *
     * @return true to colour the regions
     */
    public abstract boolean showsRegions();

    /**
     * Enter's refusal with no draft open, saying how this sub-tool opens one.
     *
     * @return the refusal
     */
    public abstract String nothingToConfirm();

    /**
     * Esc on an edited draft this sub-tool owns.
     *
     * @return true when the sub-tool took the key; false leaves it to the scene, the ring tool's
     *         last row saying what became of the draft
     */
    public abstract boolean escapeEditedDraft();

    /**
     * Esc after an unedited draft closed: whatever else the key does in this sub-tool.
     *
     * @return what the closing row adds, starting with a separator, or empty
     */
    public abstract String escapeAfterUneditedDraft();
}
