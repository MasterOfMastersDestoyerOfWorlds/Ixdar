package ixdar.geometry.mesh.quadlayout.gridmap;

import java.util.Arrays;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.numeric.sparse.system.DofSystem;
import ixdar.numeric.sparse.system.NewtonRelaxation;

/**
 * One patch's interior as its own Newton system: the slots only this patch's
 * triangles reference move, every slot it shares with a neighbour (its arcs and
 * nodes) and every frozen slot is held. Patches own disjoint slots, so solves of
 * different patches may write one global solution concurrently.
 */
public final class PatchInteriorSolve implements NewtonRelaxation.SystemAssembler {

    public static final int SKIPPED_ENTRY = Integer.MIN_VALUE;

    private static final int ELEMENT_ENTRIES = SymmetricDirichletEnergy.VARIABLES
            * SymmetricDirichletEnergy.VARIABLES;

    public final GridMapOptimizer optimizer;

    /** First gathered triangle of the patch, inclusive. */
    public final int firstTriangle;

    /** End of the patch's gathered triangles, exclusive. */
    public final int endTriangle;

    /** Global slot of every local slot, ascending. */
    public final int[] localSlots;

    /** Local slot of each of the patch's corners, from its first triangle on. */
    public final int[] localSlotByCorner;

    /**
     * The six local system variables of every triangle, triangle-major, in the
     * element's {@code x0..x2, y0..y2} order.
     */
    public final int[] variableByTriangle;

    /** The patch-local system; frozen wherever the slot is shared or held. */
    public final DofSystem local;

    /** Whether any slot of the patch is free; a patch without one is not solved. */
    public final boolean anyFree;

    /** Sorted strict-upper Hessian keys of the local system. */
    public final long[] localUpperKeys;

    /**
     * Destination of every element Hessian entry, triangle-major: an upper-values
     * index, a diagonal index as {@code -(index + 1)}, or {@link #SKIPPED_ENTRY}.
     */
    public final int[] destinationByEntry;

    /** Evaluator confined to the thread running this solve. */
    public final SymmetricDirichletEnergy element = new SymmetricDirichletEnergy();

    /**
     * Builds the patch-local slot numbering, frozen mask and Hessian pattern from
     * the global solution's current values.
     *
     * @param optimizer        the optimizer whose gathered triangles and element
     *                         primitives the patch reuses
     * @param firstTriangle    the patch's first gathered triangle, inclusive
     * @param endTriangle      end of its triangles, exclusive
     * @param ownerPatchBySlot the one patch referencing each slot, or
     *                         {@link GridMapOptimizer#SHARED_SLOT}
     * @param solution         the global interleaved slot coordinates
     */
    public PatchInteriorSolve(GridMapOptimizer optimizer, int firstTriangle, int endTriangle,
            int[] ownerPatchBySlot, double[] solution) {
        this.optimizer = optimizer;
        this.firstTriangle = firstTriangle;
        this.endTriangle = endTriangle;
        int patchId = optimizer.patchByTriangle[firstTriangle];
        int cornerBase = firstTriangle * HalfEdgeMesh.TRIANGLE_CORNERS;
        int cornerCount = (endTriangle - firstTriangle) * HalfEdgeMesh.TRIANGLE_CORNERS;
        localSlots = Arrays.stream(optimizer.slotByTriangleCorner, cornerBase,
                cornerBase + cornerCount).sorted().distinct().toArray();
        localSlotByCorner = new int[cornerCount];
        int triangles = endTriangle - firstTriangle;
        variableByTriangle = new int[triangles * SymmetricDirichletEnergy.VARIABLES];
        for (int corner = 0; corner < cornerCount; corner++) {
            int localSlot = Arrays.binarySearch(localSlots,
                    optimizer.slotByTriangleCorner[cornerBase + corner]);
            localSlotByCorner[corner] = localSlot;
            int variableBase = corner / HalfEdgeMesh.TRIANGLE_CORNERS
                    * SymmetricDirichletEnergy.VARIABLES;
            int within = corner % HalfEdgeMesh.TRIANGLE_CORNERS;
            variableByTriangle[variableBase + within] =
                    localSlot * GridMapOptimizer.SLOT_COORDINATES;
            variableByTriangle[variableBase + HalfEdgeMesh.TRIANGLE_CORNERS + within] =
                    localSlot * GridMapOptimizer.SLOT_COORDINATES + 1;
        }
        local = new DofSystem(localSlots.length * GridMapOptimizer.SLOT_COORDINATES);
        boolean free = false;
        for (int localSlot = 0; localSlot < localSlots.length; localSlot++) {
            int slot = localSlots[localSlot];
            boolean slotFree = ownerPatchBySlot[slot] == patchId
                    && !optimizer.dofs.system.frozen[slot * GridMapOptimizer.SLOT_COORDINATES];
            free |= slotFree;
            for (int axis = 0; axis < GridMapOptimizer.SLOT_COORDINATES; axis++) {
                local.solution[localSlot * GridMapOptimizer.SLOT_COORDINATES + axis] =
                        solution[slot * GridMapOptimizer.SLOT_COORDINATES + axis];
                local.frozen[localSlot * GridMapOptimizer.SLOT_COORDINATES + axis] = !slotFree;
            }
        }
        anyFree = free;
        long[] keys = new long[triangles * ELEMENT_ENTRIES];
        int keyCount = 0;
        for (int triangle = 0; triangle < triangles; triangle++) {
            int variableBase = triangle * SymmetricDirichletEnergy.VARIABLES;
            for (int row = 0; row < SymmetricDirichletEnergy.VARIABLES; row++) {
                for (int column = 0; column < SymmetricDirichletEnergy.VARIABLES; column++) {
                    int rowVariable = variableByTriangle[variableBase + row];
                    int columnVariable = variableByTriangle[variableBase + column];
                    if (rowVariable < columnVariable && !local.frozen[rowVariable]
                            && !local.frozen[columnVariable]) {
                        keys[keyCount++] = ((long) rowVariable << GridMapOptimizer.KEY_ROW_SHIFT)
                                | columnVariable;
                    }
                }
            }
        }
        localUpperKeys = Arrays.stream(keys, 0, keyCount).sorted().distinct().toArray();
        destinationByEntry = new int[triangles * ELEMENT_ENTRIES];
        for (int triangle = 0; triangle < triangles; triangle++) {
            int variableBase = triangle * SymmetricDirichletEnergy.VARIABLES;
            for (int entry = 0; entry < ELEMENT_ENTRIES; entry++) {
                int row = variableByTriangle[variableBase
                        + entry / SymmetricDirichletEnergy.VARIABLES];
                int column = variableByTriangle[variableBase
                        + entry % SymmetricDirichletEnergy.VARIABLES];
                int destination = SKIPPED_ENTRY;
                if (row == column) {
                    destination = -(row + 1);
                } else if (row < column && !local.frozen[row] && !local.frozen[column]) {
                    destination = Arrays.binarySearch(localUpperKeys,
                            ((long) row << GridMapOptimizer.KEY_ROW_SHIFT) | column);
                }
                destinationByEntry[triangle * ELEMENT_ENTRIES + entry] = destination;
            }
        }
    }

