package ixdar.geometry.mesh.quadlayout.gridmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.ExactBarycentricOrient;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;

/**
 * Makes the relaxed grid map numerically consistent and proves the extraction's
 * preconditions: shared chart copies exactly related by their arc transitions,
 * a transition resolved for every crossable arc, pinned nodes on exact
 * integers, and every chart triangle matching its patch's orientation.
 *
 * <p>
 * See also: EBC13 Algorithm 1
 */
public final class GridMapVerification {

    /** Residual within which a recovered transition is accepted before exact rounding. */
    public static final double TRANSITION_TOLERANCE = 1.0e-6;

    public final GlobalGridMap gridMap;
    public final EmbeddedTMesh tmesh;
    public final IntegerGridMap frames;
    public final LayoutPatchMaps patchMaps;

    /**
     * Quarter turns of the resolved transition per arc, carrying the right patch's
     * chart onto the left's; {@link IntegerGridMap#NOT_PLACED} for an arc the
     * tracer may not cross.
     */
    public int[] transitionTurnsByArcId;

    /** Grid u translation of the resolved transition, paired with the turns. */
    public int[] transitionTranslationUByArcId;

    /** Grid v translation of the resolved transition, paired with the turns. */
    public int[] transitionTranslationVByArcId;

    /** Whether each live patch's chart winds counter-clockwise in stored corner order. */
    public boolean[] counterClockwiseByPatch;

    /** Arcs whose transition the frames left unsolved and this pass recovered. */
    public int recoveredLoopTransitionCount;

    /** Shared vertices whose chart copies were truncated and rewritten exactly. */
    public int truncatedVertexCount;

    /** Live nodes whose canonical chart position is exactly integer. */
    public int integerNodeCount;

    /** Chart triangles whose orientation was proven exactly. */
    public int verifiedTriangleCount;

    /** Arc path vertices re-checked bitwise through their transition. */
    public int verifiedPathVertexCount;

    /**
     * Stores the relaxed grid map to canonicalize and verify.
     *
     * @param gridMap the patch maps carried into one common grid, after relaxation
     */
    public GridMapVerification(GlobalGridMap gridMap) {
        this.gridMap = gridMap;
        this.tmesh = gridMap.tmesh;
        this.frames = gridMap.frames;
        this.patchMaps = gridMap.patchMaps;
    }

    /**
     * Resolves transitions, canonicalizes every shared vertex's chart copies, and
     * proves the extraction preconditions, throwing on the first violation.
     *
     * @throws IllegalStateException when a transition cannot be resolved, chart
     *                               copies disagree, a pinned node is off-integer,
     *                               or a chart triangle is degenerate or inverted
     * @return this, verified
     */
    public GridMapVerification build() {
        resolveTransitions();
        canonicalizeArcInteriors();
        canonicalizeNodes();
        requireOrientation();
        requireArcConsistency();
        System.out.printf("[grid-verify] recoveredLoops=%d truncatedVertices=%d integerNodes=%d"
                + " pathVertices=%d triangles=%d%n", recoveredLoopTransitionCount,
                truncatedVertexCount, integerNodeCount, verifiedPathVertexCount,
                verifiedTriangleCount);
        return this;
    }

    /**
     * Whether an arc separates two distinct live patches, which is what the tracer
     * may cross.
     *
     * @param arc arc to test
     * @return whether a transition must exist for it
     */
    private boolean crossable(EmbeddedArc arc) {
        return arc.alive && arc.leftPatchId != EmbeddedTMesh.NONE
                && arc.rightPatchId != EmbeddedTMesh.NONE
                && tmesh.patches.get(arc.leftPatchId).alive
                && tmesh.patches.get(arc.rightPatchId).alive;
    }

