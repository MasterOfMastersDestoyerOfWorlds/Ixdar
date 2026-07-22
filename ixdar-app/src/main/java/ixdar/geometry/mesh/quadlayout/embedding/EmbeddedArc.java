package ixdar.geometry.mesh.quadlayout.embedding;

/**
 * One T-mesh arc, realized as a path of edges in the working copy from its start node's
 * vertex to its end node's vertex.
 *
 * <p>The operators consider only the quantized parametric length; an arc quantized to
 * zero still has positive extent on the mesh.
 */
public final class EmbeddedArc {

    /** Index of this arc in {@link EmbeddedTMesh#arcs}; stable for the object's life. */
    public final int arcId;

    /**
     * Id of the {@code TraceArc} this came from, or {@link EmbeddedTMesh#NONE} for an arc
     * minted by an operator — a zero arc inserted across a non-simple zero-patch, or an
     * arc extending a T-junction.
     */
    public final int sourceArcId;

    /** Whether the arc is still part of the T-mesh. */
    public boolean alive;

    /** Node the arc runs from; its vertex is the first of {@link #path}. */
    public int startNodeId;

    /** Node the arc runs to; its vertex is the last of {@link #path}. */
    public int endNodeId;

    /** Prescribed parametric length, never negative. Zero arcs are collapsed away. */
    public int quantizedLength;

    /** LCBK19 Def 6.1: the arc lies on a feature or boundary curve, so it is critical. */
    public boolean feature;

    /** The arc's realization as a path of edges in the working copy. */
    public ArcEdgePath path;

    /** Patch on the arc's left, walking from start node to end node. */
    public int leftPatchId;

    /** Patch on the arc's right, walking from start node to end node. */
    public int rightPatchId;

    /**
     * Creates a live arc between two nodes.
     *
     * @param arcId           index of this arc in the T-mesh's arc list
     * @param sourceArcId     originating {@code TraceArc} id, or {@link EmbeddedTMesh#NONE}
     * @param startNodeId     node the arc runs from
     * @param endNodeId       node the arc runs to
     * @param quantizedLength prescribed parametric length, never negative
     * @param feature         whether the arc lies on a feature or boundary curve
     * @param path            the arc's edge path in the working copy
     */
    public EmbeddedArc(int arcId, int sourceArcId, int startNodeId, int endNodeId,
            int quantizedLength, boolean feature, ArcEdgePath path) {
        this.arcId = arcId;
        this.sourceArcId = sourceArcId;
        this.startNodeId = startNodeId;
        this.endNodeId = endNodeId;
        this.quantizedLength = quantizedLength;
        this.feature = feature;
        this.path = path;
        this.leftPatchId = EmbeddedTMesh.NONE;
        this.rightPatchId = EmbeddedTMesh.NONE;
        this.alive = true;
    }

    /**
     * Whether the arc's two ends are the same node, which a zero arc becomes once the
     * rest of its patch has collapsed around it.
     *
     * @return true when the arc is a loop
     */
    public boolean isLoop() {
        return startNodeId == endNodeId;
    }

    /**
     * The node at the far end of the arc from one of its own.
     *
     * @param nodeId one of the arc's two nodes
     * @return the other one; the same node, for a loop
     */
    public int otherNode(int nodeId) {
        return nodeId == startNodeId ? endNodeId : startNodeId;
    }
}
