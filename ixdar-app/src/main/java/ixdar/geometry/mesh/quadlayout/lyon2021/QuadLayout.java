package ixdar.geometry.mesh.quadlayout.lyon2021;

import java.util.List;

/**
 * The output of Lyon 2021's algorithm: a conforming quad layout
 * (paper §6 first paragraph).
 *
 * <p>Most patches are 4-sided ({@link QuadLayoutPatch}); a small number are
 * 3-sided wedges around 3-valent singularities ({@link TrianglePatch},
 * PATCH-79). Layout arcs align with the seamless parametrization's
 * iso-lines within the user-specified angular bound α.
 *
 * <p>{@link #patchCount()} returns paper Table 1's {@code #P} = quads +
 * triangles combined.
 *
 * <p>{@code layoutArcs} is the registry that {@link QuadLayoutPatch#arcsBySide()}
 * indexes into. {@code tArcQuantization[a]} carries the original ILP
 * solution for the underlying {@link ixdar.geometry.mesh.quadlayout.tmesh.TArc}s
 * (consumers like {@link LyonMetrics} that walk T-arcs need this);
 * {@code layoutArcQuantization[la]} carries the integer length of each
 * {@link LayoutArc} (the same as {@code tArcQuantization[underlying]}
 * for INHERITED arcs, computed for INTERIOR/DERIVED).
 */
public record QuadLayout(List<QuadLayoutPatch> patches,
                          List<TrianglePatch> triangles,
                          List<LayoutArc> layoutArcs,
                          int[] tArcQuantization,
                          int[] layoutArcQuantization,
                          int tJunctionsResolved) {

    /** {@code #P} from Lyon Table 1 = 4-sided + 3-sided patches combined. */
    public int patchCount() { return patches.size() + triangles.size(); }

    /** Resolve a layout-arc id to its record. */
    public LayoutArc arc(int layoutArcId) { return layoutArcs.get(layoutArcId); }
}
