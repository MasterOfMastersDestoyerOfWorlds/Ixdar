package ixdar.geometry.mesh.quadlayout.tmesh;

/**
 * A T-mesh patch — a rectangular region of the parametric domain bounded by
 * exactly four arcs meeting at four corner nodes.
 *
 * <p>{@code arcIds} are listed in cyclic order (face-left, face-top,
 * face-right, face-bottom) and {@code cornerNodeIds} are the four corners in
 * matching cyclic order (bottom-left, bottom-right, top-right, top-left).
 */
public record TPatch(int id, int[] arcIds, int[] cornerNodeIds) {
}
