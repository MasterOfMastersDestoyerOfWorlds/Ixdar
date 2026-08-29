package ixdar.geometry.mesh.quadlayout.gridmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

/**
 * The frame of every layout patch in one common integer grid, which turns the separate per-patch
 * rectangle maps into a single integer grid map.
 *
 * <p>See also: LCBK19 Section 6.2; EBCK13 Constraints 1 and 2
 */
public final class IntegerGridMap {

    /** Rotations a grid automorphism may apply. */
    public static final int QUARTER_TURNS = 4;

    /** Frame of a patch no walk reached, and of every retired patch. */
    public static final int NOT_PLACED = -1;

    /** Coordinates of a grid position. */
    public static final int GRID_COORDINATES = 2;

    /** Cosine of each quarter turn, so a rotation is one formula for both int and double. */
    private static final int[] QUARTER_TURN_COSINE = {1, 0, -1, 0};

    /** Sine of each quarter turn, paired with {@link #QUARTER_TURN_COSINE}. */
    private static final int[] QUARTER_TURN_SINE = {0, 1, 0, -1};

    public final ArcNetwork tmesh;

    /** Quarter turns the patch's rectangle is rotated by, indexed by patch id. */
    public int[] quarterTurnsByPatchId;

    /** Grid u of the patch rectangle's own origin corner, indexed by patch id. */
    public int[] originUByPatchId;

    /** Grid v of the patch rectangle's own origin corner, indexed by patch id. */
    public int[] originVByPatchId;

    /** Patches given a frame; short of the live count when the patch graph is disconnected. */
    public int placedPatchCount;

    /** Separate walks the placement needed, one per connected component of the patch graph. */
    public int componentCount;

    /** Arcs whose two patches place them identically, so the map crosses them without a seam. */
    public int interiorArcCount;

    /** Arcs whose two patches place them apart, which is where the map is seamless but not one chart. */
    public int seamArcCount;

    /**
     * Arcs whose two patches disagree on quantized length, which no grid automorphism can
     * reconcile. A conforming layout must leave this at zero.
     */
    public int brokenArcCount;

    /** Arcs with the same patch on both sides, which carry no frame information. */
    public int selfAdjacentArcCount;

    /**
     * Whether each arc is a seam, indexed by arc id: its two patches place it apart, so the map is
     * only seamless across it rather than continuous.
     */
    public boolean[] seamByArcId;

    /**
     * Quarter turns of the grid automorphism taking the right patch's placement of each arc onto
     * the left patch's; {@link #NOT_PLACED} where no valid transition exists (one-sided, broken,
     * self-adjacent and loop arcs). Identity for interior arcs.
     */
    public int[] transitionQuarterTurnsByArcId;

    /** Grid u of each arc's transition translation, paired with the quarter turns. */
    public int[] transitionTranslationUByArcId;

    /** Grid v of each arc's transition translation, paired with the quarter turns. */
    public int[] transitionTranslationVByArcId;

    /** Arcs carrying a valid transition, seam and interior together. */
    public int transitionArcCount;

    /**
     * Stores the layout whose patches are framed.
     *
     * @param tmesh conforming embedded T-mesh
     */
    public IntegerGridMap(ArcNetwork tmesh) {
        this.tmesh = tmesh;
    }

    /**
     * Frames every live patch by walking the patch adjacency graph, then classifies every arc as
     * interior, seam or broken.
     *
     * @return this, placed
     */
    public IntegerGridMap build() {
        int patchCount = tmesh.patches.size();
        quarterTurnsByPatchId = new int[patchCount];
        originUByPatchId = new int[patchCount];
        originVByPatchId = new int[patchCount];
        Arrays.fill(quarterTurnsByPatchId, NOT_PLACED);
        seamByArcId = new boolean[tmesh.arcs.size()];
        boolean[] classifiedArc = new boolean[tmesh.arcs.size()];
        int[] hereStart = new int[GRID_COORDINATES];
        int[] hereEnd = new int[GRID_COORDINATES];
        int[] thereStart = new int[GRID_COORDINATES];
        int[] thereEnd = new int[GRID_COORDINATES];
        for (EmbeddedPatch seed : tmesh.patches) {
            if (!seed.alive || quarterTurnsByPatchId[seed.patchId] != NOT_PLACED) {
                continue;
            }
            componentCount++;
            quarterTurnsByPatchId[seed.patchId] = 0;
            placedPatchCount++;
            Deque<Integer> frontier = new ArrayDeque<>();
            frontier.add(seed.patchId);
            while (!frontier.isEmpty()) {
                int patchId = frontier.poll();
                for (int arcId : boundaryArcIds(patchId)) {
                    EmbeddedArc arc = tmesh.arcs.get(arcId);
                    int neighbour = arc.leftPatchId == patchId ? arc.rightPatchId : arc.leftPatchId;
                    if (neighbour == patchId) {
                        selfAdjacentArcCount += classifiedArc[arcId] ? 0 : 1;
                        classifiedArc[arcId] = true;
                        continue;
                    }
                    if (neighbour == ArcNetwork.NONE || !tmesh.patches.get(neighbour).alive) {
                        continue;
                    }
                    if (!arcLocalCoordinates(patchId, arcId, hereStart, hereEnd)
                            || !arcLocalCoordinates(neighbour, arcId, thereStart, thereEnd)) {
                        continue;
                    }
                    if (quarterTurnsByPatchId[neighbour] == NOT_PLACED) {
                        if (placeAcross(patchId, neighbour, hereStart, hereEnd, thereStart,
                                thereEnd)) {
                            placedPatchCount++;
                            frontier.add(neighbour);
                        }
                        continue;
                    }
                    if (classifiedArc[arcId]) {
                        continue;
                    }
                    classifiedArc[arcId] = true;
                    seamByArcId[arcId] =
                            classify(patchId, neighbour, hereStart, hereEnd, thereStart, thereEnd);
                }
            }
        }
        computeTransitions();
        System.out.println("[integer-grid] placed=" + placedPatchCount + " components="
                + componentCount + " interiorArcs=" + interiorArcCount + " seamArcs=" + seamArcCount
                + " brokenArcs=" + brokenArcCount + " selfAdjacentArcs=" + selfAdjacentArcCount
                + " transitions=" + transitionArcCount);
        return this;
    }