    /**
     * Copies each crossable arc's transition from the frames, deriving it from the
     * arc's own path where the frames' endpoint spans were zero (loop arcs).
     *
     * @throws IllegalStateException when an arc is self-adjacent or its transition
     *                               cannot be derived unambiguously
     */
    private void resolveTransitions() {
        int arcCount = tmesh.arcs.size();
        transitionTurnsByArcId = new int[arcCount];
        transitionTranslationUByArcId = new int[arcCount];
        transitionTranslationVByArcId = new int[arcCount];
        Arrays.fill(transitionTurnsByArcId, IntegerGridMap.NOT_PLACED);
        for (EmbeddedArc arc : tmesh.arcs) {
            if (!crossable(arc)) {
                continue;
            }
            if (arc.leftPatchId == arc.rightPatchId) {
                throw new IllegalStateException("arc " + arc.arcId + " is self-adjacent on patch "
                        + arc.leftPatchId + ", which the region boundary walk forbids");
            }
            if (frames.transitionQuarterTurnsByArcId[arc.arcId] != IntegerGridMap.NOT_PLACED) {
                transitionTurnsByArcId[arc.arcId] =
                        frames.transitionQuarterTurnsByArcId[arc.arcId];
                transitionTranslationUByArcId[arc.arcId] =
                        frames.transitionTranslationUByArcId[arc.arcId];
                transitionTranslationVByArcId[arc.arcId] =
                        frames.transitionTranslationVByArcId[arc.arcId];
                continue;
            }
            recoverTransition(arc);
        }
    }

    /**
     * Derives an arc's transition from its path vertices' positions in both
     * charts: the unique quarter turn whose induced integer translation carries
     * every right-chart copy onto its left-chart copy within tolerance.
     *
     * @param arc crossable arc whose frames transition is unsolved
     * @throws IllegalStateException when no quarter turn fits or two do
     */
    private void recoverTransition(EmbeddedArc arc) {
        List<Integer> path = arc.path.copyVertexPath;
        double[] rotated = new double[GlobalGridMap.GRID_COORDINATES];
        int chosenTurns = IntegerGridMap.NOT_PLACED;
        for (int turns = 0; turns < IntegerGridMap.QUARTER_TURNS; turns++) {
            double translationU = 0.0;
            double translationV = 0.0;
            double worstResidual = Double.POSITIVE_INFINITY;
            for (int step = 0; step < path.size(); step++) {
                int copyVertex = path.get(step);
                double leftU = chartCoordinate(arc.leftPatchId, copyVertex, 0);
                double leftV = chartCoordinate(arc.leftPatchId, copyVertex, 1);
                IntegerGridMap.rotate(turns, chartCoordinate(arc.rightPatchId, copyVertex, 0),
                        chartCoordinate(arc.rightPatchId, copyVertex, 1), rotated);
                if (step == 0) {
                    translationU = leftU - rotated[0];
                    translationV = leftV - rotated[1];
                    worstResidual = Math.max(
                            Math.abs(translationU - Math.rint(translationU)),
                            Math.abs(translationV - Math.rint(translationV)));
                    continue;
                }
                worstResidual = Math.max(worstResidual, Math.max(
                        Math.abs(rotated[0] + translationU - leftU),
                        Math.abs(rotated[1] + translationV - leftV)));
            }
            if (worstResidual < TRANSITION_TOLERANCE) {
                if (chosenTurns != IntegerGridMap.NOT_PLACED) {
                    throw new IllegalStateException("arc " + arc.arcId + " admits two transitions,"
                            + " quarter turns " + chosenTurns + " and " + turns
                            + "; its path cannot disambiguate them");
                }
                chosenTurns = turns;
                transitionTurnsByArcId[arc.arcId] = turns;
                transitionTranslationUByArcId[arc.arcId] = (int) Math.rint(translationU);
                transitionTranslationVByArcId[arc.arcId] = (int) Math.rint(translationV);
            }
        }
        if (chosenTurns == IntegerGridMap.NOT_PLACED) {
            throw new IllegalStateException("arc " + arc.arcId + " between patches "
                    + arc.leftPatchId + " and " + arc.rightPatchId
                    + " has no transition fitting its path within " + TRANSITION_TOLERANCE);
        }
        recoveredLoopTransitionCount++;
    }

    /**
     * One coordinate of a copy vertex in a patch's chart.
     *
     * @param patchId patch whose chart is read
     * @param copyVertex copy vertex to look up
     * @param axis {@code 0} for u, {@code 1} for v
     * @throws IllegalStateException when the patch's map does not hold the vertex
     * @return the stored grid coordinate
     */
    private double chartCoordinate(int patchId, int copyVertex, int axis) {
        Integer dense = gridMap.denseByCopyVertexByPatchId[patchId].get(copyVertex);
        if (dense == null) {
            throw new IllegalStateException("patch " + patchId + " has no chart copy of copy"
                    + " vertex " + copyVertex);
        }
        return gridMap.uvByPatchId[patchId][dense * GlobalGridMap.GRID_COORDINATES + axis];
    }

