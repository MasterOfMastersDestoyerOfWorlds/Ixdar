package ixdar.geometry.mesh.quadlayout.vectorfield.dvpsh;

import ixdar.geometry.mesh.quadlayout.solver.SparseLu;
import ixdar.geometry.mesh.quadlayout.solver.SparseMatrix;

/**
 * DVPSH14 complex-polynomial cross-field solver — paper-faithful path for
 * CIE*16 §4.1's "directionally hard-constrain" prescription. PATCH-125.
 *
 * <h2>Algorithm</h2>
 *
 * <p>For an N-PolyVector cross field (N=4), each face f carries a complex
 * polynomial coefficient {@code x_f = -(w_f)^4} where {@code w_f} is one
 * representative of the cross-field at f (the other three are
 * {@code i·w_f, -w_f, -i·w_f}). The 4th-power encoding makes {@code x_f}
 * automatically invariant under the 4-RoSy ψ ↔ ψ+π/2 equivalence AND the
 * line-field a_min ↔ -a_min sign ambiguity (since {@code (-w)^4 = w^4}).
 *
 * <p>For cross fields specifically, only the constant term of the polynomial
 * is variable (the others are zero by 4-RoSy symmetry, see DVPSH14 §4.1).
 * The smoothness energy from DVPSH14 eq.(6) reduces to a real combinatorial
 * Laplacian (no transport phase for the m=0 coefficient):
 *
 * <pre>
 *   E_smooth = Σ_e |x_f − x_g|²
 * </pre>
 *
 * <p>CIE*16 §4.1 directional constraints translate to Dirichlet pins:
 * {@code x_f = -e^(4i·θ_f)} for face f in a kept smooth region with target
 * angle θ_f = atan2(a_min·frame_v, a_min·frame_u). The 4th-power kills both
 * the line-field sign and the 4-RoSy class — adjacent constrained faces with
 * line-field-flipped a_min get the SAME x_f, so no spurious m_e jumps at
 * region-internal edges (the failure mode that drove our BZK09 path's 700+
 * singularity bloat per PATCH-115/116).
 *
 * <p>Real and imaginary parts of {@code x_f} decouple under the real
 * Laplacian, so we solve two independent real sparse systems via {@link SparseLu}.
 *
 * <p><b>Singularity recovery</b>: from {@code x_f}, the cross-field
 * representative is {@code w_f = (-x_f)^(1/4)} (principal branch). We extract
 * {@code θ_f = arg(w_f) ∈ [-π/4, π/4]}. Per-edge integer matchings
 * {@code m_e ∈ {-2, -1, 0, 1, 2}} are recovered from the Levi-Civita-aware
 * residual: {@code m_e = round((θ_b − θ_a − κ_e) / (π/2))}. The downstream
 * {@code SingularityFinder} then walks vertex stars exactly as it does for
 * BZK09 output — no changes needed downstream.
 *
 * <p><b>Boundary conditions</b>: the m=0 Laplacian's null space is the
 * constants. With at least one Dirichlet pin (gauge fix), the system is
 * invertible. If no constraints are supplied (CIE*16 disabled path), we pin
 * face 0 to {@code x_0 = 1} as a gauge — this produces a constant cross
 * field with NO singularities, which is the correct degenerate behavior on
 * a topologically trivial input but wrong on higher-genus surfaces. Don't
 * use the unconstrained DVPSH path for production; use BZK09.
 */
public final class DvpshCrossFieldSolver {
    public static final double NUM_4_0 = 4.0;
    public static final double NUM_2_0 = 2.0;

    /**
     * Penalty weight on Dirichlet pins. The Laplacian has small entries so
     */
    private static final double PIN_WEIGHT = 1e6;

    private DvpshCrossFieldSolver() {}

