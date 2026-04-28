package ixdar.geometry.mesh.quadlayout.tmesh;

import java.util.List;

/**
 * One motorcycle trace launched from a singularity along a cardinal direction
 * of the seamless parametrization.
 *
 * <p>{@code direction} encodes the cardinal in {0,1,2,3} = {+u, +v, -u, -v}
 * in the launching face's local parametric frame.  {@code trace} is the
 * ordered list of {@link Step}s the motorcycle visited.  {@code finalNodeId}
 * identifies the {@link TNode} where the motorcycle terminated (intersection
 * with another trace, parametric boundary hit, or — rarely — a numerical abort).
 */
public record Motorcycle(int id,
                         int singularityVertexId,
                         int direction,
                         List<Step> trace,
                         int finalNodeId) {

    /**
     * One step in a motorcycle trace through a single mesh face.  The
     * motorcycle entered face {@code meshFaceId} at parametric position
     * {@code (uIn, vIn)} and left at {@code (uOut, vOut)} (both expressed in
     * that face's local UV frame).
     */
    public record Step(int meshFaceId,
                       float uIn, float vIn,
                       float uOut, float vOut,
                       int exitEdgeIndex) {}
}
