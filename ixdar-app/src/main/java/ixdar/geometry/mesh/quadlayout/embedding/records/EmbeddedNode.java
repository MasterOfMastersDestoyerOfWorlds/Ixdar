package ixdar.geometry.mesh.quadlayout.embedding.records;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;

/**
 * One T-mesh node, sitting on one vertex of the working copy.
 *
 * <p>A critical node must never be moved; a border node may slide along the boundary but
 * not into the interior. Retire a node by clearing {@link #alive}, never by removing it:
 * ids are list indices.
 */
public final class EmbeddedNode {

    /** Index of this node in {@link ArcNetwork#nodes}; stable for the object's life. */
    public final int nodeId;

    /**
     * Id of the {@code TMeshNode} this came from, or {@link ArcNetwork#NONE} for a
     * node minted by an operator — by splitting an arc, or by extending a T-junction.
     */
    public final int sourceNodeId;

    /** Whether the node is still part of the T-mesh. */
    public boolean alive;

    /** LCBK19 Def 6.2: the node's position is prescribed, so it must never be moved. */
    public boolean critical;

    /** LCBK19 Def 6.1: the node is embedded in the surface boundary. */
    public boolean border;

    /** The end of a trace that died without meeting anything; diagnostic. */
    public boolean truncated;

    /** The vertex of the working copy that this node sits on. */
    public int copyVertex;

    /** Source-mesh vertex the node sits on, or -1 for face/edge-interior nodes. */
    public int vertexId = -1;

    /**
     * Active face whose chart hosts the node's (u, v) for face-interior nodes, or
     * -1 for nodes pinned to a mesh vertex.
     */
    public int activeFace = -1;

    /** Singularity index times four, or 0. */
    public int singularityIndex4;

    /** Chart u at the node, from the arrangement phase. */
    public double u;

    /** Chart v at the node, from the arrangement phase. */
    public double v;

    /** Embedded 3D position from the arrangement phase; null after operators mint. */
    public Vector3f position;

    /**
     * Creates a live node on a copy vertex.
     *
     * @param nodeId       index of this node in the T-mesh's node list
     * @param sourceNodeId originating {@code TMeshNode} id, or {@link ArcNetwork#NONE}
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

    /**
     * Creates an arrangement-phase node with chart and surface coordinates; the
     * working-copy embedding is filled in by the layout-embedding assembly. The
     * minting site sets {@link #critical}, {@link #border}, or {@link #truncated}
     * when they apply.
     *
     * @param nodeId            unique node id
     * @param vertexId          source-mesh vertex the node sits on, or -1
     * @param activeFace        active face hosting (u, v), or -1 when pinned to a
     *                          mesh vertex
     * @param singularityIndex4 singularity index times four, or 0
     * @param u                 chart u at the node
     * @param v                 chart v at the node
     * @param position          embedded 3D position
     */
    public EmbeddedNode(int nodeId, int vertexId, int activeFace,
            int singularityIndex4, double u, double v, Vector3f position) {
        this.nodeId = nodeId;
        this.sourceNodeId = nodeId;
        this.vertexId = vertexId;
        this.activeFace = activeFace;
        this.singularityIndex4 = singularityIndex4;
        this.u = u;
        this.v = v;
        this.position = position;
        this.copyVertex = -1;
        this.alive = true;
    }
}