    /**
     * Writes one chart copy of a copy vertex.
     *
     * @param patchId patch whose chart is written
     * @param copyVertex copy vertex to write
     * @param chartU grid u to store
     * @param chartV grid v to store
     */
    private void writeChartCopy(int patchId, int copyVertex, double chartU, double chartV) {
        int dense = gridMap.denseByCopyVertexByPatchId[patchId].get(copyVertex);
        gridMap.uvByPatchId[patchId][dense * GlobalGridMap.GRID_COORDINATES] = chartU;
        gridMap.uvByPatchId[patchId][dense * GlobalGridMap.GRID_COORDINATES + 1] = chartV;
    }

    /**
     * Truncates a coordinate so it is a multiple of the granularity every chart
     * copy of its vertex can represent exactly (EBC13 Algorithm 1, line 10).
     *
     * @param value coordinate to truncate
     * @param delta power of two at least the magnitude of every copy
     * @return the truncated coordinate
     */
    private static double truncate(double value, double delta) {
        double shift = Math.copySign(delta, value);
        return (value + shift) - shift;
    }

    /**
     * The power of two bounding every chart copy's magnitude of one shared
     * vertex, the truncation granularity scale of EBC13 Algorithm 1.
     *
     * @param magnitude largest coordinate magnitude over the vertex's copies
     * @return {@code 2^ceil(log2(magnitude))}, at least one
     */
    private static double truncationDelta(double magnitude) {
        double bound = Math.max(magnitude, 1.0);
        double delta = Math.scalb(1.0, Math.getExponent(bound));
        return delta < bound ? delta * 2.0 : delta;
    }

    /**
     * Rewrites every arc-interior path vertex's right-chart copy as the exact
     * transition image of its truncated left-chart copy, so both charts agree
     * bitwise from here on.
     */
    private void canonicalizeArcInteriors() {
        double[] rotated = new double[GlobalGridMap.GRID_COORDINATES];
        for (EmbeddedArc arc : tmesh.arcs) {
            if (!crossable(arc)
                    || transitionTurnsByArcId[arc.arcId] == IntegerGridMap.NOT_PLACED) {
                continue;
            }
            int turns = transitionTurnsByArcId[arc.arcId];
            int inverseTurns = (IntegerGridMap.QUARTER_TURNS - turns)
                    % IntegerGridMap.QUARTER_TURNS;
            int translationU = transitionTranslationUByArcId[arc.arcId];
            int translationV = transitionTranslationVByArcId[arc.arcId];
            List<Integer> path = arc.path.copyVertexPath;
            for (int step = 1; step < path.size() - 1; step++) {
                int copyVertex = path.get(step);
                double leftU = chartCoordinate(arc.leftPatchId, copyVertex, 0);
                double leftV = chartCoordinate(arc.leftPatchId, copyVertex, 1);
                IntegerGridMap.rotate(inverseTurns, leftU - translationU, leftV - translationV,
                        rotated);
                double magnitude = Math.max(Math.max(Math.abs(leftU), Math.abs(leftV)),
                        Math.max(Math.abs(rotated[0]), Math.abs(rotated[1])));
                double delta = truncationDelta(magnitude);
                leftU = truncate(leftU, delta);
                leftV = truncate(leftV, delta);
                writeChartCopy(arc.leftPatchId, copyVertex, leftU, leftV);
                IntegerGridMap.rotate(inverseTurns, leftU - translationU, leftV - translationV,
                        rotated);
                writeChartCopy(arc.rightPatchId, copyVertex, rotated[0], rotated[1]);
                truncatedVertexCount++;
            }
        }
    }

