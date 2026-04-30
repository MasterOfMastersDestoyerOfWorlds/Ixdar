package ixdar.geometry.mesh.quadlayout.lyon2021;

/**
 * A 3-sided layout patch — a wedge around a 3-valent singularity.
 *
 * <p>Lyon 2021 paper Figure 5/6 includes such triangular cells in the
 * conforming layout (PATCH-79). They are NOT degenerate: a 3-valent
 * singularity has 3 emanating motorcycle traces, and the planar dual
 * face graph naturally produces 3 wedge regions per such singularity,
 * each bounded by 2 trace arcs + 1 separatrix segment.
 *
 * <p>Cannot be evaluated by 4-sided Coons patches; downstream renderers
 * (PATCH-75 LCBK19 dense grid) handle them via Charrot-Gregory n-sided
 * patches or other 3-sided primitives.
 */
public record TrianglePatch(int id,
                             int[][] arcsBySide,    // length 3
                             int[] cornerNodeIds) { // length 3
}
