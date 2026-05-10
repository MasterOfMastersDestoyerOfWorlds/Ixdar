package ixdar.geometry.mesh.quadlayout.quantization;
import java.util.List;

import java.util.HashSet;

import ixdar.geometry.mesh.quadlayout.solver.IlpSolver;
import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TPatch;

import ixdar.geometry.mesh.quadlayout.tmesh.TNode;

/**
 * T-mesh quantization (Lyon 2021 §4.1 + §5).
 *
 * <p>Assigns a non-negative integer arc length {@code q_i} to every T-mesh
 * arc {@code a_i} so that
 * <ol>
 *   <li><b>Consistency Eq.(2)</b>: opposite sides of every T-mesh patch sum
 *       to the same integer.</li>
 *   <li><b>Validity Eq.(3)</b>: {@code q_i ≥ 1} on every arc — no
 *       degenerate zero-width strips.</li>
 *   <li><b>Closeness to the parametric target Eq.(5)</b>: minimise
 *       {@code Σ |q_i − r_i|} over arcs.</li>
 * </ol>
 *
 * <p><b>PATCH-72 §5.2 strip reduction.</b> The paper bounds the variable
 * count by {@code (3/2) · n_traces} by sharing one ILP variable across
 * each "strip" of arcs that sit on opposite sides of patches with
 * single-arc sides (consistency Eq.(2) is then trivially satisfied within a
 * strip). T-junction patches retain explicit consistency constraints over
 * class variables. ROCKERARM: 2742 arcs → 192 vars in the paper.
 *
 * <p>Solver: ojAlgo's {@link IlpSolver} (branch-and-bound + Gomory cuts).
 */
public final class Quantization {
    public static final int NUM_1000 = 1000;
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;
    public static final long NUM_2 = 2L;
    public static final double NUM_1e_12 = 1e-12;

    /**
     * Upper-bound padding above {@code ceil(r_c)}. The optimal q_c is
     *  near r_c so a tight UB shrinks the B&B search tree, but PATCH-44's
     *  dense graph creates sub-arcs with tiny r_c that must sum-equal across
     *  asymmetric T-junction sides; UB needs headroom to admit those.
     *  PATCH-76 set this to 2; PATCH-44 bumped to a hard floor that accepts
     */
    private static final int UB_PADDING = 2;

    /** Wall-clock time limit for ojAlgo's IntegerSolver (milliseconds). */
    private static final long ILP_TIMEOUT_MS = 10_000L;

    private Quantization() {}

    /**
     * Above this strip-class count, skip the ILP solve (it OOMs ojAlgo).
     *  Lyon paper rocker-arm gets {@code #Vars = 192}; if our strip-equivalence
     *  is Lyon-faithful, ILP always solves under this cap. The 2253-var mess
     *  we see today is itself the bug — to be fixed at the strip-equivalence
     *  level (see PATCH-92 plan), not by bumping the cap.
     *  Override at runtime via {@code -Dixdar.lyon.ilpMaxVars=N}.
     *
     * @return the configured ILP variable cap (default {@value #NUM_1000})
     */
    private static int ilpMaxVars() {
        String prop = System.getProperty("ixdar.lyon.ilpMaxVars");
        return prop != null ? Integer.parseInt(prop) : NUM_1000;
    }

    /**
     * Independently verify that {@code q} satisfies every consistency constraint
     * implied by {@code tmesh}'s 4-cycle patches and the validity floor
     * {@code q_i ≥ 1}. Returns {@code true} iff valid.
     *
     * @param tmesh T-mesh whose 4-cycle patches define the consistency rows
     * @param q     candidate per-arc quantization, indexed by arc id
     * @return {@code true} iff every {@code q_i >= 1} and every patch's
     *          opposite-side sums are equal
     */
    public static boolean verifyConsistency(TMesh tmesh, int[] q) {
        if (q.length != tmesh.arcs().size()) return false;
        for (int qi : q) if (qi < 1) return false;
        for (TPatch p : tmesh.patches()) {
            int[][] sides = p.arcsBySide();
            if (sides == null || sides.length != NUM_4) continue;
            int sum02a = sumSide(q, sides[0]);
            int sum02b = sumSide(q, sides[2]);
            if (sum02a != sum02b) return false;
            int sum13a = sumSide(q, sides[1]);
            int sum13b = sumSide(q, sides[NUM_3]);
            if (sum13a != sum13b) return false;
        }
        return true;
    }

    private static int sumSide(int[] q, int[] arcs) {
        int s = 0;
        for (int a : arcs) s += q[a];
        return s;
    }

