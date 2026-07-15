package ixdar.geometry.mesh.quadlayout.embedding;

/**
 * A node of the embedded T-mesh: one T-mesh node, sitting on one vertex of the working
 * copy of the triangle mesh.
 *
 * <p>Criticality is LCBK19 Def 6.2's, and it is the whole of what decides which way a
 * zero arc may collapse: a critical node holds an integer position the quantization
 * prescribed — a singularity, or a point on a feature curve — and must never be moved.
 * Border is Def 6.1's: a node embedded in the surface boundary, which may slide along
 * that boundary but never into the interior. On a closed surface no node is ever a
 * border node, and the flag exists so the operators can state the paper's rule rather
 * than a closed-surface special case of it.
 *
 * <p>A node is retired by clearing {@link #alive} rather than being removed, so that ids
 * stay equal to list indices forever. Every parallel array in the pipeline depends on
 * that.
 */
public final class EmbeddedNode {

    /** Index of this node in {@link EmbeddedTMesh#nodes}; stable for the object's life. */
    public final int nodeId;

    /**
     * Id of the {@code TMeshNode} this came from, or {@link EmbeddedTMesh#NONE} for a
     * node minted by an operator — by splitting an arc, or by extending a T-junction.
     */
    public final int sourceNodeId;

    /** Whether the node is still part of the T-mesh. */
    public boolean alive;

    /** LCBK19 Def 6.2: the node's position is prescribed, so it must never be moved. */
    public boolean critical;

    /** LCBK19 Def 6.1: the node is embedded in the surface boundary. */
    public boolean border;

    /** The vertex of the working copy that this node sits on. */
    public int copyVertex;

    /**
     * Creates a live node on a copy vertex.
     *
     * @param nodeId       index of this node in the T-mesh's node list
     * @param sourceNodeId originating {@code TMeshNode} id, or {@link EmbeddedTMesh#NONE}
     * @param copyVertex   vertex of the working copy the node sits on
     * @param critical     whether the node's position is prescribed (LCBK19 Def 6.2)
     * @param border       whether the node lies in the surface boundary (LCBK19 Def 6.1)
     */
    public EmbeddedNode(int nodeId, int sourceNodeId, int copyVertex, boolean critical,
            boolean border) {
        this.nodeId = nodeId;
        this.sourceNodeId = sourceNodeId;
        this.copyVertex = copyVertex;
        this.critical = critical;
        this.border = border;
        this.alive = true;
    }
}