    /**
     * Solves, for every two-sided arc of two framed patches, the grid automorphism taking the
     * right patch's placement onto the left patch's, verified against both arc ends.
     */
    private void computeTransitions() {
        int arcCount = tmesh.arcs.size();
        transitionQuarterTurnsByArcId = new int[arcCount];
        transitionTranslationUByArcId = new int[arcCount];
        transitionTranslationVByArcId = new int[arcCount];
        Arrays.fill(transitionQuarterTurnsByArcId, NOT_PLACED);
        int[] localStart = new int[GRID_COORDINATES];
        int[] localEnd = new int[GRID_COORDINATES];
        int[] leftStart = new int[GRID_COORDINATES];
        int[] leftEnd = new int[GRID_COORDINATES];
        int[] rightStart = new int[GRID_COORDINATES];
        int[] rightEnd = new int[GRID_COORDINATES];
        int[] rotated = new int[GRID_COORDINATES];
        for (EmbeddedArc arc : tmesh.arcs) {
            if (!arc.alive || arc.leftPatchId == ArcNetwork.NONE
                    || arc.rightPatchId == ArcNetwork.NONE
                    || arc.leftPatchId == arc.rightPatchId
                    || quarterTurnsByPatchId[arc.leftPatchId] == NOT_PLACED
                    || quarterTurnsByPatchId[arc.rightPatchId] == NOT_PLACED
                    || !arcLocalCoordinates(arc.leftPatchId, arc.arcId, localStart, localEnd)) {
                continue;
            }
            toGrid(arc.leftPatchId, localStart, leftStart);
            toGrid(arc.leftPatchId, localEnd, leftEnd);
            if (!arcLocalCoordinates(arc.rightPatchId, arc.arcId, localStart, localEnd)) {
                continue;
            }
            toGrid(arc.rightPatchId, localStart, rightStart);
            toGrid(arc.rightPatchId, localEnd, rightEnd);
            int spanLeftU = leftEnd[0] - leftStart[0];
            int spanLeftV = leftEnd[1] - leftStart[1];
            int spanRightU = rightEnd[0] - rightStart[0];
            int spanRightV = rightEnd[1] - rightStart[1];
            if (spanLeftU == 0 && spanLeftV == 0) {
                continue;
            }
            for (int quarterTurns = 0; quarterTurns < QUARTER_TURNS; quarterTurns++) {
                rotate(quarterTurns, spanRightU, spanRightV, rotated);
                if (rotated[0] != spanLeftU || rotated[1] != spanLeftV) {
                    continue;
                }
                rotate(quarterTurns, rightStart[0], rightStart[1], rotated);
                int translationU = leftStart[0] - rotated[0];
                int translationV = leftStart[1] - rotated[1];
                rotate(quarterTurns, rightEnd[0], rightEnd[1], rotated);
                if (rotated[0] + translationU != leftEnd[0]
                        || rotated[1] + translationV != leftEnd[1]) {
                    continue;
                }
                transitionQuarterTurnsByArcId[arc.arcId] = quarterTurns;
                transitionTranslationUByArcId[arc.arcId] = translationU;
                transitionTranslationVByArcId[arc.arcId] = translationV;
                transitionArcCount++;
                break;
            }
        }
    }

