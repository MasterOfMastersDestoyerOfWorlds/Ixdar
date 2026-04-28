package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.List;

import ixdar.geometry.mesh.quadlayout.solver.IlpSolver;
import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TPatch;

/**
 * T-mesh quantization (PATCH-42 — Campen-Bommes-Kobbelt 2015 base ILP).
 *
 * <p>Assigns a non-negative integer arc length {@code q_i} to every T-mesh
 * arc {@code a_i} so that
 * <ol>
 *   <li><b>Consistency</b>: opposite sides of every T-mesh patch sum to the
 *       same integer (a quad cell column is the same height on both sides).</li>
 *   <li><b>Validity</b>: {@code q_i ≥ 1} on every arc — no degenerate
 *       zero-width strips.</li>
 *   <li><b>Closeness to the parametric target</b>: {@code q_i} is as close
 *       as possible to {@link TArc#parametricLength()}, the real-valued
 *       cumulative {@code |Δu|}/{@code |Δv|} the arc traverses in the
 *       seamless parametrization.</li>
 * </ol>
 *
 * <p>v1 implements the Campen 2015 base form: consistency + validity +
 * absolute-deviation objective {@code min Σ |q_i − r_i|} via auxiliary slack
 * variables. PATCH-44 adds Lyon 2021's singularity-separation lemma and
 * angular-deviation layout constraints on top.
 *
 * <p>Patch enumeration: {@link TMesh#patches()} returns 4-cycles of arcs
 * (no T-junctions yet — a future ticket in the umbrella). Opposite-side
 * sum reduces to {@code q[arc0] = q[arc2]} and {@code q[arc1] = q[arc3]} on
 * those simple patches. T-junction-aware sums use the same ILP shape, just
 * with summed coefficients on each side.
 *
 * <p>Solver: ojAlgo's {@link IlpSolver} (branch-and-bound + Gomory cuts).
 * Typical scale: hundreds of integer + slack variables on Hand-30k.
 */
public final class Quantization {

    /** Default upper bound multiplier on each q_i: {@code ceil(2 * r_i) + 5}. */
    private static final int UB_PADDING = 5;

    private Quantization() {}

    /**
     * Independently verify that {@code q} satisfies every consistency constraint
     * implied by {@code tmesh}'s 4-cycle patches and the validity floor
     * {@code q_i ≥ 1}. Returns {@code true} iff valid; useful for downstream
     * code that consumes the result (PATCH-43 QEx must reject inconsistent
     * inputs early).
     */
    public static boolean verifyConsistency(TMesh tmesh, int[] q) {
        if (q.length != tmesh.arcs().size()) return false;
        for (int qi : q) if (qi < 1) return false;
        for (TPatch p : tmesh.patches()) {
            int[] ids = p.arcIds();
            if (ids.length != 4) continue;
            if (q[ids[0]] != q[ids[2]]) return false;   // left == right
            if (q[ids[1]] != q[ids[3]]) return false;   // top == bottom
        }
        return true;
    }

    /** Result of a quantization solve. */
    public record Result(int[] arcQuantization,
                         double objectiveValue,
                         boolean feasible) {
    }

    /**
     * Solve the quantization ILP for {@code tmesh}. Returns one integer per
     * arc, in {@link TMesh#arcs()} order.
     *
     * @throws IllegalStateException if the solver reports infeasibility
     */
    public static Result solve(TMesh tmesh) {
        List<TArc> arcs = tmesh.arcs();
        List<TPatch> patches = tmesh.patches();
        int A = arcs.size();
        if (A == 0) {
            return new Result(new int[0], 0.0, true);
        }

        IlpSolver ilp = new IlpSolver();

        // q_i: integer arc length, [1, ceil(2*r_i)+5]. Lower bound 1 enforces
        // basic validity (no zero-width strip). Upper bound is a generous cap
        // so the search space stays bounded for ojAlgo's branch-and-bound.
        int[] qVar = new int[A];
        double[] r = new double[A];
        for (int i = 0; i < A; i++) {
            r[i] = arcs.get(i).parametricLength();
            long ub = Math.max(2L, (long) Math.ceil(2.0 * r[i]) + UB_PADDING);
            qVar[i] = ilp.addIntegerVar("q_" + i, 1L, ub);
        }

        // t_i: continuous slack so we can express min Σ |q_i - r_i| as a
        // linear program: t_i ≥ q_i - r_i AND t_i ≥ r_i - q_i, then min Σ t_i.
        int[] tVar = new int[A];
        for (int i = 0; i < A; i++) {
            tVar[i] = ilp.addContinuousVar("t_" + i, 0.0, null);
        }

        int N = ilp.variableCount();   // == 2*A

        // |q_i - r_i| linearisation rows.
        for (int i = 0; i < A; i++) {
            // q_i - t_i ≤ r_i
            double[] rowA = new double[N];
            rowA[qVar[i]] = 1.0;
            rowA[tVar[i]] = -1.0;
            ilp.addLinearConstraint(rowA, IlpSolver.Op.LEQ, r[i]);
            // -q_i - t_i ≤ -r_i  (equiv. q_i + t_i ≥ r_i)
            double[] rowB = new double[N];
            rowB[qVar[i]] = -1.0;
            rowB[tVar[i]] = -1.0;
            ilp.addLinearConstraint(rowB, IlpSolver.Op.LEQ, -r[i]);
        }

        // Patch consistency: for v1 4-cycle patches, opposite arcs are equal.
        // arcIds layout (TPatch docstring): [left, top, right, bottom].
        for (TPatch p : patches) {
            int[] ids = p.arcIds();
            if (ids.length != 4) continue;   // skip non-4 cycles defensively

            // q[left] = q[right]
            double[] rowLR = new double[N];
            rowLR[qVar[ids[0]]] = 1.0;
            rowLR[qVar[ids[2]]] = -1.0;
            ilp.addLinearConstraint(rowLR, IlpSolver.Op.EQ, 0.0);

            // q[top] = q[bottom]
            double[] rowTB = new double[N];
            rowTB[qVar[ids[1]]] = 1.0;
            rowTB[qVar[ids[3]]] = -1.0;
            ilp.addLinearConstraint(rowTB, IlpSolver.Op.EQ, 0.0);
        }

        // Objective: min Σ t_i (zero on q_i themselves).
        double[] obj = new double[N];
        for (int i = 0; i < A; i++) obj[tVar[i]] = 1.0;
        ilp.setObjective(obj, IlpSolver.Sense.MINIMIZE);

        double[] x;
        try {
            x = ilp.solve();
        } catch (IllegalStateException infeasible) {
            return new Result(new int[A], Double.POSITIVE_INFINITY, false);
        }

        int[] q = new int[A];
        for (int i = 0; i < A; i++) {
            q[i] = (int) Math.round(x[qVar[i]]);
        }
        double objVal = 0.0;
        for (int i = 0; i < A; i++) {
            objVal += Math.abs(q[i] - r[i]);
        }
        return new Result(q, objVal, true);
    }
}