    /**
     * Newton-relaxes the patch's free slots with their own flip-free line search
     * and writes them into the global solution; touches no slot another patch owns.
     *
     * @param solution the global interleaved slot coordinates, updated in place
     */
    public void relax(double[] solution) {
        if (!anyFree) {
            return;
        }
        int cornerBase = firstTriangle * HalfEdgeMesh.TRIANGLE_CORNERS;
        local.energy = x -> optimizer.energyOfTriangleRange(element, firstTriangle, endTriangle,
                localSlotByCorner, cornerBase, x);
        NewtonRelaxation newton = new NewtonRelaxation(local, this, localUpperKeys,
                (x, delta) -> optimizer.maximumStepOfRange(firstTriangle, endTriangle,
                        localSlotByCorner, cornerBase, x, delta));
        newton.logIterations = false;
        newton.run();
        for (int localSlot = 0; localSlot < localSlots.length; localSlot++) {
            if (local.frozen[localSlot * GridMapOptimizer.SLOT_COORDINATES]) {
                continue;
            }
            int slot = localSlots[localSlot];
            for (int axis = 0; axis < GridMapOptimizer.SLOT_COORDINATES; axis++) {
                solution[slot * GridMapOptimizer.SLOT_COORDINATES + axis] =
                        local.solution[localSlot * GridMapOptimizer.SLOT_COORDINATES + axis];
            }
        }
    }

    /**
     * Fills the local system's composite-majorized Hessian and negative gradient
     * at a candidate local solution, the patch's share of the optimizer's assembly.
     *
     * @param x             candidate local slot coordinates
     * @param diagonal      receives the Hessian diagonal
     * @param upperValues   receives the strict-upper values in key order
     * @param rightHandSide receives the negative gradient
     */
    @Override
    public void assemble(double[] x, double[] diagonal, double[] upperValues,
            double[] rightHandSide) {
        int cornerBase = firstTriangle * HalfEdgeMesh.TRIANGLE_CORNERS;
        double[] targetX = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] targetY = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] operator = new double[GridMapOptimizer.OPERATOR_SIZE];
        double[] position = new double[GridMapOptimizer.SLOT_COORDINATES];
        for (int triangle = firstTriangle; triangle < endTriangle; triangle++) {
            System.arraycopy(optimizer.operatorByTriangle, triangle * GridMapOptimizer.OPERATOR_SIZE,
                    operator, 0, GridMapOptimizer.OPERATOR_SIZE);
            for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
                int cornerIndex = triangle * HalfEdgeMesh.TRIANGLE_CORNERS + corner;
                optimizer.cornerPosition(cornerIndex, localSlotByCorner[cornerIndex - cornerBase],
                        x, position);
                targetX[corner] = position[0];
                targetY[corner] = position[1];
            }
            element.evaluate(operator, optimizer.areaByTriangle[triangle], targetX, targetY);
            optimizer.rotateElementToSlots(triangle, element);
            int variableBase = (triangle - firstTriangle) * SymmetricDirichletEnergy.VARIABLES;
            for (int row = 0; row < SymmetricDirichletEnergy.VARIABLES; row++) {
                rightHandSide[variableByTriangle[variableBase + row]] -= element.gradient[row];
            }
            int entryBase = (triangle - firstTriangle) * ELEMENT_ENTRIES;
            for (int entry = 0; entry < ELEMENT_ENTRIES; entry++) {
                int destination = destinationByEntry[entryBase + entry];
                if (destination == SKIPPED_ENTRY) {
                    continue;
                }
                double value = element.hessian[entry / SymmetricDirichletEnergy.VARIABLES][
                        entry % SymmetricDirichletEnergy.VARIABLES];
                if (destination < 0) {
                    diagonal[-destination - 1] += value;
                } else {
                    upperValues[destination] += value;
                }
            }
        }
    }
}