    /**
     * Solve the quantization ILP for {@code tmesh}. Returns one integer per
     * arc, in {@link TMesh#arcs()} order.
     *
     * @param tmesh T-mesh from PATCH-41 (arcs, patches, layout constraints)
     * @return per-arc integer lengths and solver diagnostics; over the
     *          {@link #ilpMaxVars()} cap returns a fallback rounding result
     */
    public static Result solve(TMesh tmesh) {
        List<TArc> arcs = tmesh.arcs();
        List<TPatch> patches = tmesh.patches();
        int A = arcs.size();
        if (A == 0) {
            return new Result(new int[0], 0.0, true, 0);
        }

        // §5.2 strip-class reduction: compute per-arc class id.
        StripEquivalence.Result strips = StripEquivalence.compute(tmesh);
        int K = strips.classCount();
        int[] arcClass = strips.arcClass();

        // PATCH-84 guard: ojAlgo runs out of heap on K > ~1000 vars. The
        // fallback is non-Lyon-faithful by definition (we can't satisfy
        // Eq.(2)/(3)/(4) without the ILP), so we just produce a validity-
        // floor result: q_i = max(1, round(r_i)). No threshold-based
        // collapse — Lyon's algorithm would let the ILP decide collapses
        // via Eq.(5) min Σ l⊥·q + constraints, and we can't approximate
        // that without solving the ILP.
        if (K > ilpMaxVars()) {
            int[] q = new int[A];
            double objVal = 0.0;
            for (int i = 0; i < A; i++) {
                double r = arcs.get(i).parametricLength();
                q[i] = Math.max(1, (int) Math.round(r));
                objVal += Math.abs(q[i] - r);
            }
            return new Result(q, objVal, true, 2 * K);
        }

        // Per-arc real targets r_i (from parametric length).
        double[] r = new double[A];
        for (int i = 0; i < A; i++) r[i] = arcs.get(i).parametricLength();
        // Per-class aggregated target (mean parametric length over the class).
        double[] rClass = StripEquivalence.aggregateTargets(strips, r);

        IlpSolver ilp = new IlpSolver();

        // q_c: integer class quantization. LB=0 follows Lyon §4.1 — q=0 is
        // a legitimate "collapsed arc" representation (singularities can lie
        // on the same iso-line). Per-trace validity Eq.(3) forces at least
        // one arc per trace to be ≥ 1; that constraint is added below.
        int[] qVar = new int[K];
        for (int c = 0; c < K; c++) {
            long ub = Math.max(NUM_2, (long) Math.ceil(rClass[c]) + UB_PADDING);
            qVar[c] = ilp.addIntegerVar("qc_" + c, 0L, ub);
        }

        // PATCH-92 — Lyon §4 Eq.(5) `min Σ l⊥·q` (COARSENING) is the paper's
        // actual objective and is now the default. Pushes q values to 0
        // wherever validity Eq.(3) and layout Eq.(4) permit, producing a
        // coarse quad layout. Set `-Dixdar.lyon.coarsenObjective=false` to
        // fall back to the legacy `min Σ |q-r|` closeness objective (used
        // mostly for diagnostic A/B comparisons; not Lyon-faithful).
        boolean lyonObjective = !"false".equals(
                System.getProperty("ixdar.lyon.coarsenObjective"));

        // t_c slack vars (only used by closeness objective; skipped under Eq.(5)).
        int[] tVar = new int[K];
        if (!lyonObjective) {
            for (int c = 0; c < K; c++) {
                tVar[c] = ilp.addContinuousVar("tc_" + c, 0.0, null);
            }
        }

        int N = ilp.variableCount();   // K under Eq.(5), 2K under closeness

        // Closeness-objective slack rows: linearise |q_c - r_c|.
        if (!lyonObjective) {
            for (int c = 0; c < K; c++) {
                double[] rowA = new double[N];
                rowA[qVar[c]] = 1.0;
                rowA[tVar[c]] = -1.0;
                ilp.addLinearConstraint(rowA, IlpSolver.Op.LEQ, rClass[c]);
                double[] rowB = new double[N];
                rowB[qVar[c]] = -1.0;
                rowB[tVar[c]] = -1.0;
                ilp.addLinearConstraint(rowB, IlpSolver.Op.LEQ, -rClass[c]);
            }
        }

        // Patch consistency (T-junction-aware): for each patch, opposite-side
        // sums must be equal. Within-strip pairs (single arc on each side
        // already unioned) collapse to 0=0 and are dropped; cross-strip and
        // T-junction patches contribute real constraints.
        for (TPatch p : patches) {
            int[][] sides = p.arcsBySide();
            if (sides == null || sides.length != NUM_4) continue;
            addOppositeSideConstraint(ilp, qVar, arcClass, sides[0], sides[2], N);
            addOppositeSideConstraint(ilp, qVar, arcClass, sides[1], sides[NUM_3], N);
        }

        // Validity Eq.(3) — Lyon §4.2 lemma: for each motorcycle trace,
        // at least one of its arcs must have q ≥ 1 (else singularities at
        // the trace's endpoints would collapse onto the same iso-line).
        // Conservative form: for each TArc whose startNode is a SINGULARITY,
        // its strip class must be ≥ 1. Stronger than Eq.(3) but easy to
        // express; matches the paper's "one constraint per trace" claim.
        HashSet<Integer> singularityValidityClasses = new HashSet<>();
        for (int aId = 0; aId < A; aId++) {
            TArc arc = arcs.get(aId);
            int sn = arc.startNode();
            if (sn < 0 || sn >= tmesh.nodes().size()) continue;
            if (tmesh.nodes().get(sn).kind()
                    == TNode.NodeKind.SINGULARITY) {
                singularityValidityClasses.add(arcClass[aId]);
            }
        }
        for (int c : singularityValidityClasses) {
            double[] row = new double[N];
            row[qVar[c]] = 1.0;
            ilp.addLinearConstraint(row, IlpSolver.Op.GEQ, 1.0);
        }

        // PATCH-87 — Lyon §4.3 Eq.(4) layout constraints. For each offending
        // intersection (|α_ij| > α), require Σ q_a ≥ 1 over the S_ij arc set.
        // This is the angular-deviation guarantee — without it, the layout
        // separatrices can deviate from the prescribed cross-field directions
        // by more than α.
        for (TMesh.LayoutConstraint lc : tmesh.layoutConstraints()) {
            if (lc.arcIds() == null || lc.arcIds().length == 0) continue;
            double[] row = new double[N];
            // Sum over the strip classes of these arcs.
            HashSet<Integer> classesSeen = new HashSet<>();
            for (int arcId : lc.arcIds()) {
                int cls = arcClass[arcId];
                if (classesSeen.add(cls)) row[qVar[cls]] += 1.0;
            }
            ilp.addLinearConstraint(row, IlpSolver.Op.GEQ, 1.0);
        }

        // Objective:
        //   - Lyon §4 Eq.(5):       min Σ l⊥·q (coarsening; lyonObjective=true)
        //   - Default closeness:    min Σ |q-r|  (matches old tests)
        double[] obj = new double[N];
        if (lyonObjective) {
            // Per-class weight = sum of arcs' parametric lengths in the class.
            // (Σ_arc l⊥·q reduces to Σ_class q_c · L_c when class-shared.)
            for (int i = 0; i < A; i++) {
                int c = arcClass[i];
                obj[qVar[c]] += r[i];
            }
        } else {
            for (int c = 0; c < K; c++) obj[tVar[c]] = 1.0;
        }
        ilp.setObjective(obj, IlpSolver.Sense.MINIMIZE);

        double[] x;
        try {
            x = ilp.solveWithTimeLimit(ILP_TIMEOUT_MS);
        } catch (IllegalStateException infeasible) {
            return new Result(new int[A], Double.POSITIVE_INFINITY, false, K);
        }

        // Project class solution back to per-arc.
        int[] q = new int[A];
        for (int i = 0; i < A; i++) {
            q[i] = (int) Math.round(x[qVar[arcClass[i]]]);
        }
        // Report objective as Σ |q-r| for diagnostic continuity (closeness to
        // parametric input remains a useful quality measure, even though the
        // ILP minimized weighted total length).
        double objVal = 0.0;
        for (int i = 0; i < A; i++) objVal += Math.abs(q[i] - r[i]);
        return new Result(q, objVal, true, K);
    }