    /**
     * Propagates each live node's truncated canonical position exactly around its
     * patch fan, and requires pinned nodes on exact integers. A fan copy reached
     * twice with different values names an inconsistent transition ring.
     *
     * @throws IllegalStateException when fan copies disagree or a critical or
     *                               border node is off-integer
     */
    private void canonicalizeNodes() {
        double[] rotated = new double[GlobalGridMap.GRID_COORDINATES];
        for (EmbeddedNode node : tmesh.nodes) {
            if (!node.alive) {
                continue;
            }
            List<EmbeddedArc> fanArcs = new ArrayList<>();
            Set<Integer> fanPatches = new LinkedHashSet<>();
            for (int arcId : tmesh.arcEndsByNode.get(node.nodeId)) {
                EmbeddedArc arc = tmesh.arcs.get(arcId);
                if (crossable(arc)
                        && transitionTurnsByArcId[arc.arcId] != IntegerGridMap.NOT_PLACED
                        && !fanArcs.contains(arc)) {
                    fanArcs.add(arc);
                    fanPatches.add(arc.leftPatchId);
                    fanPatches.add(arc.rightPatchId);
                }
            }
            if (fanPatches.isEmpty()) {
                continue;
            }
            int canonicalPatch = Integer.MAX_VALUE;
            double magnitude = 0.0;
            for (int patchId : fanPatches) {
                canonicalPatch = Math.min(canonicalPatch, patchId);
                magnitude = Math.max(magnitude,
                        Math.max(Math.abs(chartCoordinate(patchId, node.copyVertex, 0)),
                                Math.abs(chartCoordinate(patchId, node.copyVertex, 1))));
            }
            double delta = truncationDelta(magnitude);
            double canonicalU = truncate(chartCoordinate(canonicalPatch, node.copyVertex, 0),
                    delta);
            double canonicalV = truncate(chartCoordinate(canonicalPatch, node.copyVertex, 1),
                    delta);
            if (node.critical || node.border) {
                if (canonicalU != Math.rint(canonicalU) || canonicalV != Math.rint(canonicalV)) {
                    throw new IllegalStateException("pinned node " + node.nodeId + " sits at ("
                            + canonicalU + ", " + canonicalV + ") in patch " + canonicalPatch
                            + ", off the integer grid");
                }
                integerNodeCount++;
            }
            writeChartCopy(canonicalPatch, node.copyVertex, canonicalU, canonicalV);
            propagateNodeFan(node, fanArcs, canonicalPatch, canonicalU, canonicalV, rotated);
        }
    }

    /**
     * Breadth-first propagation of one node's canonical position through its fan
     * arcs' transitions, writing each patch copy exactly once and checking any
     * revisit bitwise.
     *
     * @param node node whose fan is walked
     * @param fanArcs crossable arcs meeting at the node
     * @param canonicalPatch patch holding the already-written canonical copy
     * @param canonicalU the canonical grid u
     * @param canonicalV the canonical grid v
     * @param rotated scratch pair for rotations
     * @throws IllegalStateException when a revisited copy disagrees bitwise
     */
    private void propagateNodeFan(EmbeddedNode node, List<EmbeddedArc> fanArcs,
            int canonicalPatch, double canonicalU, double canonicalV, double[] rotated) {
        Map<Integer, double[]> valueByPatch = new HashMap<>();
        valueByPatch.put(canonicalPatch, new double[] {canonicalU, canonicalV});
        List<Integer> frontier = new ArrayList<>();
        frontier.add(canonicalPatch);
        for (int cursor = 0; cursor < frontier.size(); cursor++) {
            int patchId = frontier.get(cursor);
            double[] here = valueByPatch.get(patchId);
            for (EmbeddedArc arc : fanArcs) {
                int other;
                double otherU;
                double otherV;
                int turns = transitionTurnsByArcId[arc.arcId];
                int translationU = transitionTranslationUByArcId[arc.arcId];
                int translationV = transitionTranslationVByArcId[arc.arcId];
                if (arc.leftPatchId == patchId) {
                    other = arc.rightPatchId;
                    IntegerGridMap.rotate((IntegerGridMap.QUARTER_TURNS - turns)
                            % IntegerGridMap.QUARTER_TURNS,
                            here[0] - translationU, here[1] - translationV, rotated);
                    otherU = rotated[0];
                    otherV = rotated[1];
                } else if (arc.rightPatchId == patchId) {
                    other = arc.leftPatchId;
                    IntegerGridMap.rotate(turns, here[0], here[1], rotated);
                    otherU = rotated[0] + translationU;
                    otherV = rotated[1] + translationV;
                } else {
                    continue;
                }
                double[] existing = valueByPatch.get(other);
                if (existing != null) {
                    if (existing[0] != otherU || existing[1] != otherV) {
                        throw new IllegalStateException("node " + node.nodeId + " fan copies"
                                + " disagree in patch " + other + ": (" + existing[0] + ", "
                                + existing[1] + ") vs (" + otherU + ", " + otherV
                                + ") through arc " + arc.arcId);
                    }
                    continue;
                }
                valueByPatch.put(other, new double[] {otherU, otherV});
                writeChartCopy(other, node.copyVertex, otherU, otherV);
                frontier.add(other);
            }
        }
    }

