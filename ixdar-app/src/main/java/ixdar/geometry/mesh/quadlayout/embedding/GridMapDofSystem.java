package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which grid coordinates the re-parametrization may move, as solver slots: one shared slot per free
 * copy vertex, and one held slot per patch-local copy of a pinned vertex.
 *
 * <p>See also: LCBK19 Section 6.2
 */
public final class GridMapDofSystem {

    /** Two patches sharing a free vertex must agree on its grid position to within this. */
    public static final double AGREEMENT_TOLERANCE = 1.0e-9;

    public final GlobalGridMap gridMap;
    public final EmbeddedTMesh tmesh;
    public final IntegerGridMap frames;

    /** How seam-arc vertices enter the system; {@link SeamCoupling#PINNED} holds them in place. */
    public boolean seamCouplingPinned = true;

    /** How layout nodes enter the system; {@link NodeFreedom#PINNED} holds every corner. */
    public boolean nodeFreedomPinned = true;

    /** Regular nodes freed with fan-composed chart transforms. */
    public int freedNodeCount;

    /** Regular nodes kept pinned because their patch fan could not be chart-connected. */
    public int fanFailedNodeCount;

    /** Freed nodes whose patch copies disagree through their fan transforms. Must be zero. */
    public int disagreeingFreedNodeCount;

    /** Solver slot of each dense vertex of each live patch. */
    public int[][] slotByPatchDense;

    /**
     * Quarter turns applied when reading a slot into each patch's copy, indexed like
     * {@link #slotByPatchDense}; zero everywhere except a coupled seam's right-patch copies.
     */
    public int[][] rotationByPatchDense;

    /** Grid u translation of that read transform, paired with {@link #rotationByPatchDense}. */
    public double[][] translationUByPatchDense;

    /** Grid v translation of that read transform, paired with {@link #rotationByPatchDense}. */
    public double[][] translationVByPatchDense;

    /** Coupled seam vertices whose two patch copies disagree through the transition. Must be zero. */
    public int disagreeingSeamVertexCount;

    /** Seam arcs whose vertices were freed through their transition. */
    public int coupledSeamArcCount;

    /** Whether each slot is held rather than solved for. */
    public boolean[] fixedBySlot;

    /** Grid u of each slot; the value held, for a fixed slot. */
    public double[] slotU;

    /** Grid v of each slot; the value held, for a fixed slot. */
    public double[] slotV;

    /** Slots in the system, free and fixed together. */
    public int slotCount;

    /** Slots the optimization may move. */
    public int freeSlotCount;

    /**
     * Free vertices whose patches disagree on the grid position, which would mean the
     * identification is wrong. Must be zero.
     */
    public int disagreeingSharedVertexCount;

    /** Largest disagreement seen between two patches over a shared free vertex. */
    public double worstSharedDisagreement;

    /**
     * Stores the grid map whose coordinates are split into slots.
     *
     * @param gridMap the patch maps carried into one common grid
     */
    public GridMapDofSystem(GlobalGridMap gridMap) {
        this.gridMap = gridMap;
        this.tmesh = gridMap.tmesh;
        this.frames = gridMap.frames;
    }

