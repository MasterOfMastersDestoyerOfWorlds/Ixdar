package ixdar.geometry.mesh.quadlayout.quantization;

/**
 * One positive-length piece of a {@link LayoutRectangle} side. Most segments
 * cover a whole positively quantized T-mesh arc; after a rectangle split the
 * cut arc's two pieces keep the arc id with narrowed intrinsic bounds, and the
 * freshly inserted patch-crossing edge appears as a synthetic segment with no
 * backing arc.
 */
public final class LayoutSideSegment {

    /** Backing T-mesh arc id, or -1 for an inserted (synthetic) edge. */
    public final int arcId;

    /**
     * Quantized start of the covered piece in the arc's own coordinates
     * (0 at {@code startNodeId}); 0 for synthetic segments.
     */
    public final int arcStart;

    /** Quantized end of the covered piece in the arc's own coordinates. */
    public final int arcEnd;

    /**
     * True when the side's canonical direction traverses the arc from its
     * start node toward its end node.
     */
    public final boolean forward;

    /**
     * Creates one side segment.
     *
     * @param arcId    backing arc id, or -1 for an inserted edge
     * @param arcStart quantized piece start in arc coordinates
     * @param arcEnd   quantized piece end in arc coordinates
     * @param forward  whether the side direction follows the arc direction
     */
    public LayoutSideSegment(int arcId, int arcStart, int arcEnd, boolean forward) {
        this.arcId = arcId;
        this.arcStart = arcStart;
        this.arcEnd = arcEnd;
        this.forward = forward;
    }

    /**
     * Quantized length of this piece.
     *
     * @return {@code arcEnd - arcStart}
     */
    public int quantizedLength() {
        return arcEnd - arcStart;
    }
}
