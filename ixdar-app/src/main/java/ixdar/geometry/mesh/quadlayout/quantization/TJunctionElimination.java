package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshPatch;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceArc;

/**
 * Lyon 2021 §6 second half: turn the quantized T-mesh into a conforming quad
 * layout by iteratively extending every remaining T-junction across its patch —
 * connecting it to an opposing vertex when the quantized offsets match,
 * otherwise splitting the opposite side and continuing into the next patch —
 * until no T-junctions remain (LCBK19 §6). Operates purely combinatorially on
 * the rectangle complex formed by the positive-area valid patches; inserted
 * edges have no traced geometry yet, so the final patch count
 * {@link #finalPatchCount} is the product, not a render.
 */
public final class TJunctionElimination {

    /** Hard cap multiplier on total extension steps, a runaway backstop. */
    public static final int MAX_STEP_MULTIPLIER = 10;

    public final LayoutExtraction layout;
    public final QuantizedMeshGrid quantization;
    public final MotorcycleGraph motorcycleGraph;

    /** Rectangles (live and split) of the quantized complex. */
    public final List<LayoutRectangle> rectangles = new ArrayList<>();

    /** Positive-area cells before any T-junction extension. */
    public int initialRectangleCount;

    /** Positive-area cells after all extensions: the layout's #P. */
    public int finalPatchCount;

    /** Extensions resolved by an existing opposing vertex. */
    public int connectCount;

    /** Opposite-side splits that continued the extension into a neighbor. */
    public int continuationSplitCount;

    /** Extension chains dropped at an unmapped or synthetic boundary. */
    public int abortedExtensionCount;

    /** Valid patches whose opposite quantized side sums disagreed (skipped). */
    public int inconsistentPatchCount;

    /** Valid patches with exactly one zero quantized dimension (not rendered). */
    public int portalCount;

    /** Valid patches with both quantized dimensions zero (dropped entirely). */
    public int collapsedPatchCount;

    /** T-junction vertices still unresolved after the pass. */
    public int remainingTJunctionCount;

    private final Map<Integer, List<Integer>> patchIdsByArc = new HashMap<>();
    private final Map<Integer, LayoutRectangle> portalByPatchId = new HashMap<>();
    private final Map<Integer, List<LayoutRectangle>> liveByRootPatch = new HashMap<>();
    private final List<Integer> degreeByCluster = new ArrayList<>();
    private int nextRectangleId;
    private int nextSyntheticCluster;

    /**
     * Stores inputs for a §6 T-junction elimination over a collapsed layout.
     *
     * @param layout zero-arc collapse products of the solved quantization
     */
    public TJunctionElimination(LayoutExtraction layout) {
        this.layout = layout;
        this.quantization = layout.quantization;
        this.motorcycleGraph = layout.motorcycleGraph;
    }

    /**
     * Build the rectangle complex, run extension chains until no T-junction vertex
     * remains (or a chain aborts at an unmapped region), and count the final
     * patches.
     *
     * @return this, with all public products populated
     */
    public TJunctionElimination build() {
        for (int cluster = 0; cluster < layout.clusterCount; cluster++) {
            degreeByCluster.add(0);
        }
        for (TraceArc arc : motorcycleGraph.arcs) {
            if (quantization.quantizedLengthByArc[arc.arcId] > 0) {
                int startCluster = layout.clusterByNode[arc.startNodeId];
                int endCluster = layout.clusterByNode[arc.endNodeId];
                degreeByCluster.set(startCluster, degreeByCluster.get(startCluster) + 1);
                degreeByCluster.set(endCluster, degreeByCluster.get(endCluster) + 1);
            }
        }
        nextSyntheticCluster = layout.clusterCount;

        for (TMeshPatch patch : motorcycleGraph.patches) {
            if (patch.validRectangle) {
                buildCell(patch);
            }
        }
        initialRectangleCount = countLive();

        int stepCap = MAX_STEP_MULTIPLIER * Math.max(1, initialRectangleCount);
        int steps = 0;
        while (steps < stepCap) {
            int[] tJunction = null;
            for (int index = 0; index < rectangles.size(); index++) {
                LayoutRectangle cell = rectangles.get(index);
                if (!cell.alive) {
                    continue;
                }
                for (int side = 0; side < LayoutRectangle.SIDES; side++) {
                    List<LayoutSideSegment> segments = cell.sideSegments.get(side);
                    List<Integer> boundaries = cell.boundaryClusters.get(side);
                    int offset = 0;
                    for (int boundary = 1; boundary < boundaries.size() - 1; boundary++) {
                        offset += segments.get(boundary - 1).quantizedLength();
                        if (degreeByCluster.get(boundaries.get(boundary)) == 3) {
                            tJunction = new int[] { index, side, offset };
                            side = LayoutRectangle.SIDES;
                            index = rectangles.size();
                            break;
                        }
                    }
                }
            }
            if (tJunction == null) {
                break;
            }
            steps += runExtensionChain(rectangles.get(tJunction[0]), tJunction[1], tJunction[2],
                    stepCap - steps);
        }

        finalPatchCount = countLive();
        remainingTJunctionCount = countRemainingTJunctions();
        System.out.printf(
                "[conform] rectangles=%d -> #P=%d connects=%d continuations=%d aborted=%d"
                        + " inconsistent=%d portals=%d collapsed=%d tJunctionsLeft=%d%n",
                initialRectangleCount, finalPatchCount, connectCount, continuationSplitCount,
                abortedExtensionCount, inconsistentPatchCount, portalCount, collapsedPatchCount,
                remainingTJunctionCount);
        return this;
    }

