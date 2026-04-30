package ixdar.geometry.mesh.quadlayout.lyon2021;

/**
 * One patch of a Lyon 2021 conforming quad layout (paper §6).
 *
 * <p>A conforming layout patch is bounded by exactly 4 sides, each side a
 * sequence of one or more {@link LayoutArc}s. All four corners are TNodes
 * (singularities or layout intersections); no T-junctions remain on the
 * boundary.
 *
 * <p>{@code arcsBySide[s]} = ordered list of {@link LayoutArc} ids walked
 * from corner {@code s} to corner {@code (s + 1) % 4}. Resolve a layout-arc
 * id via {@link QuadLayout#arc(int)}.
 *
 * <p>{@code cornerNodeIds[s]} = {@link ixdar.geometry.mesh.quadlayout.tmesh.TNode}
 * id at corner {@code s}; corner {@code s} is the start of side {@code s}
 * and the end of side {@code (s + 3) % 4}.
 */
public record QuadLayoutPatch(int id,
                               int[][] arcsBySide,
                               int[] cornerNodeIds) {

    /** Total integer side length under a layout-arc quantization. */
    public int sideQuantization(int side, int[] qLayoutArc) {
        int sum = 0;
        for (int la : arcsBySide[side]) sum += qLayoutArc[la];
        return sum;
    }
}
