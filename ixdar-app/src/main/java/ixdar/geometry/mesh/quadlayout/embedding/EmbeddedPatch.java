package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * A patch of the embedded T-mesh: a four-sided cell of the surface partition, bounded by
 * chains of arcs.
 *
 * <p>Four <em>sides</em>, not four arcs. A side is a chain, because a T-junction of the
 * neighbouring patch lands in the middle of this one's side without being a corner of it:
 * LCBK19 §4 notes that the parametric angle between consecutive arcs at a node "can only
 * be π/2 or π", and calls the halfarcs into π/2 angles <em>corners</em> and the rest
 * <em>flat</em>. A flat node is a T-junction as far as this patch is concerned, and it is
 * what makes the T-mesh non-conforming.
 *
 * <p><b>Corners count with multiplicity, and a side may be empty.</b> This is not an edge
 * case; it is the state the whole re-embedding aims at. When the quantization gives a
 * patch zero width, its parametric image is a line segment rather than a rectangle, so
 * walking its boundary you travel the bottom, turn π/2 at the end, cross a side of length
 * nothing, turn π/2 again, and come back along the top. Both of those corners are at
 * <em>one</em> node — a double corner, drawn as a red circle in LCBK19 Figure 9. Once its
 * zero arcs are collapsed such a patch is a bigon: two opposite sides empty, two corner
 * nodes of multiplicity two, and exactly two arcs left. That bigon is precisely what
 * operator (3) consumes. So the invariant is not "four distinct corner nodes" — it is
 * that the corner multiplicities sum to four.
 *
 * <p>Sides walk the boundary in one consistent cyclic direction, so
 * {@code sideNodeIds[i].last == sideNodeIds[(i + 1) % 4].first}, and sides {@code i} and
 * {@code i + 2} are anti-parallel as walked. Every question about "the corresponding
 * point on the opposite side" is therefore answered by one formula, and it lives in
 * {@link EmbeddedTMesh#oppositeOffset}.
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