    /**
     * Convert one valid T-mesh patch into a rectangle (positive area), a portal
     * (exactly one positive dimension — chains pass straight through), or nothing
     * (fully collapsed).
     *
     * @param patch valid four-sided arrangement patch
     */
    private void buildCell(TMeshPatch patch) {
        int[] sideSums = new int[LayoutRectangle.SIDES];
        for (int side = 0; side < LayoutRectangle.SIDES; side++) {
            for (int arcId : patch.sides.get(side)) {
                sideSums[side] += quantization.quantizedLengthByArc[arcId];
            }
        }
        if (sideSums[0] != sideSums[2] || sideSums[1] != sideSums[3]) {
            inconsistentPatchCount++;
            return;
        }
        int width = sideSums[0];
        int height = sideSums[1];
        if (width == 0 && height == 0) {
            collapsedPatchCount++;
            return;
        }
        List<Integer> cycleArcIds = new ArrayList<>();
        for (int side = 0; side < LayoutRectangle.SIDES; side++) {
            cycleArcIds.addAll(patch.sides.get(side));
        }
        List<Integer> cycleNodeIds = chainCycleNodes(cycleArcIds);
        if (cycleNodeIds == null) {
            inconsistentPatchCount++;
            return;
        }

        LayoutRectangle cell = new LayoutRectangle(nextRectangleId++, patch.patchId, width, height);
        int cyclePosition = 0;
        for (int side = 0; side < LayoutRectangle.SIDES; side++) {
            List<Integer> sideArcIds = patch.sides.get(side);
            boolean reversed = side >= 2;
            List<LayoutSideSegment> segments = cell.sideSegments.get(side);
            List<Integer> boundaries = cell.boundaryClusters.get(side);
            int firstNodePosition = reversed ? cyclePosition + sideArcIds.size() : cyclePosition;
            boundaries.add(layout.clusterByNode[cycleNodeIds.get(firstNodePosition)]);
            for (int index = 0; index < sideArcIds.size(); index++) {
                int arcPosition = reversed ? sideArcIds.size() - 1 - index : index;
                int arcId = sideArcIds.get(arcPosition);
                int quantized = quantization.quantizedLengthByArc[arcId];
                if (quantized == 0) {
                    continue;
                }
                int fromNodeId = cycleNodeIds.get(cyclePosition + arcPosition + (reversed ? 1 : 0));
                TraceArc arc = motorcycleGraph.arcs.get(arcId);
                boolean forward = arc.startNodeId == fromNodeId;
                segments.add(new LayoutSideSegment(arcId, 0, quantized, forward));
                int toNodeId = cycleNodeIds.get(cyclePosition + arcPosition + (reversed ? 0 : 1));
                boundaries.add(layout.clusterByNode[toNodeId]);
            }
            cyclePosition += sideArcIds.size();
        }

        if (width > 0 && height > 0) {
            rectangles.add(cell);
            liveByRootPatch.computeIfAbsent(patch.patchId, patchId -> new ArrayList<>()).add(cell);
        } else {
            portalCount++;
            portalByPatchId.put(patch.patchId, cell);
        }
        for (int side = 0; side < LayoutRectangle.SIDES; side++) {
            for (LayoutSideSegment segment : cell.sideSegments.get(side)) {
                patchIdsByArc.computeIfAbsent(segment.arcId, arcId -> new ArrayList<>())
                        .add(patch.patchId);
            }
        }
    }