    /**
     * Proves every live patch's chart triangles share one exact orientation,
     * recording it per patch for the extraction's corner reads.
     *
     * @throws IllegalStateException when a triangle is exactly degenerate or wound
     *                               against its patch
     */
    private void requireOrientation() {
        counterClockwiseByPatch = new boolean[tmesh.patches.size()];
        double[] first = new double[] {0.0, 0.0, 1.0};
        double[] second = new double[] {0.0, 0.0, 1.0};
        double[] third = new double[] {0.0, 0.0, 1.0};
        for (int patchId = 0; patchId < tmesh.patches.size(); patchId++) {
            if (!tmesh.patches.get(patchId).alive) {
                continue;
            }
            PatchRectangleMap map = patchMaps.mapByPatchId[patchId];
            double[] uv = gridMap.uvByPatchId[patchId];
            int patchSign = 0;
            for (int triangle = 0; triangle < map.triangles.length; triangle++) {
                int[] corners = map.triangles[triangle];
                first[0] = uv[corners[0] * GlobalGridMap.GRID_COORDINATES];
                first[1] = uv[corners[0] * GlobalGridMap.GRID_COORDINATES + 1];
                second[0] = uv[corners[1] * GlobalGridMap.GRID_COORDINATES];
                second[1] = uv[corners[1] * GlobalGridMap.GRID_COORDINATES + 1];
                third[0] = uv[corners[2] * GlobalGridMap.GRID_COORDINATES];
                third[1] = uv[corners[2] * GlobalGridMap.GRID_COORDINATES + 1];
                int sign = ExactBarycentricOrient.sign(first, second, third);
                if (sign == 0) {
                    throw new IllegalStateException("patch " + patchId + " chart triangle "
                            + triangle + " is exactly degenerate; the barrier energy forbids"
                            + " this, so an upstream stage broke its postcondition");
                }
                if (patchSign == 0) {
                    patchSign = sign;
                    counterClockwiseByPatch[patchId] = sign > 0;
                } else if (sign != patchSign) {
                    throw new IllegalStateException("patch " + patchId + " chart triangle "
                            + triangle + " winds against its patch; the map is folded, which"
                            + " the barrier energy forbids");
                }
                verifiedTriangleCount++;
            }
        }
    }

    /**
     * Re-checks every crossable arc's full path bitwise through its resolved
     * transition, which canonicalization made true by construction.
     *
     * @throws IllegalStateException when any chart copy pair disagrees
     */
    private void requireArcConsistency() {
        double[] rotated = new double[GlobalGridMap.GRID_COORDINATES];
        for (EmbeddedArc arc : tmesh.arcs) {
            if (!crossable(arc)
                    || transitionTurnsByArcId[arc.arcId] == IntegerGridMap.NOT_PLACED) {
                continue;
            }
            int turns = transitionTurnsByArcId[arc.arcId];
            for (int copyVertex : arc.path.copyVertexPath) {
                IntegerGridMap.rotate(turns, chartCoordinate(arc.rightPatchId, copyVertex, 0),
                        chartCoordinate(arc.rightPatchId, copyVertex, 1), rotated);
                double mappedU = rotated[0] + transitionTranslationUByArcId[arc.arcId];
                double mappedV = rotated[1] + transitionTranslationVByArcId[arc.arcId];
                if (mappedU != chartCoordinate(arc.leftPatchId, copyVertex, 0)
                        || mappedV != chartCoordinate(arc.leftPatchId, copyVertex, 1)) {
                    throw new IllegalStateException("arc " + arc.arcId + " chart copies of copy"
                            + " vertex " + copyVertex + " disagree after canonicalization: left ("
                            + chartCoordinate(arc.leftPatchId, copyVertex, 0) + ", "
                            + chartCoordinate(arc.leftPatchId, copyVertex, 1)
                            + ") vs mapped right (" + mappedU + ", " + mappedV + ")");
                }
                verifiedPathVertexCount++;
            }
        }
    }
}
