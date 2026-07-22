package ixdar.geometry.mesh.quadlayout.embedding;

/**
 * One T-mesh node, sitting on one vertex of the working copy.
 *
 * <p>A critical node must never be moved; a border node may slide along the boundary but
 * not into the interior. Retire a node by clearing {@link #alive}, never by removing it:
 * ids are list indices.
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
