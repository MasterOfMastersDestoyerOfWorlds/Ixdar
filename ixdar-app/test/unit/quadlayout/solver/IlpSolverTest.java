package unit.quadlayout.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.IlpSolver;

public class IlpSolverTest {

    /**
     * Textbook MIP: max 3x + 2y  s.t. x + y ≤ 4, x ≤ 3, y ≤ 3, x,y ≥ 0,
     * x,y integer. Optimum = (3, 1) value 11.
     */
    @Test
    void textbookSmall() {
        IlpSolver s = new IlpSolver();
        int x = s.addIntegerVar("x", 0L, 3L);
        int y = s.addIntegerVar("y", 0L, 3L);
        assertEquals(0, x);
        assertEquals(1, y);
        s.addLinearConstraint(new double[]{ 1.0, 1.0 }, IlpSolver.Op.LEQ, 4.0);
        s.setObjective(new double[]{ 3.0, 2.0 }, IlpSolver.Sense.MAXIMIZE);
        double[] sol = s.solve();
        assertEquals(2, sol.length);
        // Allow for either (3,1) or any equivalent integer optimum.
        assertTrue(Math.abs(3 * sol[0] + 2 * sol[1] - 11.0) < 1e-6,
                "value=" + (3 * sol[0] + 2 * sol[1]));
    }

    /**
     * 100-variable MIP, knapsack-style: max c·x s.t. a·x ≤ b, x ∈ {0,1}^n.
     * We just verify the solver returns within 1s and the constraint is
     * respected.
     */
    @Test
    void hundredVariableUnderOneSecond() {
        int n = 100;
        IlpSolver s = new IlpSolver();
        for (int i = 0; i < n; i++) s.addBinaryVar("x" + i);
        double[] cost = new double[n];
        double[] weight = new double[n];
        java.util.Random rng = new java.util.Random(13);
        for (int i = 0; i < n; i++) {
            cost[i] = rng.nextInt(50) + 1;
            weight[i] = rng.nextInt(20) + 1;
        }
        s.addLinearConstraint(weight, IlpSolver.Op.LEQ, 200.0);
        s.setObjective(cost, IlpSolver.Sense.MAXIMIZE);
        long t0 = System.nanoTime();
        double[] x = s.solve();
        long t1 = System.nanoTime();
        double seconds = (t1 - t0) / 1e9;
        assertTrue(seconds < 1.0, "100-var MIP took " + seconds + "s, want <1s");
        // Verify feasibility.
        double w = 0.0;
        for (int i = 0; i < n; i++) {
            assertTrue(x[i] >= -1e-6 && x[i] <= 1 + 1e-6, "x[" + i + "] = " + x[i]);
            w += weight[i] * Math.round(x[i]);
        }
        assertTrue(w <= 200.0 + 1e-6, "weight constraint violated: " + w);
    }
}
