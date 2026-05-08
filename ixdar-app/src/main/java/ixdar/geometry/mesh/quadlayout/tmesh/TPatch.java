package ixdar.geometry.mesh.quadlayout.tmesh;

/**
 * A T-mesh patch — a quad-shaped region of the parametric domain bounded
 * by 4 sides; each side may contain multiple {@link TArc}s when
 * T-junctions are present.
 *
 * <p>{@code arcsBySide[i]} gives the arc ids on side {@code i ∈ {0..3}},
 * in walk order from corner {@code i} toward corner {@code (i + 1) % 4}.
 * For simple non-T-junction patches each side has exactly one arc.
 *
 * <p>{@code arcIds} is a legacy view: one arc per side (the first one).
 * Kept for backward compat with consumers that don't yet handle multi-arc
 * sides (Quantization v1, SplitTable v1, IntersectionTable v1). New code
 * should walk {@code arcsBySide} directly.
 *
 * <p>{@code cornerNodeIds[i]} = node id at corner {@code i}, where corner
 * {@code i} is the start of side {@code i} and the end of side {@code (i + 3) % 4}.
 */
public record TPatch(int id,
                     int[] arcIds,
                     int[][] arcsBySide,
                     int[] cornerNodeIds) {
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;

    /**
     * Build a single-arc-per-side patch (synthetic test patches + legacy.
     */
    public static TPatch single(int id, int[] arcIds, int[] cornerNodeIds) {
        int[][] sides = new int[NUM_4][];
        for (int i = 0; i < NUM_4; i++) {
            sides[i] = new int[]{arcIds[i]};
        }
        return new TPatch(id, arcIds, sides, cornerNodeIds);
    }

    /**
     * Build a multi-arc-per-side patch (T-junction-aware). Supports both
     */
    public static TPatch multi(int id, int[][] arcsBySide, int[] cornerNodeIds) {
        int[] firstArcs = new int[arcsBySide.length];
        for (int i = 0; i < arcsBySide.length; i++) {
            firstArcs[i] = arcsBySide[i].length > 0 ? arcsBySide[i][0] : -1;
        }
        return new TPatch(id, firstArcs, arcsBySide, cornerNodeIds);
    }

    /**
     * True iff this patch has 4 sides (a quad). 3-sided patches are
     */
    public boolean isQuad() { return arcsBySide != null && arcsBySide.length == NUM_4; }
    public boolean isTriangle() { return arcsBySide != null && arcsBySide.length == NUM_3; }
}
