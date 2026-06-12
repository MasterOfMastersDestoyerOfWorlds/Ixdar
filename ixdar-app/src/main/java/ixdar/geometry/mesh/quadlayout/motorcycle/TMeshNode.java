package ixdar.geometry.mesh.quadlayout.motorcycle;

import org.joml.Vector3f;

/**
 * Node of the motorcycle T-mesh arrangement. Nodes that sit exactly on a mesh
 * vertex (singularity origins, feature-chain corners, traces terminating on a
 * singular vertex) record that vertex in {@link #vertexId} and are shared by
 * everything arriving there — one mesh vertex never owns two T-mesh nodes,
 * otherwise the arrangement walk finds degree-1 dead ends and the surrounding
 * patches stop being rectangles.
 */
public final class TMeshNode {

    /** Interior or boundary singularity origin. */
    public static final int TYPE_SINGULARITY = 0;
    /** Trace-trace intersection. */
    public static final int TYPE_INTERSECTION = 1;
    /** Trace terminated on mesh boundary. */
    public static final int TYPE_BOUNDARY = 2;
    /** Feature / alignment trace endpoint. */
    public static final int TYPE_FEATURE = 3;
    /** Safety-net terminus for traces that died mid-walk (e.g. vertex degeneracy). */
    public static final int TYPE_TRUNCATED = 4;

    public final int nodeId;
    public final int type;

    /** Mesh vertex this node sits on, or -1 for nodes in face/edge interiors. */
    public final int vertexId;

    public final int singularityIndex4;
    public final double u;
    public final double v;
    public final Vector3f position;

    /**
     * Creates one embedded T-mesh node with chart and surface coordinates.
     *
     * @param nodeId            unique node id
     * @param type              node type constant
     * @param vertexId          mesh vertex the node sits on, or -1
     * @param singularityIndex4 singularity index4, or 0
     * @param u                 chart u at node
     * @param v                 chart v at node
     * @param position          embedded 3D position
     */
    public TMeshNode(int nodeId, int type, int vertexId, int singularityIndex4,
            double u, double v, Vector3f position) {
        this.nodeId = nodeId;
        this.type = type;
        this.vertexId = vertexId;
        this.singularityIndex4 = singularityIndex4;
        this.u = u;
        this.v = v;
        this.position = position;
    }
}
