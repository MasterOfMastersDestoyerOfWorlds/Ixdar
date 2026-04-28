package ixdar.geometry.mesh.quadlayout.tmesh;

/**
 * A node in the T-mesh — either a singularity (motorcycle launch site), a
 * crash intersection between two motorcycle traces, or a boundary hit.
 *
 * <p>Position is stored both as the mesh face id where the node sits and as
 * the (u, v) coordinates in that face's parametric frame.
 */
public record TNode(int id, NodeKind kind, int meshFaceId, float u, float v) {

    public enum NodeKind {
        SINGULARITY,
        INTERSECTION,
        BOUNDARY
    }
}
