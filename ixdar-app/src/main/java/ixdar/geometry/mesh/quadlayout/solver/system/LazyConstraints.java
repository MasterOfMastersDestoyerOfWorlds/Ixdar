package ixdar.geometry.mesh.quadlayout.solver.system;

import ixdar.geometry.mesh.quadlayout.solver.InteriorPointQp;
import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;
import ixdar.platform.Platforms;

/**
 * Lazy-constraint loop over a {@link DofSystem}: evaluate every inequality,
 * activate the violated plus every one below the activation threshold, and
 * re-solve the hard-constrained convex QP over the active set until no
 * constraint is violated or the round cap is reached.
 *
 * <p>See also: BCE13 Section 3.4
 */
public final class LazyConstraints {

    public final DofSystem dofs;

    /** The quadratic objective the QP minimizes. */
    public final NormalMatrix matrix;

    public final ConstraintSet constraints;

    /** Constraint rounds before giving up. */
    public final int maxRounds;

    /** Constraints still violated when the loop ended. */
    public int violated;

    /** The linear inequality set the loop enforces lazily. */
    public interface ConstraintSet {

        /**
         * The number of inequalities.
         *
         * @return constraint count
         */
        int constraintCount();

        /**
         * Evaluates every normalized inequality at x.
         *
         * @param x   candidate solution
         * @param out receives one value per constraint; negative means violated
         */
        void evaluate(double[] x, double[] out);

        /**
         * The sparse gradient's DOF indices of one constraint.
         *
         * @param constraint constraint index
         * @return DOF indices
         */
        int[] gradientDofs(int constraint);

        /**
         * The sparse gradient's coefficients of one constraint.
         *
         * @param constraint constraint index
         * @return coefficients matching {@link #gradientDofs}
         */
        double[] gradientCoefs(int constraint);

        /**
         * The right-hand bound of one constraint, {@code a·x >= bound}.
         *
         * @param constraint constraint index
         * @return the bound
         */
        double bound(int constraint);

        /**
         * The value below which a not-yet-violated constraint is activated.
         *
         * @return the activation threshold
         */
        double activationThreshold();
    }

    /**
     * Stores the system, objective and constraint set.
     *
     * @param dofs        system whose solution is constrained in place
     * @param matrix      the quadratic objective
     * @param constraints the inequality set
     * @param maxRounds   constraint rounds before giving up
     */
    public LazyConstraints(DofSystem dofs, NormalMatrix matrix, ConstraintSet constraints,
            int maxRounds) {
        this.dofs = dofs;
        this.matrix = matrix;
        this.constraints = constraints;
        this.maxRounds = maxRounds;
    }

    /**
     * Runs the loop, mutating {@code dofs.solution} in place; check
     * {@link #violated} for the outcome.
     */
    public void run() {
        double[] values = new double[constraints.constraintCount()];
        boolean[] constraintActive = new boolean[constraints.constraintCount()];
        int activeCount = 0;
        for (int round = 0; round <= maxRounds; round++) {
            constraints.evaluate(dofs.solution, values);
            violated = 0;
            double worst = Double.POSITIVE_INFINITY;
            for (int constraint = 0; constraint < values.length; constraint++) {
                worst = Math.min(worst, values[constraint]);
                violated += values[constraint] < 0.0 ? 1 : 0;
            }
            Platforms.log("[lazy-constraints] round %d violated=%d active=%d worst=%.4f%n",
                    round, violated, activeCount, worst);
            if (violated == 0 || round == maxRounds) {
                break;
            }
            long roundStart = System.nanoTime();
            for (int constraint = 0; constraint < values.length; constraint++) {
                if (!constraintActive[constraint]
                        && values[constraint] < constraints.activationThreshold()) {
                    constraintActive[constraint] = true;
                    activeCount++;
                }
            }
            int[][] activeDofs = new int[activeCount][];
            double[][] activeCoefs = new double[activeCount][];
            double[] activeBound = new double[activeCount];
            int activeCursor = 0;
            for (int constraint = 0; constraint < values.length; constraint++) {
                if (!constraintActive[constraint]) {
                    continue;
                }
                activeDofs[activeCursor] = constraints.gradientDofs(constraint);
                activeCoefs[activeCursor] = constraints.gradientCoefs(constraint);
                activeBound[activeCursor] = constraints.bound(constraint);
                activeCursor++;
            }
            InteriorPointQp qp = new InteriorPointQp(matrix, activeDofs, activeCoefs, activeBound);
            qp.solve(dofs.solution);
            Platforms.log(
                    "[lazy-constraints] round %d %.3fs (ipIterations=%d, factorizations=%d,"
                            + " converged=%b)%n",
                    round, (System.nanoTime() - roundStart) / 1.0e9, qp.iterationCount,
                    qp.factorizationCount, qp.converged);
        }
    }
}