    /**
     * Solve the DVPSH14 cross-field complex polynomial: assemble the
     * combinatorial dual Laplacian, apply directional / gauge Dirichlet pins
     * via penalty, factor with {@link SparseLu}, recover per-face theta from
     * {@code arg(-x_f)/4}, and unwrap along a BFS dual spanning tree to emit
     * per-interior-edge integer matchings (BZK09 sign convention).
     *
     * @param F                 face count
     * @param E                 interior edge count
     * @param edgeFaceA         per interior-edge: face on the A-side
     * @param edgeFaceB         per interior-edge: face on the B-side
     * @param kappa             per interior-edge: parallel-transport rotation (radians)
     * @param thetaConstraint   per face: target angle (radians) when constrained, else ignored
     * @param constrained       per face: true if thetaConstraint[f] is active
     * @throws IllegalStateException if the LU factorization of the penalised
     *                               Laplacian fails or is singular
     * @return per-face theta + per-edge integer matchings, plus the count of
     *         faces actually constrained (incl. the gauge fallback)
     */
    public static Result solve(int F, int E,
                                int[] edgeFaceA, int[] edgeFaceB, double[] kappa,
                                double[] thetaConstraint, boolean[] constrained) {
        if (F == 0) return new Result(new double[0], new int[E], 0);

        // Build the real combinatorial Laplacian L (DVPSH14 eq.(7) for m=0).
        // L[f,f] = degree(f); L[f,g] = -1 per edge between f and g.
        SparseMatrix L = new SparseMatrix(F, F);
        for (int e = 0; e < E; e++) {
            int fa = edgeFaceA[e];
            int fb = edgeFaceB[e];
            L.add(fa, fa, 1.0);
            L.add(fb, fb, 1.0);
            L.add(fa, fb, -1.0);
            L.add(fb, fa, -1.0);
        }

        // Resolve effective constraint set. If nothing supplied, gauge-pin
        // face 0 to (1, 0) so the system is invertible.
        boolean[] effPin = constrained != null ? constrained.clone() : new boolean[F];
        double[] xR = new double[F];
        double[] xI = new double[F];
        if (constrained != null && thetaConstraint != null) {
            for (int f = 0; f < F; f++) {
                if (constrained[f]) {
                    double t = thetaConstraint[f];
                    xR[f] = -Math.cos(NUM_4_0 * t);
                    xI[f] = -Math.sin(NUM_4_0 * t);
                }
            }
        }
        int constrainedFaceCount = 0;
        for (int f = 0; f < F; f++) if (effPin[f]) constrainedFaceCount++;
        if (constrainedFaceCount == 0) {
            effPin[0] = true;
            xR[0] = 1.0;
            xI[0] = 0.0;
            constrainedFaceCount = 1;
        }

        // Apply Dirichlet pins via penalty: A = L + PIN_WEIGHT·diag(pin). RHS
        // similarly. This matches the IGM Hessian's pin convention so the
        // existing SparseLu codepath can solve directly.
        SparseMatrix A = new SparseMatrix(F, F);
        L.ojAlgoStore().nonzeros().forEach(view -> {
            int row = (int) view.row();
            int col = (int) view.column();
            A.add(row, col, view.doubleValue());
        });
        double[] rhsR = new double[F];
        double[] rhsI = new double[F];
        for (int f = 0; f < F; f++) {
            if (effPin[f]) {
                A.add(f, f, PIN_WEIGHT);
                rhsR[f] = PIN_WEIGHT * xR[f];
                rhsI[f] = PIN_WEIGHT * xI[f];
            }
        }

        SparseLu lu = new SparseLu();
        if (!lu.decompose(A) || !lu.isSolvable()) {
            throw new IllegalStateException("DVPSH14 Laplacian LU failed");
        }
        double[] solR = lu.solve(rhsR);
        double[] solI = lu.solve(rhsI);

        // Recover θ from x_0 = -w^4. arg(-x_0) = arg(w^4) = 4·arg(w), so
        // θ = arg(-x_0) / 4. Principal branch maps θ into [-π/4, π/4]; this
        // SHOULD be unwrapped per face for BZK09-style downstream consumption
        // (else SingularityFinder mis-attributes branch wraps as singularities).
        double[] theta = new double[F];
        for (int f = 0; f < F; f++) {
            double a = -solR[f];
            double b = -solI[f];
            theta[f] = Math.atan2(b, a) / NUM_4_0;
        }

        // BFS-based θ unwrapping along the dual spanning tree (Lyon/BZK09
        // convention): pick θ_g + (π/2)·k for the integer k minimizing
        // |θ_g + (π/2)·k − θ_f − κ_e|, so adjacent face θs are continuous in
        // the BZK09 4-RoSy sense. After unwrapping, tree-edge m_e = 0;
        // chord-edge m_e captures genuine field winding (genus / cones).
        double piHalf = Math.PI / NUM_2_0;
        int[] periodJump = new int[E];
        boolean[] visited = new boolean[F];
        int[] queue = new int[F];
        int qHead = 0, qTail = 0;
        // Build face → list of (edge, neighborFace) once.
        int[] faceEdgeStart = new int[F + 1];
        for (int e = 0; e < E; e++) {
            faceEdgeStart[edgeFaceA[e] + 1]++;
            faceEdgeStart[edgeFaceB[e] + 1]++;
        }
        for (int f = 1; f <= F; f++) faceEdgeStart[f] += faceEdgeStart[f - 1];
        int[] faceEdgeNbr = new int[2 * E];
        int[] faceEdgeIdx = new int[2 * E];
        int[] cursor = new int[F];
        for (int e = 0; e < E; e++) {
            int fa = edgeFaceA[e], fb = edgeFaceB[e];
            int slotA = faceEdgeStart[fa] + cursor[fa]++;
            faceEdgeNbr[slotA] = fb; faceEdgeIdx[slotA] = e;
            int slotB = faceEdgeStart[fb] + cursor[fb]++;
            faceEdgeNbr[slotB] = fa; faceEdgeIdx[slotB] = e;
        }
        queue[qTail++] = 0;
        visited[0] = true;
        while (qHead < qTail) {
            int f = queue[qHead++];
            for (int s = faceEdgeStart[f]; s < faceEdgeStart[f + 1]; s++) {
                int g = faceEdgeNbr[s];
                int e = faceEdgeIdx[s];
                if (visited[g]) continue;
                // BZK09 sign convention: θ_a − θ_b + κ_e + (π/2)·m_e = 0 for
                // the optimal m_e. Here a=edgeFaceA[e], b=edgeFaceB[e]. Sign
                // when traversing from f to g depends on which side f is on.
                double sign = (edgeFaceA[e] == f) ? 1.0 : -1.0;
                // Target: θ_g such that θ_f − θ_g + (sign·κ_e) ≈ 0 (mod π/2).
                //   if f==a: θ_g_target = θ_f + κ_e   (sign=+1, but BZK09 has θ_a-θ_b+κ → θ_g = θ_f+κ)
                //   if f==b: θ_g_target = θ_f - κ_e
                double target = theta[f] + sign * kappa[e];
                int k = (int) Math.round((target - theta[g]) / piHalf);
                theta[g] += piHalf * k;
                visited[g] = true;
                queue[qTail++] = g;
                // Tree edge: m_e = 0 by construction (residual is in [-π/4, π/4]).
                periodJump[e] = 0;
            }
        }
        // Chord edges (not visited via BFS in their own right but BFS visits
        // every face, so all edges are reached). Mark non-tree edges by
        // re-scanning: an edge is a chord iff BOTH its faces were visited
        // BEFORE the edge (which the BFS above already handled by setting
        // periodJump=0 only on the tree edges). For all OTHER edges, compute
        // m_e from current (unwrapped) θ values:
        boolean[] treeEdge = new boolean[E];
        // Re-walk: we already set periodJump=0 on tree edges during BFS.
        // Re-mark by tracking: an edge was used in BFS iff the second face
        // was unvisited at the time. We don't have that history — recompute.
        // Simpler: for ALL edges, recompute m_e from the unwrapped θ. Tree
        // edges come out 0 (since unwrapping made them so); chord edges
        // produce the right cycle-space integer.
        for (int e = 0; e < E; e++) {
            int fa = edgeFaceA[e];
            int fb = edgeFaceB[e];
            double diff = theta[fa] - theta[fb] + kappa[e];
            periodJump[e] = -(int) Math.round(diff / piHalf);
        }

        return new Result(theta, periodJump, constrainedFaceCount);
    }

    public static final class Result {
        public final double[] theta;
        public final int[] periodJump;
        public final int constrainedFaceCount;

        Result(double[] theta, int[] periodJump, int constrainedFaceCount) {
            this.theta = theta;
            this.periodJump = periodJump;
            this.constrainedFaceCount = constrainedFaceCount;
        }
    }
}
