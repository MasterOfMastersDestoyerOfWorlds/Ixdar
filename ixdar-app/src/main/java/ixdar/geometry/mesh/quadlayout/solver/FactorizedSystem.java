package ixdar.geometry.mesh.quadlayout.solver;

/**
 * A factorized SPD linear system, ready for repeated dense right-hand-side
 * solves. Implementations own whatever factor representation their backend
 * uses (EJML simplicial L, PARDISO's opaque native handle, ...) and operate
 * entirely in the caller's index space: the caller applies any fill-reducing
 * permutation and free-variable compaction <em>before</em> factorization, so
 * {@code solve} vectors are already permuted and compact.
 *
 * <p>
 * This is the solver-backend seam, mirroring the {@code ixdar.platform.gl}
 * pattern: {@link EjmlCholeskyFactor} is the permanent pure-Java reference
 * implementation and native backends are selected automatically by
 * {@link CholeskyBackend} when their libraries are loadable.
 */
public interface FactorizedSystem {

    /**
     * Solve {@code A x = rhs} through the stored factor. Both vectors are in
     * the factored (permuted, compact) index space and must have length equal
     * to the factored dimension. {@code rhs} is not modified; {@code out} may
     * alias {@code rhs}.
     *
     * @param rhs right-hand side, length = factored dimension
     * @param out receives the solution, length = factored dimension
     */
    void solve(double[] rhs, double[] out);

    /**
     * Free any native resources backing the factor. Idempotent; the object
     * must not be used after release. Pure-Java implementations may no-op.
     */
    void release();
}
