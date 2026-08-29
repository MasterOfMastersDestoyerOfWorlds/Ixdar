package ixdar.geometry.mesh.quadlayout.solver.system;

import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.matrix.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.ordering.OrderingMethod;

/**
 * One direct linear solve of a {@link DofSystem}: assemble, factorize with the
 * frozen mask, solve, write the solution back. For a quadratic objective this
 * is the exact minimizer, i.e. Newton in one step.
 */
public final class SingleSolve {

    public final DofSystem dofs;

    /**
     * Stores the system to solve.
     *
     * @param dofs the DOF system
     */
    public SingleSolve(DofSystem dofs) {
        this.dofs = dofs;
    }

    /**
     * Runs the solve, mutating {@code dofs.solution} in place and pushing the
     * result through the system's write-back hook when one is set.
     */
    public void run() {
        NormalMatrix matrix = dofs.assemble();
        DirectSolver.CholeskyHandle handle =
                DirectSolver.factorize(matrix, dofs.frozen, OrderingMethod.AMD);
        DirectSolver.solveCompact(handle, matrix, matrix.rightHandSide,
                dofs.solution, dofs.solution, dofs.frozen);
        if (dofs.writeBack != null) {
            dofs.writeBack.run();
        }
    }
}