    /**
     * Pins the layout's nodes and seams, then gives every remaining copy vertex one slot shared by
     * all the patches holding it.
     *
     * @return this, built
     */
    public GridMapDofSystem build() {
        Map<Integer, Map<Integer, int[]>> freedFans = freedNodeFans();
        Set<Integer> pinned = pinnedCopyVertices(freedFans);
        Map<Integer, Integer> seamArcByCopyVertex = coupledSeamVertices();
        slotByPatchDense = new int[tmesh.patches.size()][];
        rotationByPatchDense = new int[tmesh.patches.size()][];
        translationUByPatchDense = new double[tmesh.patches.size()][];
        translationVByPatchDense = new double[tmesh.patches.size()][];
        Map<Integer, Integer> slotByCopyVertex = new HashMap<>();
        List<Boolean> fixedSlots = new ArrayList<>();
        List<Double> valuesU = new ArrayList<>();
        List<Double> valuesV = new ArrayList<>();
        double[] rotated = new double[GlobalGridMap.GRID_COORDINATES];
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            PatchRectangleMap map = gridMap.patchMaps.mapByPatchId[patch.patchId];
            double[] uv = gridMap.uvByPatchId[patch.patchId];
            int[] slotByDense = new int[map.positions.length];
            int[] rotationByDense = new int[map.positions.length];
            double[] translationUByDense = new double[map.positions.length];
            double[] translationVByDense = new double[map.positions.length];
            slotByPatchDense[patch.patchId] = slotByDense;
            rotationByPatchDense[patch.patchId] = rotationByDense;
            translationUByPatchDense[patch.patchId] = translationUByDense;
            translationVByPatchDense[patch.patchId] = translationVByDense;
            for (int dense = 0; dense < map.positions.length; dense++) {
                double here = uv[dense * GlobalGridMap.GRID_COORDINATES];
                double there = uv[dense * GlobalGridMap.GRID_COORDINATES + 1];
                int copyVertex = map.vertexLabel[dense];
                if (pinned.contains(copyVertex)) {
                    slotByDense[dense] = fixedSlots.size();
                    fixedSlots.add(true);
                    valuesU.add(here);
                    valuesV.add(there);
                    continue;
                }
                // A slot stores one chart: a freed node's primary patch chart, or a coupled
                // seam's left patch chart. Other copies carry the value there and read it back
                // through the inverse transform.
                Map<Integer, int[]> fan = freedFans.get(copyVertex);
                Integer seamArcId = fan != null ? null : seamArcByCopyVertex.get(copyVertex);
                int[] toSlotChart = null;
                if (fan != null) {
                    toSlotChart = fan.get(patch.patchId);
                } else if (seamArcId != null
                        && patch.patchId == tmesh.arcs.get(seamArcId).rightPatchId) {
                    toSlotChart = new int[] {frames.transitionQuarterTurnsByArcId[seamArcId],
                            frames.transitionTranslationUByArcId[seamArcId],
                            frames.transitionTranslationVByArcId[seamArcId]};
                }
                if (toSlotChart != null
                        && (toSlotChart[0] != 0 || toSlotChart[1] != 0 || toSlotChart[2] != 0)) {
                    IntegerGridMap.rotate(toSlotChart[0], here, there, rotated);
                    here = rotated[0] + toSlotChart[1];
                    there = rotated[1] + toSlotChart[2];
                    int[] inverse = invertTransform(toSlotChart);
                    rotationByDense[dense] = inverse[0];
                    translationUByDense[dense] = inverse[1];
                    translationVByDense[dense] = inverse[2];
                }
                Integer existing = slotByCopyVertex.get(copyVertex);
                if (existing != null) {
                    slotByDense[dense] = existing;
                    double disagreement = Math.max(Math.abs(valuesU.get(existing) - here),
                            Math.abs(valuesV.get(existing) - there));
                    worstSharedDisagreement = Math.max(worstSharedDisagreement, disagreement);
                    if (disagreement > AGREEMENT_TOLERANCE) {
                        if (fan != null) {
                            disagreeingFreedNodeCount++;
                        } else if (seamArcId != null) {
                            disagreeingSeamVertexCount++;
                        } else {
                            disagreeingSharedVertexCount++;
                        }
                    }
                    continue;
                }
                slotByCopyVertex.put(copyVertex, fixedSlots.size());
                slotByDense[dense] = fixedSlots.size();
                fixedSlots.add(false);
                valuesU.add(here);
                valuesV.add(there);
                freeSlotCount++;
            }
        }
        slotCount = fixedSlots.size();
        fixedBySlot = new boolean[slotCount];
        slotU = new double[slotCount];
        slotV = new double[slotCount];
        for (int slot = 0; slot < slotCount; slot++) {
            fixedBySlot[slot] = fixedSlots.get(slot);
            slotU[slot] = valuesU.get(slot);
            slotV[slot] = valuesV.get(slot);
        }
        System.out.println("[grid-dof] slots=" + slotCount + " free=" + freeSlotCount
                + " pinnedVertices=" + pinned.size() + " coupledSeamArcs=" + coupledSeamArcCount
                + " freedNodes=" + freedNodeCount + " fanFailed=" + fanFailedNodeCount
                + " disagreeingShared=" + disagreeingSharedVertexCount + " disagreeingSeam="
                + disagreeingSeamVertexCount + " disagreeingNode=" + disagreeingFreedNodeCount
                + " worstDisagreement=" + worstSharedDisagreement);
        return this;
    }

    /**
     * The interior vertices of every seam arc the coupling frees, mapped to their arc. Empty under
     * {@link SeamCoupling#PINNED}.
     *
     * @return seam arc id by copy vertex id
     */
    private Map<Integer, Integer> coupledSeamVertices() {
        Map<Integer, Integer> seamArcByCopyVertex = new HashMap<>();
        if (seamCouplingPinned == true) {
            return seamArcByCopyVertex;
        }
        for (EmbeddedArc arc : tmesh.arcs) {
            if (!couplable(arc)) {
                continue;
            }
            coupledSeamArcCount++;
            for (int copyVertex : arc.path.copyVertexPath) {
                seamArcByCopyVertex.put(copyVertex, arc.arcId);
            }
        }
        return seamArcByCopyVertex;
    }

    /**
     * Whether a seam arc's vertices can be freed through its transition: alive, two distinct
     * patches, and a valid automorphism solved for it.
     *
     * @param arc arc to test
     * @return whether the coupling applies
     */
    private boolean couplable(EmbeddedArc arc) {
        return arc.alive && frames.seamByArcId[arc.arcId]
                && arc.leftPatchId != EmbeddedTMesh.NONE && arc.rightPatchId != EmbeddedTMesh.NONE
                && arc.leftPatchId != arc.rightPatchId
                && frames.transitionQuarterTurnsByArcId[arc.arcId] != IntegerGridMap.NOT_PLACED;
    }

    /**
     * The transform of each adjacent patch's chart into the node's primary chart, composed across
     * the node's arc fan, for every regular node {@link NodeFreedom#REGULAR_FREE} unpins. A node
     * whose fan cannot be chart-connected is left out and stays pinned.
     *
     * @return per freed node copy vertex, the transform {@code {turns, u, v}} by patch id
     */
    private Map<Integer, Map<Integer, int[]>> freedNodeFans() {
        Map<Integer, Map<Integer, int[]>> fans = new HashMap<>();
        if (nodeFreedomPinned == true) {
            return fans;
        }
        Map<Integer, Set<Integer>> patchesByNode = new HashMap<>();
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                for (int nodeId : patch.sideNodeIds.get(side)) {
                    patchesByNode.computeIfAbsent(nodeId, key -> new HashSet<>())
                            .add(patch.patchId);
                }
            }
        }
        Map<Integer, List<Integer>> arcsByNode = new HashMap<>();
        for (EmbeddedArc arc : tmesh.arcs) {
            if (!arc.alive) {
                continue;
            }
            arcsByNode.computeIfAbsent(arc.startNodeId, key -> new ArrayList<>()).add(arc.arcId);
            if (arc.endNodeId != arc.startNodeId) {
                arcsByNode.computeIfAbsent(arc.endNodeId, key -> new ArrayList<>()).add(arc.arcId);
            }
        }
        for (EmbeddedNode node : tmesh.nodes) {
            if (!node.alive || node.critical || node.border) {
                continue;
            }
            Set<Integer> patches = patchesByNode.get(node.nodeId);
            List<Integer> incidentArcs = arcsByNode.get(node.nodeId);
            if (patches == null || incidentArcs == null) {
                fanFailedNodeCount++;
                continue;
            }
            Map<Integer, int[]> fan = new HashMap<>();
            List<Integer> frontier = new ArrayList<>();
            int primary = Collections.min(patches);
            fan.put(primary, new int[] {0, 0, 0});
            frontier.add(primary);
            for (int cursor = 0; cursor < frontier.size(); cursor++) {
                int patchId = frontier.get(cursor);
                int[] toPrimary = fan.get(patchId);
                for (int arcId : incidentArcs) {
                    EmbeddedArc arc = tmesh.arcs.get(arcId);
                    int other = arc.leftPatchId == patchId ? arc.rightPatchId
                            : arc.rightPatchId == patchId ? arc.leftPatchId : EmbeddedTMesh.NONE;
                    if (other == EmbeddedTMesh.NONE || other == patchId || fan.containsKey(other)
                            || !patches.contains(other)
                            || frames.transitionQuarterTurnsByArcId[arcId]
                                    == IntegerGridMap.NOT_PLACED) {
                        continue;
                    }
                    int[] transition = {frames.transitionQuarterTurnsByArcId[arcId],
                            frames.transitionTranslationUByArcId[arcId],
                            frames.transitionTranslationVByArcId[arcId]};
                    int[] otherToPatch = patchId == arc.leftPatchId ? transition
                            : invertTransform(transition);
                    fan.put(other, composeTransform(toPrimary, otherToPatch));
                    frontier.add(other);
                }
            }
            if (fan.size() == patches.size()) {
                fans.put(node.copyVertex, fan);
                freedNodeCount++;
            } else {
                fanFailedNodeCount++;
            }
        }
        return fans;
    }

    /**
     * The inverse of a grid automorphism given as quarter turns and an integer translation.
     *
     * @param transform the automorphism {@code {turns, u, v}}
     * @return its inverse in the same encoding
     */
    private int[] invertTransform(int[] transform) {
        int turns = (IntegerGridMap.QUARTER_TURNS - transform[0]) % IntegerGridMap.QUARTER_TURNS;
        double[] rotated = new double[GlobalGridMap.GRID_COORDINATES];
        IntegerGridMap.rotate(turns, transform[1], transform[2], rotated);
        return new int[] {turns, -(int) Math.round(rotated[0]), -(int) Math.round(rotated[1])};
    }

    /**
     * The composition applying the inner automorphism first, then the outer.
     *
     * @param outer the automorphism applied second
     * @param inner the automorphism applied first
     * @return the composed automorphism {@code {turns, u, v}}
     */
    private int[] composeTransform(int[] outer, int[] inner) {
        double[] rotated = new double[GlobalGridMap.GRID_COORDINATES];
        IntegerGridMap.rotate(outer[0], inner[1], inner[2], rotated);
        return new int[] {(outer[0] + inner[0]) % IntegerGridMap.QUARTER_TURNS,
                outer[1] + (int) Math.round(rotated[0]), outer[2] + (int) Math.round(rotated[1])};
    }

    /**
     * The copy vertices the optimization must not move: critical, border and fan-failed nodes,
     * and every vertex of an uncoupled seam arc or an arc with only one patch, so the transitions
     * across them stay exactly as framed. Under {@link NodeFreedom#PINNED}, every node.
     *
     * @param freedFans the nodes freed with fan transforms, exempt from pinning
     * @return the pinned copy vertex ids
     */
    private Set<Integer> pinnedCopyVertices(Map<Integer, Map<Integer, int[]>> freedFans) {
        Set<Integer> pinned = new HashSet<>();
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                for (int nodeId : patch.sideNodeIds.get(side)) {
                    int copyVertex = tmesh.nodes.get(nodeId).copyVertex;
                    if (!freedFans.containsKey(copyVertex)) {
                        pinned.add(copyVertex);
                    }
                }
            }
        }
        for (EmbeddedArc arc : tmesh.arcs) {
            boolean oneSided = arc.leftPatchId == EmbeddedTMesh.NONE
                    || arc.rightPatchId == EmbeddedTMesh.NONE;
            if (!arc.alive || !(frames.seamByArcId[arc.arcId] || oneSided)) {
                continue;
            }
            if (seamCouplingPinned == false && !oneSided && couplable(arc)) {
                continue;
            }
            pinned.addAll(arc.path.copyVertexPath);
        }
        return pinned;
    }

    /**
     * Copies the slot values back into every patch's grid coordinates through each copy's read
     * transform, so the map reflects a solve.
     */
    public void writeBack() {
        double[] rotated = new double[GlobalGridMap.GRID_COORDINATES];
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            int[] slotByDense = slotByPatchDense[patch.patchId];
            int[] rotationByDense = rotationByPatchDense[patch.patchId];
            double[] translationUByDense = translationUByPatchDense[patch.patchId];
            double[] translationVByDense = translationVByPatchDense[patch.patchId];
            double[] uv = gridMap.uvByPatchId[patch.patchId];
            for (int dense = 0; dense < slotByDense.length; dense++) {
                IntegerGridMap.rotate(rotationByDense[dense], slotU[slotByDense[dense]],
                        slotV[slotByDense[dense]], rotated);
                uv[dense * GlobalGridMap.GRID_COORDINATES] =
                        rotated[0] + translationUByDense[dense];
                uv[dense * GlobalGridMap.GRID_COORDINATES + 1] =
                        rotated[1] + translationVByDense[dense];
            }
        }
    }
}
