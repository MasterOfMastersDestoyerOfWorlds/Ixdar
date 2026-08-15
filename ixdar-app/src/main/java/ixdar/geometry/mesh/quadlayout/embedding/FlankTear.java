package ixdar.geometry.mesh.quadlayout.embedding;

/**
 * The first live arc found lying beside a patch it does not name: the covers along its path
 * disagree with its recorded flanks, so the arrangement and the T-mesh no longer describe the
 * same surface. Captured by {@link EmbeddedTMesh#firstFlankTear} for validation and rendering.
 */
public final class FlankTear {

    /** Arc whose recorded flanks disagree with the covers beside it. */
    public final int arcId;

    /** Index of the first disagreeing hop along the arc's copy-vertex path. */
    public final int hop;

    /** Resolved patch covering the face left of that hop. */
    public final int coverLeftPatchId;

    /** Resolved patch covering the face right of that hop. */
    public final int coverRightPatchId;

    /**
     * Records where an arc's flanks and the covers beside it disagree.
     *
     * @param arcId             arc whose flanks disagree with the covers beside it
     * @param hop               index of the first disagreeing hop along its path
     * @param coverLeftPatchId  resolved patch covering the face left of that hop
     * @param coverRightPatchId resolved patch covering the face right of that hop
     */
    public FlankTear(int arcId, int hop, int coverLeftPatchId, int coverRightPatchId) {
        this.arcId = arcId;
        this.hop = hop;
        this.coverLeftPatchId = coverLeftPatchId;
        this.coverRightPatchId = coverRightPatchId;
    }
}
