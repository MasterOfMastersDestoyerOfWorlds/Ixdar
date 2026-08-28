package ixdar.geometry.mesh.quadlayout.embedding.records;

import java.util.ArrayList;
import java.util.List;

import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;

/**
 * A four-sided cell of the surface partition, bounded by four chains of arcs.
 *
 * <p>Corners count with multiplicity and a side may be empty, so the invariant is that
 * the multiplicities sum to four. Sides {@code i} and {@code i + 2} are anti-parallel.
 *
 * <p>See also: LCBK19 Section 4
 */
public final class EmbeddedPatch {

    /** Sides of a patch. */
    public static final int SIDES = 4;

    /** Index of this patch in {@link EmbeddedTMesh#patches}; stable for the object's life. */
    public final int patchId;

    /**
     * Id of the {@code TMeshPatch} this came from, or {@link EmbeddedTMesh#NONE} for a
     * patch minted by splitting another one.
     */
    public final int sourcePatchId;

    /** Arcs of each side, in the side's walking order. A side may be empty. */
    public final List<List<Integer>> sideArcIds;

    /**
     * Nodes of each side, in the side's walking order, so that side {@code i} has one
     * more node than it has arcs. An empty side holds exactly one node, and that node is
     * a double corner.
     */
    public final List<List<Integer>> sideNodeIds;

    /** Whether the patch is still part of the T-mesh. */
    public boolean alive;

    /**
     * Whether the arrangement boundary resolved to exactly one cycle with exactly
     * four corners; false patches are excluded from quantization constraints and
     * rejected by the embedding assembly.
     */
    public boolean validRectangle;

    /**
     * Creates a live patch with four empty sides, to be filled by the caller.
     *
     * @param patchId       index of this patch in the T-mesh's patch list
     * @param sourcePatchId originating {@code TMeshPatch} id, or {@link EmbeddedTMesh#NONE}
     */
    public EmbeddedPatch(int patchId, int sourcePatchId) {
        this.patchId = patchId;
        this.sourcePatchId = sourcePatchId;
        this.sideArcIds = new ArrayList<>(SIDES);
        this.sideNodeIds = new ArrayList<>(SIDES);
        for (int side = 0; side < SIDES; side++) {
            sideArcIds.add(new ArrayList<>());
            sideNodeIds.add(new ArrayList<>());
        }
        this.alive = true;
    }

    /**
     * The node at the start of a side, which is one of the patch's four corners. A node
     * appearing at the start of two sides is a double corner.
     *
     * @param side side index in {@code [0, 4)}
     * @return the corner node the side starts at
     */
    public int cornerNodeId(int side) {
        return sideNodeIds.get(side).get(0);
    }
}