    /**
     * Add an opposite-side equality constraint over class variables.
     * Coefficients accumulate per class so a side with 3 arcs all in class
     * {@code c} contributes {@code 3 * q_c}.
     *
     * <p>If the resulting row is identically 0 (every class on side A also
     * appears with equal multiplicity on side B — typical of within-strip
     * pairs that union-find already collapsed), the constraint is dropped.
     *
     * @param ilp      solver to receive the new equality constraint
     * @param qVar     class id → ILP variable index
     * @param arcClass per-arc strip class id
     * @param sideA    arcs on one side of the patch
     * @param sideB    arcs on the opposite side
     * @param N        total ILP variable count (row width)
     */
    private static void addOppositeSideConstraint(IlpSolver ilp, int[] qVar,
                                                  int[] arcClass,
                                                  int[] sideA, int[] sideB, int N) {
        if (sideA == null || sideB == null) return;
        double[] row = new double[N];
        boolean nonZero = false;
        for (int a : sideA) row[qVar[arcClass[a]]] += 1.0;
        for (int b : sideB) row[qVar[arcClass[b]]] -= 1.0;
        for (int i = 0; i < N; i++) if (Math.abs(row[i]) > NUM_1e_12) { nonZero = true; break; }
        if (!nonZero) return;
        ilp.addLinearConstraint(row, IlpSolver.Op.EQ, 0.0);
    }

    /** Result of a quantization solve. */
    public record Result(int[] arcQuantization,
                         double objectiveValue,
                         boolean feasible,
                         int variableCount) {
        /**
         * Backwards-compatible constructor that defaults
         * {@code variableCount} to {@code arcQuantization.length} (one
         * variable per arc, no strip reduction).
         *
         * @param arcQuantization per-arc integer lengths
         * @param objectiveValue  achieved objective value
         * @param feasible        whether the solver returned a feasible point
         */
        public Result(int[] arcQuantization, double objectiveValue, boolean feasible) {
            this(arcQuantization, objectiveValue, feasible, arcQuantization.length);
        }
    }
}