    /**
     * Reconstruct the node sequence around a patch cycle from its undirected arc id
     * list by chaining shared endpoints; entry {@code i} is the node before arc
     * {@code i}, with one extra closing entry equal to the first.
     *
     * @param cycleArcIds boundary arcs in cycle order
     * @return node ids of size {@code cycleArcIds.size() + 1}, or {@code null} when
     *         the arcs do not chain
     */
    private List<Integer> chainCycleNodes(List<Integer> cycleArcIds) {
        if (cycleArcIds.size() < 2) {
            return null;
        }
        TraceArc firstArc = motorcycleGraph.arcs.get(cycleArcIds.get(0));
        TraceArc secondArc = motorcycleGraph.arcs.get(cycleArcIds.get(1));
        int sharedNodeId;
        if (firstArc.endNodeId == secondArc.startNodeId || firstArc.endNodeId == secondArc.endNodeId) {
            sharedNodeId = firstArc.endNodeId;
        } else if (firstArc.startNodeId == secondArc.startNodeId
                || firstArc.startNodeId == secondArc.endNodeId) {
            sharedNodeId = firstArc.startNodeId;
        } else {
            return null;
        }
        List<Integer> nodeIds = new ArrayList<>();
        nodeIds.add(firstArc.startNodeId == sharedNodeId ? firstArc.endNodeId : firstArc.startNodeId);
        nodeIds.add(sharedNodeId);
        for (int position = 1; position < cycleArcIds.size(); position++) {
            TraceArc arc = motorcycleGraph.arcs.get(cycleArcIds.get(position));
            int fromNodeId = nodeIds.get(nodeIds.size() - 1);
            if (arc.startNodeId == fromNodeId) {
                nodeIds.add(arc.endNodeId);
            } else if (arc.endNodeId == fromNodeId) {
                nodeIds.add(arc.startNodeId);
            } else {
                return null;
            }
        }
        return nodeIds;
    }

    /**
     * Count interior side boundaries with degree-3 clusters over live rectangles
     * (the honest leftover count when chains aborted).
     *
     * @return remaining T-junction vertices
     */
    private int countRemainingTJunctions() {
        int remaining = 0;
        for (LayoutRectangle cell : rectangles) {
            if (!cell.alive) {
                continue;
            }
            for (int side = 0; side < LayoutRectangle.SIDES; side++) {
                List<Integer> boundaries = cell.boundaryClusters.get(side);
                for (int boundary = 1; boundary < boundaries.size() - 1; boundary++) {
                    if (degreeByCluster.get(boundaries.get(boundary)) == 3) {
                        remaining++;
                    }
                }
            }
        }
        return remaining;
    }

    private int countLive() {
        int live = 0;
        for (LayoutRectangle cell : rectangles) {
            if (cell.alive) {
                live++;
            }
        }
        return live;
    }

