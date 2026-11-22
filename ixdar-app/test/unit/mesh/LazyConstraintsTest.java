package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

import ixdar.numeric.sparse.matrix.NormalMatrix;
import ixdar.numeric.sparse.system.DofSystem;
import ixdar.numeric.sparse.system.LazyConstraints;

/**
 * The BCE13 §3.4 lazy-constraint loop over one DOF with the objective ½x², whose
 * inequalities are the test's own rows {@code coefficient·x ≥ 1}.
 *
 * <p>
 * A round whose active set the interior-point solve proves infeasible throws: every later round
 * only adds rows to that set, and the map it leaves is invalid.
 */
class LazyConstraintsTest implements LazyConstraints.ConstraintSet {

    /** Round cap far above what either case needs, so a spinning loop would show. */
    private static final int MAX_ROUNDS = 60;

    /** Slack allowed on the solved DOF. */
    private static final double SOLUTION_TOLERANCE = 1.0e-6;

    /** The value below which a satisfied row is activated anyway. */
    private static final double ACTIVATION_THRESHOLD = 0.5;

    /** Each row's coefficient on the single DOF. */
    private double[] rowCoefficient;

    /** Calls to {@link #evaluate}, one per round the loop opened. */
    private int evaluations;

    /**
     * A single row {@code x ≥ 1} is violated at the start, activated, and solved
     * onto its bound in one round, with no infeasibility reported.
     */
    @Test
    void aSatisfiableRowIsSolvedOntoItsBound() {
        rowCoefficient = new double[] { 1.0 };
        DofSystem system = new DofSystem(1);
        LazyConstraints loop = new LazyConstraints(system, objective(), this, MAX_ROUNDS);
        loop.run();

        assertEquals(0, loop.violated, "x ≥ 1 is satisfiable");
        assertEquals(1.0, system.solution[0], SOLUTION_TOLERANCE, "½x² is least on the bound");
    }

    /**
     * The rows {@code x ≥ 1} and {@code −x ≥ 1} admit no x; the first round's solve proves it,
     * and the loop throws, naming both rows, instead of running on or returning an invalid x.
     */
    @Test
    void anInfeasibleActiveSetThrowsNamingItsRows() {
        rowCoefficient = new double[] { 1.0, -1.0 };
        DofSystem system = new DofSystem(1);
        LazyConstraints loop = new LazyConstraints(system, objective(), this, MAX_ROUNDS);

        IllegalStateException failure = assertThrows(IllegalStateException.class, loop::run,
                "x ≥ 1 and −x ≥ 1 together admit no x");
        assertEquals(1, evaluations, "the first solved round proves it, before another opens");
        assertTrue(failure.getMessage().contains("constraint 0")
                && failure.getMessage().contains("constraint 1"), failure.getMessage());
    }

    @Override
    public int constraintCount() {
        return rowCoefficient.length;
    }

    @Override
    public void evaluate(double[] x, double[] out) {
        evaluations++;
        for (int row = 0; row < rowCoefficient.length; row++) {
            out[row] = rowCoefficient[row] * x[0] - 1.0;
        }
    }

    @Override
    public int[] gradientDofs(int constraint) {
        return new int[] { 0 };
    }

    @Override
    public double[] gradientCoefs(int constraint) {
        return new double[] { rowCoefficient[constraint] };
    }

    @Override
    public double bound(int constraint) {
        return 1.0;
    }

    @Override
    public double activationThreshold() {
        return ACTIVATION_THRESHOLD;
    }

    /**
     * The objective ½x² with no linear term.
     *
     * @return its one-by-one system
     */
    private NormalMatrix objective() {
        return new NormalMatrix(new double[] { 1.0 }, new HashMap<>(), new double[1]);
    }
}