    /**
     * Every arc bounding a patch, in boundary walking order.
     *
     * @param patchId patch to walk
     * @return the arc ids of all four sides, concatenated
     */
    private List<Integer> boundaryArcIds(int patchId) {
        EmbeddedPatch patch = tmesh.patches.get(patchId);
        List<Integer> arcIds = new ArrayList<>();
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            arcIds.addAll(patch.sideArcIds.get(side));
        }
        return arcIds;
    }

    /**
     * The grid position of an arc's two ends inside one patch's own rectangle, keyed to the arc's
     * own orientation so both patches report the same two logical ends.
     *
     * @param patchId patch whose rectangle the arc is read in
     * @param arcId   arc to locate
     * @param atStart receives the position of the arc's {@code startNodeId} end
     * @param atEnd   receives the position of the arc's {@code endNodeId} end
     * @return whether the arc was found on the patch's boundary
     */
    public boolean arcLocalCoordinates(int patchId, int arcId, int[] atStart, int[] atEnd) {
        EmbeddedPatch patch = tmesh.patches.get(patchId);
        int width = tmesh.sideQuadCount(patchId, 0);
        int height = tmesh.sideQuadCount(patchId, 1);
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideArcs = patch.sideArcIds.get(side);
            List<Integer> sideNodes = patch.sideNodeIds.get(side);
            int offset = 0;
            for (int arcIndex = 0; arcIndex < sideArcs.size(); arcIndex++) {
                EmbeddedArc arc = tmesh.arcs.get(sideArcs.get(arcIndex));
                int walkedFrom = offset;
                offset += arc.quadCount;
                if (sideArcs.get(arcIndex) != arcId) {
                    continue;
                }
                boolean forward = sideNodes.get(arcIndex) == arc.startNodeId;
                sidePosition(side, forward ? walkedFrom : offset, width, height, atStart);
                sidePosition(side, forward ? offset : walkedFrom, width, height, atEnd);
                return true;
            }
        }
        return false;
    }

    /**
     * The rectangle corner a side starts at, advanced along that side, matching the corner order
     * {@link PatchRectangleMap} pins the boundary to.
     *
     * @param side   side index in {@code [0, 4)}
     * @param offset quantized distance along the side from its own corner
     * @param width  rectangle width, the extent of sides 0 and 2
     * @param height rectangle height, the extent of sides 1 and 3
     * @param out    receives the position
     */
    private void sidePosition(int side, int offset, int width, int height, int[] out) {
        switch (side) {
            case 0:
                out[0] = offset;
                out[1] = 0;
                break;
            case 1:
                out[0] = width;
                out[1] = offset;
                break;
            case 2:
                out[0] = width - offset;
                out[1] = height;
                break;
            default:
                out[0] = 0;
                out[1] = height - offset;
                break;
        }
    }

    /**
     * Frames an unplaced patch so the arc it shares with a placed one lands on the same two grid
     * positions.
     *
     * @param placedPatchId   patch already carrying a frame
     * @param unplacedPatchId patch to frame
     * @param hereStart       arc's start end in the placed patch's rectangle
     * @param hereEnd         arc's end end in the placed patch's rectangle
     * @param thereStart      arc's start end in the unplaced patch's rectangle
     * @param thereEnd        arc's end end in the unplaced patch's rectangle
     * @return whether a rotation matching the two ends exists
     */
    private boolean placeAcross(int placedPatchId, int unplacedPatchId, int[] hereStart,
            int[] hereEnd, int[] thereStart, int[] thereEnd) {
        int[] globalStart = new int[GRID_COORDINATES];
        int[] globalEnd = new int[GRID_COORDINATES];
        toGrid(placedPatchId, hereStart, globalStart);
        toGrid(placedPatchId, hereEnd, globalEnd);
        int wantedU = globalEnd[0] - globalStart[0];
        int wantedV = globalEnd[1] - globalStart[1];
        int spanU = thereEnd[0] - thereStart[0];
        int spanV = thereEnd[1] - thereStart[1];
        int[] rotated = new int[GRID_COORDINATES];
        for (int quarterTurns = 0; quarterTurns < QUARTER_TURNS; quarterTurns++) {
            rotate(quarterTurns, spanU, spanV, rotated);
            if (rotated[0] != wantedU || rotated[1] != wantedV) {
                continue;
            }
            rotate(quarterTurns, thereStart[0], thereStart[1], rotated);
            quarterTurnsByPatchId[unplacedPatchId] = quarterTurns;
            originUByPatchId[unplacedPatchId] = globalStart[0] - rotated[0];
            originVByPatchId[unplacedPatchId] = globalStart[1] - rotated[1];
            return true;
        }
        brokenArcCount++;
        return false;
    }

    /**
     * Records whether two framed patches place their shared arc on the same grid positions, which
     * is what separates an interior arc from a seam.
     *
     * @param patchId    one of the arc's patches
     * @param neighbour  the other
     * @param hereStart  arc's start end in the first patch's rectangle
     * @param hereEnd    arc's end end in the first patch's rectangle
     * @param thereStart arc's start end in the second patch's rectangle
     * @param thereEnd   arc's end end in the second patch's rectangle
     * @return whether the arc is a seam
     */
    private boolean classify(int patchId, int neighbour, int[] hereStart, int[] hereEnd,
            int[] thereStart, int[] thereEnd) {
        int hereSpan = Math.abs(hereEnd[0] - hereStart[0]) + Math.abs(hereEnd[1] - hereStart[1]);
        int thereSpan = Math.abs(thereEnd[0] - thereStart[0]) + Math.abs(thereEnd[1] - thereStart[1]);
        if (hereSpan != thereSpan) {
            brokenArcCount++;
            return true;
        }
        int[] fromHere = new int[GRID_COORDINATES];
        int[] fromThere = new int[GRID_COORDINATES];
        toGrid(patchId, hereStart, fromHere);
        toGrid(neighbour, thereStart, fromThere);
        boolean sameStart = fromHere[0] == fromThere[0] && fromHere[1] == fromThere[1];
        toGrid(patchId, hereEnd, fromHere);
        toGrid(neighbour, thereEnd, fromThere);
        boolean sameEnd = fromHere[0] == fromThere[0] && fromHere[1] == fromThere[1];
        if (sameStart && sameEnd) {
            interiorArcCount++;
            return false;
        }
        seamArcCount++;
        return true;
    }

    /**
     * The common-grid position of a point given in a patch's own rectangle.
     *
     * @param patchId patch whose frame is applied
     * @param local   position in the patch's rectangle
     * @param out     receives the grid position
     */
    public void toGrid(int patchId, int[] local, int[] out) {
        rotate(quarterTurnsByPatchId[patchId], local[0], local[1], out);
        out[0] += originUByPatchId[patchId];
        out[1] += originVByPatchId[patchId];
    }

    /**
     * The position in a patch's own rectangle of a point given in the common grid, undoing
     * {@link #toGrid}.
     *
     * @param patchId patch whose frame is undone
     * @param gridU   u in the common grid
     * @param gridV   v in the common grid
     * @param out     receives the rectangle position
     */
    public void toLocal(int patchId, double gridU, double gridV, double[] out) {
        int quarterTurns = quarterTurnsByPatchId[patchId];
        int cosine = QUARTER_TURN_COSINE[quarterTurns];
        int sine = QUARTER_TURN_SINE[quarterTurns];
        double shiftedU = gridU - originUByPatchId[patchId];
        double shiftedV = gridV - originVByPatchId[patchId];
        out[0] = cosine * shiftedU + sine * shiftedV;
        out[1] = -sine * shiftedU + cosine * shiftedV;
    }

    /**
     * Rotates a continuous grid vector by a multiple of π/2 about the origin, for the coupled
     * coordinates other stages carry through an arc's transition.
     *
     * @param quarterTurns turns to apply, in {@code [0, 4)}
     * @param u            grid u
     * @param v            grid v
     * @param out          receives the rotated vector
     */
    public static void rotate(int quarterTurns, double u, double v, double[] out) {
        int cosine = QUARTER_TURN_COSINE[quarterTurns];
        int sine = QUARTER_TURN_SINE[quarterTurns];
        out[0] = cosine * u - sine * v;
        out[1] = sine * u + cosine * v;
    }

    /**
     * Rotates a grid vector by a multiple of π/2 about the origin.
     *
     * @param quarterTurns turns to apply, in {@code [0, 4)}
     * @param u            grid u
     * @param v            grid v
     * @param out          receives the rotated vector
     */
    private void rotate(int quarterTurns, int u, int v, int[] out) {
        int cosine = QUARTER_TURN_COSINE[quarterTurns];
        int sine = QUARTER_TURN_SINE[quarterTurns];
        out[0] = cosine * u - sine * v;
        out[1] = sine * u + cosine * v;
    }

    /**
     * The common-grid position of a point given in a patch's own rectangle, for the continuous
     * coordinates the map solves in rather than the layout's integers.
     *
     * @param patchId patch whose frame is applied
     * @param localU  u in the patch's rectangle
     * @param localV  v in the patch's rectangle
     * @param out     receives the grid position
     */
    public void toGrid(int patchId, double localU, double localV, double[] out) {
        int quarterTurns = quarterTurnsByPatchId[patchId];
        int cosine = QUARTER_TURN_COSINE[quarterTurns];
        int sine = QUARTER_TURN_SINE[quarterTurns];
        out[0] = cosine * localU - sine * localV + originUByPatchId[patchId];
        out[1] = sine * localU + cosine * localV + originVByPatchId[patchId];
    }
}
