package ixdar.geometry.mesh.quadlayout.solver;

/**
 * A factorized SPD linear system, ready for repeated dense right-hand-side
 * solves. Fill-reducing permutation and free-variable compaction are the
 * caller's responsibility and must be applied before factorization, so every
 * vector crossing this interface is already permuted and compact.
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