    /**
     * Run one extension chain from a T-junction vertex: cross the current rectangle
     * to its opposite side, connect when a vertex already sits at the same
     * quantized offset, otherwise split the opposite side and continue into the
     * neighboring rectangle, passing straight through zero-thickness portal cells.
     *
     * @param startRectangle rectangle the T-junction's missing edge enters
     * @param startSide      side carrying the T-junction vertex
     * @param startOffset    canonical quantized offset of the vertex
     * @param stepBudget     remaining global step budget
     * @return number of steps consumed
     */
    private int runExtensionChain(LayoutRectangle startRectangle, int startSide, int startOffset,
            int stepBudget) {
        LayoutRectangle current = startRectangle;
        int entrySide = startSide;
        int entryOffset = startOffset;
        int entryCluster = boundaryClusterAt(current, entrySide, entryOffset);
        int steps = 0;
        while (steps < stepBudget) {
            steps++;
            int exitSide = (entrySide + 2) % LayoutRectangle.SIDES;
            int[] exitBoundary = boundaryIndexAt(current, exitSide, entryOffset);
            if (exitBoundary != null) {
                int exitCluster = current.boundaryClusters.get(exitSide).get(exitBoundary[0]);
                splitForChain(current, entrySide, entryOffset, entryCluster, exitCluster);
                degreeByCluster.set(entryCluster, degreeByCluster.get(entryCluster) + 1);
                degreeByCluster.set(exitCluster, degreeByCluster.get(exitCluster) + 1);
                connectCount++;
                return steps;
            }
            LayoutSideSegment exitSegment = segmentAt(current, exitSide, entryOffset);
            if (exitSegment == null || exitSegment.arcId < 0) {
                abortedExtensionCount++;
                return steps;
            }
            int withinSegment = entryOffset - offsetOfSegmentStart(current, exitSide, exitSegment);
            int intrinsic = exitSegment.forward
                    ? exitSegment.arcStart + withinSegment
                    : exitSegment.arcEnd - withinSegment;
            int[] neighbor = resolveNeighbor(exitSegment.arcId, intrinsic, current.rootPatchId);
            if (neighbor == null) {
                abortedExtensionCount++;
                return steps;
            }
            LayoutRectangle next = rectangles.get(neighbor[0]);
            if (neighbor[3] >= 0) {
                // The neighbor already has a vertex at this exact offset: use
                // its cluster as the edge endpoint and finish — its T points
                // back at us and this edge resolves it.
                int exitCluster = neighbor[3];
                splitForChain(current, entrySide, entryOffset, entryCluster, exitCluster);
                degreeByCluster.set(entryCluster, degreeByCluster.get(entryCluster) + 1);
                degreeByCluster.set(exitCluster, degreeByCluster.get(exitCluster) + 1);
                connectCount++;
                return steps;
            }
            int newCluster = nextSyntheticCluster++;
            degreeByCluster.add(2);
            splitForChain(current, entrySide, entryOffset, entryCluster, newCluster);

            degreeByCluster.set(entryCluster, degreeByCluster.get(entryCluster) + 1);
            degreeByCluster.set(newCluster, degreeByCluster.get(newCluster) + 1);
            continuationSplitCount++;
            current = next;
            entrySide = neighbor[1];
            entryOffset = neighbor[2];
            entryCluster = newCluster;
        }
        abortedExtensionCount++;
        return steps;
    }

    /**
     * Resolve which live rectangle lies on the far side of an arc at a given
     * intrinsic position, passing straight through portal cells.
     *
     * @param arcId          crossed arc id
     * @param intrinsic      quantized position in the arc's own coordinates
     * @param excludePatchId root patch id of the side the chain comes from
     * @return {rectangle index, side, canonical offset, existing vertex cluster or
     *         -1}, or {@code null} when unmapped
     */
    private int[] resolveNeighbor(int arcId, int intrinsic, int excludePatchId) {
        int currentArcId = arcId;
        int currentIntrinsic = intrinsic;
        int fromPatchId = excludePatchId;
        for (int hop = 0; hop <= portalByPatchId.size(); hop++) {
            int targetPatchId = otherPatchOfArc(currentArcId, fromPatchId);
            if (targetPatchId < 0) {
                return null;
            }
            LayoutRectangle portal = portalByPatchId.get(targetPatchId);
            if (portal == null) {
                return locateInLiveRectangles(targetPatchId, currentArcId, currentIntrinsic);
            }
            int[] across = portalAcross(portal, currentArcId, currentIntrinsic);
            if (across == null) {
                return null;
            }
            currentArcId = across[0];
            currentIntrinsic = across[1];
            fromPatchId = targetPatchId;
        }
        return null;
    }

    /**
     * The other valid patch bounded by an arc.
     *
     * @param arcId       arc id
     * @param fromPatchId patch the chain currently leaves
     * @return the opposite patch id, or -1 when none is mapped
     */
    private int otherPatchOfArc(int arcId, int fromPatchId) {
        List<Integer> patchIds = patchIdsByArc.get(arcId);
        if (patchIds == null) {
            return -1;
        }
        for (int patchId : patchIds) {
            if (patchId != fromPatchId) {
                return patchId;
            }
        }
        return -1;
    }

