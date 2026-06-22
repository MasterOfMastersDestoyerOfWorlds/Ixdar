package ixdar.geometry.mesh.quadlayout.motorcycle.records;

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

    public enum Type {
        /** Interior or boundary singularity origin. */
        SINGULARITY,
        /** Trace-trace intersection. */
        INTERSECTION,
        /** Trace terminated on mesh boundary. */
        BOUNDARY,
        /** Feature / alignment trace endpoint. */
        FEATURE,
        /**
         * Safety-net terminus for traces that died mid-walk (e.g. vertex degeneracy).
         */
        TRUNCATED
    }

    public final int nodeId;
    public final Type type;

    /** Mesh vertex this node sits on, or -1 for nodes in face/edge interiors. */
    public final int vertexId;

    /**
     * Active face whose chart hosts the node's (u, v) for face-interior nodes, or
     * -1 for nodes pinned to a mesh vertex ({@code vertexId >= 0}).
     */
    public final int activeFace;

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
     * @param activeFace        active face hosting (u, v), or -1 when the node is
     *                          pinned to a mesh vertex
     * @param singularityIndex4 singularity index4, or 0
     * @param u                 chart u at node
     * @param v                 chart v at node
     * @param position          embedded 3D position
     */
    public TMeshNode(int nodeId, Type type, int vertexId, int activeFace, int singularityIndex4,
            double u, double v, Vector3f position) {
        this.nodeId = nodeId;
        this.type = type;
        this.vertexId = vertexId;
        this.activeFace = activeFace;
        this.singularityIndex4 = singularityIndex4;
        this.u = u;
        this.v = v;
        this.position = position;
    }
}
