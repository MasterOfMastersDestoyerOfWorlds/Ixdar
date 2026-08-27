package ixdar.geometry.mesh.quadlayout.solver.system;

import ixdar.geometry.mesh.quadlayout.solver.AMDOrdering;
import ixdar.geometry.mesh.quadlayout.solver.CholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.IncrementalCholeskySolver;
import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;
import ixdar.platform.Platforms;

/**
 * Greedy integer rounding over a {@link DofSystem}: solve relaxed, snap the
 * candidate closest to an integer, pin it as a rank-1 penalty update of the
 * retained factor, re-solve, repeat until every candidate is pinned.
 *
 * <p>See also: BZK09 Section 5, Davis chapter 4.10
 */
public final class GreedyRounding {

    public final DofSystem dofs;

    /** Which degrees of freedom are integer candidates. */
    public final boolean[] candidate;

    /** Shared pin mask; {@link #onPin} must set the pinned entry true. */
    public final boolean[] pinned;

    /** Diagonal penalty weight one pin adds. */
    public final double penaltyWeight;

    /** Records one pin in the owning stage (mask and value bookkeeping). */
    public final PinListener onPin;

    /**
     * The fast path's retained factor when no candidate needed pinning; the
     * caller owns its release.
     */
    public DirectSolver.CholeskyHandle retainedHandle;

    /** The matrix behind {@link #retainedHandle}. */
    public NormalMatrix retainedMatrix;

    public int pinCount;

    /** Records one pin in the owning stage. */
    public interface PinListener {

        /**
         * One candidate was snapped.
         *
         * @param dof   degree of freedom pinned
         * @param value integer value it was pinned at
         */
        void pinned(int dof, double value);
    }

    /**
     * Stores the system and the rounding hooks.
     *
     * @param dofs          system whose solution is rounded in place
     * @param candidate     integer-candidate mask, length dofCount
     * @param pinned        shared pin mask, length dofCount
     * @param penaltyWeight diagonal penalty weight per pin
     * @param onPin         pin bookkeeping in the owning stage
     */
    public GreedyRounding(DofSystem dofs, boolean[] candidate, boolean[] pinned,
            double penaltyWeight, PinListener onPin) {
        this.dofs = dofs;
        this.candidate = candidate;
        this.pinned = pinned;
        this.penaltyWeight = penaltyWeight;
        this.onPin = onPin;
    }

    /**
     * Runs the rounding, mutating {@code dofs.solution} in place. With no
     * unpinned candidate and a native backend, one direct solve suffices and
     * its factor is retained for the caller.
     *
     * @throws IllegalStateException when the cold factor or a rank-1 update fails
     */
    public void run() {
        long assembleStart = System.nanoTime();
        NormalMatrix baseMatrix = dofs.assemble();
        long amdStart = System.nanoTime();
        AMDOrdering ordering = new AMDOrdering();
        ordering.order(baseMatrix);
        int[] perm = ordering.permutation;
        long amdEnd = System.nanoTime();

        boolean anyUnpinnedCandidate = false;
        for (int i = 0; i < dofs.dofCount; i++) {
            if (candidate[i] && !pinned[i]) {
                anyUnpinnedCandidate = true;
                break;
            }
        }
        if (!anyUnpinnedCandidate && CholeskyBackend.pardisoAvailable()) {
            long nativeFactorStart = System.nanoTime();
            retainedHandle = DirectSolver.factorizeWithPerm(baseMatrix, dofs.frozen, perm);
            retainedMatrix = baseMatrix;
            DirectSolver.solveCompact(retainedHandle, baseMatrix, baseMatrix.rightHandSide,
                    dofs.solution, dofs.solution, dofs.frozen);
            Platforms.log(
                    "[rounding] assemble %.3fs, amd %.3fs, native factor+solve %.3fs"
                            + " (n=%d, 0 candidates to pin)%n",
                    (amdStart - assembleStart) / 1.0e9,
                    (amdEnd - amdStart) / 1.0e9,
                    (System.nanoTime() - nativeFactorStart) / 1.0e9,
                    dofs.dofCount);
            return;
        }

        long coldFactorStart = System.nanoTime();
        IncrementalCholeskySolver incremental = new IncrementalCholeskySolver();
        if (!incremental.setAWithPerm(baseMatrix, perm)) {
            throw new IllegalStateException(
                    "greedy rounding: cold Cholesky factor of the base system failed");
        }
        long pinLoopStart = System.nanoTime();
        Platforms.log("[rounding] assemble %.3fs, amd %.3fs, cold factor %.3fs (n=%d)%n",
                (amdStart - assembleStart) / 1.0e9,
                (coldFactorStart - amdStart) / 1.0e9,
                (pinLoopStart - coldFactorStart) / 1.0e9,
                dofs.dofCount);
        incremental.solve(baseMatrix.rightHandSide, dofs.solution);
        double[] runningRhs = baseMatrix.rightHandSide.clone();

        while (true) {
            int bestIdx = -1;
            double bestDist = Double.POSITIVE_INFINITY;
            double bestValue = 0;
            for (int i = 0; i < dofs.dofCount; i++) {
                if (!candidate[i] || pinned[i]) {
                    continue;
                }
                double x = dofs.solution[i];
                double rounded = Math.rint(x);
                double dist = Math.abs(x - rounded);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestIdx = i;
                    bestValue = rounded;
                }
            }
            if (bestIdx < 0) {
                break;
            }
            onPin.pinned(bestIdx, bestValue);
            if (!incremental.pinDof(bestIdx, penaltyWeight)) {
                throw new IllegalStateException(
                        "greedy rounding: rank-1 update failed at DOF " + bestIdx);
            }
            runningRhs[bestIdx] += penaltyWeight * bestValue;
            incremental.solve(runningRhs, dofs.solution);
            pinCount++;
        }
        Platforms.log("[rounding] pin+solve loop %.3fs (%d pins)%n",
                (System.nanoTime() - pinLoopStart) / 1.0e9, pinCount);
    }
}
