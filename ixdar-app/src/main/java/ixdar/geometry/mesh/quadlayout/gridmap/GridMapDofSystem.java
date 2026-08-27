package ixdar.geometry.mesh.quadlayout.gridmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.quadlayout.ChartAtlas;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.solver.system.DofSystem;
import ixdar.platform.Platforms;

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

    /**
     * Regular nodes kept pinned because the transitions around their fan do not compose to the
     * identity. Such a node is a singularity of the grid map, which BCE13 (2) holds at an integer.
     */
    public int holonomyPinnedNodeCount;

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

    /**
     * The canonical solve state: interleaved {@code (u, v)} per slot in
     * {@code solution}, both scalars of a fixed slot frozen.
     */
    public DofSystem system;

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
        Map<Integer, Map<Integer, double[]>> freedFans = freedNodeFans();
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
                Map<Integer, double[]> fan = freedFans.get(copyVertex);
                Integer seamArcId = fan != null ? null : seamArcByCopyVertex.get(copyVertex);
                double[] toSlotChart = null;
                if (fan != null) {
                    toSlotChart = fan.get(patch.patchId);
                } else if (seamArcId != null
                        && patch.patchId == tmesh.arcs.get(seamArcId).rightPatchId) {
                    toSlotChart = gridMap.atlas.transition(seamArcId, patch.patchId);
                }
                if (toSlotChart != null
                        && (toSlotChart[0] != 0 || toSlotChart[1] != 0 || toSlotChart[2] != 0)) {
                    IntegerGridMap.rotate((int) toSlotChart[0], here, there, rotated);
                    here = rotated[0] + toSlotChart[1];
                    there = rotated[1] + toSlotChart[2];
                    double[] inverse = ChartAtlas.invert(toSlotChart);
                    rotationByDense[dense] = (int) inverse[0];
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
        system = new DofSystem(slotCount * GlobalGridMap.GRID_COORDINATES);
        system.solve = this::relax;
        for (int slot = 0; slot < slotCount; slot++) {
            system.solution[slot * GlobalGridMap.GRID_COORDINATES] = valuesU.get(slot);
            system.solution[slot * GlobalGridMap.GRID_COORDINATES + 1] = valuesV.get(slot);
            system.frozen[slot * GlobalGridMap.GRID_COORDINATES] = fixedSlots.get(slot);
            system.frozen[slot * GlobalGridMap.GRID_COORDINATES + 1] = fixedSlots.get(slot);
        }
        Platforms.log("[grid-dof] slots=" + slotCount + " free=" + freeSlotCount
                + " pinnedVertices=" + pinned.size() + " coupledSeamArcs=" + coupledSeamArcCount
                + " freedNodes=" + freedNodeCount + " fanFailed=" + fanFailedNodeCount
                + " holonomyPinned=" + holonomyPinnedNodeCount
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
                && gridMap.atlas.hasTransition(arc.arcId);
    }

    /**
     * The transform of each adjacent patch's chart into the node's primary chart, composed across
     * the node's arc fan, for every regular node {@link NodeFreedom#REGULAR_FREE} unpins. A node
     * whose fan cannot be chart-connected is left out and stays pinned.
     *
     * @return per freed node copy vertex, the transform {@code {turns, u, v}} by patch id
     */
    private Map<Integer, Map<Integer, double[]>> freedNodeFans() {
        Map<Integer, Map<Integer, double[]>> fans = new HashMap<>();
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
            Map<Integer, double[]> fan = new HashMap<>();
            List<Integer> frontier = new ArrayList<>();
            int primary = Collections.min(patches);
            fan.put(primary, new double[] {0, 0, 0});
            frontier.add(primary);
            for (int cursor = 0; cursor < frontier.size(); cursor++) {
                int patchId = frontier.get(cursor);
                double[] toPrimary = fan.get(patchId);
                for (int arcId : incidentArcs) {
                    EmbeddedArc arc = tmesh.arcs.get(arcId);
                    int other = arc.leftPatchId == patchId ? arc.rightPatchId
                            : arc.rightPatchId == patchId ? arc.leftPatchId : EmbeddedTMesh.NONE;
                    if (other == EmbeddedTMesh.NONE || other == patchId || fan.containsKey(other)
                            || !patches.contains(other)
                            || !gridMap.atlas.hasTransition(arcId)) {
                        continue;
                    }
                    fan.put(other, ChartAtlas.compose(toPrimary,
                            gridMap.atlas.transition(arcId, other)));
                    frontier.add(other);
                }
            }
            if (fan.size() != patches.size()) {
                fanFailedNodeCount++;
            } else if (!fanHolonomyTrivial(fan, incidentArcs)) {
                holonomyPinnedNodeCount++;
            } else {
                fans.put(node.copyVertex, fan);
                freedNodeCount++;
            }
        }
        return fans;
    }

    /**
     * Whether every arc of a node's fan agrees with the spanning-tree transforms the fan was built
     * from, which is the fan closing up rather than opening a wedge when the node moves.
     *
     * <p>See also: BCE13 (2)
     *
     * @param fan          transform into the primary chart by patch id
     * @param incidentArcs arcs meeting at the node
     * @return whether the transitions compose to the identity around the fan
     */
    private boolean fanHolonomyTrivial(Map<Integer, double[]> fan, List<Integer> incidentArcs) {
        for (int arcId : incidentArcs) {
            EmbeddedArc arc = tmesh.arcs.get(arcId);
            double[] leftToPrimary = fan.get(arc.leftPatchId);
            double[] rightToPrimary = fan.get(arc.rightPatchId);
            if (leftToPrimary == null || rightToPrimary == null
                    || !gridMap.atlas.hasTransition(arcId)) {
                continue;
            }
            double[] expected = ChartAtlas.compose(leftToPrimary,
                    gridMap.atlas.transition(arcId, arc.rightPatchId));
            if (expected[0] != rightToPrimary[0] || expected[1] != rightToPrimary[1]
                    || expected[2] != rightToPrimary[2]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The copy vertices the optimization must not move: critical, border and fan-failed nodes,
     * and every vertex of an uncoupled seam arc or an arc with only one patch, so the transitions
     * across them stay exactly as framed. Under {@link NodeFreedom#PINNED}, every node.
     *
     * @param freedFans the nodes freed with fan transforms, exempt from pinning
     * @return the pinned copy vertex ids
     */
    private Set<Integer> pinnedCopyVertices(Map<Integer, Map<Integer, double[]>> freedFans) {
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
                IntegerGridMap.rotate(rotationByDense[dense],
                        system.solution[slotByDense[dense] * GlobalGridMap.GRID_COORDINATES],
                        system.solution[slotByDense[dense] * GlobalGridMap.GRID_COORDINATES + 1],
                        rotated);
                uv[dense * GlobalGridMap.GRID_COORDINATES] =
                        rotated[0] + translationUByDense[dense];
                uv[dense * GlobalGridMap.GRID_COORDINATES + 1] =
                        rotated[1] + translationVByDense[dense];
            }
        }
    }

    /**
     * The number of slots, free and fixed together.
     *
     * @return slot count
     */
    public int dofCount() {
        return slotCount * GlobalGridMap.GRID_COORDINATES;
    }

    /**
     * Newton-relaxes the slots in place and rebuilds the grid map's iso surface
     * from the relaxed coordinates.
     */
    public void relax() {
        gridMap.gridOptimizer = new GridMapOptimizer(this, gridMap.seamless);
        gridMap.gridOptimizer.build();
        gridMap.isoSurfaceRelaxed =
                new GridMapIsoSurface(gridMap.patchMaps, gridMap.uvByPatchId).build();
        Platforms.log("[global-grid] relax %.2e→%.2e it=%d%n", gridMap.gridOptimizer.energyBefore,
                gridMap.gridOptimizer.energyAfter, gridMap.gridOptimizer.iterationCount);
    }
}