    /**
     * Carry a crossing straight through a zero-thickness portal cell: find the
     * entry arc on one positive side, translate to the same canonical offset on the
     * opposite positive side, and report the arc and intrinsic position there.
     *
     * @param portal    portal cell (exactly one positive dimension)
     * @param arcId     arc the chain enters through
     * @param intrinsic quantized position in the entry arc's coordinates
     * @return {exit arc id, exit intrinsic position}, or {@code null} when the
     *         offset lands on a portal vertex or an unmapped spot
     */
    private int[] portalAcross(LayoutRectangle portal, int arcId, int intrinsic) {
        int firstPositiveSide = portal.width > 0 ? 0 : 1;
        for (int side : new int[] { firstPositiveSide, firstPositiveSide + 2 }) {
            int offset = 0;
            for (LayoutSideSegment segment : portal.sideSegments.get(side)) {
                if (segment.arcId == arcId
                        && intrinsic > segment.arcStart && intrinsic < segment.arcEnd) {
                    int canonicalOffset = offset + (segment.forward
                            ? intrinsic - segment.arcStart
                            : segment.arcEnd - intrinsic);
                    int oppositeSide = (side + 2) % LayoutRectangle.SIDES;
                    LayoutSideSegment exit = segmentAt(portal, oppositeSide, canonicalOffset);
                    if (exit == null || exit.arcId < 0) {
                        return null;
                    }
                    int within = canonicalOffset
                            - offsetOfSegmentStart(portal, oppositeSide, exit);
                    int exitIntrinsic = exit.forward
                            ? exit.arcStart + within
                            : exit.arcEnd - within;
                    return new int[] { exit.arcId, exitIntrinsic };
                }
                offset += segment.quantizedLength();
            }
        }
        return null;
    }

    /**
     * Find the live rectangle of a root patch whose side contains an arc at the
     * given intrinsic position.
     *
     * @param rootPatchId root patch to search
     * @param arcId       crossed arc id
     * @param intrinsic   quantized position in the arc's own coordinates
     * @return {rectangle index, side, canonical offset, existing vertex cluster or
     *         -1}, or {@code null} when unmapped
     */
    private int[] locateInLiveRectangles(int rootPatchId, int arcId, int intrinsic) {
        List<LayoutRectangle> candidates = liveByRootPatch.get(rootPatchId);
        if (candidates == null) {
            return null;
        }
        for (LayoutRectangle cell : candidates) {
            if (!cell.alive) {
                continue;
            }
            for (int side = 0; side < LayoutRectangle.SIDES; side++) {
                int offset = 0;
                List<LayoutSideSegment> segments = cell.sideSegments.get(side);
                List<Integer> boundaries = cell.boundaryClusters.get(side);
                for (int index = 0; index < segments.size(); index++) {
                    LayoutSideSegment segment = segments.get(index);
                    if (segment.arcId == arcId) {
                        if (intrinsic > segment.arcStart && intrinsic < segment.arcEnd) {
                            int canonicalOffset = offset + (segment.forward
                                    ? intrinsic - segment.arcStart
                                    : segment.arcEnd - intrinsic);
                            return new int[] {
                                    rectangles.indexOf(cell), side, canonicalOffset, -1 };
                        }
                        boolean atSegmentEnd = segment.forward
                                ? intrinsic == segment.arcEnd
                                : intrinsic == segment.arcStart;
                        if (atSegmentEnd && index < segments.size() - 1) {
                            int canonicalOffset = offset + segment.quantizedLength();
                            return new int[] { rectangles.indexOf(cell), side, canonicalOffset,
                                    boundaries.get(index + 1) };
                        }
                    }
                    offset += segment.quantizedLength();
                }
            }
        }
        return null;
    }

    /**
     * Cluster of the boundary sitting exactly at a canonical offset on a side.
     *
     * @param cell   rectangle
     * @param side   side index
     * @param offset canonical offset; must hit a boundary
     * @return cluster id at that boundary
     */
    private int boundaryClusterAt(LayoutRectangle cell, int side, int offset) {
        int[] boundary = boundaryIndexAt(cell, side, offset);
        return boundary == null ? -1 : cell.boundaryClusters.get(side).get(boundary[0]);
    }

    /**
     * Boundary index at an exact canonical offset, interior only.
     *
     * @param cell   rectangle
     * @param side   side index
     * @param offset canonical offset
     * @return {boundary index} or {@code null} when the offset is inside a segment
     *         or at a corner
     */
    private int[] boundaryIndexAt(LayoutRectangle cell, int side, int offset) {
        List<LayoutSideSegment> segments = cell.sideSegments.get(side);
        int accumulated = 0;
        for (int index = 0; index < segments.size(); index++) {
            if (accumulated == offset && index > 0) {
                return new int[] { index };
            }
            accumulated += segments.get(index).quantizedLength();
        }
        return null;
    }

