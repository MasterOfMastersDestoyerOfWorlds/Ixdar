package ixdar.geometry.mesh.quadlayout.tmesh;

import java.util.List;

/**
 * A T-mesh arc — a maximal motorcycle-trace segment running between two
 * {@link TNode}s along the same iso-line of the seamless parametrization.
 *
 * <p>{@code meshFaceCrossings} records the per-face traversal of the
 * underlying mesh as int pairs {@code [faceId, directionInFace]} so that
 * downstream stages (PATCH-42 quantization, T-mesh rendering) can recover the
 * geometric path.
 *
 * <p>{@code parametricLength} is the cumulative {@code |Δu|} or {@code |Δv|}
 * traversed by this arc in the seamless parametrization (which axis depends
 * on {@link #direction}). This is the real-valued target {@code r_i} the
 * PATCH-42 quantization ILP rounds to a non-negative integer length.
 */
public record TArc(int id,
                   int startNode,
                   int endNode,
                   List<int[]> meshFaceCrossings,
                   int direction,
                   float parametricLength) {
}
