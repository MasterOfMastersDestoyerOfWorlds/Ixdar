package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
import java.util.List;

/**
 * One face of the planar T-mesh arrangement bounded by trace arcs.
 */
public final class TMeshPatch {

    public final int patchId;

    /**
     * Boundary arcs in cyclic order around the patch (consecutive duplicates
     * removed). Populated by {@code PatchBoundaryBuilder}.
     */
    public final List<Integer> boundingArcIds = new ArrayList<>();

    /**
     * The boundary cycle split at its corners into sides, each a run of arc
     * ids; for a valid rectangular patch there are exactly four, with side
     * {@code i} parametrically opposite side {@code (i + 2) % 4}. Lyon's
     * eq. (2) consistency constraints equate the quantized sums of opposite
     * sides.
     */
    public final List<List<Integer>> sides = new ArrayList<>();

    /**
     * Whether the boundary resolved to exactly one cycle with exactly four
     * corners. Patches that fail this (degenerate traces, unresolved boundary
     * stretches) are excluded from consistency constraints and logged.
     */
    public boolean validRectangle;

    /**
     * Creates one patch entry in the T-mesh arrangement.
     *
     * @param patchId unique patch id
     */
    public TMeshPatch(int patchId) {
        this.patchId = patchId;
    }
}