    /**
     * Segment containing a canonical offset strictly in its interior.
     *
     * @param cell   rectangle or portal
     * @param side   side index
     * @param offset canonical offset
     * @return containing segment, or {@code null} when the offset hits a boundary
     *         or exceeds the side
     */
    private LayoutSideSegment segmentAt(LayoutRectangle cell, int side, int offset) {
        int accumulated = 0;
        for (LayoutSideSegment segment : cell.sideSegments.get(side)) {
            if (offset > accumulated && offset < accumulated + segment.quantizedLength()) {
                return segment;
            }
            accumulated += segment.quantizedLength();
        }
        return null;
    }

    /**
     * Canonical offset of a segment's start within its side.
     *
     * @param cell    rectangle or portal
     * @param side    side index
     * @param segment segment on that side
     * @return canonical start offset
     */
    private int offsetOfSegmentStart(LayoutRectangle cell, int side, LayoutSideSegment segment) {
        int accumulated = 0;
        for (LayoutSideSegment candidate : cell.sideSegments.get(side)) {
            if (candidate == segment) {
                return accumulated;
            }
            accumulated += candidate.quantizedLength();
        }
        return accumulated;
    }

    /**
     * Split a rectangle along the inserted edge crossing it: a vertical cut at a
     * column when the chain entered through side 0 or 2, a horizontal cut at a row
     * otherwise. The two cut clusters are assigned to the entry and exit side as
     * appropriate.
     *
     * @param cell         rectangle to split
     * @param entrySide    side the chain entered through
     * @param offset       canonical offset of the cut
     * @param entryCluster edge endpoint cluster on the entry side
     * @param exitCluster  edge endpoint cluster on the exit side
     */
    private void splitForChain(LayoutRectangle cell, int entrySide, int offset,
            int entryCluster, int exitCluster) {
        if (entrySide % 2 == 0) {
            int side0Cluster = entrySide == 0 ? entryCluster : exitCluster;
            int side2Cluster = entrySide == 0 ? exitCluster : entryCluster;
            splitCell(cell, 0, offset, side0Cluster, side2Cluster);
        } else {
            int side1Cluster = entrySide == 1 ? entryCluster : exitCluster;
            int side3Cluster = entrySide == 1 ? exitCluster : entryCluster;
            splitCell(cell, 1, offset, side1Cluster, side3Cluster);
        }
    }

    /**
     * Split a rectangle into two halves at a canonical offset measured along the
     * given axis side (0 for a vertical cut at a column, 1 for a horizontal cut at
     * a row). The half containing the axis side's canonical start keeps the uncut
     * perpendicular side at offset zero; the inserted edge becomes a synthetic side
     * of both halves.
     *
     * @param cell            rectangle to split
     * @param axisSide        0 to cut columns, 1 to cut rows
     * @param offset          canonical cut offset along the axis side
     * @param axisCluster     cut cluster on the axis side
     * @param oppositeCluster cut cluster on the axis side's opposite
     */
    private void splitCell(LayoutRectangle cell, int axisSide, int offset,
            int axisCluster, int oppositeCluster) {
        int oppositeSide = axisSide + 2;
        int perpendicularLow = axisSide == 0 ? 3 : 0;
        int perpendicularHigh = axisSide == 0 ? 1 : 2;
        int axisExtent = cell.sideExtent(axisSide);
        int perpendicularExtent = cell.sideExtent(perpendicularLow);
        if (offset <= 0 || offset >= axisExtent) {
            throw new IllegalStateException("degenerate split at offset " + offset
                    + " of extent " + axisExtent);
        }

        LayoutRectangle low = new LayoutRectangle(nextRectangleId++, cell.rootPatchId,
                axisSide == 0 ? offset : cell.width,
                axisSide == 0 ? cell.height : offset);
        LayoutRectangle high = new LayoutRectangle(nextRectangleId++, cell.rootPatchId,
                axisSide == 0 ? cell.width - offset : cell.width,
                axisSide == 0 ? cell.height : cell.height - offset);

        cutSideInto(cell, axisSide, offset, axisCluster, low, high);
        cutSideInto(cell, oppositeSide, offset, oppositeCluster, low, high);

        low.sideSegments.get(perpendicularLow).addAll(cell.sideSegments.get(perpendicularLow));
        low.boundaryClusters.get(perpendicularLow).addAll(cell.boundaryClusters.get(perpendicularLow));
        high.sideSegments.get(perpendicularHigh).addAll(cell.sideSegments.get(perpendicularHigh));
        high.boundaryClusters.get(perpendicularHigh).addAll(cell.boundaryClusters.get(perpendicularHigh));

        // Both perpendicular sides run canonically from the side-3/side-0
        // column-or-row toward side-1/side-2: a column cut's inserted edge
        // starts at the side-0 cut cluster, a row cut's at the side-3 one.
        int insertedFirstCluster = axisSide == 0 ? axisCluster : oppositeCluster;
        int insertedSecondCluster = axisSide == 0 ? oppositeCluster : axisCluster;
        LayoutSideSegment insertedLow = new LayoutSideSegment(-1, 0, perpendicularExtent, true);
        LayoutSideSegment insertedHigh = new LayoutSideSegment(-1, 0, perpendicularExtent, true);
        low.sideSegments.get(perpendicularHigh).add(insertedLow);
        low.boundaryClusters.get(perpendicularHigh).add(insertedFirstCluster);
        low.boundaryClusters.get(perpendicularHigh).add(insertedSecondCluster);
        high.sideSegments.get(perpendicularLow).add(insertedHigh);
        high.boundaryClusters.get(perpendicularLow).add(insertedFirstCluster);
        high.boundaryClusters.get(perpendicularLow).add(insertedSecondCluster);

        cell.alive = false;
        rectangles.add(low);
        rectangles.add(high);
        List<LayoutRectangle> siblings = liveByRootPatch.get(cell.rootPatchId);
        siblings.remove(cell);
        siblings.add(low);
        siblings.add(high);
    }

