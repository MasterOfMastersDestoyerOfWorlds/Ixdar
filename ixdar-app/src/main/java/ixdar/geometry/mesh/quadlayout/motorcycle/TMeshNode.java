package ixdar.geometry.mesh.quadlayout.motorcycle;

import org.joml.Vector3f;

/**
 * Node of the motorcycle T-mesh arrangement.
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
    public final int singularityVertexId;
    public final int singularityIndex4;
    public final float u;
    public final float v;
    public final Vector3f position;

    /**
     * Creates one embedded T-mesh node with chart and surface coordinates.
     *
     * @param nodeId              unique node id
     * @param type                node type constant
     * @param singularityVertexId singularity vertex id, or -1
     * @param singularityIndex4   singularity index4, or 0
     * @param u                   chart u at node
     * @param v                   chart v at node
     * @param position            embedded 3D position
     */
    public TMeshNode(int nodeId, int type, int singularityVertexId, int singularityIndex4,
            float u, float v, Vector3f position) {
        this.nodeId = nodeId;
        this.type = type;
        this.singularityVertexId = singularityVertexId;
        this.singularityIndex4 = singularityIndex4;
        this.u = u;
        this.v = v;
        this.position = position;
    }
}
