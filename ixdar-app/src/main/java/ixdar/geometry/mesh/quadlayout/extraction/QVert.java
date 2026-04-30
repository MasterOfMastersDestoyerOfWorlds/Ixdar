package ixdar.geometry.mesh.quadlayout.extraction;

import org.joml.Vector3f;

/**
 * QEx (Ebke 2013) quad-mesh vertex — one integer point in the parametric
 * domain whose preimage falls on the input triangle mesh. The preimage
 * lives in one of three places, encoded in {@link Source}:
 *
 * <ul>
 *   <li>{@link Source#VERT} — integer (u,v) lands exactly on a mesh vertex.
 *       {@code sourceId} is the mesh vertex id.</li>
 *   <li>{@link Source#EDGE} — integer (u,v) lands on the interior of a mesh
 *       edge. {@code sourceId} is the interior-edge id.</li>
 *   <li>{@link Source#FACE} — integer (u,v) lands strictly inside a triangle.
 *       {@code sourceId} is the face id.</li>
 * </ul>
 *
 * <p>{@code uv} stores the integer parametric coordinates (Java floats are
 * sufficient since they're literal integers, not interpolated values).
 * {@code position} stores the corresponding 3D position on the surface
 * (interpolated barycentrically inside the source primitive).
 */
public record QVert(int id,
                    Source source,
                    int sourceId,
                    float u,
                    float v,
                    Vector3f position) {

    public enum Source { VERT, EDGE, FACE }
}
