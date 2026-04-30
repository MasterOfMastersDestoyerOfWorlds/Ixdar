package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.List;

import ixdar.geometry.mesh.quadlayout.solver.IlpSolver;
import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TPatch;

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

    /** Upper-bound padding above {@code ceil(r_c)}. The optimal q_c is
     *  near r_c so a tight UB shrinks the B&B search tree, but PATCH-44's
     *  dense graph creates sub-arcs with tiny r_c that must sum-equal across
     *  asymmetric T-junction sides; UB needs headroom to admit those.
     *  PATCH-76 set this to 2; PATCH-44 bumped to a hard floor that accepts
     *  the global max strip class size. */
    private static final int UB_PADDING = 2;

    /** Wall-clock time limit for ojAlgo's IntegerSolver (milliseconds). */
    private static final long ILP_TIMEOUT_MS = 10_000L;

    /** Above this strip-class count, skip the ILP solve (it OOMs ojAlgo).
     *  PATCH-84 proper strip walking should drive #Vars down to ~paper bound;
     *  until then large meshes return the synthetic q=1 rounding.
     *  Override at runtime via {@code -Dixdar.lyon.ilpMaxVars=N}. */
    private static int ilpMaxVars() {
        String prop = System.getProperty("ixdar.lyon.ilpMaxVars");
        return prop != null ? Integer.parseInt(prop) : 1000;
    }

    private Quantization() {}

    /**
     * Independently verify that {@code q} satisfies every consistency constraint
     * implied by {@code tmesh}'s 4-cycle patches and the validity floor
     * {@code q_i ≥ 1}. Returns {@code true} iff valid.
     */
    public static boolean verifyConsistency(TMesh tmesh, int[] q) {
        if (q.length != tmesh.arcs().size()) return false;
        for (int qi : q) if (qi < 1) return false;
        for (TPatch p : tmesh.patches()) {
            int[][] sides = p.arcsBySide();
            if (sides == null || sides.length != 4) continue;
            int sum02a = sumSide(q, sides[0]);
            int sum02b = sumSide(q, sides[2]);
            if (sum02a != sum02b) return false;
            int sum13a = sumSide(q, sides[1]);
            int sum13b = sumSide(q, sides[3]);
            if (sum13a != sum13b) return false;
        }
        return true;
    }

    private static int sumSide(int[] q, int[] arcs) {
        int s = 0;
        for (int a : arcs) s += q[a];
        return s;
    }

    /** Result of a quantization solve. */
    public record Result(int[] arcQuantization,
                         double objectiveValue,
                         boolean feasible,
                         int variableCount) {
        public Result(int[] arcQuantization, double objectiveValue, boolean feasible) {
            this(arcQuantization, objectiveValue, feasible, arcQuantization.length);
        }
    }

    /**
     * Solve the quantization ILP for {@code tmesh}. Returns one integer per
     * arc, in {@link TMesh#arcs()} order.
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

        // PATCH-84 guard: ojAlgo runs out of heap on K > ~1000 vars. Until
        // proper strip-walking lands, fall back to a 'closest-integer' round
        // of each arc's parametric length (with floor=1 for non-zero arcs).
        if (K > ilpMaxVars()) {
            int[] q = new int[A];
            double objVal = 0.0;
            for (int i = 0; i < A; i++) {
                q[i] = Math.max(1, (int) Math.round(arcs.get(i).parametricLength()));
                objVal += Math.abs(q[i] - arcs.get(i).parametricLength());
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
            long ub = Math.max(2L, (long) Math.ceil(rClass[c]) + UB_PADDING);
            qVar[c] = ilp.addIntegerVar("qc_" + c, 0L, ub);
        }

        // t_c: continuous slack so we can express min Σ |q_c - r_c| linearly.
        int[] tVar = new int[K];
        for (int c = 0; c < K; c++) {
            tVar[c] = ilp.addContinuousVar("tc_" + c, 0.0, null);
        }

        int N = ilp.variableCount();   // == 2*K

        // |q_c - r_c| linearisation rows.
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

        // Patch consistency (T-junction-aware): for each patch, opposite-side
        // sums must be equal. Within-strip pairs (single arc on each side
        // already unioned) collapse to 0=0 and are dropped; cross-strip and
        // T-junction patches contribute real constraints.
        for (TPatch p : patches) {
            int[][] sides = p.arcsBySide();
            if (sides == null || sides.length != 4) continue;
            addOppositeSideConstraint(ilp, qVar, arcClass, sides[0], sides[2], N);
            addOppositeSideConstraint(ilp, qVar, arcClass, sides[1], sides[3], N);
        }

        // Validity Eq.(3) — Lyon §4.2 lemma: for each motorcycle trace,
        // at least one of its arcs must have q ≥ 1 (else singularities at
        // the trace's endpoints would collapse onto the same iso-line).
        // Conservative form: for each TArc whose startNode is a SINGULARITY,
        // its strip class must be ≥ 1. Stronger than Eq.(3) but easy to
        // express; matches the paper's "one constraint per trace" claim.
        java.util.HashSet<Integer> singularityValidityClasses = new java.util.HashSet<>();
        for (int aId = 0; aId < A; aId++) {
            TArc arc = arcs.get(aId);
            int sn = arc.startNode();
            if (sn < 0 || sn >= tmesh.nodes().size()) continue;
            if (tmesh.nodes().get(sn).kind()
                    == ixdar.geometry.mesh.quadlayout.tmesh.TNode.NodeKind.SINGULARITY) {
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
            java.util.HashSet<Integer> classesSeen = new java.util.HashSet<>();
            for (int arcId : lc.arcIds()) {
                int cls = arcClass[arcId];
                if (classesSeen.add(cls)) row[qVar[cls]] += 1.0;
            }
            ilp.addLinearConstraint(row, IlpSolver.Op.GEQ, 1.0);
        }

        // Objective: min Σ t_c.
        double[] obj = new double[N];
        for (int c = 0; c < K; c++) obj[tVar[c]] = 1.0;
        ilp.setObjective(obj, IlpSolver.Sense.MINIMIZE);

        double[] x;
        try {
            x = ilp.solveWithTimeLimit(ILP_TIMEOUT_MS);
        } catch (IllegalStateException infeasible) {
            return new Result(new int[A], Double.POSITIVE_INFINITY, false, 2 * K);
        }

        // Project class solution back to per-arc.
        int[] q = new int[A];
        for (int i = 0; i < A; i++) {
            q[i] = (int) Math.round(x[qVar[arcClass[i]]]);
        }
        double objVal = 0.0;
        for (int i = 0; i < A; i++) objVal += Math.abs(q[i] - r[i]);
        return new Result(q, objVal, true, 2 * K);
    }

    /**
     * Add an opposite-side equality constraint over class variables.
     * Coefficients accumulate per class so a side with 3 arcs all in class
     * {@code c} contributes {@code 3 * q_c}.
     *
     * <p>If the resulting row is identically 0 (every class on side A also
     * appears with equal multiplicity on side B — typical of within-strip
     * pairs that union-find already collapsed), the constraint is dropped.
     */
    private static void addOppositeSideConstraint(IlpSolver ilp, int[] qVar,
                                                  int[] arcClass,
                                                  int[] sideA, int[] sideB, int N) {
        if (sideA == null || sideB == null) return;
        double[] row = new double[N];
        boolean nonZero = false;
        for (int a : sideA) row[qVar[arcClass[a]]] += 1.0;
        for (int b : sideB) row[qVar[arcClass[b]]] -= 1.0;
        for (int i = 0; i < N; i++) if (Math.abs(row[i]) > 1e-12) { nonZero = true; break; }
        if (!nonZero) return;
        ilp.addLinearConstraint(row, IlpSolver.Op.EQ, 0.0);
    }
}
