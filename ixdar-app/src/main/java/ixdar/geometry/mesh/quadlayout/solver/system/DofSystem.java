package ixdar.geometry.mesh.quadlayout.solver.system;

import ixdar.geometry.mesh.quadlayout.solver.matrix.NormalMatrix;

/**
 * The canonical solve state every strategy operates on: one solution vector,
 * one frozen mask, and the hooks a producing stage supplies (assembly, energy,
 * write-back). Strategies in this package mutate {@link #solution} in place.
 */
public final class DofSystem {

    public final int dofCount;

    /** The solution vector; strategies mutate it in place. */
    public final double[] solution;

    /** What the shared solvers freeze: row removed, value read from {@link #solution}. */
    public final boolean[] frozen;

    /** Fills the SPD system at a solution; constant for linear stacks. */
    public Assembler assembler;

    /**
     * The objective at a solution; null means the quadratic 0.5 x'Ax - b'x of
     * the assembled system.
     */
    public Energy energy;

    /** Pushes {@link #solution} into the owning model. */
    public Runnable writeBack;

    /** The producing stage's own solve pipeline; null means one direct solve. */
    public Runnable solve;

    /** Fills the SPD system (matrix values and right-hand side) at a solution. */
    public interface Assembler {

        /**
         * The SPD system at x.
         *
         * @param x candidate solution
         * @return the assembled system; may be a refreshed persistent instance
         */
        NormalMatrix assemble(double[] x);
    }

    /** Evaluates the objective at a solution. */
    public interface Energy {

        /**
         * The objective at x.
         *
         * @param x candidate solution
         * @return energy at x; may be positive infinity past a barrier
         */
        double energy(double[] x);
    }

    /**
     * Allocates the canonical state.
     *
     * @param dofCount number of degrees of freedom
     */
    public DofSystem(int dofCount) {
        this.dofCount = dofCount;
        this.solution = new double[dofCount];
        this.frozen = new boolean[dofCount];
    }

    /**
     * Runs the producing stage's solve pipeline, or one plain
     * {@link SingleSolve} when the stage wired none.
     */
    public void relax() {
        if (solve != null) {
            solve.run();
            return;
        }
        new SingleSolve(this).run();
    }

    /**
     * The SPD system at the current solution.
     *
     * @return the assembled system
     */
    public NormalMatrix assemble() {
        return assembler.assemble(solution);
    }

    /**
     * The objective at the current solution: the supplied energy hook, or the
     * assembled system's quadratic energy when none is set.
     *
     * @return energy at the current solution
     */
    public double energy() {
        if (energy != null) {
            return energy.energy(solution);
        }
        return assemble().quadraticEnergy(solution);
    }

    /**
     * Freezes one degree of freedom at a value.
     *
     * @param dof   degree of freedom to freeze
     * @param value value it is held at
     */
    public void freeze(int dof, double value) {
        frozen[dof] = true;
        solution[dof] = value;
    }
}