    /**
     * Distribute one axis-parallel side of a split rectangle onto the two halves,
     * cutting the segment containing the offset (or splitting the boundary lists at
     * an existing boundary).
     *
     * @param cell       rectangle being split
     * @param side       axis-parallel side to distribute
     * @param offset     canonical cut offset
     * @param cutCluster cluster at the cut on this side
     * @param low        half keeping offsets below the cut
     * @param high       half keeping offsets above the cut
     */
    private void cutSideInto(LayoutRectangle cell, int side, int offset, int cutCluster,
            LayoutRectangle low, LayoutRectangle high) {
        List<LayoutSideSegment> segments = cell.sideSegments.get(side);
        List<Integer> boundaries = cell.boundaryClusters.get(side);
        List<LayoutSideSegment> lowSegments = low.sideSegments.get(side);
        List<Integer> lowBoundaries = low.boundaryClusters.get(side);
        List<LayoutSideSegment> highSegments = high.sideSegments.get(side);
        List<Integer> highBoundaries = high.boundaryClusters.get(side);

        lowBoundaries.add(boundaries.get(0));
        int accumulated = 0;
        boolean cutDone = false;
        for (int index = 0; index < segments.size(); index++) {
            LayoutSideSegment segment = segments.get(index);
            int segmentEnd = accumulated + segment.quantizedLength();
            if (!cutDone && offset > accumulated && offset < segmentEnd) {
                int within = offset - accumulated;
                if (segment.forward) {
                    lowSegments.add(new LayoutSideSegment(segment.arcId, segment.arcStart,
                            segment.arcStart + within, true));
                    highSegments.add(new LayoutSideSegment(segment.arcId,
                            segment.arcStart + within, segment.arcEnd, true));
                } else {
                    lowSegments.add(new LayoutSideSegment(segment.arcId,
                            segment.arcEnd - within, segment.arcEnd, false));
                    highSegments.add(new LayoutSideSegment(segment.arcId, segment.arcStart,
                            segment.arcEnd - within, false));
                }
                lowBoundaries.add(cutCluster);
                highBoundaries.add(cutCluster);
                highBoundaries.add(boundaries.get(index + 1));
                cutDone = true;
            } else if (segmentEnd <= offset && !cutDone) {
                lowSegments.add(segment);
                lowBoundaries.add(boundaries.get(index + 1));
                if (segmentEnd == offset) {
                    highBoundaries.add(cutCluster);
                    cutDone = true;
                }
            } else {
                highSegments.add(segment);
                highBoundaries.add(boundaries.get(index + 1));
            }
            accumulated = segmentEnd;
        }
    }
}
