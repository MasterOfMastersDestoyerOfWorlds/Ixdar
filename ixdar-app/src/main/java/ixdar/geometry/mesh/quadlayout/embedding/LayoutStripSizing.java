package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * How many quads to lay along each arc, chosen from the arcs' parametric lengths and shared by
 * every arc of a quad strip so that opposite sides of every patch agree.
 *
 * <p>See also: LCK21a Section 6, "size chosen compatibly based on the patches' parametric extent"
 */
public final class LayoutStripSizing {

    /** Strip id of an arc not yet assigned to one. */
    public static final int UNASSIGNED = -1;

    public final EmbeddedTMesh tmesh;

    /** Parametric length of each arc, indexed by arc id. */
    public final double[] lengthByArc;

    /** Parametric length one quad edge should span. */
    public final double targetEdgeLength;

    /** Strip each arc belongs to, indexed by arc id; {@link #UNASSIGNED} for a retired arc. */
    public int[] stripByArc;

    /** Quads laid along each arc, indexed by arc id; zero for a retired arc. */
    public int[] quadsByArc;

    /** Number of strips found. */
    public int stripCount;

    /** Quads in the sized mesh, the sum over patches of the two directions multiplied. */
    public int quadCount;

    /**
     * Largest ratio of a strip's longest arc to its mean. Consistency forces one count on the
     * whole strip, so a spread far above one means no assignment can size that strip well.
     */
    public double worstStripSpread;

    /**
     * Stores the layout, its measured arcs and the target quad size.
     *
     * @param tmesh            conforming embedded T-mesh
     * @param lengthByArc      parametric length of each arc, indexed by arc id
     * @param targetEdgeLength parametric length one quad edge should span
     */
    public LayoutStripSizing(EmbeddedTMesh tmesh, double[] lengthByArc, double targetEdgeLength) {
        this.tmesh = tmesh;
        this.lengthByArc = lengthByArc;
        this.targetEdgeLength = targetEdgeLength;
    }

    /**
     * Groups the arcs into strips and gives each strip one quad count.
     *
     * @throws IllegalStateException when a patch side carries more than one arc, which leaves the
     *                               strips ill-defined
     * @return this, sized
     */
    public LayoutStripSizing build() {
        requireSingleArcSides();
        stripByArc = new int[tmesh.arcs.size()];
        quadsByArc = new int[tmesh.arcs.size()];
        Arrays.fill(stripByArc, UNASSIGNED);
        List<List<Integer>> arcsByStrip = new ArrayList<>();
        for (EmbeddedArc arc : tmesh.arcs) {
            if (!arc.alive || stripByArc[arc.arcId] != UNASSIGNED) {
                continue;
            }
            arcsByStrip.add(floodStrip(arc.arcId, stripCount++));
        }
        List<Double> allLengths = new ArrayList<>();
        for (List<Integer> members : arcsByStrip) {
            double total = 0.0;
            double longest = 0.0;
            for (int arcId : members) {
                double length = lengthByArc[arcId];
                total += length;
                longest = Math.max(longest, length);
                allLengths.add(length);
            }
            int quads = Math.max(1,
                    (int) Math.round(total / members.size() / targetEdgeLength));
            worstStripSpread = Math.max(worstStripSpread,
                    longest / Math.max(1.0e-9, total / members.size()));
            for (int arcId : members) {
                quadsByArc[arcId] = quads;
            }
        }
        for (EmbeddedPatch patch : tmesh.patches) {
            if (patch.alive) {
                quadCount += sideQuads(patch, 0) * sideQuads(patch, 1);
            }
        }
        Collections.sort(allLengths);
        System.out.printf(
                "[layout-sizing] strips=%d quads=%d target=%.4f arcParametric min=%.4f"
                        + " median=%.4f max=%.4f worstStripSpread=%.1f%n",
                stripCount, quadCount, targetEdgeLength, allLengths.get(0),
                allLengths.get(allLengths.size() / 2), allLengths.get(allLengths.size() - 1),
                worstStripSpread);
        return this;
    }

    /**
     * The quads laid along one side of a patch.
     *
     * @param patch patch to read
     * @param side  side index in {@code [0, 4)}
     * @return the side's quad count
     */
    public int sideQuads(EmbeddedPatch patch, int side) {
        return quadsByArc[patch.sideArcIds.get(side).get(0)];
    }

    /**
     * Collects one strip: the arcs reachable from a seed by stepping to the opposite side of an
     * incident patch, which are exactly the arcs a rectangle forces to carry the same count.
     *
     * @param seedArcId arc to start from
     * @param strip     strip id to stamp
     * @return the strip's member arc ids
     */
    private List<Integer> floodStrip(int seedArcId, int strip) {
        List<Integer> frontier = new ArrayList<>();
        stripByArc[seedArcId] = strip;
        frontier.add(seedArcId);
        for (int cursor = 0; cursor < frontier.size(); cursor++) {
            int arcId = frontier.get(cursor);
            EmbeddedArc arc = tmesh.arcs.get(arcId);
            for (int patchId : new int[] { arc.leftPatchId, arc.rightPatchId }) {
                if (patchId == EmbeddedTMesh.NONE || !tmesh.patches.get(patchId).alive) {
                    continue;
                }
                EmbeddedPatch patch = tmesh.patches.get(patchId);
                for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                    if (patch.sideArcIds.get(side).get(0) != arcId) {
                        continue;
                    }
                    int oppositeArcId = patch.sideArcIds
                            .get((side + 2) % EmbeddedPatch.SIDES).get(0);
                    if (stripByArc[oppositeArcId] == UNASSIGNED) {
                        stripByArc[oppositeArcId] = strip;
                        frontier.add(oppositeArcId);
                    }
                }
            }
        }
        return frontier;
    }

    /**
     * Checks every side of every live patch carries exactly one arc, the shape the strip flood
     * assumes.
     *
     * @throws IllegalStateException when a side carries a different number of arcs
     */
    private void requireSingleArcSides() {
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                int arcCount = patch.sideArcIds.get(side).size();
                if (arcCount != 1) {
                    throw new IllegalStateException("patch " + patch.patchId + " side " + side
                            + " carries " + arcCount + " arcs; the strips are only well defined"
                            + " once every side is one arc, so a degree-two node interior to a"
                            + " side has to be merged away first");
                }
            }
        }
    }
}
